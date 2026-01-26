package com.peik.cornernas

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import com.peik.cornernas.util.AppLog
import com.peik.cornernas.util.LogStore
import com.peik.cornernas.util.NetworkUtils
import com.peik.cornernas.util.applyKeepScreenOnIfEnabled
import com.peik.cornernas.util.clearKeepScreenOn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {
    private val logTag = "CornerNASSettings"
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_settings)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }

        val group = findViewById<RadioGroup>(R.id.radio_language_group)
        val locales = AppCompatDelegate.getApplicationLocales()
        when (locales.toLanguageTags()) {
            "en" -> group.check(R.id.radio_language_en)
            "zh" -> group.check(R.id.radio_language_zh)
            else -> group.check(R.id.radio_language_system)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val newLocales = when (checkedId) {
                R.id.radio_language_en -> LocaleListCompat.forLanguageTags("en")
                R.id.radio_language_zh -> LocaleListCompat.forLanguageTags("zh")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
            AppCompatDelegate.setApplicationLocales(newLocales)
        }

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val developerSwitch = findViewById<MaterialSwitch>(R.id.switch_developer_mode)
        val keepScreenSwitch = findViewById<MaterialSwitch>(R.id.switch_keep_screen_on)
        val keepScreenHelp = findViewById<View>(R.id.keep_screen_on_help)
        val developerTools = findViewById<View>(R.id.developer_tools_container)
        val testButton = findViewById<MaterialButton>(R.id.button_test_server)

        val devEnabled = prefs.getBoolean(KEY_DEV_MODE, false)
        developerSwitch.isChecked = devEnabled
        developerTools.visibility = if (devEnabled) View.VISIBLE else View.GONE

        val keepScreenEnabled = prefs.getBoolean(MainActivity.KEY_KEEP_SCREEN_ON, false)
        keepScreenSwitch.isChecked = keepScreenEnabled

        developerSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_DEV_MODE, isChecked).apply()
            developerTools.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        keepScreenSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(MainActivity.KEY_KEEP_SCREEN_ON, isChecked).apply()
            applyKeepScreenOnIfEnabled(this)
        }

        keepScreenHelp.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.keep_screen_on_help_title))
                .setMessage(getString(R.string.keep_screen_on_help_message))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        testButton.setOnClickListener { testServer() }

        findViewById<MaterialButton>(R.id.button_view_logs).setOnClickListener {
            showLogs()
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

    private fun testServer() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val port = prefs.getInt(MainActivity.KEY_SERVER_PORT, 0)
        if (port <= 0) {
            AppLog.w(logTag, "Test server skipped: port=$port")
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
                if (localResult && lanResult == false) {
                    showLanHelp()
                }
            }
        }.start()
    }

    private fun showLanHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.lan_help_title))
            .setMessage(getString(R.string.lan_help_message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun testUrl(url: String): Boolean {
        return try {
            AppLog.i(logTag, "Testing server at $url")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            AppLog.i(logTag, "Test response code=$code url=$url")
            code in 200..399
        } catch (e: Exception) {
            AppLog.w(logTag, "Test failed url=$url", e)
            false
        }
    }

    private fun showLogs() {
        val entries = LogStore.getRecent(60_000)
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val text = if (entries.isEmpty()) {
            getString(R.string.logs_empty)
        } else {
            entries.joinToString("\n") { entry ->
                "${formatter.format(Date(entry.timestamp))} ${entry.level}/${entry.tag}: ${entry.message}"
            }
        }
        val textView = MaterialTextView(this).apply {
            this.text = text
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(textView)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logs_title))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val KEY_DEV_MODE = "developer_mode_enabled"
    }
}
