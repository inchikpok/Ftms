package com.custom.treadmill.ble

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class TreadmillData(
    val speedKmh: Double = 0.0,
    val inclinePercent: Double = 0.0,
    val distanceKm: Double = 0.0,
    val calories: Int = 0,
    val elapsedSec: Int = 0,
    val heartRate: Int = 0,
    val isRunning: Boolean = false
)

interface ITreadmillProtocol {

    val protocolName: String
    val writeCharacteristicUuid: UUID?
    val data: StateFlow<TreadmillData>
    val capabilities: StateFlow<TreadmillCapabilities>

    suspend fun initialize(conn: BleConnection): Boolean
    suspend fun requestControl(): Boolean
    suspend fun start(): Boolean
    suspend fun stop(): Boolean
    suspend fun setSpeed(speedKmh: Double): Boolean
    suspend fun setIncline(percent: Double): Boolean
    fun onNotification(n: BleNotification)
}
