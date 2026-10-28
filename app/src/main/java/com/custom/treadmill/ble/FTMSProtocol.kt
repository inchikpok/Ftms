package com.custom.treadmill.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import java.util.UUID

class FTMSProtocol : ITreadmillProtocol {

    override val protocolName: String = "FTMS (стандарт)"

    private val _data = MutableStateFlow(TreadmillData())
    override val data: StateFlow<TreadmillData> = _data

    private val _capabilities = MutableStateFlow(TreadmillCapabilities())
    override val capabilities: StateFlow<TreadmillCapabilities> = _capabilities

    private var conn: BleConnection? = null
    private var controlPointUuid: UUID? = null
    override val writeCharacteristicUuid: UUID? get() = controlPointUuid

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendMutex = Mutex()

    var onLog: ((String) -> Unit)? = null

    var sendStartCommand: Boolean = true
    var startPreCommandDelayMs: Long = 200L
    var speedResolutionKmh: Double = 0.0
    var roundInclineToWhole: Boolean = true

    @Volatile private var lastRequestedSpeed = 0.0
    @Volatile private var lastSpeedSentAt = 0L
    @Volatile private var lastRequestedIncline = 0.0
    @Volatile private var lastInclineSentAt = 0L

    private var retryJob: Job? = null

    private fun log(msg: String) {
        Log.d("FTMS", msg)
        onLog?.invoke("FTMS: $msg")
    }

    override suspend fun initialize(conn: BleConnection): Boolean {
        this.conn = conn
        var controlFound = false

        for (uuid in Uuids.CONTROL_POINT_CANDIDATES) {
            if (conn.findCharacteristic(uuid) != null) {
                controlPointUuid = uuid
                controlFound = true
                Log.d("FTMS", "Control Point: $uuid")
                break
            }
        }

        var caps = TreadmillCapabilities()
        var anyLoaded = false

        val featureBytes = conn.findCharacteristic(Uuids.FTMS_FEATURE)?.let {
            conn.read(it.uuid)?.also { b -> log("Feature: ${b.toHex()}") }
        }
        featureBytes?.let {
            anyLoaded = true
            val fmap = TreadmillCapabilities.parseFeature(it)
            caps = caps.copy(
                supportsIncline = fmap["incline"] == true,
                supportsDistance = fmap["distance"] == true,
                supportsCalories = fmap["calories"] == true,
                supportsHeartRate = fmap["heartRate"] == true,
                supportsElapsedTime = fmap["elapsedTime"] == true
            )
        }

        val speedRangeBytes = conn.findCharacteristic(Uuids.FTMS_SUPPORTED_SPEED_RANGE)?.let {
            conn.read(it.uuid)?.also { b -> log("Speed Range: ${b.toHex()}") }
        }
        speedRangeBytes?.let {
            TreadmillCapabilities.parseSpeedRange(it)?.let { (mn, mx, inc) ->
                anyLoaded = true
                caps = caps.copy(minSpeedKmh = mn, maxSpeedKmh = mx, speedIncrementKmh = inc)
            }
        }

        val inclineRangeBytes = conn.findCharacteristic(Uuids.FTMS_SUPPORTED_INCLINE_RANGE)?.let {
            conn.read(it.uuid)?.also { b -> log("Incline Range: ${b.toHex()}") }
        }
        inclineRangeBytes?.let {
            TreadmillCapabilities.parseInclineRange(it)?.let { (mn, mx, inc) ->
                anyLoaded = true
                caps = caps.copy(
                    minInclinePercent = mn,
                    maxInclinePercent = mx,
                    inclineIncrementPercent = inc,
                    supportsIncline = true
                )
            }
        }

        if (anyLoaded) {
            caps = caps.copy(isLoaded = true)
            log("Возможности загружены: скорость %.1f–%.1f, наклон %s".format(
                caps.minSpeedKmh, caps.maxSpeedKmh,
                if (caps.supportsIncline) "%.1f–%.1f%%".format(caps.minInclinePercent, caps.maxInclinePercent) else "нет"
            ))
        } else {
            log("FTMS-возможности не предоставлены — используем дефолты")
        }
        _capabilities.value = caps

        // [ФИКС] Ждём реального ACK от дескрипторов
        if (conn.findCharacteristic(Uuids.FTMS_TREADMILL_DATA) != null) {
            conn.setNotifySuspend(Uuids.FTMS_TREADMILL_DATA, true, indicate = false)
        }
        controlPointUuid?.let { conn.setNotifySuspend(it, true, indicate = true) }

        lastRequestedSpeed = 0.0
        lastSpeedSentAt = 0L
        lastRequestedIncline = 0.0
        lastInclineSentAt = 0L

        delay(300)
        requestControl()
        startRetryLoop()

        return controlFound
    }

    private fun startRetryLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (isActive) {
                delay(2500L)
                val now = System.currentTimeMillis()
                val beltSpeed = _data.value.speedKmh
                val beltIncline = _data.value.inclinePercent

                val speedTarget = lastRequestedSpeed
                if (beltSpeed >= 0.5 && speedTarget >= 0.5 && now - lastSpeedSentAt >= 5000L) {
                    if (abs(beltSpeed - speedTarget) >= 0.5) {
                        sendSpeedRaw(speedTarget)
                        lastSpeedSentAt = now
                        log("retry speed $speedTarget (belt at $beltSpeed)")
                    }
                }

                val caps = _capabilities.value
                if (caps.isLoaded && !caps.supportsIncline) {
                    // наклон не поддерживается — не ретраим
                } else {
                    val inclineTarget = lastRequestedIncline
                    if (beltSpeed >= 0.5 && now - lastInclineSentAt >= 5000L) {
                        if (abs(beltIncline - inclineTarget) >= 0.5) {
                            sendInclineRaw(inclineTarget)
                            lastInclineSentAt = now
                            log("retry incline $inclineTarget (belt at $beltIncline)")
                        }
                    }
                }
            }
        }
    }

    fun shutdown() {
        retryJob?.cancel()
        retryJob = null
        scope.cancel()
    }

    private fun applySpeedResolution(v: Double): Double {
        if (v <= 0.0) return 0.0
        val caps = _capabilities.value
        val res = if (caps.isLoaded && caps.speedIncrementKmh > 0.0) caps.speedIncrementKmh else speedResolutionKmh
        if (res <= 0.0) return v
        val floored = floor(v / res) * res
        return floored.coerceAtLeast(res)
    }

    private suspend fun sendSpeedRaw(speedKmh: Double) {
        val uuid = controlPointUuid ?: return
        val c = conn ?: return
        val raw = (speedKmh * 100.0).roundToInt().coerceIn(0, 65535)
        sendMutex.withLock {
            val ok = c.writeSuspend(uuid, byteArrayOf(0x02, (raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte()), true)
            if (!ok) log("speed write FAILED: $speedKmh")
        }
    }

    private suspend fun sendInclineRaw(percent: Double) {
        val uuid = controlPointUuid ?: return
        val c = conn ?: return
        val raw = (percent * 10.0).roundToInt().coerceIn(0, 32767)
        sendMutex.withLock {
            val ok = c.writeSuspend(uuid, byteArrayOf(0x03, (raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte()), true)
            if (!ok) log("incline write FAILED: $percent")
        }
    }

    override suspend fun requestControl(): Boolean {
        val uuid = controlPointUuid ?: return false
        val c = conn ?: return false
        return sendMutex.withLock {
            c.writeSuspend(uuid, byteArrayOf(0x00), true)
        }
    }

    override suspend fun start(): Boolean {
        val uuid = controlPointUuid ?: return false
        val c = conn ?: return false

        sendMutex.withLock {
            c.writeSuspend(uuid, byteArrayOf(0x00), true)
            delay(startPreCommandDelayMs)
            if (sendStartCommand) {
                c.writeSuspend(uuid, byteArrayOf(0x07), true)
            }
        }
        log("старт (00+07)")
        return true
    }

    suspend fun ping07() {
        val uuid = controlPointUuid ?: return
        if (!sendStartCommand) return
        val c = conn ?: return
        sendMutex.withLock {
            c.writeSuspend(uuid, byteArrayOf(0x07), true)
        }
        Log.d("FTMS", "-> 07 (ping)")
    }

    override suspend fun stop(): Boolean {
        val uuid = controlPointUuid ?: return false
        val c = conn ?: return false
        lastRequestedSpeed = 0.0

        // Всегда отправляем 08 01, даже если кажется, что дорожка уже стоит.
        // Это очищает буфер дорожки и гарантирует остановку.
        sendMutex.withLock {
            c.writeSuspend(uuid, byteArrayOf(0x08, 0x01), true)
        }
        log("стоп (08 01)")
        return true
    }

    override suspend fun setSpeed(speedKmh: Double): Boolean {
        val v = applySpeedResolution(speedKmh.coerceAtLeast(0.0))
        lastRequestedSpeed = v
        lastSpeedSentAt = System.currentTimeMillis()
        sendSpeedRaw(v)
        log("скорость $v")
        return true
    }

    override suspend fun setIncline(percent: Double): Boolean {
        val caps = _capabilities.value
        if (caps.isLoaded && !caps.supportsIncline) {
            log("наклон не поддерживается дорожкой — команда проигнорирована")
            return false
        }
        val t = if (roundInclineToWhole) percent.coerceIn(0.0, 30.0).roundToInt().toDouble() else percent.coerceIn(0.0, 30.0)
        lastRequestedIncline = t
        lastInclineSentAt = System.currentTimeMillis()
        sendInclineRaw(t)
        log("наклон $t%")
        return true
    }

    suspend fun sendRaw(bytes: ByteArray): Boolean {
        val uuid = controlPointUuid ?: return false
        val c = conn ?: return false
        return sendMutex.withLock {
            c.writeSuspend(uuid, bytes, true)
        }
    }

    override fun onNotification(n: BleNotification) {
        when (n.characteristicUuid) {
            Uuids.FTMS_TREADMILL_DATA -> parseTreadmillData(n.value)
            Uuids.FTMS_CONTROL_POINT, Uuids.FTMS_CONTROL_POINT_ALT -> parseControlPointResponse(n.value)
        }
    }

    private fun parseControlPointResponse(b: ByteArray) {
        if (b.size >= 3 && (b[0].toInt() and 0xFF) == 0x80) {
            val op = b[1].toInt() and 0xFF
            val res = b[2].toInt() and 0xFF
            Log.d("FTMS", "CP resp op=0x%02X res=%d".format(op, res))
        }
    }

    private fun parseTreadmillData(b: ByteArray) {
        if (b.size < 2) return
        try {
            val flags = u16(b, 0)
            var o = 2
            val prev = _data.value

            var speed = prev.speedKmh
            // [ФИКС] Если флаг наклона не пришел, оставляем предыдущее значение
            var incline = prev.inclinePercent
            var distance = prev.distanceKm
            var calories = prev.calories
            var elapsed = prev.elapsedSec
            var hr = prev.heartRate

            // Instantaneous Speed (sint16)
            if (o + 2 <= b.size) {
                speed = u16(b, o) / 100.0
                o += 2
            } else return

            // Average Speed (sint16)
            if (flags and (1 shl 1) != 0) {
                if (o + 2 > b.size) return
                o += 2
            }

            // Total Distance (uint24)
            if (flags and (1 shl 2) != 0) {
                if (o + 3 > b.size) return
                distance = u24(b, o) / 1000.0
                o += 3
            }

            // Inclination (sint16) + Ramp Angle (sint16) = 4 байта
            if (flags and (1 shl 3) != 0) {
                if (o + 4 > b.size) return
                incline = s16(b, o) / 10.0
                o += 4
            }

            // Elevation Gain (uint16 pos + uint16 neg) = 4 байта
            if (flags and (1 shl 4) != 0) {
                if (o + 4 > b.size) return
                o += 4
            }

            // Instantaneous Pace (uint8)
            if (flags and (1 shl 5) != 0) {
                if (o + 1 > b.size) return
                o += 1
            }

            // Average Pace (uint8)
            if (flags and (1 shl 6) != 0) {
                if (o + 1 > b.size) return
                o += 1
            }

            // Total Energy (uint16) + Energy Per Hour (uint16) + Energy Per Minute (uint8) = 5 байт
            if (flags and (1 shl 7) != 0) {
                if (o + 5 > b.size) return
                calories = u16(b, o)
                o += 5
            }

            // Heart Rate (uint8)
            if (flags and (1 shl 8) != 0) {
                if (o + 1 > b.size) return
                hr = b[o].toInt() and 0xFF
                o += 1
            }

            // Metabolic Equivalent (uint8)
            if (flags and (1 shl 9) != 0) {
                if (o + 1 > b.size) return
                o += 1
            }

            // Elapsed Time (uint16)
            if (flags and (1 shl 10) != 0) {
                if (o + 2 > b.size) return
                elapsed = u16(b, o)
                o += 2
            }

            _data.value = TreadmillData(speed, incline, distance, calories, elapsed, hr, isRunning = speed > 0.05)
        } catch (e: Exception) {
            Log.w("FTMS", "Ошибка разбора: ${e.message}")
        }
    }

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun s16(b: ByteArray, o: Int): Int {
        val raw = u16(b, o)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }
    private fun u24(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)
}
