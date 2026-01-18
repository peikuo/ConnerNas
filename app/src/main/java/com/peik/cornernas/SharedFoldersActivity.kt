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
        updateList()
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
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val padding = dpToPx(8)
                    setPadding(0, padding, 0, padding)
                }
                val label = MaterialTextView(this).apply {
                    text = name
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val delete = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialIconButtonStyle
                ).apply {
                    setIconResource(android.R.drawable.ic_menu_delete)
                    contentDescription = getString(R.string.delete_folder)
                    setOnClickListener {
                        removeFolder(uriString)
                        updateList()
                    }
                }
                row.addView(label)
                row.addView(delete)
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

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
