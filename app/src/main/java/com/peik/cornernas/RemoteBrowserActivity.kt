package com.peik.cornernas

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.peik.cornernas.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RemoteBrowserActivity : AppCompatActivity() {
    private val logTag = "CornerNASRemote"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val selected = linkedSetOf<String>()
    private var entries: List<RemoteEntry> = emptyList()
    private var host: String = ""
    private var port: Int = 0
    private var deviceName: String = ""
    private var mode: String = MODE_BROWSE
    private var currentPath: String = "/"
    private var transferStatusView: TextView? = null
    private var transferLogView: TextView? = null
    private var selectionMode = false
    private var pendingAction: String? = null
    private var selectionActionsView: View? = null
    private var viewMode: String = VIEW_MODE_GRID

    private val pickTargetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val data = result.data ?: return@registerForActivityResult
                val targetHost = data.getStringExtra(EXTRA_TARGET_HOST) ?: return@registerForActivityResult
                val targetPort = data.getIntExtra(EXTRA_TARGET_PORT, 0)
                val targetPath = data.getStringExtra(EXTRA_TARGET_PATH) ?: return@registerForActivityResult
                if (targetPort <= 0) return@registerForActivityResult
                if (targetHost == host && targetPort == port) {
                    Toast.makeText(this, getString(R.string.move_same_device), Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                when (pendingAction) {
                    ACTION_COPY -> copySelection(targetHost, targetPort, targetPath)
                    ACTION_MOVE -> moveSelection(targetHost, targetPort, targetPath)
                }
                pendingAction = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_browser)

        host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        port = intent.getIntExtra(EXTRA_PORT, 0)
        deviceName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BROWSE
        if (host.isBlank() || port <= 0) {
            Toast.makeText(this, getString(R.string.remote_list_failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_remote)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = deviceName.ifBlank { getString(R.string.all_shares_title) }

        val moveButton = findViewById<MaterialButton>(R.id.button_move)
        val copyButton = findViewById<MaterialButton>(R.id.button_copy)
        val cancelButton = findViewById<MaterialButton>(R.id.button_cancel_select)
        val selectButton = findViewById<MaterialButton>(R.id.button_select_target)
        val actionBar = findViewById<View>(R.id.remote_action_bar)
        selectionActionsView = findViewById(R.id.selection_actions)
        transferStatusView = findViewById(R.id.text_transfer_status)
        transferLogView = findViewById(R.id.text_transfer_log)

        if (mode == MODE_PICK) {
            moveButton.visibility = View.GONE
            copyButton.visibility = View.GONE
            cancelButton.visibility = View.GONE
            selectButton.visibility = View.VISIBLE
            actionBar.visibility = View.VISIBLE
            selectButton.setOnClickListener { selectCurrentFolder() }
        } else {
            moveButton.visibility = View.VISIBLE
            copyButton.visibility = View.VISIBLE
            cancelButton.visibility = View.VISIBLE
            selectButton.visibility = View.GONE
            actionBar.visibility = View.VISIBLE
            moveButton.setOnClickListener { confirmMove() }
            copyButton.setOnClickListener { confirmCopy() }
            cancelButton.setOnClickListener { exitSelectionMode() }
        }

        findViewById<MaterialButton>(R.id.button_remote_up).setOnClickListener {
            if (currentPath == "/") {
                finish()
            } else {
                currentPath = parentPath(currentPath)
                selected.clear()
                selectionMode = false
                updateSelectionUi()
                loadEntries()
            }
        }
        findViewById<MaterialButton>(R.id.button_view_mode).setOnClickListener {
            viewMode = if (viewMode == VIEW_MODE_GRID) VIEW_MODE_LIST else VIEW_MODE_GRID
            updateViewModeUi()
            renderEntries()
        }

        updateViewModeUi()
        loadEntries()
    }

    private fun confirmMove() {
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.select_items_to_move), Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.move_warning_title))
            .setMessage(getString(R.string.move_warning_message))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pendingAction = ACTION_MOVE
                val intent = Intent(this, AllSharesActivity::class.java).apply {
                    putExtra(AllSharesActivity.EXTRA_MODE, AllSharesActivity.MODE_PICK)
                }
                pickTargetLauncher.launch(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmCopy() {
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.select_items_to_copy), Toast.LENGTH_SHORT).show()
            return
        }
        pendingAction = ACTION_COPY
        val intent = Intent(this, AllSharesActivity::class.java).apply {
            putExtra(AllSharesActivity.EXTRA_MODE, AllSharesActivity.MODE_PICK)
        }
        pickTargetLauncher.launch(intent)
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        updateSelectionUi()
        renderEntries()
    }

    private fun selectCurrentFolder() {
        val segments = splitSegments(currentPath)
        if (segments.isEmpty()) {
            Toast.makeText(this, getString(R.string.pick_target_folder_hint), Toast.LENGTH_SHORT).show()
            return
        }
        val data = Intent().apply {
            putExtra(EXTRA_TARGET_HOST, host)
            putExtra(EXTRA_TARGET_PORT, port)
            putExtra(EXTRA_TARGET_PATH, currentPath)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun loadEntries() {
        Thread {
            val result = fetchList(currentPath)
            mainHandler.post {
                if (result != null) {
                    currentPath = result.path
                    val pathView = findViewById<TextView>(R.id.text_remote_path)
                    pathView.text = getString(R.string.web_path_label, currentPath)
                    entries = result.entries
                    renderEntries()
                } else {
                    Toast.makeText(this, getString(R.string.remote_list_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun renderEntries() {
        val grid = findViewById<GridLayout>(R.id.grid_remote)
        grid.removeAllViews()
        grid.columnCount = if (viewMode == VIEW_MODE_GRID) 3 else 1
        val visibleEntries = if (mode == MODE_PICK) {
            entries.filter { it.type == "dir" }
        } else {
            entries
        }.filter { !it.name.startsWith('.') }
        val inflater = LayoutInflater.from(this)
        visibleEntries.forEach { entry ->
            val layoutRes = if (viewMode == VIEW_MODE_GRID) {
                R.layout.item_remote_entry
            } else {
                R.layout.item_remote_entry_list
            }
            val item = inflater.inflate(layoutRes, grid, false)
            val card = item.findViewById<MaterialCardView>(R.id.entry_card)
            val icon = item.findViewById<ImageView>(R.id.entry_icon)
            val name = item.findViewById<MaterialTextView>(R.id.entry_name)
            val check = item.findViewById<CheckBox>(R.id.entry_check)
            icon.setImageResource(iconResForEntry(entry))
            name.text = entry.name
            val isSelected = selected.contains(entry.name)
            updateEntrySelection(card, check, isSelected)
            if (mode == MODE_BROWSE) {
                check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            } else {
                check.visibility = View.GONE
            }
            card.setOnClickListener {
                when {
                    mode == MODE_PICK && entry.type == "dir" -> {
                        currentPath = joinPath(currentPath, entry.name)
                        selected.clear()
                        selectionMode = false
                        updateSelectionUi()
                        loadEntries()
                    }
                    mode == MODE_BROWSE && selectionMode -> {
                        toggleSelection(entry, card, check)
                    }
                    entry.type == "dir" -> {
                        currentPath = joinPath(currentPath, entry.name)
                        selected.clear()
                        selectionMode = false
                        updateSelectionUi()
                        loadEntries()
                    }
                    else -> {
                        openRemoteFile(entry)
                    }
                }
            }
            if (mode == MODE_BROWSE) {
                card.setOnLongClickListener {
                    if (!selectionMode) {
                        selectionMode = true
                        updateSelectionUi()
                    }
                    toggleSelection(entry, card, check)
                    true
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
            }
            grid.addView(item, params)
        }
    }

    private fun updateEntrySelection(card: MaterialCardView, check: CheckBox, selected: Boolean) {
        check.isChecked = selected
        if (selected) {
            card.strokeWidth = dpToPx(2)
            card.strokeColor = ContextCompat.getColor(this, R.color.md_theme_primary)
        } else {
            card.strokeWidth = 0
        }
    }

    private fun toggleSelection(entry: RemoteEntry, card: MaterialCardView, check: CheckBox) {
        if (selected.contains(entry.name)) {
            selected.remove(entry.name)
            updateEntrySelection(card, check, false)
        } else {
            selected.add(entry.name)
            updateEntrySelection(card, check, true)
        }
        if (selected.isEmpty()) {
            selectionMode = false
        }
        updateSelectionUi()
        renderEntries()
    }

    private fun updateSelectionUi() {
        if (mode == MODE_BROWSE) {
            selectionActionsView?.visibility = if (selectionMode) View.VISIBLE else View.GONE
        }
    }

    private fun updateViewModeUi() {
        val button = findViewById<MaterialButton>(R.id.button_view_mode)
        button.text = if (viewMode == VIEW_MODE_GRID) {
            getString(R.string.view_mode_list)
        } else {
            getString(R.string.view_mode_grid)
        }
    }

    private fun openRemoteFile(entry: RemoteEntry) {
        val entryPath = joinPath(currentPath, entry.name)
        val url = buildUrl(host, port, "/api/v1/file", "path", entryPath)
        val mime = guessMimeType(entry.name) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_open_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun iconResForEntry(entry: RemoteEntry): Int {
        if (entry.type == "dir") return R.drawable.folder_24
        val mime = guessMimeType(entry.name)
        return when {
            mime?.startsWith("image/") == true -> R.drawable.image_24
            mime?.startsWith("video/") == true -> R.drawable.video_camera_back_24
            mime?.startsWith("text/") == true -> R.drawable.text_ad_24
            mime?.startsWith("audio/") == true -> R.drawable.audio_file_24
            mime == "application/pdf" -> R.drawable.picture_as_pdf_24
            isArchive(entry.name) -> R.drawable.archive_24
            else -> R.drawable.files_24
        }
    }

    private fun isArchive(name: String): Boolean {
        val extension = MimeTypeMap.getFileExtensionFromUrl(name).lowercase()
        if (extension.isBlank()) return false
        return extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    }

    private fun guessMimeType(name: String): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(name).lowercase()
        if (extension.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun moveSelection(targetHost: String, targetPort: Int, targetPath: String) {
        Toast.makeText(this, getString(R.string.move_started), Toast.LENGTH_SHORT).show()
        val selectedEntries = entries.filter { selected.contains(it.name) }
        Thread {
            var ok = true
            var done = 0
            val total = selectedEntries.size
            for (entry in selectedEntries) {
                mainHandler.post {
                    updateTransferStatus(done + 1, total, entry.name, false)
                    appendTransferLog(entry.name)
                }
                if (!moveEntry(entry, currentPath, targetHost, targetPort, targetPath)) {
                    ok = false
                    break
                }
                done += 1
            }
            mainHandler.post {
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.move_done) else getString(R.string.move_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) {
                    selected.clear()
                    selectionMode = false
                    updateSelectionUi()
                    loadEntries()
                }
            }
        }.start()
    }

    private fun copySelection(targetHost: String, targetPort: Int, targetPath: String) {
        Toast.makeText(this, getString(R.string.copy_started), Toast.LENGTH_SHORT).show()
        val selectedEntries = entries.filter { selected.contains(it.name) }
        Thread {
            var ok = true
            var done = 0
            val total = selectedEntries.size
            for (entry in selectedEntries) {
                mainHandler.post {
                    updateTransferStatus(done + 1, total, entry.name, true)
                    appendTransferLog(entry.name)
                }
                if (!copyEntry(entry, currentPath, targetHost, targetPort, targetPath)) {
                    ok = false
                    break
                }
                done += 1
            }
            mainHandler.post {
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.copy_done) else getString(R.string.copy_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) {
                    selected.clear()
                    selectionMode = false
                    updateSelectionUi()
                    loadEntries()
                }
            }
        }.start()
    }

    private fun copyEntry(
        entry: RemoteEntry,
        sourcePath: String,
        targetHost: String,
        targetPort: Int,
        targetParentPath: String
    ): Boolean {
        val entryPath = joinPath(sourcePath, entry.name)
        return if (entry.type == "dir") {
            copyDirectory(entry, entryPath, targetHost, targetPort, targetParentPath)
        } else {
            copyFile(entry, entryPath, targetHost, targetPort, targetParentPath)
        }
    }

    private fun copyDirectory(
        entry: RemoteEntry,
        sourcePath: String,
        targetHost: String,
        targetPort: Int,
        targetParentPath: String
    ): Boolean {
        if (!mkdirRemote(targetHost, targetPort, targetParentPath, entry.name)) {
            return false
        }
        val targetPath = joinPath(targetParentPath, entry.name)
        val listing = fetchList(sourcePath) ?: return false
        for (child in listing.entries) {
            if (!copyEntry(child, sourcePath, targetHost, targetPort, targetPath)) {
                return false
            }
        }
        return true
    }

    private fun copyFile(
        entry: RemoteEntry,
        sourcePath: String,
        targetHost: String,
        targetPort: Int,
        targetParentPath: String
    ): Boolean {
        val downloadUrl = buildUrl(host, port, "/api/v1/file", "path", sourcePath)
        val uploadUrl = buildUrl(targetHost, targetPort, "/api/v1/upload", "path", targetParentPath)
        val boundary = "----CornerNAS${System.currentTimeMillis()}"
        var downloadConn: HttpURLConnection? = null
        var uploadConn: HttpURLConnection? = null
        try {
            downloadConn = URL(downloadUrl).openConnection() as HttpURLConnection
            downloadConn.connectTimeout = 5000
            downloadConn.readTimeout = 5000
            val code = downloadConn.responseCode
            if (code !in 200..399) return false
            val input = downloadConn.inputStream
            uploadConn = URL(uploadUrl).openConnection() as HttpURLConnection
            uploadConn.connectTimeout = 5000
            uploadConn.readTimeout = 5000
            uploadConn.requestMethod = "POST"
            uploadConn.doOutput = true
            uploadConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            uploadConn.outputStream.use { output ->
                output.write("--$boundary\r\n".toByteArray())
                output.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${entry.name}\"\r\n"
                        .toByteArray()
                )
                output.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                input.copyTo(output)
                output.write("\r\n--$boundary--\r\n".toByteArray())
                output.flush()
            }
            input.close()
            val uploadCode = uploadConn.responseCode
            if (uploadCode !in 200..399) return false
        } catch (e: Exception) {
            AppLog.w(logTag, "Copy file failed: $sourcePath", e)
            return false
        } finally {
            downloadConn?.disconnect()
            uploadConn?.disconnect()
        }
        return true
    }

    private fun moveEntry(
        entry: RemoteEntry,
        sourcePath: String,
        targetHost: String,
        targetPort: Int,
        targetParentPath: String
    ): Boolean {
        val entryPath = joinPath(sourcePath, entry.name)
        return if (entry.type == "dir") {
            moveDirectory(entry, entryPath, targetHost, targetPort, targetParentPath)
        } else {
            moveFile(entry, entryPath, targetHost, targetPort, targetParentPath)
        }
    }

    private fun moveDirectory(
        entry: RemoteEntry,
        sourcePath: String,
        targetHost: String,
        targetPort: Int,
        targetParentPath: String
    ): Boolean {
        if (!mkdirRemote(targetHost, targetPort, targetParentPath, entry.name)) {
            return false
        }
        val targetPath = joinPath(targetParentPath, entry.name)
        val listing = fetchList(sourcePath) ?: return false
        for (child in listing.entries) {
            if (!moveEntry(child, sourcePath, targetHost, targetPort, targetPath)) {
                return false
            }
        }
        return deleteRemote(host, port, sourcePath)
    }

    private fun moveFile(
        entry: RemoteEntry,
        sourcePath: String,
        targetHost: String,
        targetPort: Int,
        targetParentPath: String
    ): Boolean {
        val downloadUrl = buildUrl(host, port, "/api/v1/file", "path", sourcePath)
        val uploadUrl = buildUrl(targetHost, targetPort, "/api/v1/upload", "path", targetParentPath)
        val boundary = "----CornerNAS${System.currentTimeMillis()}"
        var downloadConn: HttpURLConnection? = null
        var uploadConn: HttpURLConnection? = null
        try {
            downloadConn = URL(downloadUrl).openConnection() as HttpURLConnection
            downloadConn.connectTimeout = 5000
            downloadConn.readTimeout = 5000
            val code = downloadConn.responseCode
            if (code !in 200..399) return false
            val input = downloadConn.inputStream
            uploadConn = URL(uploadUrl).openConnection() as HttpURLConnection
            uploadConn.connectTimeout = 5000
            uploadConn.readTimeout = 5000
            uploadConn.requestMethod = "POST"
            uploadConn.doOutput = true
            uploadConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            uploadConn.outputStream.use { output ->
                output.write("--$boundary\r\n".toByteArray())
                output.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${entry.name}\"\r\n"
                        .toByteArray()
                )
                output.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                input.copyTo(output)
                output.write("\r\n--$boundary--\r\n".toByteArray())
                output.flush()
            }
            input.close()
            val uploadCode = uploadConn.responseCode
            if (uploadCode !in 200..399) return false
        } catch (e: Exception) {
            AppLog.w(logTag, "Move file failed: $sourcePath", e)
            return false
        } finally {
            downloadConn?.disconnect()
            uploadConn?.disconnect()
        }
        return deleteRemote(host, port, sourcePath)
    }

    private fun mkdirRemote(targetHost: String, targetPort: Int, parentPath: String, name: String): Boolean {
        val url = buildUrl(targetHost, targetPort, "/api/v1/mkdir", "path", parentPath, "name", name)
        return postSimple(url)
    }

    private fun deleteRemote(targetHost: String, targetPort: Int, path: String): Boolean {
        val url = buildUrl(targetHost, targetPort, "/api/v1/delete", "path", path)
        return postSimple(url)
    }

    private fun postSimple(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (e: Exception) {
            AppLog.w(logTag, "POST failed url=$url", e)
            false
        }
    }

    private fun fetchList(path: String): RemoteList? {
        val url = buildUrl(host, port, "/api/v1/list", "path", path)
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val jsonText = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseList(jsonText)
        } catch (e: Exception) {
            AppLog.w(logTag, "List failed path=$path", e)
            null
        }
    }

    private fun parseList(jsonText: String): RemoteList {
        val json = JSONObject(jsonText)
        val path = json.optString("path", "/")
        val array = json.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                add(
                    RemoteEntry(
                        name = entry.optString("name"),
                        type = entry.optString("type"),
                        size = if (entry.has("size")) entry.optLong("size") else null
                    )
                )
            }
        }
        return RemoteList(path, entries)
    }

    private fun buildUrl(host: String, port: Int, path: String, vararg params: String): String {
        val builder = StringBuilder()
        builder.append("http://").append(host).append(':').append(port).append(path)
        if (params.isNotEmpty()) {
            builder.append('?')
            params.toList().chunked(2).forEachIndexed { index, pair ->
                if (pair.size < 2) return@forEachIndexed
                if (index > 0) builder.append('&')
                builder.append(URLEncoder.encode(pair[0], "UTF-8"))
                builder.append('=')
                builder.append(URLEncoder.encode(pair[1], "UTF-8"))
            }
        }
        return builder.toString()
    }

    private fun joinPath(base: String, name: String): String {
        return if (base == "/") {
            "/$name"
        } else {
            "$base/$name"
        }
    }

    private fun parentPath(path: String): String {
        val segments = splitSegments(path)
        if (segments.isEmpty()) return "/"
        val parent = segments.dropLast(1)
        return if (parent.isEmpty()) "/" else "/" + parent.joinToString("/")
    }

    private fun splitSegments(path: String): List<String> {
        return path.trim('/').split('/').filter { it.isNotBlank() }
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun updateTransferStatus(done: Int, total: Int, name: String, isCopy: Boolean) {
        val formatRes = if (isCopy) R.string.transfer_progress_copy else R.string.transfer_progress
        transferStatusView?.apply {
            text = getString(formatRes, done, total, name)
            visibility = View.VISIBLE
        }
    }

    private fun appendTransferLog(name: String) {
        val logView = transferLogView ?: return
        val existing = logView.text?.toString().orEmpty()
        val next = if (existing.isBlank()) {
            "${getString(R.string.transfer_log_title)}\n$name"
        } else {
            "$existing\n$name"
        }
        logView.text = next
        logView.visibility = View.VISIBLE
    }

    data class RemoteEntry(val name: String, val type: String, val size: Long? = null)
    data class RemoteList(val path: String, val entries: List<RemoteEntry>)

    companion object {
        const val ACTION_MOVE = "move"
        const val ACTION_COPY = "copy"
        const val VIEW_MODE_GRID = "grid"
        const val VIEW_MODE_LIST = "list"
        const val EXTRA_HOST = "remote_host"
        const val EXTRA_PORT = "remote_port"
        const val EXTRA_NAME = "remote_name"
        const val EXTRA_MODE = "remote_mode"
        const val MODE_BROWSE = "browse"
        const val MODE_PICK = "pick"
        const val EXTRA_TARGET_HOST = "target_host"
        const val EXTRA_TARGET_PORT = "target_port"
        const val EXTRA_TARGET_PATH = "target_path"
    }
}
