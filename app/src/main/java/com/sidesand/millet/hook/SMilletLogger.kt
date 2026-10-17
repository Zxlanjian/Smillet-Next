package com.sidesand.millet.hook

import android.util.Log
import com.sidesand.millet.data.ConfigKeys
import com.sidesand.millet.data.MilletConfigSnapshot
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SMilletLogger {
    private const val TAG = "Smillet-Next"
    const val MAX_BUFFER_LINES = 3000
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024L // 2 MB
    private const val ROTATE_KEEP_LINES = 2500
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val memoryBuffer = ArrayDeque<String>(MAX_BUFFER_LINES)
    private val canonicalLogRegex = Regex("20\\d{2}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[(DEBUG|INFO|WARN|ERROR)]")

    @Volatile
    var config: MilletConfigSnapshot = MilletConfigSnapshot()

    @Volatile
    var externalLogForwarder: ((String) -> Unit)? = null

    /**
     * 日志级别：
     * - "debug" (调试): rank = 1，输出所有调试与信息日志
     * - "info"  (信息): rank = 2，输出常规信息与告警/错误日志
     * - "none"  (无):   rank = 99，不输出任何日志
     */
    fun levelRank(levelStr: String): Int = when (levelStr.lowercase(Locale.ROOT).trim()) {
        "debug", "调试" -> 1
        "none", "off", "无" -> 99
        else -> 2
    }

    fun isDebugEnabled(): Boolean = levelRank(config.logLevel) <= 1

    private fun shouldLog(targetRank: Int): Boolean {
        val currentRank = levelRank(config.logLevel)
        if (currentRank >= 99) return false
        return targetRank >= currentRank
    }

    fun normalizeLogLine(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val match = canonicalLogRegex.find(trimmed)
        if (match != null && match.range.first > 0) {
            return trimmed.substring(match.range.first).trim()
        }
        return trimmed
    }

    fun debug(msg: String) {
        if (!shouldLog(1)) return
        write(Log.DEBUG, "DEBUG", msg, null)
    }

    fun info(msg: String) {
        if (!shouldLog(2)) return
        write(Log.INFO, "INFO", msg, null)
    }

    fun warn(msg: String) {
        if (!shouldLog(2)) return
        write(Log.WARN, "INFO", msg, null)
    }

    fun error(msg: String, t: Throwable? = null) {
        if (!shouldLog(2)) return
        val full = if (t != null) "$msg: ${t.message}" else msg
        write(Log.ERROR, "INFO", full, t)
    }

    fun recordExternalLog(formattedLine: String) {
        if (levelRank(config.logLevel) >= 99) return
        val normalized = normalizeLogLine(formattedLine)
        if (normalized.isEmpty()) return
        synchronized(memoryBuffer) {
            if (memoryBuffer.size >= MAX_BUFFER_LINES) {
                memoryBuffer.removeFirst()
            }
            memoryBuffer.addLast(normalized)
        }
        appendToLocalFile(normalized)
    }

    fun getBufferedLogs(): String {
        val fileLines = runCatching {
            val file = File(ConfigKeys.LOCAL_LOG_PATH)
            if (file.exists() && file.canRead()) {
                file.readLines()
                    .map { normalizeLogLine(it) }
                    .filter { it.isNotEmpty() }
                    .takeLast(MAX_BUFFER_LINES)
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        val memLines = synchronized(memoryBuffer) {
            memoryBuffer.toList()
        }

        if (fileLines.isEmpty()) {
            return memLines.joinToString("\n")
        }
        val merged = LinkedHashSet<String>(fileLines.size + memLines.size)
        merged.addAll(fileLines)
        merged.addAll(memLines)
        val sorted = merged.toList().sortedBy { line ->
            if (line.length >= 23 && line.startsWith("20")) line.substring(0, 23) else line
        }
        return sorted.takeLast(MAX_BUFFER_LINES).joinToString("\n")
    }

    private fun appendToLocalFile(formatted: String) {
        runCatching {
            val file = File(ConfigKeys.LOCAL_LOG_PATH)
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val tail = runCatching { file.readLines().takeLast(ROTATE_KEEP_LINES) }.getOrDefault(emptyList())
                file.writeText(tail.joinToString("\n") + "\n")
            }
            FileWriter(file, true).use { it.appendLine(formatted) }
        }
    }

    private fun write(priority: Int, level: String, msg: String, t: Throwable?) {
        val formatted = "${dateFormat.format(Date())} [$level] [$TAG] $msg"
        synchronized(memoryBuffer) {
            if (memoryBuffer.size >= MAX_BUFFER_LINES) {
                memoryBuffer.removeFirst()
            }
            memoryBuffer.addLast(formatted)
        }
        XposedCompat.log(priority, TAG, formatted, t)
        val forwarder = externalLogForwarder
        if (forwarder != null) {
            runCatching { forwarder.invoke(formatted) }
        } else {
            appendToLocalFile(formatted)
        }
    }
}
