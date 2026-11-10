package com.custom.treadmill.logic

import kotlin.math.roundToInt

/**
 * Конверсия скорости (км/ч) в темп (мин/км) и обратно.
 *
 * Внутри приложения везде используется скорость. Темп — производная величина,
 * нужная только для отображения. Поэтому храним и передаём по BLE только
 * скорость, а темп считаем на лету.
 *
 * Формат темпа: «M:SS» (например, «7:03»). Для скоростей ≤ 0.05 км/ч
 * возвращается «—», чтобы не показывать «∞» или огромные числа.
 */
object SpeedFormatter {

    private const val MIN_SPEED_KMH = 0.05

    /** Секунд на километр. Возвращает 0 при скорости ≤ 0.05 км/ч. */
    fun speedToPaceSeconds(speedKmh: Double): Int {
        if (speedKmh <= MIN_SPEED_KMH) return 0
        return (3600.0 / speedKmh).roundToInt()
    }

    /** «7:03» или «—». */
    fun formatPace(speedKmh: Double): String {
        val sec = speedToPaceSeconds(speedKmh)
        if (sec <= 0) return "—"
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    /**
     * «7:03 /км» или «—». Если unit пустой — возвращает только цифры.
     * При скорости 0 возвращает просто «—» без юнита — иначе получится
     * бессмысленное «— /км».
     */
    fun formatPaceWithUnit(speedKmh: Double, unit: String): String {
        val pace = formatPace(speedKmh)
        if (pace == "—") return pace
        return if (unit.isBlank()) pace else "$pace $unit"
    }
}
