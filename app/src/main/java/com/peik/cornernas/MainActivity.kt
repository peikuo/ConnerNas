package com.peik.cornernas

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.peik.cornernas.util.NetworkUtils

class MainActivity : AppCompatActivity() {
    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistFolder(uri)
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.button_pick).setOnClickListener {
            pickFolderLauncher.launch(null)
        }

        findViewById<Button>(R.id.button_start).setOnClickListener {
            val intent = Intent(this, CornerNASServerService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }

        findViewById<Button>(R.id.button_stop).setOnClickListener {
            val intent = Intent(this, CornerNASServerService::class.java)
            stopService(intent)
        }

        updateStatus()
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
        val count = prefs.getStringSet(KEY_TREE_URIS, emptySet())?.size ?: 0
        findViewById<TextView>(R.id.text_status).text = "Status: $count shared folders"
        val ip = NetworkUtils.getLocalIpAddress()
        findViewById<TextView>(R.id.text_ip).text = "IP: ${ip ?: "unknown"}"
    }

    companion object {
        const val PREFS_NAME = "cornernas_prefs"
        const val KEY_TREE_URIS = "tree_uris"
    }
}
