package com.custom.treadmill.ble

import java.util.UUID

object Uuids {

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    val FTMS_FEATURE: UUID = UUID.fromString("00002acc-0000-1000-8000-00805f9b34fb")
    val FTMS_TREADMILL_DATA: UUID = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb")
    val FTMS_SUPPORTED_SPEED_RANGE: UUID = UUID.fromString("00002ad4-0000-1000-8000-00805f9b34fb")
    val FTMS_SUPPORTED_INCLINE_RANGE: UUID = UUID.fromString("00002ad5-0000-1000-8000-00805f9b34fb")
    val FTMS_SUPPORTED_HR_RANGE: UUID = UUID.fromString("00002ad7-0000-1000-8000-00805f9b34fb")
    val FTMS_STATUS: UUID = UUID.fromString("00002ada-0000-1000-8000-00805f9b34fb")

    val FTMS_CONTROL_POINT: UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
    val FTMS_CONTROL_POINT_ALT: UUID = UUID.fromString("00002a66-0000-1000-8000-00805f9b34fb")

    val CONTROL_POINT_CANDIDATES: List<UUID> =
        listOf(FTMS_CONTROL_POINT, FTMS_CONTROL_POINT_ALT)

    val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
}

fun ByteArray.toHex(): String =
    if (isEmpty()) "(пусто)" else joinToString(" ") { "%02X".format(it) }

fun String.hexToBytes(): ByteArray {
    val cleaned = this.replace(",", " ").replace("-", " ").replace("0x", "", ignoreCase = true)
    return cleaned.split(" ", "\n", "\t")
        .filter { it.isNotBlank() }
        .map { it.trim().toInt(16).toByte() }
        .toByteArray()
}
