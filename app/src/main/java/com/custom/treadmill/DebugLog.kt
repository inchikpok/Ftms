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
// [ФИКС] Внутри ArrayDeque + снимок в StateFlow раз в 300 мс вместо копирования
// List на каждую строку. При частом BLE это заметно снижает нагрузку на GC:
// старый код делал (old + line).takeLast(3000) на каждый emit — O(n) копию
// списка на 3000 элементов. Новый — O(1) add/removeFirst + один снимок раз в 300мс.
object DebugLog {

    private const val MAX_LINES = 3000
    private const val LOG_KEEP_DAYS = 3
    private const val EMIT_INTERVAL_MS = 300L

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val bufferLock = Any()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // Один writer для файла + один scheduler для периодического снимка в StateFlow.
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
            // Периодический снимок буфера → StateFlow для UI.
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
            // Если ничего не изменилось — не дёргаем StateFlow зря.
            if (snapshot.size != _lines.value.size || (snapshot.isNotEmpty() && snapshot.last() != _lines.value.lastOrNull())) {
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
        val today = dayFmt.format(Date())
        if (logDay != today) {
            logDay = today
            logFile = File(dir, "debug-$today.log")
        }
    }

    fun d(tag: String, msg: String) {
        val line = "[${timeFmt.format(Date())}][$tag] $msg"
        // [ФИКС] O(1) вставка и удаление в ArrayDeque.
        synchronized(bufferLock) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast(line)
        }
        Log.d("TC.$tag", msg)
        val f = logFile ?: return
        writer.execute {
            try {
                if (dayFmt.format(Date()) != logDay) f.parentFile?.let { rotate(it) }
                f.appendText(line + "\n")
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
