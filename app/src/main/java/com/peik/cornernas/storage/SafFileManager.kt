package com.peik.cornernas.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.ContentType

class SafFileManager(
    private val context: Context,
    private val sharedFoldersProvider: () -> List<SharedFolder>
) {
    data class Entry(
        val name: String,
        val type: String,
        val size: Long? = null
    )

    private val resolver: ContentResolver = context.contentResolver
    private val pathResolver = PathResolver()

    fun list(path: String?): Pair<String, List<Entry>> {
        val normalized = pathResolver.normalize(path)
        if (normalized == "/") {
            val roots = sharedFoldersProvider().map {
                Entry(name = it.name, type = "dir")
            }
            return normalized to roots
        }
        val file = resolveDocumentFile(normalized) ?: return normalized to emptyList()
        if (!file.isDirectory) {
            return normalized to emptyList()
        }
        val entries = file.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            if (child.isDirectory) {
                Entry(name = name, type = "dir")
            } else {
                Entry(name = name, type = "file", size = child.length())
            }
        }.sortedWith(compareBy({ it.type }, { it.name.lowercase() }))
        return normalized to entries
    }

    fun resolveDocumentFile(path: String): DocumentFile? {
        val normalized = pathResolver.normalize(path)
        val segments = pathResolver.splitSegments(normalized)
        if (segments.isEmpty()) return null
        val rootName = segments.first()
        val rootFolder = sharedFoldersProvider().firstOrNull { it.name == rootName } ?: return null
        var current = DocumentFile.fromTreeUri(context, Uri.parse(rootFolder.uriString)) ?: return null
        for (segment in segments.drop(1)) {
            val next = current.findFile(segment) ?: return null
            current = next
        }
        return current
    }

    fun openInputStream(file: DocumentFile) = resolver.openInputStream(file.uri)

    fun openOutputStream(file: DocumentFile) = resolver.openOutputStream(file.uri)

    fun createFile(parent: DocumentFile, name: String, mimeType: String): DocumentFile? {
        val existing = parent.findFile(name)
        if (existing != null) {
            existing.delete()
        }
        return parent.createFile(mimeType, name)
    }

    fun resolveMimeType(file: DocumentFile): ContentType? {
        val resolved = resolver.getType(file.uri) ?: return null
        return try {
            ContentType.parse(resolved)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
