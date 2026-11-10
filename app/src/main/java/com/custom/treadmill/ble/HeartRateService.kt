package com.custom.treadmill.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HeartRateService(private val scope: CoroutineScope) {

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private var conn: BleConnection? = null
    private var lastUpdateMillis: Long = 0L
    private var watchdogJob: Job? = null

    // [ФИКС] Если 12 секунд не приходило пакетов — пульс сбрасывается в 0,
    // чтобы UI и AutoHR не работали по устаревшим данным.
    private val staleTimeoutMs = 12_000L

    fun initialize(conn: BleConnection): Boolean {
        this.conn = conn
        val char = conn.findCharacteristic(Uuids.HR_MEASUREMENT) ?: return false
        startWatchdog()
        return conn.setNotify(char.uuid, true, indicate = false)
    }

    fun onNotification(n: BleNotification) {
        if (n.characteristicUuid == Uuids.HR_MEASUREMENT) parse(n.value)
    }

    fun reset() {
        _heartRate.value = 0
        lastUpdateMillis = 0L
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val last = lastUpdateMillis
                if (last > 0L && System.currentTimeMillis() - last > staleTimeoutMs) {
                    if (_heartRate.value != 0) {
                        val sec = (System.currentTimeMillis() - last) / 1000
                        Log.d("HR", "пульс протух (${sec}с без пакетов) — сброс в 0")
                        _heartRate.value = 0
                    }
                }
            }
        }
    }

    private fun parse(b: ByteArray) {
        if (b.isEmpty()) return
        try {
            val flags = b[0].toInt() and 0xFF
            val hr = if (flags and 0x01 != 0) {
                if (b.size < 3) return
                (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)
            } else {
                if (b.size < 2) return
                b[1].toInt() and 0xFF
            }
            lastUpdateMillis = System.currentTimeMillis()
            _heartRate.value = hr
            Log.d("HR", "пульс = $hr")
        } catch (e: Exception) {
            Log.w("HR", "Ошибка разбора HR: ${e.message}")
        }
    }
}
