package com.custom.treadmill.data.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HrSamplesCodecTest {

    @Test
    fun `encode empty list returns empty string`() {
        assertEquals("", HrSamplesCodec.encode(emptyList()))
    }

    @Test
    fun `decode empty string returns empty list`() {
        assertTrue(HrSamplesCodec.decode("").isEmpty())
        assertTrue(HrSamplesCodec.decode("   ").isEmpty())
    }

    @Test
    fun `roundtrip preserves samples`() {
        val original = listOf(
            0 to 85,
            5 to 92,
            10 to 120,
            300 to 145,
            1800 to 138
        )
        val encoded = HrSamplesCodec.encode(original)
        val decoded = HrSamplesCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `encode format is expected`() {
        val samples = listOf(0 to 120, 5 to 125, 10 to 130)
        assertEquals("0:120,5:125,10:130", HrSamplesCodec.encode(samples))
    }

    @Test
    fun `decode skips garbage parts`() {
        // мусор между корректными элементами должен игнорироваться
        val decoded = HrSamplesCodec.decode("abc,0:120,,5:125,:bad,10:130,60:xyz")
        assertEquals(listOf(0 to 120, 5 to 125, 10 to 130), decoded)
    }

    @Test
    fun `decode skips zero and negative bpm`() {
        val decoded = HrSamplesCodec.decode("0:120,5:0,10:-5,15:125")
        assertEquals(listOf(0 to 120, 15 to 125), decoded)
    }

    @Test
    fun `decode skips parts with no colon or colon at start`() {
        val decoded = HrSamplesCodec.decode(":120,5:,100,15:125")
        assertEquals(listOf(15 to 125), decoded)
    }

    @Test
    fun `decode allows negative time offsets`() {
        // Отрицательное время не имеет смысла, но кодек его не отбрасывает —
        // это ответственность вызывающей стороны. Проверяем текущее поведение.
        val decoded = HrSamplesCodec.decode("-5:120,0:125")
        assertEquals(listOf(-5 to 120, 0 to 125), decoded)
    }

    @Test
    fun `single sample roundtrip`() {
        val original = listOf(0 to 100)
        assertEquals(original, HrSamplesCodec.decode(HrSamplesCodec.encode(original)))
    }

    @Test
    fun `large list roundtrip`() {
        // 45 минут с шагом 5 секунд = 540 точек
        val original = (0 until 540).map { i -> (i * 5) to (80 + (i % 90)) }
        val decoded = HrSamplesCodec.decode(HrSamplesCodec.encode(original))
        assertEquals(original, decoded)
    }
}
