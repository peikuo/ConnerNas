package com.peik.cornernas.server

import android.content.Context
import com.peik.cornernas.R
import com.peik.cornernas.storage.SafFileManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.core.readAvailable
import kotlin.math.min

fun Route.configureFileRoutes(context: Context, safFileManager: SafFileManager) {
    route("") {
        get("/list") {
            val path = call.request.queryParameters["path"]
            val (normalized, entries) = safFileManager.list(path)
            call.respondText(buildListJson(normalized, entries), ContentType.Application.Json)
        }
        get("/file") {
            val path = call.request.queryParameters["path"]
            if (path.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, context.getString(R.string.error_missing_path))
                return@get
            }
            val file = safFileManager.resolveDocumentFile(path)
            if (file == null || !file.isFile) {
                call.respond(HttpStatusCode.NotFound, context.getString(R.string.error_file_not_found))
                return@get
            }
            val input = safFileManager.openInputStream(file)
            if (input == null) {
                call.respond(HttpStatusCode.InternalServerError, context.getString(R.string.error_open_file))
                return@get
            }
            val contentType = safFileManager.resolveMimeType(file) ?: ContentType.Application.OctetStream
            val totalLength = file.length()
            val rangeHeader = call.request.headers[HttpHeaders.Range]
            val range = parseRange(rangeHeader, totalLength)
            call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
            if (range == null) {
                if (totalLength > 0) {
                    call.response.headers.append(HttpHeaders.ContentLength, totalLength.toString())
                }
                call.respondOutputStream(contentType) {
                    input.use { it.copyTo(this) }
                }
                return@get
            }
            val start = range.first
            val end = range.second
            val contentLength = end - start + 1
            call.response.headers.append(HttpHeaders.ContentRange, "bytes $start-$end/$totalLength")
            call.response.headers.append(HttpHeaders.ContentLength, contentLength.toString())
            call.respondOutputStream(contentType, HttpStatusCode.PartialContent) {
                input.use { stream ->
                    var skipped = 0L
                    while (skipped < start) {
                        val step = min(DEFAULT_BUFFER_SIZE.toLong(), start - skipped).toInt()
                        val skipCount = stream.skip(step.toLong())
                        if (skipCount <= 0L) break
                        skipped += skipCount
                    }
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val toRead = min(buffer.size.toLong(), remaining).toInt()
                        val read = stream.read(buffer, 0, toRead)
                        if (read <= 0) break
                        write(buffer, 0, read)
                        remaining -= read.toLong()
                    }
                }
            }
        }
        post("/upload") {
            val path = call.request.queryParameters["path"]
            if (path.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, context.getString(R.string.error_missing_path))
                return@post
            }
            val parent = safFileManager.resolveDocumentFile(path)
            if (parent == null || !parent.isDirectory) {
                call.respond(HttpStatusCode.NotFound, context.getString(R.string.error_target_dir_not_found))
                return@post
            }
            val multipart = call.receiveMultipart()
            var uploaded = false
            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    val filename = part.originalFileName ?: "upload.bin"
                    val created = safFileManager.createFile(parent, filename, part.contentType?.toString() ?: "application/octet-stream")
                    if (created != null) {
                        safFileManager.openOutputStream(created)?.use { output ->
                            part.provider().use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (!input.endOfInput) {
                                    val read = input.readAvailable(buffer, 0, buffer.size)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        uploaded = true
                    }
                }
                part.dispose()
            }
            if (uploaded) {
                call.respondText(context.getString(R.string.upload_ok), ContentType.Text.Plain, HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.BadRequest, context.getString(R.string.error_no_file_uploaded))
            }
        }
    }
}

private fun buildListJson(path: String, entries: List<SafFileManager.Entry>): String {
    val builder = StringBuilder()
    builder.append("{\"path\":\"").append(escapeJson(path)).append("\",\"entries\":[")
    entries.forEachIndexed { index, entry ->
        if (index > 0) builder.append(',')
        builder.append("{\"name\":\"").append(escapeJson(entry.name)).append("\",\"type\":\"")
            .append(escapeJson(entry.type)).append("\"")
        if (entry.size != null) {
            builder.append(",\"size\":").append(entry.size)
        }
        builder.append('}')
    }
    builder.append("]}")
    return builder.toString()
}

private fun escapeJson(value: String): String {
    val escaped = StringBuilder()
    value.forEach { ch ->
        when (ch) {
            '"' -> escaped.append("\\\"")
            '\\' -> escaped.append("\\\\")
            '\n' -> escaped.append("\\n")
            '\r' -> escaped.append("\\r")
            '\t' -> escaped.append("\\t")
            else -> escaped.append(ch)
        }
    }
    return escaped.toString()
}

private fun parseRange(header: String?, totalLength: Long): Pair<Long, Long>? {
    if (header.isNullOrBlank()) return null
    if (!header.startsWith("bytes=")) return null
    if (totalLength <= 0) return null
    val rangePart = header.removePrefix("bytes=").trim()
    val dashIndex = rangePart.indexOf('-')
    if (dashIndex <= 0) return null
    val startText = rangePart.substring(0, dashIndex).trim()
    val endText = rangePart.substring(dashIndex + 1).trim()
    val start = startText.toLongOrNull() ?: return null
    val end = if (endText.isEmpty()) totalLength - 1 else endText.toLongOrNull() ?: return null
    if (start < 0 || end < start) return null
    return start to min(end, totalLength - 1)
}
