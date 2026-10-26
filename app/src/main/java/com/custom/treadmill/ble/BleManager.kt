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

    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
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

    fun startScan() {
        if (!hasScanPermission()) {
            _scanLog.tryEmit("Нет разрешения на сканирование BLE")
            return
        }
        val a = adapter ?: run {
            _scanLog.tryEmit("Bluetooth адаптер недоступен")
            return
        }
        if (!a.isEnabled) {
            _scanLog.tryEmit("Bluetooth выключен")
            return
        }

        // Отменяем старые задачи и принудительно стопаем сканер
        scanRunnable?.let { handler.removeCallbacks(it) }
        try {
            a.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}

        _scanResults.value = emptyList()
        _scanLog.tryEmit("Подготовка к сканированию...")

        // [ФИКС] Даем дорожке 1.5 секунды, чтобы она "поняла", что отключилась, и начала вещать сигнал заново
        scanRunnable = Runnable {
            val scanner = a.bluetoothLeScanner ?: return@Runnable
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
        handler.postDelayed(scanRunnable!!, 1500)
    }

    fun stopScan() {
        scanRunnable?.let { handler.removeCallbacks(it) }
        scanRunnable = null
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
