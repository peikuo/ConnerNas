package com.peik.cornernas

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.peik.cornernas.util.applyKeepScreenOnIfEnabled
import com.peik.cornernas.util.clearKeepScreenOn

class SharedFoldersActivity : AppCompatActivity() {
    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistFolder(uri)
                updateList()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shared_folders)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_shared)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.button_add_folder).setOnClickListener {
            pickFolderLauncher.launch(null)
        }

        updateList()
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOnIfEnabled(this)
        updateList()
    }

    override fun onPause() {
        clearKeepScreenOn(this)
        super.onPause()
    }

    private fun updateList() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val uris = prefs.getStringSet(MainActivity.KEY_TREE_URIS, emptySet()).orEmpty().toList()
        val container = findViewById<LinearLayout>(R.id.shared_manage_container)
        val empty = findViewById<TextView>(R.id.text_shared_manage_empty)
        val countView = findViewById<TextView>(R.id.text_shared_manage_count)
        container.removeAllViews()
        countView.text = getString(R.string.shared_folders_count, uris.size)
        if (uris.isEmpty()) {
            empty.visibility = android.view.View.VISIBLE
            return
        }
        empty.visibility = android.view.View.GONE
        uris.mapNotNull { uriString ->
            val doc = DocumentFile.fromTreeUri(this, Uri.parse(uriString))
            val name = doc?.name ?: uriString
            name to uriString
        }.sortedBy { it.first.lowercase() }
            .forEach { (name, uriString) ->
                val row = layoutInflater.inflate(R.layout.item_shared_folder, container, false)
                row.findViewById<MaterialTextView>(R.id.shared_folder_name).text = name
                row.findViewById<MaterialButton>(R.id.shared_folder_delete).setOnClickListener {
                    removeFolder(uriString)
                    updateList()
                }
                container.addView(row)
            }
    }

    private fun persistFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(MainActivity.KEY_TREE_URIS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(uri.toString())
        prefs.edit().putStringSet(MainActivity.KEY_TREE_URIS, current).apply()
    }

    private fun removeFolder(uriString: String) {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(MainActivity.KEY_TREE_URIS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.remove(uriString)) {
            prefs.edit().putStringSet(MainActivity.KEY_TREE_URIS, current).apply()
        }
        val uri = Uri.parse(uriString)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
    }

}
