package com.custom.treadmill.logic

import com.custom.treadmill.data.repository.AppSettings

object HeartRateZoneCalculator {

    fun calculateMaxHr(age: Int, isMale: Boolean): Int {
        return if (isMale) {
            (208 - 0.7 * age).toInt()
        } else {
            (206 - 0.88 * age).toInt()
        }
    }

    /**
     * Границы зон по модели Karvonen (5 зон).
     *
     * Возвращает 5 границ в уд/мин:
     *  - [0] = 50% HRR + restingHr
     *  - [1] = 60% HRR + restingHr
     *  - [2] = 70% HRR + restingHr
     *  - [3] = 80% HRR + restingHr
     *  - [4] = 90% HRR + restingHr
     *
     * Интерпретация (единая для всего приложения, см. zoneOf/zoneRange):
     *  - Z1 (восстановление): < [1] (включает «< 50%»)
     *  - Z2 (лёгкая):         [1]..[2]  (60–70%)
     *  - Z3 (аэробная):       [2]..[3]  (70–80%)
     *  - Z4 (пороговая):      [3]..[4]  (80–90%)
     *  - Z5 (максимальная):   > [4]     (выше 90%)
     *
     * [ФИКС] Раньше ProfileScreen и HistoryScreen показывали Z4 = 70–80%
     * и Z5 = >80%, сдвигая всю шкалу на одну зону вниз. [4] (90%) при этом
     * не использовался вообще. Теперь оба используют zoneOf/zoneRange,
     * и границы совпадают между всеми экранами.
     */
    fun getZoneBounds(maxHr: Int, restingHr: Int): List<Int> {
        val hrr = (maxHr - restingHr).coerceAtLeast(1)
        return listOf(
            (hrr * 0.50 + restingHr).toInt(),
            (hrr * 0.60 + restingHr).toInt(),
            (hrr * 0.70 + restingHr).toInt(),
            (hrr * 0.80 + restingHr).toInt(),
            (hrr * 0.90 + restingHr).toInt()
        )
    }

    /**
     * Индекс зоны (0..4) для пульса bpm.
     * Если bpm ниже [1] (60%), возвращает 0 — Z1 включает и «< 50%», и «50–60%».
     */
    fun zoneOf(bpm: Int, zoneBounds: List<Int>): Int {
        if (zoneBounds.size != 5) return 0
        return when {
            bpm < zoneBounds[1] -> 0
            bpm < zoneBounds[2] -> 1
            bpm < zoneBounds[3] -> 2
            bpm < zoneBounds[4] -> 3
            else -> 4
        }
    }

    /**
     * Границы отображения зоны index (0..4):
     *  - Z1: (null, zones[1])   → подпись «< zones[1]»
     *  - Z2: (zones[1], zones[2])
     *  - Z3: (zones[2], zones[3])
     *  - Z4: (zones[3], zones[4])
     *  - Z5: (zones[4], null)   → подпись «> zones[4]»
     */
    fun zoneRange(index: Int, zoneBounds: List<Int>): Pair<Int?, Int?> {
        if (zoneBounds.size != 5) return Pair(null, null)
        return when (index) {
            0 -> Pair(null, zoneBounds[1])
            1 -> Pair(zoneBounds[1], zoneBounds[2])
            2 -> Pair(zoneBounds[2], zoneBounds[3])
            3 -> Pair(zoneBounds[3], zoneBounds[4])
            4 -> Pair(zoneBounds[4], null)
            else -> Pair(null, null)
        }
    }

    fun getEffectiveMaxHr(settings: AppSettings): Int {
        val p = settings.activeProfile
        return if (settings.autoMaxHr) {
            calculateMaxHr(p.age, p.isMale)
        } else {
            settings.maxHr.coerceAtLeast(p.restingHr + 20)
        }
    }
}
