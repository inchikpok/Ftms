package com.custom.treadmill.logic

import com.custom.treadmill.data.database.ProgramData
import com.custom.treadmill.data.database.ProgramSegment
import com.custom.treadmill.data.repository.ExperienceLevel
import com.custom.treadmill.data.repository.UserProfile
import kotlin.math.roundToInt

enum class WorkoutGoal(val title: String, val description: String) {
    WEIGHT_LOSS("Похудение", "Жиросжигание, низкий пульс, ходьба в горку"),
    CARDIO("Выносливость", "Крепкое сердце, ровный бег в среднем темпе"),
    SPEED("Скорость", "Интервалы, высокий пульс, развитие скорости")
}

object ProgramGenerator {
    fun generate(
        profile: UserProfile,
        goal: WorkoutGoal,
        durationMin: Int,
        intensityPercent: Int = 100,
        maxSpeedLimit: Double = 20.0,
        maxInclineLimit: Double = 15.0
    ): ProgramData {
        val segments = mutableListOf<ProgramSegment>()
        val bmi = profile.weightKg / ((profile.heightCm / 100.0) * (profile.heightCm / 100.0))

        val bmiMult = when {
            bmi > 35 -> 0.65
            bmi > 30 -> 0.75
            bmi > 25 -> 0.88
            else -> 1.0
        }

        val levelJog = when (profile.level) {
            ExperienceLevel.BEGINNER -> 6.0
            ExperienceLevel.INTERMEDIATE -> 8.5
            ExperienceLevel.ADVANCED -> 11.0
        }
        val levelRun = when (profile.level) {
            ExperienceLevel.BEGINNER -> 8.0
            ExperienceLevel.INTERMEDIATE -> 12.0
            ExperienceLevel.ADVANCED -> 15.0
        }
        val levelIncline = when (profile.level) {
            ExperienceLevel.BEGINNER -> 4.0
            ExperienceLevel.INTERMEDIATE -> 8.0
            ExperienceLevel.ADVANCED -> 12.0
        }
        
        // Множитель для наклона в зависимости от уровня
        val inclineLevelMult = when (profile.level) {
            ExperienceLevel.BEGINNER -> 0.6
            ExperienceLevel.INTERMEDIATE -> 0.8
            ExperienceLevel.ADVANCED -> 1.0
        }

        val userMult = intensityPercent / 100.0

        // Защита от некорректных настроек
        val safeMaxSpeed = maxSpeedLimit.coerceAtLeast(5.0)
        val safeMaxIncline = maxInclineLimit.coerceAtLeast(1.0)

        val walkSpeed = (4.5 * bmiMult * userMult).coerceIn(2.0, safeMaxSpeed).roundToTenth()
        val jogSpeed = (levelJog * bmiMult * userMult).coerceIn(4.5, safeMaxSpeed).roundToTenth()
        val runSpeed = (levelRun * bmiMult * userMult).coerceIn(6.0, safeMaxSpeed).roundToTenth()
        
        // Базовый наклон для программы с учетом уровня и интенсивности
        val baseIncline = (levelIncline * inclineLevelMult * userMult).coerceIn(0.0, safeMaxIncline)

        when (goal) {
            WorkoutGoal.WEIGHT_LOSS -> {
                val warmupMin = (durationMin * 0.2).toInt().coerceIn(3, 10)
                val cooldownMin = (durationMin * 0.15).toInt().coerceIn(3, 8)
                val mainMin = durationMin - warmupMin - cooldownMin

                segments.add(ProgramSegment(warmupMin * 60, walkSpeed, 0.0, "Разминка (Ходьба)"))
                
                // Ходьба в горку - 80% от baseIncline
                val hillIncline = (baseIncline * 0.8).coerceIn(1.0, safeMaxIncline)
                
                var remainingSec = mainMin * 60
                var block = 1
                while (remainingSec > 0) {
                    val fastSec = minOf(3 * 60, remainingSec)
                    segments.add(ProgramSegment(fastSec, jogSpeed, hillIncline, "Ходьба в горку $block"))
                    remainingSec -= fastSec
                    if (remainingSec <= 0) break
                    
                    val slowSec = minOf(2 * 60, remainingSec)
                    segments.add(ProgramSegment(slowSec, walkSpeed, 0.0, "Восстановление $block"))
                    remainingSec -= slowSec
                    block++
                }
                segments.add(ProgramSegment(cooldownMin * 60, walkSpeed, 0.0, "Заминка"))
            }

            WorkoutGoal.CARDIO -> {
                val warmupMin = (durationMin * 0.2).toInt().coerceIn(3, 10)
                val cooldownMin = (durationMin * 0.15).toInt().coerceIn(3, 8)
                val mainMin = durationMin - warmupMin - cooldownMin

                segments.add(ProgramSegment(warmupMin * 60, walkSpeed, 0.0, "Разминка (Ходьба)"))
                
                // Основной бег разбиваем на 3 части с разным наклоном (волны)
                if (mainMin > 0) {
                    val mainSec = mainMin * 60
                    val partSec = mainSec / 3
                    val partIncline = (baseIncline * 0.4).coerceIn(0.0, safeMaxIncline)
                    
                    segments.add(ProgramSegment(partSec, jogSpeed, 0.0, "Бег (ровно)"))
                    segments.add(ProgramSegment(partSec, jogSpeed, partIncline, "Бег (подъем)"))
                    segments.add(ProgramSegment(mainSec - partSec * 2, jogSpeed, 0.0, "Бег (ровно)"))
                }
                segments.add(ProgramSegment(cooldownMin * 60, walkSpeed, 0.0, "Заминка"))
            }

            WorkoutGoal.SPEED -> {
                val warmupMin = (durationMin * 0.25).toInt().coerceIn(5, 15)
                val cooldownMin = (durationMin * 0.15).toInt().coerceIn(3, 10)
                val mainMin = durationMin - warmupMin - cooldownMin

                segments.add(ProgramSegment(warmupMin * 60, jogSpeed, 0.0, "Разминка (Бег)"))
                
                val fastSec = when (profile.level) {
                    ExperienceLevel.BEGINNER -> 30
                    ExperienceLevel.INTERMEDIATE -> 60
                    ExperienceLevel.ADVANCED -> 90
                }
                val slowSec = when (profile.level) {
                    ExperienceLevel.BEGINNER -> 90
                    ExperienceLevel.INTERMEDIATE -> 60
                    ExperienceLevel.ADVANCED -> 60
                }
                
                // Интервалы в горку - 60% от baseIncline
                val speedIncline = (baseIncline * 0.6).coerceIn(0.0, safeMaxIncline)

                var remainingSec = mainMin * 60
                var intNum = 1
                while (remainingSec > 0) {
                    val fast = minOf(fastSec, remainingSec)
                    segments.add(ProgramSegment(fast, runSpeed, speedIncline, "Интервал $intNum (горка)"))
                    remainingSec -= fast
                    if (remainingSec <= 0) break
                    
                    val slow = minOf(slowSec, remainingSec)
                    segments.add(ProgramSegment(slow, (jogSpeed * 0.85).roundToTenth(), 0.0, "Отдых $intNum"))
                    remainingSec -= slow
                    intNum++
                }
                segments.add(ProgramSegment(cooldownMin * 60, walkSpeed, 0.0, "Заминка"))
            }
        }

        val levelStr = when (profile.level) {
            ExperienceLevel.BEGINNER -> "новичок"
            ExperienceLevel.INTERMEDIATE -> "средний"
            ExperienceLevel.ADVANCED -> "профи"
        }
        val title = when (goal) {
            WorkoutGoal.WEIGHT_LOSS -> "Жиросжигание ${durationMin}м"
            WorkoutGoal.CARDIO -> "Кардио ${durationMin}м"
            WorkoutGoal.SPEED -> "Интервалы ${durationMin}м"
        }

        return ProgramData(
            name = title,
            description = "${goal.title} · Уровень: $levelStr · Интенсивность: $intensityPercent%",
            segments = segments
        )
    }

    private fun Double.roundToTenth(): Double = (this * 10).roundToInt() / 10.0
}
