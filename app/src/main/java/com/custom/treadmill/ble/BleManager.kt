package com.custom.treadmill.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private val _scanLog = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val scanLog: SharedFlow<String> = _scanLog.asSharedFlow()

    private val _scanCooldownUntil = MutableStateFlow(0L)
    val scanCooldownUntil: StateFlow<Long> = _scanCooldownUntil.asStateFlow()

    private var scanning = false

    // [ФИКС] Защита от ALREADY_STARTED: если скан не запустился — перезапустим.
    @Volatile private var shouldScan = false
    @Volatile private var scanRetryCount = 0
    private val maxScanRetries = 3
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanRetryCount = 0
            val name = safeName(result)
            val existing = _scanResults.value
            if (existing.none { it.device.address == result.device.address }) {
                _scanResults.value = existing + result
                _scanLog.tryEmit("Найдено: '$name' [${result.device.address}] RSSI=${result.rssi}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanLog.tryEmit("Ошибка сканирования: код $errorCode")
            scanning = false

            when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> {
                    if (shouldScan && scanRetryCount < maxScanRetries) {
                        scanRetryCount++
                        _scanLog.tryEmit("ALREADY_STARTED — перезапуск #$scanRetryCount")
                        mainHandler.postDelayed({
                            if (shouldScan) doStartScanInternal(clearResults = false)
                        }, 300L)
                    } else {
                        _scanLog.tryEmit("ALREADY_STARTED — лимит попыток исчерпан")
                    }
                }
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> {
                    _scanLog.tryEmit("Регистрация приложения не удалась — вкл/выкл Bluetooth")
                }
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> {
                    _scanLog.tryEmit("Внутренняя ошибка сканера — попробуйте ещё раз")
                }
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> {
                    _scanLog.tryEmit("BLE-сканирование не поддерживается")
                }
                else -> {
                    // 4 = SCAN_FAILED_SCANNING_TOO_FREQUENTLY (Android 8+)
                    _scanLog.tryEmit("Слишком частое сканирование — подождите немного")
                }
            }
        }
    }

    fun safeName(r: ScanResult): String = try {
        r.device.name ?: r.scanRecord?.deviceName ?: "(без имени)"
    } catch (e: SecurityException) {
        "(нет разрешения)"
    }

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun setScanCooldown(ms: Long) {
        val until = System.currentTimeMillis() + ms
        _scanCooldownUntil.value = until
        _scanLog.tryEmit("Установлен кулдаун сканирования до ${until} (${ms}мс)")
    }

    fun startScan() {
        if (!hasScanPermission()) {
            _scanLog.tryEmit("Нет разрешения на сканирование BLE")
            return
        }

        val now = System.currentTimeMillis()
        val wait = (_scanCooldownUntil.value - now).coerceAtLeast(0)
        if (wait > 0) {
            val sec = (wait / 1000).toInt() + 1
            _scanLog.tryEmit("Сканирование заблокировано кулдауном: осталось $sec сек")
            return
        }

        shouldScan = true
        scanRetryCount = 0
        doStartScanInternal(clearResults = true)
    }

    private fun doStartScanInternal(clearResults: Boolean) {
        val a = adapter ?: run {
            _scanLog.tryEmit("Bluetooth адаптер недоступен")
            return
        }
        if (!a.isEnabled) {
            _scanLog.tryEmit("Bluetooth выключен")
            return
        }
        val scanner = a.bluetoothLeScanner ?: run {
            _scanLog.tryEmit("BLE сканер недоступен")
            return
        }

        try {
            scanner.stopScan(scanCallback)
        } catch (_: Exception) {}

        if (clearResults) {
            _scanResults.value = emptyList()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
            scanning = true
            _scanLog.tryEmit("Сканирование запущено")
        } catch (e: Exception) {
            _scanLog.tryEmit("Ошибка запуска сканирования: ${e.message}")
        }
    }

    fun stopScan() {
        shouldScan = false
        scanRetryCount = 0
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
        _scanLog.tryEmit("Сканирование остановлено")
    }

    fun isTreadmillDevice(r: ScanResult): Boolean {
        val name = safeName(r).lowercase()
        if (name.startsWith("fs-") || name.contains("fitshow")) return true
        val uuids = r.scanRecord?.serviceUuids ?: return false
        return uuids.any { it.uuid == Uuids.FTMS_SERVICE }
    }

    fun isHeartRateDevice(r: ScanResult): Boolean {
        val uuids = r.scanRecord?.serviceUuids ?: return false
        return uuids.any { it.uuid == Uuids.HR_SERVICE }
    }
}
