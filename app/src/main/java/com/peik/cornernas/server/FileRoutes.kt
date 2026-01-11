package com.peik.cornernas.server

import android.content.Context
import com.peik.cornernas.storage.SafFileManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
                call.respond(HttpStatusCode.BadRequest, "Missing path")
                return@get
            }
            val file = safFileManager.resolveDocumentFile(path)
            if (file == null || !file.isFile) {
                call.respond(HttpStatusCode.NotFound, "File not found")
                return@get
            }
            val input = safFileManager.openInputStream(file)
            if (input == null) {
                call.respond(HttpStatusCode.InternalServerError, "Unable to open file")
                return@get
            }
            call.respondOutputStream(ContentType.Application.OctetStream) {
                input.use { it.copyTo(this) }
            }
        }
        post("/upload") {
            val path = call.request.queryParameters["path"]
            if (path.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing path")
                return@post
            }
            val parent = safFileManager.resolveDocumentFile(path)
            if (parent == null || !parent.isDirectory) {
                call.respond(HttpStatusCode.NotFound, "Target directory not found")
                return@post
            }
            val multipart = call.receiveMultipart()
            var uploaded = false
            multipart.forEachPart { part ->
                if (part is io.ktor.server.request.PartData.FileItem) {
                    val filename = part.originalFileName ?: "upload.bin"
                    val created = safFileManager.createFile(parent, filename, part.contentType?.toString() ?: "application/octet-stream")
                    if (created != null) {
                        safFileManager.openOutputStream(created)?.use { output ->
                            part.streamProvider().use { input ->
                                input.copyTo(output)
                            }
                        }
                        uploaded = true
                    }
                }
                part.dispose()
            }
            if (uploaded) {
                call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.BadRequest, "No file uploaded")
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
