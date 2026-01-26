package com.peik.cornernas.server

import android.content.Context
import com.peik.cornernas.R
import com.peik.cornernas.storage.SafFileManager
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.configureRoutes(context: Context, safFileManager: SafFileManager) {
    route("/") {
        get {
            call.respondText(buildIndexHtml(context, call, safFileManager), io.ktor.http.ContentType.Text.Html)
        }
    }
    route("/api/v1") {
        get("/ping") {
            call.respondText("ok")
        }
        configureFileRoutes(context, safFileManager)
    }
}

private fun buildIndexHtml(context: Context, call: ApplicationCall, safFileManager: SafFileManager): String {
    val path = call.request.queryParameters["path"] ?: "/"
    val (normalized, entries) = safFileManager.list(path)
    val builder = StringBuilder()
    builder.append("<html><body>")
    builder.append("<h1>").append(context.getString(R.string.app_name)).append("</h1>")
    builder.append("<p>")
        .append(context.getString(R.string.web_path_label, normalized))
        .append("</p>")
    builder.append("<ul>")
    entries.forEach { entry ->
        val entryPath = if (normalized == "/") "/${entry.name}" else "$normalized/${entry.name}"
        if (entry.type == "dir") {
            builder.append("<li>[")
                .append(context.getString(R.string.web_dir_tag))
                .append("] <a href=\"/?path=$entryPath\">${entry.name}</a></li>")
        } else {
            builder.append("<li>[")
                .append(context.getString(R.string.web_file_tag))
                .append("] <a href=\"/api/v1/file?path=$entryPath\">${entry.name}</a></li>")
        }
    }
    builder.append("</ul>")
    builder.append("<form method=\"post\" enctype=\"multipart/form-data\" action=\"/api/v1/upload?path=$normalized\">\n")
    builder.append("<input type=\"file\" name=\"file\" />\n")
    builder.append("<button type=\"submit\">")
        .append(context.getString(R.string.web_upload))
        .append("</button>\n")
    builder.append("</form>")
    builder.append("</body></html>")
    return builder.toString()
}
