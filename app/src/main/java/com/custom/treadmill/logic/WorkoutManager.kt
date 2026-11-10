package com.custom.treadmill.logic

import com.custom.treadmill.DebugLog
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

    private var segmentNameProviderRef: (Int) -> String = { n -> "Segment $n" }

    fun start(
        scope: CoroutineScope,
        program: ProgramData,
        onSetSpeed: suspend (Double) -> Unit,
        onSetIncline: suspend (Double) -> Unit = ::noopDouble,
        onFinish: suspend () -> Unit = { },
        onError: suspend (String) -> Unit = { },
        segmentNameProvider: (Int) -> String = { n -> "Segment $n" }
    ) {
        stop()
        currentProgram = program
        scopeRef = scope
        onSetSpeedRef = onSetSpeed
        onSetInclineRef = onSetIncline
        onFinishRef = onFinish
        onErrorRef = onError
        segmentNameProviderRef = segmentNameProvider

        val total = program.segments.sumOf { it.durationSec }
        val firstSeg = program.segments.firstOrNull()

        DebugLog.d("WM", "START program='${program.name}' segments=${program.segments.size} totalSec=$total")

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
            segmentDurationSec = firstSeg?.durationSec ?: 0,
            currentSegmentName = firstSeg?.name?.takeIf { it.isNotBlank() }
                ?: segmentNameProviderRef(1),
            progressPercent = 0
        )
        launchLoop()
    }

    fun pause() {
        if (!_state.value.running || _state.value.paused) return
        job?.cancel()
        job = null
        val s = _state.value
        DebugLog.d("WM", "PAUSE seg=${s.currentSegmentIndex + 1}/${s.totalSegments} atSec=${s.segmentElapsedSec}")
        _state.value = s.copy(paused = true)
    }

    fun resume() {
        if (!_state.value.paused) return
        val s = _state.value
        DebugLog.d("WM", "RESUME seg=${s.currentSegmentIndex + 1}/${s.totalSegments} atSec=${s.segmentElapsedSec}")
        _state.value = s.copy(paused = false)
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
            DebugLog.d("WM", "SKIP → FINISH (last segment)")
            _state.value = s.copy(running = false, paused = false, finished = true, progressPercent = 100)
            scopeRef?.launch { onFinishRef?.invoke() }
            return
        }

        val next = program.segments[nextIndex]

        job?.cancel()
        job = null

        DebugLog.d("WM", "SKIP ${s.currentSegmentIndex + 1} → ${nextIndex + 1}/${program.segments.size} name='${next.name}'")

        val newProgress = ((nextIndex.toFloat() / program.segments.size) * 100).toInt()

        _state.value = s.copy(
            currentSegmentIndex = nextIndex,
            currentSegmentName = next.name.takeIf { it.isNotBlank() }
                ?: segmentNameProviderRef(nextIndex + 1),
            segmentDurationSec = next.durationSec,
            segmentElapsedSec = 0,
            totalElapsedSec = s.totalElapsedSec,
            progressPercent = newProgress
        )

        launchLoop()
    }

    fun stop() {
        val s = _state.value
        if (s.running || s.paused) {
            DebugLog.d("WM", "STOP elapsed=${s.totalElapsedSec}s seg=${s.currentSegmentIndex + 1}/${s.totalSegments}")
        }
        job?.cancel()
        job = null
        currentProgram = null
        scopeRef = null
        onSetSpeedRef = null
        onSetInclineRef = null
        onFinishRef = null
        onErrorRef = null
        segmentNameProviderRef = { n -> "Segment $n" }
        _state.value = WorkoutState()
    }

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
                    DebugLog.d("WM", "SEGMENT ${index + 1}/${segments.size} name='${firstSeg.name}' speed=${firstSeg.speedKmh} incline=${firstSeg.inclinePercent} dur=${firstSeg.durationSec}s")
                    val spdOk = safeSetSpeed(firstSeg.speedKmh, onSetSpeed)
                    val incOk = safeSetIncline(firstSeg.inclinePercent, onSetIncline)
                    if (!spdOk || !incOk) {
                        DebugLog.d("WM", "ERROR command failed at seg ${index + 1} spdOk=$spdOk incOk=$incOk")
                        _state.value = _state.value.copy(running = false, paused = true)
                        onError("Не удалось отправить команду на дорожку")
                        return@launch
                    }
                    val newProgress = ((index.toFloat() / segments.size) * 100).toInt()
                    _state.value = _state.value.copy(
                        currentSegmentIndex = index,
                        currentSegmentName = firstSeg.name.takeIf { it.isNotBlank() }
                            ?: segmentNameProviderRef(index + 1),
                        segmentDurationSec = firstSeg.durationSec,
                        segmentElapsedSec = 0,
                        progressPercent = newProgress
                    )
                } else {
                    DebugLog.d("WM", "SEGMENT RESUME ${index + 1}/${segments.size} name='${firstSeg.name}' atSec=${_state.value.segmentElapsedSec}")
                    val spdOk = safeSetSpeed(firstSeg.speedKmh, onSetSpeed)
                    val incOk = safeSetIncline(firstSeg.inclinePercent, onSetIncline)
                    if (!spdOk || !incOk) {
                        DebugLog.d("WM", "ERROR resume command failed at seg ${index + 1} spdOk=$spdOk incOk=$incOk")
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
                        DebugLog.d("WM", "SEGMENT ${index + 1}/${segments.size} name='${nextSeg.name}' speed=${nextSeg.speedKmh} incline=${nextSeg.inclinePercent} dur=${nextSeg.durationSec}s (transition)")
                        val spdOk = safeSetSpeed(nextSeg.speedKmh, onSetSpeed)
                        val incOk = safeSetIncline(nextSeg.inclinePercent, onSetIncline)
                        if (!spdOk || !incOk) {
                            DebugLog.d("WM", "ERROR transition command failed at seg ${index + 1} spdOk=$spdOk incOk=$incOk")
                            _state.value = _state.value.copy(running = false, paused = true)
                            onError("Не удалось отправить команду на дорожку")
                            return@launch
                        }
                        val newProgress = ((index.toFloat() / segments.size) * 100).toInt()
                        _state.value = _state.value.copy(
                            currentSegmentIndex = index,
                            currentSegmentName = nextSeg.name.takeIf { it.isNotBlank() }
                                ?: segmentNameProviderRef(index + 1),
                            segmentDurationSec = nextSeg.durationSec,
                            segmentElapsedSec = 0,
                            progressPercent = newProgress
                        )
                    }
                }
            }

            if (isActive) {
                DebugLog.d("WM", "FINISH totalElapsed=${totalElapsed}s")
                _state.value = _state.value.copy(running = false, paused = false, finished = true, progressPercent = 100)
                onFinish()
            }
        }
    }

    private suspend fun noopDouble(@Suppress("UNUSED_PARAMETER") value: Double) {
    }
}
