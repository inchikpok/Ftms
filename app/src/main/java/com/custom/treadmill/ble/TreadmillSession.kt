package com.custom.treadmill.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.custom.treadmill.DebugLog
import com.custom.treadmill.R
import com.custom.treadmill.TreadmillApp
import com.custom.treadmill.data.database.HrSamplesCodec
import com.custom.treadmill.data.database.ProgramData
import com.custom.treadmill.data.database.WorkoutLogEntity
import com.custom.treadmill.logic.HeartRateController
import com.custom.treadmill.logic.SpeedFormatter
import com.custom.treadmill.logic.TtsManager
import com.custom.treadmill.logic.WorkoutManager
import com.custom.treadmill.service.TreadmillService
import com.custom.treadmill.ui.components.HrPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class TreadmillSession(private val appContext: Context) {

    private val ble = (appContext as TreadmillApp).bleManager
    private val settingsStore = (appContext as TreadmillApp).settingsStore
    private val repository = (appContext as TreadmillApp).programRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var treadmillConn: BleConnection? = null
    private var hrConn: BleConnection? = null
    private var protocol: FTMSProtocol? = null
    private val hrService = HeartRateService(scope)

    private var treadmillStateJob: Job? = null
    private var treadmillNotifyJob: Job? = null
    private var treadmillLogJob: Job? = null
    private var hrStateJob: Job? = null
    private var hrNotifyJob: Job? = null
    private var hrLogJob: Job? = null
    private var hrMirrorJob: Job? = null
    private var autoHrJob: Job? = null
    private var startJob: Job? = null
    private var autoReconnectJob: Job? = null

    private val speedChannel = Channel<Double>(Channel.CONFLATED)
    private val inclineChannel = Channel<Double>(Channel.CONFLATED)

    private val _treadmillState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val treadmillState: StateFlow<BleConnectionState> = _treadmillState.asStateFlow()

    private val _treadmillName = MutableStateFlow<String?>(null)
    val treadmillName: StateFlow<String?> = _treadmillName.asStateFlow()

    private val _capabilities = MutableStateFlow(TreadmillCapabilities())
    val capabilities: StateFlow<TreadmillCapabilities> = _capabilities.asStateFlow()

    private val _hrName = MutableStateFlow<String?>(null)
    val hrName: StateFlow<String?> = _hrName.asStateFlow()

    private val _hrState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val hrState: StateFlow<BleConnectionState> = _hrState.asStateFlow()

    private val _treadmillData = MutableStateFlow(TreadmillData())
    val treadmillData: StateFlow<TreadmillData> = _treadmillData.asStateFlow()

    private val _targetSpeed = MutableStateFlow(0.0)
    val targetSpeed: StateFlow<Double> = _targetSpeed.asStateFlow()

    private val _targetIncline = MutableStateFlow(0.0)
    val targetIncline: StateFlow<Double> = _targetIncline.asStateFlow()

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _hrHistory = MutableStateFlow<List<HrPoint>>(emptyList())
    val hrHistory: StateFlow<List<HrPoint>> = _hrHistory.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _uiElapsedSec = MutableStateFlow(0)
    val uiElapsedSec: StateFlow<Int> = _uiElapsedSec.asStateFlow()

    private val _uiDistanceKm = MutableStateFlow(0.0)
    val uiDistanceKm: StateFlow<Double> = _uiDistanceKm.asStateFlow()

    private val _uiCalories = MutableStateFlow(0)
    val uiCalories: StateFlow<Int> = _uiCalories.asStateFlow()

    val scanCooldownUntil: StateFlow<Long> = ble.scanCooldownUntil
    val logs: StateFlow<List<String>> = DebugLog.lines

    private var lastSpeedCmdTime = 0L
    private var lastInclineCmdTime = 0L
    private var isStartingBelt = false
    private var speedBeforePause: Double = 0.0
    private var totalCaloriesDouble = 0.0

    private val workoutManager = WorkoutManager()
    val workoutState = workoutManager.state

    private var lastConnectedMac: String? = null
    private var isAutoReconnecting = false
    private var isManualDisconnect = false
    private var trainingStartMillis: Long = 0L
    private var wasRunningBeforeDisconnect: Boolean = false

    private var hrSum = 0L; private var hrCount = 0L; private var hrMax = 0
    private var speedSum = 0.0; private var speedCount = 0L
    private var workoutLogSaved = false

    private val tts: TtsManager get() = (appContext as TreadmillApp).ttsManager
    private var lastAutoHrEnabled: Boolean = false
    private var lastSpokenSegmentIndex: Int = -1

    init {
        DebugLog.init(appContext)
        scope.launch { ble.scanLog.collect { addLog(it) } }

        scope.launch {
            for (speed in speedChannel) {
                try {
                    protocol?.setSpeed(speed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addLog("Ошибка отправки скорости $speed: ${e.message}")
                }
            }
        }
        scope.launch {
            for (incl in inclineChannel) {
                try {
                    protocol?.setIncline(incl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addLog("Ошибка отправки уклона $incl: ${e.message}")
                }
            }
        }

        scope.launch {
            _heartRate.collect { hr ->
                if (hr > 0 && _uiElapsedSec.value > 0) {
                    val now = System.currentTimeMillis()
                    val list = _hrHistory.value
                    val last = list.lastOrNull()
                    if (last == null || now - last.timestamp >= 900L) {
                        _hrHistory.value = list + HrPoint(now, hr)
                    }
                }
            }
        }

        scope.launch {
            _treadmillData.collect { d ->
                val now = System.currentTimeMillis()
                val isWorkoutRunning = workoutManager.state.value.running
                if (!isStartingBelt && !isWorkoutRunning && now - lastSpeedCmdTime > 3500 && abs(_targetSpeed.value - d.speedKmh) > 0.1) {
                    _targetSpeed.value = d.speedKmh
                }
                if (!isWorkoutRunning && now - lastInclineCmdTime > 3500 && abs(_targetIncline.value - d.inclinePercent) > 0.4) {
                    _targetIncline.value = d.inclinePercent
                }
            }
        }

        scope.launch {
            settingsStore.settings.collect { s ->
                if (s.hrEnabled != lastAutoHrEnabled) {
                    lastAutoHrEnabled = s.hrEnabled
                    restartAutoHrLoop()
                }
            }
        }

        scope.launch {
            workoutManager.state.collect { ws ->
                if (!ws.running) { lastSpokenSegmentIndex = -1; return@collect }
                if (ws.paused) return@collect
                val idx = ws.currentSegmentIndex
                if (idx == lastSpokenSegmentIndex) return@collect
                if (idx == 0 && lastSpokenSegmentIndex == -1) { lastSpokenSegmentIndex = 0; return@collect }
                lastSpokenSegmentIndex = idx
                speakSegmentChange(idx, ws.totalSegments, ws.currentSegmentName)
            }
        }

        startMetricsLoop()
    }

    private fun speak(text: String) {
        if (!settingsStore.settings.value.ttsEnabled) return
        tts.speak(text)
        addLog("TTS: $text")
    }

    fun testSpeak(text: String) {
        tts.speak(text)
        addLog("TTS тест: $text")
    }

    private fun speakSegmentChange(idx: Int, total: Int, name: String) {
        scope.launch {
            delay(150L)
            val n = idx + 1
            val spd = _targetSpeed.value
            val inc = _targetIncline.value
            val template = settingsStore.settings.value.ttsSegmentTemplate
            // [ФИКС] Добавлены {pace} и {pace_text} — темп цифрой и словами.
            val text = template
                .replace("{i}", n.toString())
                .replace("{n}", total.toString())
                .replace("{segName}", name.ifBlank { appContext.getString(R.string.programs_segment_default, n) })
                .replace("{speed}", "%.1f".format(spd))
                .replace("{speed_text}", fmtSpeed(spd))
                .replace("{pace}", SpeedFormatter.formatPace(spd))
                .replace("{pace_text}", SpeedFormatter.formatPaceText(spd, appContext))
                .replace("{angle}", inc.roundToInt().toString())
                .replace("{angle_text}", fmtAngle(inc))
            speak(text)
        }
    }

    private fun fmtSpeed(kmh: Double): String {
        val n = kmh.roundToInt()
        return appContext.resources.getQuantityString(R.plurals.tts_kmh_plural, n, n)
    }

    private fun fmtAngle(pct: Double): String {
        val n = pct.roundToInt()
        return appContext.resources.getQuantityString(R.plurals.tts_percent_plural, n, n)
    }

    private fun restartAutoHrLoop() {
        autoHrJob?.cancel(); autoHrJob = null
        val s = settingsStore.settings.value
        if (!s.hrEnabled) return
        if (_treadmillState.value != BleConnectionState.READY) return
        autoHrJob = scope.launch {
            addLog("AutoHR: цикл запущен (интервал ${s.intervalSec}с, режим ${s.hrMode})")
            while (isActive) {
                val cur = settingsStore.settings.value
                if (!cur.hrEnabled) break
                if (_treadmillState.value != BleConnectionState.READY) break
                delay(cur.intervalSec * 1000L)
                val s2 = settingsStore.settings.value
                if (!s2.hrEnabled) break
                if (_treadmillState.value != BleConnectionState.READY) break
                val ws = workoutManager.state.value
                if (ws.running || ws.paused) continue
                if (_isPaused.value) continue
                val hr = _heartRate.value
                if (hr <= 0) continue
                val beltSpeed = _treadmillData.value.speedKmh
                if (beltSpeed < 0.5) continue
                val newSpeed = HeartRateController.evaluate(s2, hr, beltSpeed)
                if (newSpeed != null) {
                    addLog("AutoHR: пульс=$hr, скорость $beltSpeed → $newSpeed")
                    setSpeed(newSpeed)
                }
            }
            addLog("AutoHR: цикл остановлен")
        }
    }

    fun showMessage(msg: String) { _statusMessage.value = msg }

    private fun startMetricsLoop() {
        scope.launch {
            while (isActive) {
                delay(1000L)
                if (_treadmillState.value != BleConnectionState.READY) continue

                val hr = _heartRate.value
                if (hr > 0 && _uiElapsedSec.value > 0) {
                    hrSum += hr
                    hrCount++
                    if (hr > hrMax) hrMax = hr
                }

                val d = _treadmillData.value

                if (d.speedKmh > 0.05) {
                    speedSum += d.speedKmh
                    speedCount++
                }

                if (d.speedKmh < 0.3) continue

                val weight = settingsStore.settings.value.activeProfile.weightKg
                val met = if (d.speedKmh < 6.0) 1.0 + (d.speedKmh * 0.5) + (d.inclinePercent * 0.1)
                else 1.0 + (d.speedKmh * 0.9) + (d.inclinePercent * 0.15)
                val kcalPerMin = (met * 3.5 * weight) / 200.0
                _uiElapsedSec.value += 1
                _uiDistanceKm.value += d.speedKmh / 3600.0
                totalCaloriesDouble += kcalPerMin / 60.0
                _uiCalories.value = totalCaloriesDouble.toInt()

                if (_uiElapsedSec.value % 60 == 0) {
                    addLog(
                        "Tick t=${_uiElapsedSec.value}s spd=${"%.1f".format(d.speedKmh)} " +
                        "inc=${"%.0f".format(d.inclinePercent)}% hr=$hr " +
                        "target=${"%.1f".format(_targetSpeed.value)}"
                    )
                }
            }
        }
    }

    fun startScanTreadmill() {
        val wait = (ble.scanCooldownUntil.value - System.currentTimeMillis()).coerceAtLeast(0)
        if (wait > 0) {
            _statusMessage.value = appContext.getString(R.string.main_btn_cooldown, (wait / 1000).toInt() + 1)
            addLog("startScanTreadmill: BLOCKED, wait=$wait")
            return
        }
        addLog("startScanTreadmill: запуск")
        ble.startScan()
    }

    fun startScanHeartRate() {
        val wait = (ble.scanCooldownUntil.value - System.currentTimeMillis()).coerceAtLeast(0)
        if (wait > 0) {
            _statusMessage.value = appContext.getString(R.string.main_btn_cooldown, (wait / 1000).toInt() + 1)
            addLog("startScanHeartRate: BLOCKED, wait=$wait")
            return
        }
        addLog("startScanHeartRate: запуск")
        ble.startScan()
    }

    fun stopScan() = ble.stopScan()
    fun clearStatusMessage() { _statusMessage.value = null }

    fun connectTreadmill(device: BluetoothDevice) {
        lastConnectedMac = device.address
        isManualDisconnect = false
        wasRunningBeforeDisconnect = false
        autoReconnectJob?.cancel(); autoReconnectJob = null
        isAutoReconnecting = false
        disconnectTreadmillInternal()
        _treadmillName.value = try { device.name } catch (_: SecurityException) { null } ?: "Treadmill"

        val intent = Intent(appContext, TreadmillService::class.java)
        ContextCompat.startForegroundService(appContext, intent)

        val conn = BleConnection(appContext, "TR")
        treadmillConn = conn
        treadmillLogJob = scope.launch { conn.log.collect { addLog(it) } }

        treadmillStateJob = scope.launch {
            var hasBeenReady = false
            conn.state.collect { state ->
                _treadmillState.value = state
                if (state == BleConnectionState.READY) { hasBeenReady = true; onTreadmillReady(conn) }
                if (state == BleConnectionState.DISCONNECTED && hasBeenReady) {
                    if (!isManualDisconnect && lastConnectedMac != null) triggerAutoReconnect() else stopService()
                }
            }
        }
        treadmillNotifyJob = scope.launch { conn.notifications.collect { protocol?.onNotification(it) } }
        conn.connect(device)
    }

    private fun triggerAutoReconnect() {
        if (isAutoReconnecting) return
        val mac = lastConnectedMac ?: return
        val adapter = ble.adapter ?: return

        val ws = workoutManager.state.value
        val workoutWasRunning = ws.running && !ws.paused
        val manualWasRunning = !workoutWasRunning && !_isPaused.value && _treadmillData.value.speedKmh > 0.5
        if (workoutWasRunning) { addLog("Потеря связи — программа на паузе"); workoutManager.pause() }
        if (manualWasRunning) {
            addLog("Потеря связи — ручной режим на паузе")
            speedBeforePause = _targetSpeed.value.coerceAtLeast(1.0)
            _isPaused.value = true
        }
        wasRunningBeforeDisconnect = workoutWasRunning || manualWasRunning

        isAutoReconnecting = true
        addLog("Связь потеряна. Запуск автопереподключения к $mac...")
        _statusMessage.value = appContext.getString(R.string.session_lost_connection)

        autoReconnectJob = scope.launch {
            for (i in 1..10) {
                if (isManualDisconnect) break
                if (_treadmillState.value == BleConnectionState.READY) break
                addLog("Попытка переподключения $i из 10...")
                val device = try { adapter.getRemoteDevice(mac) } catch (_: Exception) { null }
                if (device != null) {
                    _treadmillState.value = BleConnectionState.CONNECTING
                    treadmillConn?.disconnect()
                    val conn = BleConnection(appContext, "TR")
                    treadmillConn = conn
                    treadmillLogJob?.cancel()
                    treadmillLogJob = scope.launch { conn.log.collect { addLog(it) } }
                    treadmillStateJob?.cancel()
                    treadmillStateJob = scope.launch {
                        conn.state.collect { state ->
                            _treadmillState.value = state
                            if (state == BleConnectionState.READY) onTreadmillReady(conn)
                        }
                    }
                    treadmillNotifyJob?.cancel()
                    treadmillNotifyJob = scope.launch { conn.notifications.collect { protocol?.onNotification(it) } }
                    conn.connect(device)
                }
                delay(3000)
            }
            isAutoReconnecting = false
            autoReconnectJob = null
            if (_treadmillState.value != BleConnectionState.READY) {
                addLog("Автопереподключение не удалось после 10 попыток.")
                _statusMessage.value = appContext.getString(R.string.session_reconnect_failed)
                wasRunningBeforeDisconnect = false
                stopService()
            } else {
                addLog("Связь успешно восстановлена!")
                _statusMessage.value = if (wasRunningBeforeDisconnect)
                    appContext.getString(R.string.session_reconnected_paused)
                else appContext.getString(R.string.session_reconnected)
                wasRunningBeforeDisconnect = false
            }
        }
    }

    private fun onTreadmillReady(conn: BleConnection) {
        scope.launch {
            protocol?.shutdown()
            val p = FTMSProtocol().apply {
                sendStartCommand = true
                onLog = { msg -> addLog(msg) }
            }
            protocol = p
            addLog("Протокол: ${p.protocolName}")
            scope.launch { p.data.collect { _treadmillData.value = it } }
            scope.launch { p.capabilities.collect { _capabilities.value = it } }
            val ok = p.initialize(conn)
            addLog(if (ok) "Протокол инициализирован" else "Ошибка инициализации")
            _statusMessage.value = if (ok) appContext.getString(R.string.session_treadmill_ready) else appContext.getString(R.string.session_chars_not_found)
            _targetSpeed.value = _treadmillData.value.speedKmh
            _targetIncline.value = _treadmillData.value.inclinePercent
            restartAutoHrLoop()
        }
    }

    fun disconnectTreadmill() {
        isManualDisconnect = true
        lastConnectedMac = null
        wasRunningBeforeDisconnect = false
        addLog("Ручное отключение дорожки")
        ble.setScanCooldown(5_000L)
        _statusMessage.value = appContext.getString(R.string.main_btn_cooldown, 5)
        autoReconnectJob?.cancel(); autoReconnectJob = null
        isAutoReconnecting = false
        disconnectTreadmillInternal()
        stopService()
    }

    private fun disconnectTreadmillInternal() {
        treadmillStateJob?.cancel(); treadmillStateJob = null
        treadmillNotifyJob?.cancel(); treadmillNotifyJob = null
        autoHrJob?.cancel(); autoHrJob = null
        startJob?.cancel(); startJob = null
        autoReconnectJob?.cancel(); autoReconnectJob = null
        isAutoReconnecting = false
        treadmillConn?.disconnect()
        treadmillConn = null
        protocol?.shutdown()
        protocol = null
        val oldLogJob = treadmillLogJob
        treadmillLogJob = null
        if (oldLogJob != null) { scope.launch { delay(500L); oldLogJob.cancel() } }
        _treadmillState.value = BleConnectionState.DISCONNECTED
        _treadmillData.value = TreadmillData()
        _capabilities.value = TreadmillCapabilities()
        _targetSpeed.value = 0.0
        _targetIncline.value = 0.0
        _treadmillName.value = null
        _isPaused.value = false
        speedBeforePause = 0.0
    }

    private fun stopService() {
        appContext.stopService(Intent(appContext, TreadmillService::class.java))
    }

    fun connectHeartRate(device: BluetoothDevice) {
        disconnectHeartRate()
        _hrName.value = try { device.name } catch (_: SecurityException) { null } ?: "HR"
        val conn = BleConnection(appContext, "HR")
        hrConn = conn
        hrService.reset()
        hrLogJob = scope.launch { conn.log.collect { addLog(it) } }
        hrStateJob = scope.launch {
            conn.state.collect { state ->
                _hrState.value = state
                if (state == BleConnectionState.READY) {
                    val ok = hrService.initialize(conn)
                    addLog(if (ok) "HR-сервис инициализирован" else "HR Measurement не найдена")
                }
                if (state == BleConnectionState.DISCONNECTED) {
                    _heartRate.value = 0
                    _statusMessage.value = appContext.getString(R.string.session_hr_disconnected)
                }
            }
        }
        hrNotifyJob = scope.launch {
            conn.notifications.collect {
                hrService.onNotification(it)
            }
        }
        hrMirrorJob = scope.launch {
            hrService.heartRate.collect { _heartRate.value = it }
        }
        conn.connect(device)
    }

    fun disconnectHeartRate() {
        hrStateJob?.cancel(); hrNotifyJob?.cancel(); hrLogJob?.cancel()
        hrMirrorJob?.cancel(); hrMirrorJob = null
        hrConn?.disconnect(); hrConn = null
        hrService.reset()
        _hrState.value = BleConnectionState.DISCONNECTED
        _heartRate.value = 0
        _hrName.value = null
    }

    fun setSpeed(kmh: Double) {
        val s = settingsStore.settings.value
        val caps = _capabilities.value
        val rawMin = if (caps.isLoaded) maxOf(s.minSpeedKmh, caps.minSpeedKmh) else s.minSpeedKmh
        val rawMax = if (caps.isLoaded) minOf(s.maxSpeedKmh, caps.maxSpeedKmh) else s.maxSpeedKmh
        val lo = minOf(rawMin, rawMax)
        val hi = maxOf(rawMin, rawMax)
        val safe = kmh.coerceIn(lo, hi)
        _targetSpeed.value = safe
        lastSpeedCmdTime = System.currentTimeMillis()
        speedChannel.trySend(safe)
    }

    fun setIncline(percent: Double) {
        val s = settingsStore.settings.value
        val caps = _capabilities.value
        if (caps.isLoaded && !caps.supportsIncline) { _targetIncline.value = 0.0; return }
        val rawMax = if (caps.isLoaded) minOf(s.maxInclinePercent, caps.maxInclinePercent) else s.maxInclinePercent
        val hi = rawMax.coerceAtLeast(0.0)
        val safe = percent.coerceIn(0.0, hi)
        _targetIncline.value = safe
        lastInclineCmdTime = System.currentTimeMillis()
        inclineChannel.trySend(safe)
    }

    private suspend fun waitForBeltRunning(minSpeedKmh: Double = 0.9, timeoutMs: Long = 15000L): Boolean {
        val start = System.currentTimeMillis()
        var lastPingAt = 0L
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (_treadmillData.value.speedKmh >= minSpeedKmh) return true
            val now = System.currentTimeMillis()
            if (now - lastPingAt >= 4000L) {
                protocol?.ping07()
                val target = if (speedBeforePause > 1.0) speedBeforePause else 1.0
                setSpeed(target)
                lastPingAt = now
            }
            delay(200L)
        }
        return false
    }

    fun startTreadmill() {
        if (_treadmillState.value != BleConnectionState.READY) return
        val isResume = _isPaused.value
        if (isResume) speak(settingsStore.settings.value.ttsResumeText)
        if (!isResume) {
            workoutManager.stop()
            resetMetrics()
            resetStats()
            workoutLogSaved = false
            speedBeforePause = 0.0
        }
        val resumeSpeed = if (isResume) speedBeforePause else 0.0
        if (isResume && resumeSpeed > 0.5) _targetSpeed.value = resumeSpeed
        _isPaused.value = false
        isStartingBelt = true
        startJob?.cancel()

        val startSpeed = if (resumeSpeed >= 1.0) resumeSpeed else 1.0
        addLog("=== MANUAL START === resume=$isResume targetSpeed=$startSpeed")

        startJob = scope.launch {
            try {
                protocol?.start()
                setSpeed(startSpeed)
                if (waitForBeltRunning(0.9, 15000L)) {
                    val targetInclineLocal = _targetIncline.value
                    val caps = _capabilities.value
                    if (targetInclineLocal > 0.0 && (!caps.isLoaded || caps.supportsIncline)) {
                        protocol?.setIncline(targetInclineLocal)
                    }
                } else {
                    _statusMessage.value = appContext.getString(R.string.session_treadmill_no_response)
                }
            } finally { isStartingBelt = false }
        }
    }

    fun pauseTreadmill() {
        if (_treadmillState.value != BleConnectionState.READY || _isPaused.value) return
        startJob?.cancel(); startJob = null
        speedBeforePause = _targetSpeed.value.coerceAtLeast(1.0)
        _isPaused.value = true
        addLog("=== MANUAL PAUSE === speedBeforePause=$speedBeforePause")
        scope.launch { protocol?.stop() }
        val text = settingsStore.settings.value.ttsPauseText
        if (text.isNotBlank()) speak(text)
    }

    fun togglePauseTreadmill() {
        if (_isPaused.value) startTreadmill() else pauseTreadmill()
    }

    fun togglePause() {
        val ws = workoutManager.state.value
        if (ws.running || ws.paused) togglePauseWorkout() else togglePauseTreadmill()
    }

    fun stopTreadmill() {
        startJob?.cancel(); startJob = null
        val ws = workoutManager.state.value
        if (ws.running || ws.paused) { stopWorkout(); return }
        addLog("=== MANUAL STOP === elapsed=${_uiElapsedSec.value}s dist=${"%.2f".format(_uiDistanceKm.value)}km")
        _isPaused.value = false
        speedBeforePause = 0.0
        scope.launch { protocol?.stop() }
        saveWorkoutLog()
    }

    fun startWorkout(program: ProgramData) {
        resetStats()
        resetMetrics()
        workoutLogSaved = false
        speedBeforePause = 0.0
        _isPaused.value = false
        lastSpokenSegmentIndex = 0
        isStartingBelt = true
        startJob?.cancel()

        addLog("=== WORKOUT START === program='${program.name}' segments=${program.segments.size}")

        startJob = scope.launch {
            try {
                protocol?.start()
                setSpeed(1.0)
                if (waitForBeltRunning(0.9, 15000L)) {
                    workoutManager.start(
                        scope = scope, program = program,
                        segmentNameProvider = { n -> appContext.getString(R.string.programs_segment_default, n) },
                        onSetSpeed = {
                            val s = settingsStore.settings.value
                            val caps = _capabilities.value
                            val rawMin = if (caps.isLoaded) maxOf(s.minSpeedKmh, caps.minSpeedKmh) else s.minSpeedKmh
                            val rawMax = if (caps.isLoaded) minOf(s.maxSpeedKmh, caps.maxSpeedKmh) else s.maxSpeedKmh
                            val lo = minOf(rawMin, rawMax)
                            val hi = maxOf(rawMin, rawMax)
                            val safe = it.coerceIn(lo, hi)
                            _targetSpeed.value = safe
                            lastSpeedCmdTime = System.currentTimeMillis()
                            protocol?.setSpeed(safe)
                        },
                        onSetIncline = {
                            val s = settingsStore.settings.value
                            val caps = _capabilities.value
                            if (!caps.isLoaded || caps.supportsIncline) {
                                val rawMax = if (caps.isLoaded) minOf(s.maxInclinePercent, caps.maxInclinePercent) else s.maxInclinePercent
                                val hi = rawMax.coerceAtLeast(0.0)
                                val safe = it.coerceIn(0.0, hi)
                                _targetIncline.value = safe
                                lastInclineCmdTime = System.currentTimeMillis()
                                protocol?.setIncline(safe)
                            } else {
                                _targetIncline.value = 0.0
                            }
                        },
                        onFinish = {
                            val avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else 0
                            val avgSpd = if (speedCount > 0) speedSum / speedCount else 0.0
                            val dist = _uiDistanceKm.value
                            addLog("=== WORKOUT FINISH === dist=${"%.2f".format(dist)}km elapsed=${_uiElapsedSec.value}s hr_avg=$avgHr hr_max=$hrMax")
                            protocol?.stop()
                            _isPaused.value = false
                            saveWorkoutLog()
                            showMessage(appContext.getString(R.string.workout_finished))
                            val distStr = "%.1f".format(dist).replace(".", ",")
                            val template = settingsStore.settings.value.ttsFinishTemplate
                            // [ФИКС] {pace}/{pace_text} на финише — от средней скорости.
                            val text = template
                                .replace("{dist}", distStr)
                                .replace("{hr}", avgHr.toString())
                                .replace("{pace}", SpeedFormatter.formatPace(avgSpd))
                                .replace("{pace_text}", SpeedFormatter.formatPaceText(avgSpd, appContext))
                            speak(text)
                        },
                        onError = { msg ->
                            addLog("=== WORKOUT ERROR === $msg")
                            protocol?.stop()
                            _statusMessage.value = msg
                        }
                    )
                    val first = program.segments.firstOrNull()
                    if (first != null) {
                        val s = settingsStore.settings.value
                        val lo = minOf(s.minSpeedKmh, s.maxSpeedKmh)
                        val hi = maxOf(s.minSpeedKmh, s.maxSpeedKmh)
                        val safeSpeed = first.speedKmh.coerceIn(lo, hi)
                        val template = s.ttsStartTemplate
                        // [ФИКС] {pace}/{pace_text} на старте — от скорости первого сегмента.
                        val text = template
                            .replace("{name}", program.name)
                            .replace("{segName}", first.name.ifBlank { appContext.getString(R.string.programs_segment_default, 1) })
                            .replace("{speed}", "%.1f".format(safeSpeed))
                            .replace("{speed_text}", fmtSpeed(safeSpeed))
                            .replace("{pace}", SpeedFormatter.formatPace(safeSpeed))
                            .replace("{pace_text}", SpeedFormatter.formatPaceText(safeSpeed, appContext))
                        speak(text)
                    }
                } else {
                    addLog("=== WORKOUT START FAILED === treadmill no response")
                    _statusMessage.value = appContext.getString(R.string.session_treadmill_no_response)
                }
            } finally { isStartingBelt = false }
        }
    }

    fun pauseWorkout() {
        startJob?.cancel(); startJob = null
        workoutManager.pause()
        scope.launch { protocol?.stop() }
        val text = settingsStore.settings.value.ttsPauseText
        if (text.isNotBlank()) speak(text)
    }

    fun resumeWorkout() {
        val text = settingsStore.settings.value.ttsResumeText
        if (text.isNotBlank()) speak(text)
        isStartingBelt = true
        startJob?.cancel()
        startJob = scope.launch {
            try {
                protocol?.start()
                setSpeed(1.0)
                if (waitForBeltRunning(0.9, 15000L)) workoutManager.resume()
            } finally { isStartingBelt = false }
        }
    }

    fun togglePauseWorkout() {
        val s = workoutManager.state.value
        if (s.paused) resumeWorkout() else pauseWorkout()
    }

    fun skipWorkoutSegment() { workoutManager.skipCurrentSegment() }

    fun stopWorkout() {
        startJob?.cancel(); startJob = null
        addLog("=== WORKOUT STOP (manual) === elapsed=${_uiElapsedSec.value}s")
        saveWorkoutLog()
        workoutManager.stop()
        scope.launch { protocol?.stop() }
    }

    fun setAutoHrEnabled(enabled: Boolean) { settingsStore.update { it.copy(hrEnabled = enabled) } }

    private fun resetMetrics() {
        _uiElapsedSec.value = 0
        _uiDistanceKm.value = 0.0
        _uiCalories.value = 0
        totalCaloriesDouble = 0.0
        trainingStartMillis = System.currentTimeMillis()
    }

    private fun resetStats() {
        hrSum = 0L; hrCount = 0L; hrMax = 0; speedSum = 0.0; speedCount = 0L
    }

    fun saveWorkoutLog() {
        if (workoutLogSaved) return
        val ws = workoutManager.state.value
        val isProgram = ws.programName.isNotBlank()
        val durationSec = if (isProgram && ws.totalElapsedSec > 0) ws.totalElapsedSec else _uiElapsedSec.value
        if (durationSec <= 0) return
        workoutLogSaved = true

        val avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else 0
        val avgSpd = if (speedCount > 0) speedSum / speedCount else 0.0

        val hrSamplesEncoded = run {
            if (trainingStartMillis <= 0L) return@run ""
            val raw = _hrHistory.value.filter { it.timestamp >= trainingStartMillis && it.bpm > 0 }
            if (raw.isEmpty()) return@run ""
            val stepMs = 5000L
            val sampled = raw.distinctBy { ((it.timestamp - trainingStartMillis) / stepMs) }
                .map { ((it.timestamp - trainingStartMillis) / 1000).toInt() to it.bpm }
            HrSamplesCodec.encode(sampled)
        }

        scope.launch {
            repository.addLog(WorkoutLogEntity(
                dateMillis = System.currentTimeMillis(),
                programName = if (isProgram) ws.programName else "",
                durationSec = durationSec,
                distanceKm = _uiDistanceKm.value,
                calories = _uiCalories.value,
                avgHeartRate = avgHr,
                maxHeartRate = hrMax,
                avgSpeedKmh = avgSpd,
                hrSamples = hrSamplesEncoded
            ))
            addLog("Тренировка сохранена в журнал")
        }
        resetStats()
    }

    fun sendRawHex(hex: String): Boolean {
        val p = protocol ?: return false
        val bytes = try { hex.hexToBytes() } catch (e: Exception) {
            addLog("Ошибка hex: ${e.message}"); return false
        }
        scope.launch {
            val ok = p.sendRaw(bytes)
            addLog("Hex $hex: ${if (ok) "отправлено" else "ОШИБКА"}")
        }
        return true
    }

    private fun addLog(line: String) { DebugLog.d("SESSION", line) }
    fun clearLogs() { DebugLog.clear() }
}
