package com.custom.treadmill

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Центральный хаб логирования.
//
// Пишет одновременно в:
//   - StateFlow<List<String>> — для UI (DebugScreen)
//   - Logcat (фильтр: TC.TAG)
//   - Файл filesDir/logs/debug-YYYY-MM-DD.log — для экспорта
//
// Формат: [HH:mm:ss.SSS][TAG] сообщение
//
// [ФИКС] SimpleDateFormat обёрнут в ThreadLocal: d() вызывается из 4+ потоков
// одновременно (BLE, main, TTS, service). Без этого в логе появляются
// битые таймстампы вида "[12:34:5" или "[null]".
object DebugLog {

    private const val MAX_LINES = 3000
    private const val LOG_KEEP_DAYS = 3
    private const val EMIT_INTERVAL_MS = 300L

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val bufferLock = Any()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    // [ФИКС] ThreadLocal вместо shared instance.
    private val timeFmt: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
    private val dayFmt: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    private fun formatTimeNow(): String = timeFmt.get()!!.format(Date())
    private fun formatDayNow(): String = dayFmt.get()!!.format(Date())

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DebugLog-writer").apply { isDaemon = true }
    }
    private val emitter = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DebugLog-emitter").apply { isDaemon = true }
    }

    @Volatile private var logFile: File? = null
    @Volatile private var logDay: String? = null
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            cleanupOld(dir)
            rotate(dir)
            d("SYS", "DebugLog готов, dir=${dir.absolutePath}")
            emitter.scheduleWithFixedDelay(
                { emitSnapshot() },
                EMIT_INTERVAL_MS,
                EMIT_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (e: Exception) {
            Log.w("DebugLog", "init failed: ${e.message}")
        }
    }

    private fun emitSnapshot() {
        try {
            val snapshot = synchronized(bufferLock) { buffer.toList() }
            if (snapshot.size != _lines.value.size ||
                (snapshot.isNotEmpty() && snapshot.last() != _lines.value.lastOrNull())
            ) {
                _lines.value = snapshot
            }
        } catch (_: Exception) { }
    }

    private fun cleanupOld(dir: File) {
        try {
            val cutoff = System.currentTimeMillis() - LOG_KEEP_DAYS * 24L * 3600 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        } catch (_: Exception) {}
    }

    private fun rotate(dir: File) {
        val today = formatDayNow()
        if (logDay != today) {
            logDay = today
            logFile = File(dir, "debug-$today.log")
        }
    }

    fun d(tag: String, msg: String) {
        val line = "[${formatTimeNow()}][$tag] $msg"
        synchronized(bufferLock) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast(line)
        }
        Log.d("TC.$tag", msg)
        // [ФИКС] Раньше `f` захватывался до writer — если за день ротация
        // случалась между вызовами, строка уходила в старый файл. Теперь
        // читаем logFile заново внутри writer.
        writer.execute {
            try {
                val dir = logFile?.parentFile ?: return@execute
                if (formatDayNow() != logDay) rotate(dir)
                logFile?.appendText(line + "\n")
            } catch (e: Exception) {
                Log.w("DebugLog", "write failed: ${e.message}")
            }
        }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        val text = buildString {
            append(msg)
            if (t != null) append(" | ${t.javaClass.simpleName}: ${t.message}")
        }
        d("ERR/$tag", text)
    }

    fun clear() {
        synchronized(bufferLock) { buffer.clear() }
        _lines.value = emptyList()
        writer.execute {
            try { logFile?.writeText("") } catch (_: Exception) {}
        }
    }

    fun exportText(): String = buildString {
        appendLine("=== Treadmill Control debug log ===")
        appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        val snapshot = synchronized(bufferLock) { buffer.toList() }
        appendLine("Memory lines: ${snapshot.size}")
        val f = logFile
        if (f != null && f.exists()) {
            appendLine("File: ${f.name} (${f.length()} bytes)")
            appendLine("-----------------------------------")
            try { append(f.readText()) }
            catch (e: Exception) { appendLine("read failed: ${e.message}") }
        } else {
            appendLine("-----------------------------------")
            append(snapshot.joinToString("\n"))
        }
    }
}
