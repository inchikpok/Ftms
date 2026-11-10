package com.custom.treadmill.ui.viewmodels

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import com.custom.treadmill.DebugLog
import com.custom.treadmill.R
import com.custom.treadmill.TreadmillApp
import com.custom.treadmill.data.database.ProgramData
import com.custom.treadmill.data.repository.AppSettings
import com.custom.treadmill.data.repository.HrMode
import com.custom.treadmill.data.repository.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class ScanMode { NONE, TREADMILL, HEART_RATE }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val session = TreadmillApp.instance.session
    private val settingsStore = TreadmillApp.instance.settingsStore

    val settings = settingsStore.settings
    val capabilities = session.capabilities
    val scanCooldownUntil = session.scanCooldownUntil

    val treadmillState = session.treadmillState
    val treadmillName = session.treadmillName
    val hrName = session.hrName
    val hrState = session.hrState
    val treadmillData = session.treadmillData
    val targetSpeed = session.targetSpeed
    val targetIncline = session.targetIncline
    val heartRate = session.heartRate
    val hrHistory = session.hrHistory
    val statusMessage = session.statusMessage
    val isPaused = session.isPaused
    val uiElapsedSec = session.uiElapsedSec
    val uiDistanceKm = session.uiDistanceKm
    val uiCalories = session.uiCalories
    val logs = session.logs
    val workoutState = session.workoutState

    val scanResults = TreadmillApp.instance.bleManager.scanResults
    val scanLog = TreadmillApp.instance.bleManager.scanLog

    private val _scanMode = MutableStateFlow(ScanMode.NONE)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    fun showMessage(msg: String) = session.showMessage(msg)
    fun clearStatusMessage() = session.clearStatusMessage()

    // [ФИКС] Останавливаем скан перед подключением.
    // Иначе LaunchedEffect(scanMode) в MainScreen отменяется и stopScan() не вызывается —
    // BLE-скан продолжает висеть в фоне до закрытия приложения.
    fun connectTreadmill(device: BluetoothDevice) {
        session.stopScan()
        _scanMode.value = ScanMode.NONE
        session.connectTreadmill(device)
    }

    fun connectHeartRate(device: BluetoothDevice) {
        session.stopScan()
        _scanMode.value = ScanMode.NONE
        session.connectHeartRate(device)
    }

    fun disconnectTreadmill() = session.disconnectTreadmill()
    fun disconnectHeartRate() = session.disconnectHeartRate()

    fun setSpeed(kmh: Double) = session.setSpeed(kmh)
    fun setIncline(percent: Double) = session.setIncline(percent)

    fun startTreadmill() = session.startTreadmill()
    fun pauseTreadmill() = session.pauseTreadmill()
    fun togglePauseTreadmill() = session.togglePauseTreadmill()
    fun stopTreadmill() = session.stopTreadmill()

    fun startWorkout(program: ProgramData) = session.startWorkout(program)
    fun pauseWorkout() = session.pauseWorkout()
    fun resumeWorkout() = session.resumeWorkout()
    fun togglePauseWorkout() = session.togglePauseWorkout()
    fun stopWorkout() = session.stopWorkout()
    fun skipWorkoutSegment() = session.skipWorkoutSegment()
    fun saveWorkoutLog() = session.saveWorkoutLog()

    fun sendRawHex(hex: String): Boolean = session.sendRawHex(hex)
    fun setAutoHrEnabled(enabled: Boolean) = session.setAutoHrEnabled(enabled)

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)
    fun setHrMode(mode: HrMode) = settingsStore.update { it.copy(hrMode = mode) }

    fun updateProfile(profile: UserProfile) = settingsStore.updateProfile(profile)
    fun addProfile(profile: UserProfile) = settingsStore.addProfile(profile)
    fun deleteProfile(id: String) = settingsStore.deleteProfile(id)
    fun setActiveProfile(id: String) = settingsStore.setActiveProfile(id)

    fun testSpeak(template: String) {
        val ctx = getApplication<Application>()
        // [ФИКС] Тестовые значения для шаблона — берём из ресурсов и plurals,
        // чтобы тест на локали X звучал бы так же, как боевая озвучка на локали X.
        // fmtSpeed/fmtAngle в TreadmillSession тоже округляют до целого и берут plurals —
        // здесь используем те же правила.
        val speedNum = 8.5
        val angleNum = 3.0
        val speedInt = speedNum.roundToInt()
        val angleInt = angleNum.roundToInt()
        val speedText = ctx.resources.getQuantityString(R.plurals.tts_kmh_plural, speedInt, speedInt)
        val angleText = ctx.resources.getQuantityString(R.plurals.tts_percent_plural, angleInt, angleInt)

        val text = template
            .replace("{name}", ctx.getString(R.string.tts_test_workout_name))
            .replace("{segName}", ctx.getString(R.string.gen_seg_warmup))
            .replace("{i}", "2")
            .replace("{n}", "5")
            .replace("{speed}", "%.1f".format(speedNum))
            .replace("{speed_text}", speedText)
            .replace("{angle}", angleInt.toString())
            .replace("{angle_text}", angleText)
            .replace("{dist}", "5.2")
            .replace("{hr}", "140")
        session.testSpeak(text)
    }

    fun startScanTreadmill() { _scanMode.value = ScanMode.TREADMILL; session.startScanTreadmill() }
    fun startScanHeartRate() { _scanMode.value = ScanMode.HEART_RATE; session.startScanHeartRate() }
    fun stopScan() { _scanMode.value = ScanMode.NONE; session.stopScan() }

    fun clearLogs() = session.clearLogs()
}
