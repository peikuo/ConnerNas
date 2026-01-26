package com.peik.cornernas.util

import java.util.concurrent.CopyOnWriteArrayList

object LogStore {
    data class Entry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    private const val MAX_AGE_MS = 60_000L
    private val entries = CopyOnWriteArrayList<Entry>()

    fun add(level: String, tag: String, message: String) {
        val now = System.currentTimeMillis()
        entries.add(Entry(now, level, tag, message))
        purge(now)
    }

    fun getRecent(windowMs: Long = MAX_AGE_MS): List<Entry> {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs
        return entries.filter { it.timestamp >= cutoff }
    }

    private fun purge(now: Long) {
        val cutoff = now - MAX_AGE_MS
        entries.removeIf { it.timestamp < cutoff }
    }
}
