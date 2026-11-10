package com.custom.treadmill.data.repository

import com.custom.treadmill.DebugLog
import com.custom.treadmill.data.database.ProgramData
import com.custom.treadmill.data.database.ProgramDao
import com.custom.treadmill.data.database.ProgramEntity
import com.custom.treadmill.data.database.ProgramSegment
import com.custom.treadmill.data.database.WorkoutLogDao
import com.custom.treadmill.data.database.WorkoutLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

class ProgramRepository(
    private val programDao: ProgramDao,
    private val logDao: WorkoutLogDao
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val programs: Flow<List<ProgramEntity>> = programDao.getAll()
    val logs: Flow<List<WorkoutLogEntity>> = logDao.getAll()

    suspend fun save(entity: ProgramEntity): Long =
        if (entity.id == 0L) programDao.insert(entity)
        else {
            programDao.update(entity)
            entity.id
        }

    suspend fun delete(entity: ProgramEntity) = programDao.delete(entity)
    suspend fun addLog(log: WorkoutLogEntity) = logDao.insert(log)

    suspend fun addDemoLogs() {
        val demos = buildDemoLogs().map { it.copy(programName = "(demo) ${it.programName}") }
        logDao.insertAll(demos)
    }

    suspend fun clearLogs() = logDao.deleteAll()

    // ---------- Сериализация ----------

    /**
     * [ФИКС] Раньше битый JSON молча подменялся на пустой список сегментов,
     * и пользователь видел «программу без сегментов» без объяснений.
     * Теперь:
     *  - случай логгируется в DebugLog с id и именем программы,
     *  - UI (ProgramsScreen) видит пустой segments и показывает плашку
     *    «Программа повреждена, удалите её» вместо графика.
     */
    fun toData(e: ProgramEntity): ProgramData {
        if (e.segmentsJson.isBlank()) {
            DebugLog.d("PROGRAMS", "toData: пустой segmentsJson для id=${e.id} name=${e.name}")
            return ProgramData(e.name, e.description, emptyList())
        }
        val segs: List<ProgramSegment> = try {
            json.decodeFromString(
                ListSerializer(ProgramSegment.serializer()),
                e.segmentsJson
            )
        } catch (ex: Exception) {
            DebugLog.d("PROGRAMS", "toData: битый JSON для id=${e.id} name=${e.name}: ${ex.message}")
            emptyList()
        }
        return ProgramData(e.name, e.description, segs)
    }

    fun toEntity(d: ProgramData, id: Long = 0): ProgramEntity =
        ProgramEntity(
            id,
            d.name,
            d.description,
            json.encodeToString(ListSerializer(ProgramSegment.serializer()), d.segments)
        )

    fun exportJson(d: ProgramData): String =
        json.encodeToString(ProgramData.serializer(), d)

    fun importJson(text: String): ProgramData =
        json.decodeFromString(ProgramData.serializer(), text)

    // ============================================================
    //  Генератор демо-данных
    // ============================================================
    private data class DemoSeed(
        val daysAgo: Int,
        val name: String,
        val durationMin: Int,
        val speedKmh: Double,
        val avgHr: Int,
        val maxHr: Int
    )

    private fun buildDemoLogs(): List<WorkoutLogEntity> {
        val dayMs = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        val rnd = Random(42)
        val weightKg = 75.0

        val seeds = listOf(
            DemoSeed(40, "Интервалы 4×3 мин", 30, 10.0, 178, 195),
            DemoSeed(37, "Лёгкий бег",        30,  8.0, 138, 155),
            DemoSeed(34, "Длительный бег",    45,  9.0, 152, 168),
            DemoSeed(31, "Ходьба",            30,  4.5, 105, 118),
            DemoSeed(28, "Темповый бег",      25, 10.5, 168, 182),
            DemoSeed(25, "Интервалы 4×3 мин", 30, 10.5, 172, 188),
            DemoSeed(22, "Лёгкий бег",        30,  8.5, 140, 157),
            DemoSeed(19, "Восстановительный", 20,  6.5, 118, 132),
            DemoSeed(16, "Длительный бег",    50,  9.5, 150, 166),
            DemoSeed(13, "Темповый бег",      25, 11.0, 165, 180),
            DemoSeed(10, "Лёгкий бег",        30,  8.5,   0,   0),
            DemoSeed( 7, "Интервалы 4×3 мин", 30, 11.0, 168, 185),
            DemoSeed( 4, "Лёгкий бег",        30,  9.0, 135, 152),
            DemoSeed( 2, "Восстановительный", 20,  6.5, 110, 128),
            DemoSeed( 1, "Длительный бег",    45, 10.0, 145, 162)
        )

        return seeds.map { s ->
            val date = now - s.daysAgo.toLong() * dayMs
            val durationSec = s.durationMin * 60
            val hours = durationSec / 3600.0
            val dist = (s.speedKmh * hours * (1.0 + rnd.nextDouble(-0.02, 0.02))).coerceAtLeast(0.1)
            val met = if (s.speedKmh < 6.0) 1.0 + s.speedKmh * 0.5 else 1.0 + s.speedKmh * 0.9
            val kcal = (met * 3.5 * weightKg / 200.0 * s.durationMin * (1.0 + rnd.nextDouble(-0.03, 0.03)))
                .toInt().coerceAtLeast(0)

            WorkoutLogEntity(
                dateMillis = date,
                programName = s.name,
                durationSec = durationSec,
                distanceKm = dist,
                calories = kcal,
                avgHeartRate = s.avgHr,
                maxHeartRate = s.maxHr,
                avgSpeedKmh = s.speedKmh
            )
        }
    }
}
