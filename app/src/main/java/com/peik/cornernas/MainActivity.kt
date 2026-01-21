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
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.PermissionChecker
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.peik.cornernas.util.AppLog
import com.peik.cornernas.util.NetworkUtils
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.provider.Settings

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
        findViewById<android.widget.ImageButton>(R.id.button_edit_device_name)
            .setOnClickListener { showEditDeviceNameDialog() }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_shared_folders)
            .setOnClickListener {
                startActivity(Intent(this, SharedFoldersActivity::class.java))
            }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.button_copy_url)
            .setOnClickListener { copyServerUrl() }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.button_share_url)
            .setOnClickListener { shareServerUrl() }

        findViewById<Button>(R.id.button_all_shares).setOnClickListener {
            startActivity(Intent(this, AllSharesActivity::class.java))
        }

        findViewById<Button>(R.id.button_service_toggle).setOnClickListener {
            if (serverRunning) {
                val intent = Intent(this, CornerNASServerService::class.java)
                stopService(intent)
                updateServerUi(false)
                updateStatus()
                Toast.makeText(this, getString(R.string.server_stopped), Toast.LENGTH_SHORT).show()
            } else {
                maybeStartServer()
            }
        }

        updateServerUi(false)
        updateStatus()
        updateDeviceNameUi()
        updateDevices()
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateDeviceNameUi()
    }

    override fun onStop() {
        stopDiscovery()
        super.onStop()
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

    private fun copyServerUrl() {
        val url = getServerUrl() ?: run {
            Toast.makeText(this, getString(R.string.url_not_available), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_url), url))
        Toast.makeText(this, getString(R.string.url_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareServerUrl() {
        val url = getServerUrl() ?: run {
            Toast.makeText(this, getString(R.string.url_not_available), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_url)))
    }

    private fun getServerUrl(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val port = prefs.getInt(KEY_SERVER_PORT, 0)
        val ip = NetworkUtils.getLocalIpAddress()
        if (port <= 0 || ip.isNullOrBlank()) return null
        return "http://$ip:$port/"
    }

    private fun updateServerUi(isRunning: Boolean) {
        serverRunning = isRunning
        findViewById<TextView>(R.id.text_toolbar_status).text = if (isRunning) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
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
        updateStatus()
        Toast.makeText(this, getString(R.string.server_started), Toast.LENGTH_SHORT).show()
        val port = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_SERVER_PORT, 0)
        AppLog.i(logTag, "Start server requested, stored port=$port")
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

    private fun updateDeviceNameUi() {
        findViewById<TextView>(R.id.text_device_name).text = getDeviceName()
    }

    private fun getDeviceName(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_DEVICE_NAME, null)
        if (!stored.isNullOrBlank()) return stored
        val systemName = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        return systemName?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    private fun showEditDeviceNameDialog() {
        val input = EditText(this).apply {
            setText(getDeviceName())
            hint = getString(R.string.device_name_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.edit_device_name))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, getString(R.string.device_name_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
                updateDeviceNameUi()
                if (serverRunning) {
                    stopService(Intent(this, CornerNASServerService::class.java))
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, CornerNASServerService::class.java)
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            item.text = "${device.name} (${device.host}:${device.port})"
            item.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            val padding = dpToPx(8)
            item.setPadding(0, padding, 0, padding)
            item.setCompoundDrawablesWithIntrinsicBounds(deviceIconRes(device.name), 0, 0, 0)
            item.compoundDrawablePadding = dpToPx(8)
            container.addView(item)
        }
    }

    private fun deviceIconRes(name: String): Int {
        return if (isTabletName(name)) {
            R.drawable.ic_device_tablet
        } else {
            R.drawable.ic_device_phone
        }
    }

    private fun isTabletName(name: String): Boolean {
        val lower = name.lowercase()
        val keywords = listOf("pad", "tablet", "tab", "ipad")
        return keywords.any { lower.contains(it) }
    }

    data class DeviceInfo(val name: String, val host: String, val port: Int)

    companion object {
        const val PREFS_NAME = "cornernas_prefs"
        const val KEY_TREE_URIS = "tree_uris"
        const val KEY_SERVER_PORT = "server_port"
        const val KEY_DEVICE_NAME = "device_name"
        private const val SERVICE_TYPE = "_cornernas._tcp."
    }
}
