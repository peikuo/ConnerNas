package com.peik.cornernas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.peik.cornernas.server.KtorServer
import com.peik.cornernas.storage.SafFileManager
import com.peik.cornernas.storage.SharedFolder

class CornerNASServerService : Service() {
    private var server: KtorServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        val safFileManager = SafFileManager(this) { loadSharedFolders() }
        server = KtorServer(this, safFileManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        server?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.service_notification_title))
        .setContentText(getString(R.string.service_notification_body))
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setOngoing(true)
        .build()

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

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "cornernas_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
