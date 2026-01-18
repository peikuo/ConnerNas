package com.peik.cornernas

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.PermissionChecker
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.peik.cornernas.util.NetworkUtils
import com.google.android.material.textview.MaterialTextView
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private val logTag = "CornerNASMain"
    private var serverRunning = false
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val discovered = linkedMapOf<String, DeviceInfo>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startServer()
            } else {
                Toast.makeText(this, getString(R.string.notification_required), Toast.LENGTH_LONG).show()
                updateServerUi(false)
            }
        }
    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistFolder(uri)
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else {
                false
            }
        }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_shared_folders)
            .setOnClickListener {
                startActivity(Intent(this, SharedFoldersActivity::class.java))
            }

        findViewById<Button>(R.id.button_share_all).setOnClickListener {
            pickFolderLauncher.launch(null)
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.button_test_server)
            .setOnClickListener { testServer() }

        findViewById<Button>(R.id.button_service_toggle).setOnClickListener {
            if (serverRunning) {
                val intent = Intent(this, CornerNASServerService::class.java)
                stopService(intent)
                updateServerUi(false)
                Toast.makeText(this, getString(R.string.server_stopped), Toast.LENGTH_SHORT).show()
            } else {
                maybeStartServer()
            }
        }

        updateServerUi(false)
        updateStatus()
        updateDevices()
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onStop() {
        stopDiscovery()
        super.onStop()
    }

    private fun persistFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_TREE_URIS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(uri.toString())
        prefs.edit().putStringSet(KEY_TREE_URIS, current).apply()
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uris = prefs.getStringSet(KEY_TREE_URIS, emptySet()).orEmpty()
        updateSharedFolders(uris.toList())
        val ip = NetworkUtils.getLocalIpAddress()
        val port = prefs.getInt(KEY_SERVER_PORT, 0)
        findViewById<TextView>(R.id.text_ip).text = if (ip == null) {
            getString(R.string.ip_unknown)
        } else if (port > 0) {
            getString(R.string.ip_label_with_port, ip, port)
        } else {
            getString(R.string.ip_label, ip)
        }
    }

    private fun testServer() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val port = prefs.getInt(KEY_SERVER_PORT, 0)
        if (!serverRunning || port <= 0) {
            Log.w(logTag, "Test server skipped: running=$serverRunning port=$port")
            Toast.makeText(this, getString(R.string.test_server_not_running), Toast.LENGTH_SHORT).show()
            return
        }
        val ip = NetworkUtils.getLocalIpAddress()
        Thread {
            val localResult = testUrl("http://127.0.0.1:$port/")
            val lanResult = if (ip.isNullOrBlank()) null else testUrl("http://$ip:$port/")
            mainHandler.post {
                val message = getString(
                    R.string.test_server_result,
                    if (localResult) getString(R.string.test_ok) else getString(R.string.test_fail),
                    if (lanResult == null) getString(R.string.test_unknown) else if (lanResult) getString(R.string.test_ok) else getString(R.string.test_fail)
                )
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun testUrl(url: String): Boolean {
        return try {
            Log.i(logTag, "Testing server at $url")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            Log.i(logTag, "Test response code=$code url=$url")
            code in 200..399
        } catch (e: Exception) {
            Log.w(logTag, "Test failed url=$url", e)
            false
        }
    }

    private fun updateServerUi(isRunning: Boolean) {
        serverRunning = isRunning
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.subtitle = if (isRunning) {
            getString(R.string.appbar_running)
        } else {
            getString(R.string.appbar_stopped)
        }
        findViewById<TextView>(R.id.text_status_state).text = if (isRunning) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
        }
        val control = findViewById<com.google.android.material.button.MaterialButton>(R.id.button_service_toggle)
        if (isRunning) {
            control.text = getString(R.string.stop_service)
            control.setBackgroundColor(getColor(R.color.md_theme_error))
            control.setTextColor(getColor(R.color.md_theme_on_error))
        } else {
            control.text = getString(R.string.start_service)
            control.setBackgroundColor(getColor(R.color.md_theme_primary))
            control.setTextColor(getColor(R.color.md_theme_on_primary))
        }
    }

    private fun maybeStartServer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startServer()
            return
        }
        val granted = PermissionChecker.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PermissionChecker.PERMISSION_GRANTED
        if (granted) {
            startServer()
        } else {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startServer() {
        val intent = Intent(this, CornerNASServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateServerUi(true)
        Toast.makeText(this, getString(R.string.server_started), Toast.LENGTH_SHORT).show()
        val port = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_SERVER_PORT, 0)
        Log.i(logTag, "Start server requested, stored port=$port")
    }

    private fun updateSharedFolders(uris: List<String>) {
        val container = findViewById<LinearLayout>(R.id.shared_list_container)
        val empty = findViewById<TextView>(R.id.text_shared_empty)
        val countView = findViewById<TextView>(R.id.text_shared_count)
        container.removeAllViews()
        countView.text = getString(R.string.shared_folders_count, uris.size)
        if (uris.isEmpty()) {
            empty.visibility = android.view.View.VISIBLE
            return
        }
        empty.visibility = android.view.View.GONE
        uris.mapNotNull { uriString ->
            val doc = DocumentFile.fromTreeUri(this, Uri.parse(uriString))
            doc?.name ?: uriString
        }.sortedBy { it.lowercase() }
            .forEach { name ->
                val item = MaterialTextView(this)
                item.text = "- $name"
                item.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                val padding = dpToPx(8)
                item.setPadding(0, padding, 0, padding)
                container.addView(item)
            }
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun startDiscovery() {
        if (discoveryListener != null) return
        val manager = nsdManager ?: return
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("cornernas").apply {
            setReferenceCounted(false)
            acquire()
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    resolveService(serviceInfo)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                discovered.remove(serviceInfo.serviceName)
                updateDevices()
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
            }
        }
        discoveryListener = listener
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        val manager = nsdManager ?: return
        val listener = discoveryListener ?: return
        manager.stopServiceDiscovery(listener)
        discoveryListener = null
        multicastLock?.release()
        multicastLock = null
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val device = DeviceInfo(info.serviceName, host, info.port)
                discovered[info.serviceName] = device
                updateDevices()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
        })
    }

    private fun updateDevices() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateDevices() }
            return
        }
        val container = findViewById<LinearLayout>(R.id.devices_list_container)
        val empty = findViewById<TextView>(R.id.text_devices_empty)
        val countView = findViewById<TextView>(R.id.text_devices_count)
        container.removeAllViews()
        countView.text = getString(R.string.devices_count, discovered.size)
        if (discovered.isEmpty()) {
            empty.visibility = android.view.View.VISIBLE
            return
        }
        empty.visibility = android.view.View.GONE
        discovered.values.sortedBy { it.name.lowercase() }.forEach { device ->
            val item = MaterialTextView(this)
            item.text = "- ${device.name} (${device.host}:${device.port})"
            item.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            val padding = dpToPx(8)
            item.setPadding(0, padding, 0, padding)
            container.addView(item)
        }
    }

    data class DeviceInfo(val name: String, val host: String, val port: Int)

    companion object {
        const val PREFS_NAME = "cornernas_prefs"
        const val KEY_TREE_URIS = "tree_uris"
        const val KEY_SERVER_PORT = "server_port"
        private const val SERVICE_TYPE = "_cornernas._tcp."
    }
}
