package com.custom.treadmill.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class BleConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, DISCOVERING, READY, FAILED
}

data class GattCharInfo(
    val serviceUuid: UUID,
    val uuid: UUID,
    val properties: Int
)

data class BleNotification(val characteristicUuid: UUID, val value: ByteArray)

@SuppressLint("MissingPermission")
class BleConnection(
    private val context: Context,
    private val tag: String
) {

    private var gatt: BluetoothGatt? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _log = MutableSharedFlow<String>(extraBufferCapacity = 1024)
    val log: SharedFlow<String> = _log.asSharedFlow()

    private val _notifications = MutableSharedFlow<BleNotification>(extraBufferCapacity = 1024)
    val notifications: SharedFlow<BleNotification> = _notifications.asSharedFlow()

    private val _characteristics = MutableStateFlow<List<GattCharInfo>>(emptyList())
    val characteristics: StateFlow<List<GattCharInfo>> = _characteristics.asStateFlow()

    // [ФИКС] Один Mutex и один Deferred для всех операций
    private val gattMutex = Mutex()
    @Volatile private var activeDeferred: CompletableDeferred<Any?>? = null

    @Volatile private var isDisconnecting = false
    private var disconnectTimeoutJob: Job? = null

    private fun log(msg: String) {
        Log.d(tag, msg)
        _log.tryEmit("[$tag] $msg")
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (isDisconnecting) {
                        log("CONNECTED после запроса disconnect — отключаемся повторно")
                        try { g.disconnect() } catch (_: Exception) {}
                        return
                    }
                    _state.value = BleConnectionState.CONNECTED
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("DISCONNECTED callback")
                    if (gatt === g) {
                        forceCloseGatt(g)
                    } else {
                        log("Старый GATT отключился — игнорируем")
                        isDisconnecting = false
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = BleConnectionState.FAILED
                return
            }
            val list = mutableListOf<GattCharInfo>()
            for (service in g.services) {
                log("Service ${service.uuid}")
                for (c in service.characteristics) {
                    log("   Char ${c.uuid} props=${c.properties}")
                    list.add(GattCharInfo(service.uuid, c.uuid, c.properties))
                }
            }
            _characteristics.value = list
            _state.value = BleConnectionState.READY
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val result = if (status == BluetoothGatt.GATT_SUCCESS) value else null
            log("read ${c.uuid} status=$status value=${value.toHex()}")
            activeDeferred?.complete(result)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val result = if (status == BluetoothGatt.GATT_SUCCESS) c.value else null
            log("read ${c.uuid} status=$status value=${result?.toHex()}")
            activeDeferred?.complete(result)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            log("<- ${c.uuid} ${value.toHex()}")
            _notifications.tryEmit(BleNotification(c.uuid, value))
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            log("<- ${c.uuid} ${v.toHex()}")
            _notifications.tryEmit(BleNotification(c.uuid, v))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            log("write ${c.uuid} status=$status")
            activeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("descriptorWrite ${d.uuid} status=$status")
            activeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("mtu=$mtu status=$status")
        }
    }

    fun connect(device: BluetoothDevice) {
        log("Подключение к ${device.address}…")
        _state.value = BleConnectionState.CONNECTING
        isDisconnecting = false

        gatt?.let { old ->
            try { old.disconnect() } catch (_: Exception) {}
            closeGattSilently(old)
        }
        gatt = null

        gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, callback)
            }
        } catch (e: Exception) {
            log("Ошибка connectGatt: ${e.message}")
            null
        }
    }

    fun disconnect() {
        if (isDisconnecting) {
            log("disconnect: уже в процессе — игнор")
            return
        }

        val g = gatt
        if (g == null) {
            log("disconnect: gatt уже null — только сброс состояния")
            _state.value = BleConnectionState.DISCONNECTED
            completePending()
            return
        }

        isDisconnecting = true
        log("Запрос на отключение (ждём DISCONNECTED callback)…")

        try {
            g.disconnect()
            log("disconnect() вызван")
        } catch (e: Exception) {
            log("Ошибка disconnect: ${e.message}")
        }

        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = scope.launch {
            delay(3000L)
            if (gatt === g) {
                log("Таймаут ожидания DISCONNECTED — принудительное закрытие")
                forceCloseGatt(g)
            }
        }
    }

    private fun forceCloseGatt(g: BluetoothGatt) {
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = null

        closeGattSilently(g)

        if (gatt === g) gatt = null
        _state.value = BleConnectionState.DISCONNECTED
        isDisconnecting = false
        completePending()
    }

    private fun closeGattSilently(g: BluetoothGatt?) {
        if (g == null) return
        try {
            val refreshMethod = g.javaClass.getMethod("refresh")
            refreshMethod.invoke(g)
            log("GATT refresh OK")
        } catch (_: Exception) { }
        try {
            g.close()
            log("GATT close OK")
        } catch (e: Exception) {
            log("Ошибка gatt.close: ${e.message}")
        }
    }

    private fun completePending() {
        activeDeferred?.complete(null)
        activeDeferred = null
    }

    fun isReady(): Boolean = _state.value == BleConnectionState.READY && gatt != null

    fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        for (s in g.services) {
            for (c in s.characteristics) if (c.uuid == uuid) return c
        }
        return null
    }

    fun findCharacteristic(serviceUuid: UUID, requiredPropertyMask: Int): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        val s = g.getService(serviceUuid) ?: return null
        return s.characteristics.firstOrNull { (it.properties and requiredPropertyMask) != 0 }
    }

    suspend fun setNotifySuspend(uuid: UUID, enable: Boolean, indicate: Boolean = false): Boolean = gattMutex.withLock {
        val g = gatt ?: return@withLock false
        val c = findCharacteristic(uuid) ?: run {
            log("setNotify: характеристика $uuid не найдена")
            return@withLock false
        }
        if (!g.setCharacteristicNotification(c, enable)) {
            log("setCharacteristicNotification($uuid) = false")
            return@withLock false
        }
        val cccd = c.getDescriptor(Uuids.CCCD) ?: return@withLock false
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            indicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        // [ФИКС] Используем CompletableDeferred<Any?> для обхода инвариантности дженериков
        val deferred = CompletableDeferred<Any?>()
        activeDeferred = deferred

        try {
            val queued = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == 0
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!queued) {
                activeDeferred = null
                return@withLock false
            }
        } catch (e: Exception) {
            log("Ошибка setNotify: ${e.message}")
            activeDeferred = null
            return@withLock false
        }

        val result = withTimeoutOrNull(2000L) { deferred.await() }
        activeDeferred = null
        val ok = result as? Boolean ?: false
        if (!ok) log("setNotify TIMEOUT/FAIL для $uuid")
        ok
    }

    // [ФИКС] Оставляем старую сигнатуру для HeartRateService (возвращает Boolean)
    fun setNotify(uuid: UUID, enable: Boolean, indicate: Boolean = false): Boolean {
        if (gatt == null) return false
        scope.launch { setNotifySuspend(uuid, enable, indicate) }
        return true
    }

    suspend fun read(uuid: UUID): ByteArray? = gattMutex.withLock {
        val g = gatt ?: return@withLock null
        val c = findCharacteristic(uuid) ?: run {
            log("read: характеристика $uuid не найдена")
            return@withLock null
        }
        val deferred = CompletableDeferred<Any?>()
        activeDeferred = deferred
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.readCharacteristic(c)
            } else {
                @Suppress("DEPRECATION")
                g.readCharacteristic(c)
            }
        } catch (e: Exception) {
            log("Ошибка read: ${e.message}")
            activeDeferred = null
            return@withLock null
        }
        val result = withTimeoutOrNull(2000L) { deferred.await() }
        activeDeferred = null
        result as? ByteArray
    }

    suspend fun writeSuspend(uuid: UUID, data: ByteArray, withResponse: Boolean = true): Boolean {
        val c = findCharacteristic(uuid) ?: run {
            log("writeSuspend: характеристика $uuid не найдена")
            return false
        }
        return writeSuspend(c, data, withResponse)
    }

    suspend fun writeSuspend(c: BluetoothGattCharacteristic, data: ByteArray, withResponse: Boolean): Boolean = gattMutex.withLock {
        val g = gatt ?: return@withLock false
        log("-> ${c.uuid} ${data.toHex()}")

        val deferred = CompletableDeferred<Any?>()
        activeDeferred = deferred

        val queued = try {
            if (Build.VERSION.SDK_INT >= 33) {
                val wt = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(c, data, wt) == 0
            } else {
                @Suppress("DEPRECATION")
                c.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            log("Ошибка writeSuspend: ${e.message}")
            activeDeferred = null
            return@withLock false
        }

        if (!queued) {
            log("writeSuspend REJECTED for ${c.uuid} (write already in progress)")
            activeDeferred = null
            return@withLock false
        }

        val result = withTimeoutOrNull(2000L) { deferred.await() }
        activeDeferred = null
        if (result == null) {
            log("writeSuspend TIMEOUT for ${c.uuid}")
            return@withLock false
        }
        result as? Boolean ?: false
    }

    fun write(c: BluetoothGattCharacteristic, data: ByteArray, withResponse: Boolean): Boolean {
        val g = gatt ?: return false
        log("-> ${c.uuid} ${data.toHex()}")
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                val wt = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(c, data, wt) == 0
            } else {
                @Suppress("DEPRECATION")
                c.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            log("Ошибка write: ${e.message}")
            false
        }
    }

    fun write(uuid: UUID, data: ByteArray, withResponse: Boolean = true): Boolean {
        val c = findCharacteristic(uuid) ?: return false
        return write(c, data, withResponse)
    }

    fun requestMtu(mtu: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { gatt?.requestMtu(mtu) } catch (_: Exception) {}
        }
    }
}
