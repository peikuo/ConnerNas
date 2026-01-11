package com.peik.cornernas.server

import android.content.Context
import com.peik.cornernas.storage.SafFileManager
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.configureRoutes(context: Context, safFileManager: SafFileManager) {
    route("/") {
        get {
            call.respondText(buildIndexHtml(call, safFileManager), io.ktor.http.ContentType.Text.Html)
        }
    }
    route("/api/v1") {
        configureFileRoutes(context, safFileManager)
    }
}

private fun buildIndexHtml(call: ApplicationCall, safFileManager: SafFileManager): String {
    val path = call.request.queryParameters["path"] ?: "/"
    val (normalized, entries) = safFileManager.list(path)
    val builder = StringBuilder()
    builder.append("<html><body>")
    builder.append("<h1>CornerNAS</h1>")
    builder.append("<p>Path: ").append(normalized).append("</p>")
    builder.append("<ul>")
    entries.forEach { entry ->
        val entryPath = if (normalized == "/") "/${entry.name}" else "$normalized/${entry.name}"
        if (entry.type == "dir") {
            builder.append("<li>[DIR] <a href=\"/?path=$entryPath\">${entry.name}</a></li>")
        } else {
            builder.append("<li>[FILE] <a href=\"/api/v1/file?path=$entryPath\">${entry.name}</a></li>")
        }
    }
    builder.append("</ul>")
    builder.append("<form method=\"post\" enctype=\"multipart/form-data\" action=\"/api/v1/upload?path=$normalized\">\n")
    builder.append("<input type=\"file\" name=\"file\" />\n")
    builder.append("<button type=\"submit\">Upload</button>\n")
    builder.append("</form>")
    builder.append("</body></html>")
    return builder.toString()
}
