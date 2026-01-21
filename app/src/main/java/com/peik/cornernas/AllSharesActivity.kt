package com.peik.cornernas

import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.util.TypedValue
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.peik.cornernas.util.AppLog

class AllSharesActivity : AppCompatActivity() {
    private val discovered = linkedMapOf<String, DeviceInfo>()
    private val resolveRetries = linkedMapOf<String, Int>()
    private val pendingQueue = ArrayDeque<NsdServiceInfo>()
    private val pendingNames = linkedSetOf<String>()
    private var resolving = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var mode: String = MODE_BROWSE
    private val logTag = "CornerNASAllShares"

    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                setResult(RESULT_OK, result.data)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_shares)

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BROWSE

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_all_shares)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = if (mode == MODE_PICK) {
            getString(R.string.select_target_device)
        } else {
            getString(R.string.all_shares_title)
        }
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onStop() {
        stopDiscovery()
        super.onStop()
    }

    private fun startDiscovery() {
        if (discoveryListener != null) return
        val manager = nsdManager ?: return
        AppLog.i(logTag, "Start discovery mode=$mode")
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("cornernas_allshares").apply {
            setReferenceCounted(false)
            acquire()
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                AppLog.i(logTag, "Discovery started type=$regType")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    AppLog.i(logTag, "Service found name=${serviceInfo.serviceName}")
                    enqueueResolve(serviceInfo)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                AppLog.w(logTag, "Service lost name=${serviceInfo.serviceName}")
                discovered.remove(serviceInfo.serviceName)
                pendingNames.remove(serviceInfo.serviceName)
                updateDevices()
            }
            override fun onDiscoveryStopped(serviceType: String) {
                AppLog.i(logTag, "Discovery stopped type=$serviceType")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(logTag, "Discovery start failed type=$serviceType code=$errorCode")
                stopDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(logTag, "Discovery stop failed type=$serviceType code=$errorCode")
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
        pendingQueue.clear()
        pendingNames.clear()
        resolving = false
        AppLog.i(logTag, "Discovery stopped and lock released")
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        val name = serviceInfo.serviceName
        if (discovered.containsKey(name) || pendingNames.contains(name)) return
        pendingNames.add(name)
        pendingQueue.add(serviceInfo)
        resolveNext()
    }

    private fun resolveNext() {
        if (resolving) return
        val next = pendingQueue.removeFirstOrNull() ?: return
        resolving = true
        resolveService(next)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val device = DeviceInfo(info.serviceName, host, info.port)
                discovered[info.serviceName] = device
                pendingNames.remove(info.serviceName)
                resolveRetries.remove(info.serviceName)
                AppLog.i(logTag, "Service resolved name=${info.serviceName} host=$host port=${info.port}")
                resolving = false
                updateDevices()
                resolveNext()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                AppLog.w(logTag, "Resolve failed name=${info.serviceName} code=$errorCode")
                val attempts = (resolveRetries[info.serviceName] ?: 0) + 1
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    resolving = false
                    pendingQueue.addFirst(info)
                    mainHandler.postDelayed({ resolveNext() }, RESOLVE_RETRY_DELAY_MS)
                    return
                }
                if (attempts <= MAX_RESOLVE_RETRIES) {
                    resolveRetries[info.serviceName] = attempts
                    val delay = RESOLVE_RETRY_DELAY_MS * attempts
                    mainHandler.postDelayed({
                        resolving = false
                        resolveService(info)
                    }, delay)
                    AppLog.w(logTag, "Retry resolve name=${info.serviceName} attempt=$attempts")
                    return
                }
                pendingNames.remove(info.serviceName)
                resolving = false
                resolveNext()
            }
        })
    }

    private fun updateDevices() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateDevices() }
            return
        }
        val grid = findViewById<GridLayout>(R.id.grid_devices)
        val empty = findViewById<TextView>(R.id.text_devices_empty_all)
        grid.removeAllViews()
        if (discovered.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        discovered.values.sortedBy { it.name.lowercase() }.forEach { device ->
            val card = MaterialCardView(this).apply {
                radius = dpToPx(16).toFloat()
                isClickable = true
                isFocusable = true
                val outValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = ContextCompat.getDrawable(this@AllSharesActivity, outValue.resourceId)
                setOnClickListener { onDeviceSelected(device) }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
                gravity = android.view.Gravity.START
            }
            val icon = android.widget.ImageView(this).apply {
                setImageResource(deviceIconRes(device.name))
                val size = dpToPx(44)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    gravity = android.view.Gravity.START
                }
            }
            val title = MaterialTextView(this).apply {
                text = device.name
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            val subtitle = MaterialTextView(this).apply {
                text = "${device.host}:${device.port}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            content.addView(icon)
            content.addView(title)
            content.addView(subtitle)
            card.addView(content)
            grid.addView(card)
        }
    }

    private fun onDeviceSelected(device: DeviceInfo) {
        if (mode == MODE_PICK) {
            val intent = Intent(this, RemoteBrowserActivity::class.java).apply {
                putExtra(RemoteBrowserActivity.EXTRA_HOST, device.host)
                putExtra(RemoteBrowserActivity.EXTRA_PORT, device.port)
                putExtra(RemoteBrowserActivity.EXTRA_NAME, device.name)
                putExtra(RemoteBrowserActivity.EXTRA_MODE, RemoteBrowserActivity.MODE_PICK)
            }
            pickFolderLauncher.launch(intent)
        } else {
            val intent = Intent(this, RemoteBrowserActivity::class.java).apply {
                putExtra(RemoteBrowserActivity.EXTRA_HOST, device.host)
                putExtra(RemoteBrowserActivity.EXTRA_PORT, device.port)
                putExtra(RemoteBrowserActivity.EXTRA_NAME, device.name)
                putExtra(RemoteBrowserActivity.EXTRA_MODE, RemoteBrowserActivity.MODE_BROWSE)
            }
            startActivity(intent)
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

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    data class DeviceInfo(val name: String, val host: String, val port: Int)

    companion object {
        const val EXTRA_MODE = "all_shares_mode"
        const val MODE_BROWSE = "browse"
        const val MODE_PICK = "pick"
        private const val SERVICE_TYPE = "_cornernas._tcp."
        private const val MAX_RESOLVE_RETRIES = 3
        private const val RESOLVE_RETRY_DELAY_MS = 500L
    }
}
