package com.peik.cornernas

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textview.MaterialTextView
import com.peik.cornernas.util.applyKeepScreenOnIfEnabled
import com.peik.cornernas.util.clearKeepScreenOn
import com.peik.cornernas.discovery.DeviceDiscoveryManager

class AllSharesActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mode: String = MODE_BROWSE
    private var devices: List<DeviceDiscoveryManager.DiscoveredDevice> = emptyList()
    private val discoveryListener: (List<DeviceDiscoveryManager.DiscoveredDevice>) -> Unit = { list ->
        devices = list
        updateDevices()
    }

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

    override fun onResume() {
        super.onResume()
        applyKeepScreenOnIfEnabled(this)
    }

    override fun onPause() {
        clearKeepScreenOn(this)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        DeviceDiscoveryManager.addListener(this, discoveryListener)
    }

    override fun onStop() {
        DeviceDiscoveryManager.removeListener(discoveryListener)
        super.onStop()
    }

    private fun updateDevices() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateDevices() }
            return
        }
        val container = findViewById<LinearLayout>(R.id.devices_list_container)
        val empty = findViewById<TextView>(R.id.text_devices_empty_all)
        container.removeAllViews()
        if (devices.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        devices.forEach { device ->
            val item = layoutInflater.inflate(R.layout.item_shared_device, container, false)
            val card = item.findViewById<com.google.android.material.card.MaterialCardView>(R.id.device_card)
            val icon = item.findViewById<android.widget.ImageView>(R.id.device_icon)
            val title = item.findViewById<MaterialTextView>(R.id.device_name)
            val subtitle = item.findViewById<MaterialTextView>(R.id.device_subtitle)
            icon.setImageResource(deviceIconRes(device.name))
            title.text = device.name
            subtitle.text = "${device.host}:${device.port}"
            card.setOnClickListener { onDeviceSelected(device) }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(6), 0, dpToPx(6))
            }
            container.addView(item, params)
        }
    }

    private fun onDeviceSelected(device: DeviceDiscoveryManager.DiscoveredDevice) {
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

    companion object {
        const val EXTRA_MODE = "all_shares_mode"
        const val MODE_BROWSE = "browse"
        const val MODE_PICK = "pick"
    }
}
