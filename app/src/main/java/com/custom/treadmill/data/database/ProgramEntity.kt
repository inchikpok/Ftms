package com.custom.treadmill.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProgramSegment(
    @SerialName("duration_sec") val durationSec: Int,
    @SerialName("speed_kmh") val speedKmh: Double,
    @SerialName("incline_percent") val inclinePercent: Double = 0.0,
    val name: String = ""
)

@Serializable
data class ProgramData(
    val name: String,
    val description: String = "",
    val segments: List<ProgramSegment> = emptyList()
)

@Entity(tableName = "programs")
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val segmentsJson: String
)

@Entity(tableName = "workout_logs")
data class WorkoutLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMillis: Long,
    val programName: String,
    val durationSec: Int,
    val distanceKm: Double,
    val calories: Int,
    val avgHeartRate: Int,
    val maxHeartRate: Int,
    val avgSpeedKmh: Double,
    // [ИТ.2] Компактная кривая пульса "0:120,5:125,...". Пустая строка = нет данных.
    val hrSamples: String = ""
)
