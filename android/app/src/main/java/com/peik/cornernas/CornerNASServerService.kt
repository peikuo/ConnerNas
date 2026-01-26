package com.peik.cornernas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.peik.cornernas.util.AppLog
import com.peik.cornernas.server.KtorServer
import com.peik.cornernas.storage.SafFileManager
import com.peik.cornernas.storage.SharedFolder
import java.net.ServerSocket
import kotlin.random.Random

class CornerNASServerService : Service() {
    private val logTag = "CornerNASService"
    private var server: KtorServer? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serverPort: Int = 0
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private var keepAliveStarted = false
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            refreshNsdRegistration()
            keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val safFileManager = SafFileManager(this) { loadSharedFolders() }
        server = KtorServer(this, safFileManager)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            AppLog.i(logTag, "Stop action received, shutting down service")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (serverPort == 0) {
            serverPort = pickRandomPort()
            saveServerPort(serverPort)
        }
        AppLog.i(logTag, "Starting server on port=$serverPort")
        server?.start(serverPort)
        registerService()
        startKeepAlive()
        return START_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopKeepAlive()
        unregisterService()
        server?.stop()
        server = null
        saveServerPort(0)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CornerNASServerService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_body))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_service), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun loadSharedFolders(): List<SharedFolder> {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val uris = prefs.getStringSet(MainActivity.KEY_TREE_URIS, emptySet()).orEmpty()
        return uris.mapNotNull { uriString ->
            val doc = DocumentFile.fromTreeUri(this, android.net.Uri.parse(uriString))
            val name = doc?.name ?: return@mapNotNull null
            SharedFolder(name = name, uriString = uriString)
        }
    }

    private fun registerService() {
        if (registrationListener != null) return
        val manager = nsdManager ?: return
        if (serverPort == 0) return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceDisplayName()
            serviceType = "_cornernas._tcp."
            port = serverPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                AppLog.i(logTag, "NSD registered: ${serviceInfo.serviceName}:${serviceInfo.port}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                AppLog.w(logTag, "NSD register failed: code=$errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                AppLog.i(logTag, "NSD unregistered")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                AppLog.w(logTag, "NSD unregister failed: code=$errorCode")
            }
        }
        registrationListener = listener
        manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun refreshNsdRegistration() {
        if (serverPort == 0) return
        if (nsdManager == null) return
        try {
            unregisterService()
            registerService()
        } catch (e: Exception) {
            AppLog.w(logTag, "NSD refresh failed", e)
        }
    }

    private fun startKeepAlive() {
        if (keepAliveStarted) return
        keepAliveStarted = true
        keepAliveHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS)
    }

    private fun stopKeepAlive() {
        if (!keepAliveStarted) return
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        keepAliveStarted = false
    }

    private fun deviceDisplayName(): String {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(MainActivity.KEY_DEVICE_NAME, null)
        if (!stored.isNullOrBlank()) return stored
        val name = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    private fun unregisterService() {
        val manager = nsdManager ?: return
        val listener = registrationListener ?: return
        manager.unregisterService(listener)
        registrationListener = null
    }

    private fun saveServerPort(port: Int) {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(MainActivity.KEY_SERVER_PORT, port).apply()
    }

    private fun pickRandomPort(): Int {
        val min = 10000
        val max = 99999
        repeat(50) {
            val port = Random.nextInt(min, max + 1)
            if (isPortAvailable(port)) {
                return port
            }
        }
        return min
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "cornernas_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_SERVICE = "com.peik.cornernas.action.STOP_SERVICE"
        private const val KEEP_ALIVE_INTERVAL_MS = 3 * 60 * 1000L
    }
}
