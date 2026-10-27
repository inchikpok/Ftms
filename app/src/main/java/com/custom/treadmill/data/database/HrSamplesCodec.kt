package com.custom.treadmill.data.database

object HrSamplesCodec {

    /** Кодирует список (offsetSec, bpm) в строку "0:120,5:125,..." */
    fun encode(samples: List<Pair<Int, Int>>): String {
        if (samples.isEmpty()) return ""
        val sb = StringBuilder(samples.size * 8)
        samples.forEachIndexed { i, (t, bpm) ->
            if (i > 0) sb.append(',')
            sb.append(t).append(':').append(bpm)
        }
        return sb.toString()
    }

    /** Декодирует "0:120,5:125,..." обратно в список. Некорректные фрагменты пропускает. */
    fun decode(s: String): List<Pair<Int, Int>> {
        if (s.isBlank()) return emptyList()
        val result = ArrayList<Pair<Int, Int>>(s.length / 6 + 1)
        for (part in s.split(',')) {
            val idx = part.indexOf(':')
            if (idx <= 0) continue
            val t = part.substring(0, idx).toIntOrNull() ?: continue
            val b = part.substring(idx + 1).toIntOrNull() ?: continue
            if (b > 0) result.add(t to b)
        }
        return result
    }
}
