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
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.peik.cornernas.storage.SafFileManager
import com.peik.cornernas.storage.SharedFolder
import com.peik.cornernas.util.AppLog
import com.peik.cornernas.util.NetworkUtils
import com.peik.cornernas.util.applyKeepScreenOnIfEnabled
import com.peik.cornernas.util.clearKeepScreenOn
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File

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
    private var transferProgressView: com.google.android.material.progressindicator.LinearProgressIndicator? = null
    private var transferHideRunnable: Runnable? = null
    private var transferBlocker: View? = null
    private var selectionMode = false
    private var pendingAction: String? = null
    private var selectionActionsView: View? = null
    private var actionBarView: View? = null
    private var viewMode: String = VIEW_MODE_GRID
    private val safFileManager by lazy {
        SafFileManager(this) { loadSharedFolders() }
    }

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
        if (!NetworkUtils.isLocalLanHost(host)) {
            Toast.makeText(this, getString(R.string.remote_host_not_local), Toast.LENGTH_SHORT).show()
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
        actionBarView = actionBar
        selectionActionsView = findViewById(R.id.selection_actions)
        transferStatusView = findViewById(R.id.text_transfer_status)
        transferLogView = findViewById(R.id.text_transfer_log)
        transferProgressView = findViewById(R.id.transfer_progress)
        transferBlocker = findViewById(R.id.transfer_blocker)

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

    override fun onResume() {
        super.onResume()
        applyKeepScreenOnIfEnabled(this)
    }

    override fun onPause() {
        clearKeepScreenOn(this)
        super.onPause()
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
        setLoading(true)
        Thread {
            val result = fetchList(currentPath)
            mainHandler.post {
                setLoading(false)
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

    private fun setLoading(loading: Boolean) {
        val indicator = findViewById<View>(R.id.remote_loading)
        indicator?.visibility = if (loading) View.VISIBLE else View.GONE
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
        if (mime.startsWith("image/")) {
            downloadAndOpenFile(entry.name, url, mime)
            return
        }
        if (mime.startsWith("video/")) {
            val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_URL, url)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, entry.name)
            }
            startActivity(intent)
            return
        }
        openRemoteUrl(url, mime, entry.name)
    }

    private fun openRemoteUrl(url: String, mime: String, name: String) {
        openWithChooser(Uri.parse(url), mime, name, false)
    }

    private fun downloadAndOpenFile(name: String, url: String, mime: String) {
        Toast.makeText(this, getString(R.string.download_start), Toast.LENGTH_SHORT).show()
        Thread {
            val cacheDir = File(cacheDir, "remote_files").apply { mkdirs() }
            val file = File(cacheDir, "${System.currentTimeMillis()}_$name")
            val ok = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode !in 200..399) {
                    conn.disconnect()
                    false
                } else {
                    conn.inputStream.use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    conn.disconnect()
                    true
                }
            } catch (e: Exception) {
                AppLog.w(logTag, "Download failed url=$url", e)
                false
            }
            mainHandler.post {
                if (!ok) {
                    Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
                    openRemoteUrl(url, mime, name)
                    return@post
                }
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
                openWithChooser(uri, mime, name, true)
            }
        }.start()
    }

    private fun openWithChooser(uri: Uri, mime: String, name: String, grantRead: Boolean) {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        val key = rememberKey(mime, name)
        val baseIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (grantRead) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val savedPackage = prefs.getString(key, null)
        if (!savedPackage.isNullOrBlank()) {
            val intent = Intent(baseIntent).setPackage(savedPackage)
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                prefs.edit().remove(key).apply()
            }
        }
        val candidates = packageManager.queryIntentActivities(baseIntent, 0)
        if (candidates.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_open_file), Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map {
            it.loadLabel(packageManager).toString()
        }.toTypedArray()
        var selectedIndex = -1
        var rememberChoice = false
        val titleView = layoutInflater.inflate(R.layout.dialog_open_with_title, null)
        titleView.findViewById<TextView>(R.id.text_open_with_title).text = getString(R.string.open_with)
        titleView.findViewById<CheckBox>(R.id.checkbox_remember_choice).apply {
            text = getString(R.string.remember_choice)
            setOnCheckedChangeListener { _, checked ->
                rememberChoice = checked
            }
        }
        MaterialAlertDialogBuilder(this)
            .setCustomTitle(titleView)
            .setSingleChoiceItems(labels, -1) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selectedIndex < 0) {
                    Toast.makeText(this, getString(R.string.select_app_hint), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val pkg = candidates[selectedIndex].activityInfo.packageName
                if (rememberChoice) {
                    prefs.edit().putString(key, pkg).apply()
                }
                try {
                    startActivity(Intent(baseIntent).setPackage(pkg))
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.error_open_file), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rememberKey(mime: String, name: String): String {
        val normalized = mime.lowercase()
        if (normalized != "*/*") {
            return "open_with_pkg_${normalized.replace('/', '_')}"
        }
        val extension = MimeTypeMap.getFileExtensionFromUrl(name).lowercase()
        return if (extension.isBlank()) {
            "open_with_pkg_unknown"
        } else {
            "open_with_pkg_ext_$extension"
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
        val selectedEntries = entries.filter { selected.contains(it.name) }
        Thread {
            mainHandler.post {
                setTransferInProgress(true)
                showPreflightStatus(false)
            }
            val preflight = preflightTargetChecks(targetHost, targetPort, targetPath, selectedEntries)
            if (!preflight.ok) {
                mainHandler.post {
                    setTransferInProgress(false)
                    val messageRes = if (preflight.exists) {
                        R.string.transfer_target_exists
                    } else {
                        R.string.transfer_target_check_failed
                    }
                    val message = if (preflight.exists) {
                        getString(messageRes, preflight.name ?: "")
                    } else {
                        getString(messageRes)
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            var ok = true
            var done = 0
            val total = selectedEntries.size
            mainHandler.post {
                AppLog.i(logTag, "Move start total=$total to $targetHost:$targetPort$targetPath")
                showTransferPreparing(false, total)
                TransferService.start(this)
                updateTransferNotification(0, total, false)
                Toast.makeText(this, getString(R.string.move_started), Toast.LENGTH_SHORT).show()
            }
            for (entry in selectedEntries) {
                mainHandler.post {
                    updateTransferStatus(done + 1, total, entry.name, false)
                    appendTransferLog(entry.name)
                    AppLog.i(logTag, "Move item ${done + 1}/$total name=${entry.name}")
                    updateTransferNotification(done + 1, total, false)
                }
                if (!moveEntry(entry, currentPath, targetHost, targetPort, targetPath)) {
                    ok = false
                    break
                }
                done += 1
            }
            mainHandler.post {
                AppLog.i(logTag, "Move finished ok=$ok done=$done total=$total")
                finishTransferUi(ok, false)
                TransferService.stop(this)
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.move_done) else getString(R.string.move_failed),
                    Toast.LENGTH_SHORT
                ).show()
                clearSelectionAfterTransfer()
                if (ok) {
                    loadEntries()
                } else {
                    renderEntries()
                }
            }
        }.start()
    }

    private fun copySelection(targetHost: String, targetPort: Int, targetPath: String) {
        val selectedEntries = entries.filter { selected.contains(it.name) }
        Thread {
            mainHandler.post {
                setTransferInProgress(true)
                showPreflightStatus(true)
            }
            val preflight = preflightTargetChecks(targetHost, targetPort, targetPath, selectedEntries)
            if (!preflight.ok) {
                mainHandler.post {
                    setTransferInProgress(false)
                    val messageRes = if (preflight.exists) {
                        R.string.transfer_target_exists
                    } else {
                        R.string.transfer_target_check_failed
                    }
                    val message = if (preflight.exists) {
                        getString(messageRes, preflight.name ?: "")
                    } else {
                        getString(messageRes)
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }
            var ok = true
            var done = 0
            val total = selectedEntries.size
            mainHandler.post {
                AppLog.i(logTag, "Copy start total=$total to $targetHost:$targetPort$targetPath")
                showTransferPreparing(true, total)
                TransferService.start(this)
                updateTransferNotification(0, total, true)
                Toast.makeText(this, getString(R.string.copy_started), Toast.LENGTH_SHORT).show()
            }
            for (entry in selectedEntries) {
                mainHandler.post {
                    updateTransferStatus(done + 1, total, entry.name, true)
                    appendTransferLog(entry.name)
                    AppLog.i(logTag, "Copy item ${done + 1}/$total name=${entry.name}")
                    updateTransferNotification(done + 1, total, true)
                }
                if (!copyEntry(entry, currentPath, targetHost, targetPort, targetPath)) {
                    ok = false
                    break
                }
                done += 1
            }
            mainHandler.post {
                AppLog.i(logTag, "Copy finished ok=$ok done=$done total=$total")
                finishTransferUi(ok, true)
                TransferService.stop(this)
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.copy_done) else getString(R.string.copy_failed),
                    Toast.LENGTH_SHORT
                ).show()
                clearSelectionAfterTransfer()
                if (ok) {
                    loadEntries()
                } else {
                    renderEntries()
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
        if (isLocalTarget(targetHost, targetPort)) {
            val ok = copyEntryToLocal(entry, entryPath, targetParentPath)
            if (!ok) {
                AppLog.w(logTag, "Local copy failed, fallback to upload name=${entry.name}")
            }
            return ok || copyEntryRemote(entry, entryPath, targetHost, targetPort, targetParentPath)
        }
        return copyEntryRemote(entry, entryPath, targetHost, targetPort, targetParentPath)
    }

    private fun copyEntryRemote(
        entry: RemoteEntry,
        entryPath: String,
        targetHost: String,
        targetPort: Int,
        targetParentPath: String
    ): Boolean {
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

    private fun copyEntryToLocal(entry: RemoteEntry, sourcePath: String, targetParentPath: String): Boolean {
        return if (entry.type == "dir") {
            copyDirectoryToLocal(entry, sourcePath, targetParentPath)
        } else {
            copyFileToLocal(entry, sourcePath, targetParentPath)
        }
    }

    private fun copyDirectoryToLocal(
        entry: RemoteEntry,
        sourcePath: String,
        targetParentPath: String
    ): Boolean {
        val parentDoc = safFileManager.resolveDocumentFile(targetParentPath)
        if (parentDoc == null || !parentDoc.isDirectory) {
            AppLog.w(logTag, "Local copy dir failed, parent missing path=$targetParentPath")
            return false
        }
        if (parentDoc.findFile(entry.name) != null) {
            AppLog.w(logTag, "Local copy dir blocked, exists name=${entry.name}")
            return false
        }
        val targetDir = safFileManager.createDirectory(parentDoc, entry.name) ?: return false
        val targetPath = joinPath(targetParentPath, entry.name)
        val listing = fetchList(sourcePath) ?: return false
        for (child in listing.entries) {
            val childPath = joinPath(sourcePath, child.name)
            if (!copyEntryToLocal(child, childPath, targetPath)) {
                return false
            }
        }
        return targetDir.exists()
    }

    private fun copyFileToLocal(
        entry: RemoteEntry,
        sourcePath: String,
        targetParentPath: String
    ): Boolean {
        val parentDoc = safFileManager.resolveDocumentFile(targetParentPath)
        if (parentDoc == null || !parentDoc.isDirectory) {
            AppLog.w(logTag, "Local copy file failed, parent missing path=$targetParentPath")
            return false
        }
        if (parentDoc.findFile(entry.name) != null) {
            AppLog.w(logTag, "Local copy file blocked, exists name=${entry.name}")
            return false
        }
        val mime = guessMimeType(entry.name) ?: "application/octet-stream"
        val targetFile = safFileManager.createFile(parentDoc, entry.name, mime) ?: return false
        val downloadUrl = buildUrl(host, port, "/api/v1/file", "path", sourcePath)
        return downloadToDocumentFile(downloadUrl, targetFile)
    }

    private fun downloadToDocumentFile(url: String, targetFile: DocumentFile): Boolean {
        var downloadConn: HttpURLConnection? = null
        try {
            downloadConn = URL(url).openConnection() as HttpURLConnection
            downloadConn.connectTimeout = 5000
            downloadConn.readTimeout = 5000
            val code = downloadConn.responseCode
            if (code !in 200..399) return false
            val input = downloadConn.inputStream
            safFileManager.openOutputStream(targetFile)?.use { output ->
                input.copyTo(output)
            } ?: return false
            input.close()
        } catch (e: Exception) {
            AppLog.w(logTag, "Local download failed url=$url", e)
            return false
        } finally {
            downloadConn?.disconnect()
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
        if (isLocalTarget(targetHost, targetPort)) {
            val ok = moveEntryToLocal(entry, entryPath, targetParentPath)
            if (!ok) {
                AppLog.w(logTag, "Local move failed, fallback to upload name=${entry.name}")
            }
            return ok || moveEntryRemote(entry, entryPath, targetHost, targetPort, targetParentPath)
        }
        return moveEntryRemote(entry, entryPath, targetHost, targetPort, targetParentPath)
    }

    private fun moveEntryRemote(
        entry: RemoteEntry,
        entryPath: String,
        targetHost: String,
        targetPort: Int,
        targetParentPath: String
    ): Boolean {
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

    private fun moveEntryToLocal(entry: RemoteEntry, sourcePath: String, targetParentPath: String): Boolean {
        val ok = copyEntryToLocal(entry, sourcePath, targetParentPath)
        if (!ok) return false
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

    private fun fetchListFrom(host: String, port: Int, path: String): RemoteList? {
        val url = buildUrl(host, port, "/api/v1/list", "path", path)
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val jsonText = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseList(jsonText)
        } catch (e: Exception) {
            AppLog.w(logTag, "List failed host=$host path=$path", e)
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

    private fun isLocalTarget(targetHost: String, targetPort: Int): Boolean {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        val localPort = prefs.getInt(MainActivity.KEY_SERVER_PORT, 0)
        val localHost = NetworkUtils.getLocalIpAddress()
        if (localPort <= 0 || localHost.isNullOrBlank()) return false
        return targetPort == localPort && targetHost == localHost
    }

    private fun checkTargetAvailability(
        targetHost: String,
        targetPort: Int,
        targetParentPath: String,
        name: String
    ): TargetCheck {
        if (isLocalTarget(targetHost, targetPort)) {
            val parent = safFileManager.resolveDocumentFile(targetParentPath)
            if (parent == null || !parent.isDirectory) {
                AppLog.w(logTag, "Target check failed, local parent missing path=$targetParentPath")
                return TargetCheck.ERROR
            }
            return if (parent.findFile(name) != null) {
                TargetCheck.EXISTS
            } else {
                TargetCheck.AVAILABLE
            }
        }
        val listing = fetchListFrom(targetHost, targetPort, targetParentPath) ?: return TargetCheck.ERROR
        return if (listing.entries.any { it.name == name }) {
            TargetCheck.EXISTS
        } else {
            TargetCheck.AVAILABLE
        }
    }

    private fun preflightTargetChecks(
        targetHost: String,
        targetPort: Int,
        targetParentPath: String,
        entries: List<RemoteEntry>
    ): TargetCheckResult {
        for (entry in entries) {
            when (checkTargetAvailability(targetHost, targetPort, targetParentPath, entry.name)) {
                TargetCheck.EXISTS -> return TargetCheckResult(false, true, entry.name)
                TargetCheck.ERROR -> return TargetCheckResult(false, false, entry.name)
                TargetCheck.AVAILABLE -> Unit
            }
        }
        return TargetCheckResult(true, false, null)
    }

    private fun loadSharedFolders(): List<SharedFolder> {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        val uris = prefs.getStringSet(MainActivity.KEY_TREE_URIS, emptySet()).orEmpty()
        return uris.mapNotNull { uriString ->
            val doc = DocumentFile.fromTreeUri(this, android.net.Uri.parse(uriString))
            val name = doc?.name ?: return@mapNotNull null
            SharedFolder(name = name, uriString = uriString)
        }
    }

    private enum class TargetCheck {
        AVAILABLE,
        EXISTS,
        ERROR
    }

    private data class TargetCheckResult(
        val ok: Boolean,
        val exists: Boolean,
        val name: String?
    )

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
        transferProgressView?.apply {
            isIndeterminate = false
            max = total.coerceAtLeast(1)
            progress = done.coerceAtMost(max)
            visibility = View.VISIBLE
        }
    }

    private fun showPreflightStatus(isCopy: Boolean) {
        val statusRes = if (isCopy) R.string.transfer_checking_copy else R.string.transfer_checking_move
        transferStatusView?.apply {
            text = getString(statusRes)
            visibility = View.VISIBLE
        }
        transferProgressView?.apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        transferLogView?.apply {
            text = ""
            visibility = View.GONE
        }
    }

    private fun showTransferPreparing(isCopy: Boolean, total: Int) {
        val statusRes = if (isCopy) R.string.transfer_start_copy else R.string.transfer_start_move
        transferStatusView?.apply {
            text = getString(statusRes, total)
            visibility = View.VISIBLE
        }
        transferProgressView?.apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        transferLogView?.apply {
            text = ""
            visibility = View.GONE
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

    private fun setTransferInProgress(inProgress: Boolean) {
        transferHideRunnable?.let { mainHandler.removeCallbacks(it) }
        if (inProgress) {
            transferBlocker?.visibility = View.VISIBLE
            actionBarView?.isEnabled = false
            transferProgressView?.apply {
                isIndeterminate = true
                visibility = View.VISIBLE
            }
        } else {
            transferBlocker?.visibility = View.GONE
            actionBarView?.isEnabled = true
            transferProgressView?.visibility = View.GONE
            transferStatusView?.visibility = View.GONE
            transferLogView?.visibility = View.GONE
        }
    }

    private fun finishTransferUi(ok: Boolean, isCopy: Boolean) {
        val statusRes = if (ok) {
            if (isCopy) R.string.copy_done else R.string.move_done
        } else {
            if (isCopy) R.string.copy_failed else R.string.move_failed
        }
        transferStatusView?.apply {
            text = getString(statusRes)
            visibility = View.VISIBLE
        }
        transferProgressView?.apply {
            isIndeterminate = false
            visibility = View.VISIBLE
        }
        updateTransferNotificationFinal(ok, isCopy)
        transferHideRunnable = Runnable { setTransferInProgress(false) }
        mainHandler.postDelayed(transferHideRunnable!!, 1500)
    }

    private fun clearSelectionAfterTransfer() {
        selected.clear()
        selectionMode = false
        updateSelectionUi()
    }

    private fun updateTransferNotification(done: Int, total: Int, isCopy: Boolean) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        val textRes = if (isCopy) R.string.transfer_notification_copy else R.string.transfer_notification_move
        val builder = androidx.core.app.NotificationCompat.Builder(this, TransferService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.transfer_notification_title))
            .setContentText(getString(textRes, done, total))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), total <= 0)
        manager.notify(TransferService.NOTIFICATION_ID, builder.build())
    }

    private fun updateTransferNotificationFinal(ok: Boolean, isCopy: Boolean) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        val textRes = if (ok) {
            if (isCopy) R.string.transfer_notification_copy_done else R.string.transfer_notification_move_done
        } else {
            if (isCopy) R.string.transfer_notification_copy_failed else R.string.transfer_notification_move_failed
        }
        val builder = androidx.core.app.NotificationCompat.Builder(this, TransferService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.transfer_notification_title))
            .setContentText(getString(textRes))
            .setOnlyAlertOnce(true)
            .setOngoing(false)
        manager.notify(TransferService.NOTIFICATION_ID, builder.build())
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
