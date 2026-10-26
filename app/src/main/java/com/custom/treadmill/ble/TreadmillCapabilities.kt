package com.custom.treadmill.ble

data class TreadmillCapabilities(
    val isLoaded: Boolean = false,
    val supportsIncline: Boolean = false,
    val supportsDistance: Boolean = false,
    val supportsCalories: Boolean = false,
    val supportsHeartRate: Boolean = false,
    val supportsElapsedTime: Boolean = false,
    val minSpeedKmh: Double = 0.5,
    val maxSpeedKmh: Double = 20.0,
    val speedIncrementKmh: Double = 0.1,
    val minInclinePercent: Double = 0.0,
    val maxInclinePercent: Double = 15.0,
    val inclineIncrementPercent: Double = 0.5
) {
    companion object {
        // FTMS Feature (0x2ACC) — 32 бита, но обычно хватает первых 16
        fun parseFeature(bytes: ByteArray): Map<String, Boolean> {
            if (bytes.size < 2) return emptyMap()
            val flags = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
            return mapOf(
                "incline" to ((flags shr 3) and 1 != 0),
                "distance" to ((flags shr 2) and 1 != 0),
                "calories" to ((flags shr 9) and 1 != 0),
                "heartRate" to ((flags shr 10) and 1 != 0),
                "elapsedTime" to ((flags shr 12) and 1 != 0)
            )
        }

        // Speed Range (0x2AD4): 3 × uint16 в 0.01 км/ч (min, max, increment)
        fun parseSpeedRange(bytes: ByteArray): Triple<Double, Double, Double>? {
            if (bytes.size < 6) return null
            val minS = u16(bytes, 0) / 100.0
            val maxS = u16(bytes, 2) / 100.0
            val incS = u16(bytes, 4) / 100.0
            if (maxS <= 0.0 || maxS > 50.0 || minS < 0.0 || minS > maxS) return null
            return Triple(minS, maxS, if (incS > 0.0) incS else 0.1)
        }

        // Incline Range (0x2AD5): sint16 (min), sint16 (max), uint16 (increment) в 0.1%
        fun parseInclineRange(bytes: ByteArray): Triple<Double, Double, Double>? {
            if (bytes.size < 6) return null
            val minI = s16(bytes, 0) / 10.0
            val maxI = s16(bytes, 2) / 10.0
            val incI = u16(bytes, 4) / 10.0
            if (maxI <= 0.0 || maxI > 30.0) return null
            return Triple(minI.coerceAtLeast(0.0), maxI, if (incI > 0.0) incI else 0.5)
        }

        private fun u16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun s16(b: ByteArray, o: Int): Int {
            val v = u16(b, o)
            return if (v >= 0x8000) v - 0x10000 else v
        }
    }
}
