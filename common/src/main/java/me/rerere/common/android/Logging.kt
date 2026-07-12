package me.rerere.common.android

import java.util.Collections

private const val MAX_RECENT_LOGS = 2000

object Logging {
    private val recentLogs = Collections.synchronizedList(arrayListOf<String>())

    fun log(tag: String, message: String) {
        synchronized(recentLogs) {
            recentLogs.add(0, "$tag: $message")
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeAt(recentLogs.size - 1)
            }
        }
    }

    fun getRecentLogs(): List<String> {
        return synchronized(recentLogs) {
            recentLogs.toList()
        }
    }
}
