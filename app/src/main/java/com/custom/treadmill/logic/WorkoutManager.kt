package com.custom.treadmill.logic

import com.custom.treadmill.data.database.ProgramData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WorkoutState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val finished: Boolean = false,
    val programName: String = "",
    val totalSec: Int = 0,
    val totalElapsedSec: Int = 0,
    val currentSegmentIndex: Int = 0,
    val totalSegments: Int = 0,
    val currentSegmentName: String = "",
    val segmentElapsedSec: Int = 0,
    val segmentDurationSec: Int = 0,
    val progressPercent: Int = 0
)

class WorkoutManager {

    private val _state = MutableStateFlow(WorkoutState())
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private var job: Job? = null
    private var currentProgram: ProgramData? = null
    private var scopeRef: CoroutineScope? = null
    private var onSetSpeedRef: (suspend (Double) -> Unit)? = null
    private var onSetInclineRef: (suspend (Double) -> Unit)? = null
    private var onFinishRef: (suspend () -> Unit)? = null
    private var onErrorRef: (suspend (String) -> Unit)? = null

    fun start(
        scope: CoroutineScope,
        program: ProgramData,
        onSetSpeed: suspend (Double) -> Unit,
        onSetIncline: suspend (Double) -> Unit = ::noopDouble,
        onFinish: suspend () -> Unit = { },
        onError: suspend (String) -> Unit = { }
    ) {
        stop()
        currentProgram = program
        scopeRef = scope
        onSetSpeedRef = onSetSpeed
        onSetInclineRef = onSetIncline
        onFinishRef = onFinish
        onErrorRef = onError

        val total = program.segments.sumOf { it.durationSec }
        _state.value = WorkoutState(
            running = true,
            paused = false,
            finished = false,
            programName = program.name,
            totalSec = total,
            totalSegments = program.segments.size,
            currentSegmentIndex = 0,
            segmentElapsedSec = 0,
            totalElapsedSec = 0,
            segmentDurationSec = program.segments.firstOrNull()?.durationSec ?: 0,
            currentSegmentName = program.segments.firstOrNull()?.name.orEmpty(),
            progressPercent = 0
        )
        launchLoop()
    }

    fun pause() {
        if (!_state.value.running || _state.value.paused) return
        job?.cancel()
        job = null
        _state.value = _state.value.copy(paused = true)
    }

    fun resume() {
        if (!_state.value.paused) return
        _state.value = _state.value.copy(paused = false)
        launchLoop()
    }

    fun togglePause() {
        val s = _state.value
        when {
            s.paused -> resume()
            s.running -> pause()
        }
    }

    fun skipCurrentSegment() {
        val s = _state.value
        if (!s.running) return
        val program = currentProgram ?: return

        val nextIndex = s.currentSegmentIndex + 1

        if (nextIndex >= program.segments.size) {
            job?.cancel()
            job = null
            _state.value = s.copy(running = false, paused = false, finished = true, progressPercent = 100)
            scopeRef?.launch { onFinishRef?.invoke() }
            return
        }

        val next = program.segments[nextIndex]

        job?.cancel()
        job = null

        val newProgress = ((nextIndex.toFloat() / program.segments.size) * 100).toInt()

        _state.value = s.copy(
            currentSegmentIndex = nextIndex,
            currentSegmentName = next.name.ifBlank { "Сегмент ${nextIndex + 1}" },
            segmentDurationSec = next.durationSec,
            segmentElapsedSec = 0,
            totalElapsedSec = s.totalElapsedSec,
            progressPercent = newProgress
        )

        launchLoop()
    }

    fun stop() {
        job?.cancel()
        job = null
        currentProgram = null
        scopeRef = null
        onSetSpeedRef = null
        onSetInclineRef = null
        onFinishRef = null
        onErrorRef = null
        _state.value = WorkoutState()
    }

    // [ФИКС] CancellationException пробрасываем, чтобы корутину можно было корректно отменить
    private suspend fun safeSetSpeed(speed: Double, block: suspend (Double) -> Unit): Boolean {
        return try {
            block(speed)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun safeSetIncline(incline: Double, block: suspend (Double) -> Unit): Boolean {
        return try {
            block(incline)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun launchLoop() {
        val scope = scopeRef ?: return
        val program = currentProgram ?: return
        val onSetSpeed: suspend (Double) -> Unit = onSetSpeedRef ?: return
        val onSetIncline: suspend (Double) -> Unit = onSetInclineRef ?: ::noopDouble
        val onFinish: suspend () -> Unit = onFinishRef ?: { }
        val onError: suspend (String) -> Unit = onErrorRef ?: { }

        job = scope.launch {
            val segments = program.segments

            var index = _state.value.currentSegmentIndex
            var totalElapsed = _state.value.totalElapsedSec

            if (index < segments.size) {
                val firstSeg = segments[index]
                if (_state.value.segmentElapsedSec == 0) {
                    val spdOk = safeSetSpeed(firstSeg.speedKmh, onSetSpeed)
                    val incOk = safeSetIncline(firstSeg.inclinePercent, onSetIncline)
                    if (!spdOk || !incOk) {
                        _state.value = _state.value.copy(running = false, paused = true)
                        // [ФИКС] сообщаем наружу — сессия остановит мотор и покажет ошибку
                        onError("Не удалось отправить команду на дорожку")
                        return@launch
                    }
                    val newProgress = ((index.toFloat() / segments.size) * 100).toInt()
                    _state.value = _state.value.copy(
                        currentSegmentIndex = index,
                        currentSegmentName = firstSeg.name.ifBlank { "Сегмент ${index + 1}" },
                        segmentDurationSec = firstSeg.durationSec,
                        segmentElapsedSec = 0,
                        progressPercent = newProgress
                    )
                } else {
                    val spdOk = safeSetSpeed(firstSeg.speedKmh, onSetSpeed)
                    val incOk = safeSetIncline(firstSeg.inclinePercent, onSetIncline)
                    if (!spdOk || !incOk) {
                        _state.value = _state.value.copy(running = false, paused = true)
                        onError("Не удалось отправить команду на дорожку")
                        return@launch
                    }
                }
            }

            while (index < segments.size && isActive) {
                val seg = segments[index]

                var s = _state.value.segmentElapsedSec
                while (s < seg.durationSec && isActive) {
                    delay(1000L)
                    s++
                    totalElapsed++

                    val currentProgress = ((index + (s.toFloat() / seg.durationSec.toFloat())) / segments.size * 100).toInt()

                    _state.value = _state.value.copy(
                        segmentElapsedSec = s,
                        totalElapsedSec = totalElapsed,
                        progressPercent = currentProgress
                    )
                }

                if (s >= seg.durationSec) {
                    index++
                    if (index < segments.size) {
                        val nextSeg = segments[index]
                        val spdOk = safeSetSpeed(nextSeg.speedKmh, onSetSpeed)
                        val incOk = safeSetIncline(nextSeg.inclinePercent, onSetIncline)
                        if (!spdOk || !incOk) {
                            _state.value = _state.value.copy(running = false, paused = true)
                            onError("Не удалось отправить команду на дорожку")
                            return@launch
                        }
                        val newProgress = ((index.toFloat() / segments.size) * 100).toInt()
                        _state.value = _state.value.copy(
                            currentSegmentIndex = index,
                            currentSegmentName = nextSeg.name.ifBlank { "Сегмент ${index + 1}" },
                            segmentDurationSec = nextSeg.durationSec,
                            segmentElapsedSec = 0,
                            progressPercent = newProgress
                        )
                    }
                }
            }

            if (isActive) {
                _state.value = _state.value.copy(running = false, paused = false, finished = true, progressPercent = 100)
                onFinish()
            }
        }
    }

    private suspend fun noopDouble(@Suppress("UNUSED_PARAMETER") value: Double) {
    }
}
