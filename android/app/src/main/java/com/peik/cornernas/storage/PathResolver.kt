package com.peik.cornernas.storage

class PathResolver {
    fun normalize(path: String?): String {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty() || raw == "/") {
            return "/"
        }
        return if (raw.startsWith("/")) raw else "/$raw"
    }

    fun splitSegments(path: String): List<String> {
        return path.trim('/').split('/').filter { it.isNotBlank() }
    }
}
