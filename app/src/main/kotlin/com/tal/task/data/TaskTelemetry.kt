package com.tal.task.data

import com.tal.task.engine.TouchResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TelemetrySnapshot(
    val totalTaps: Int = 0,
    val rescuedTaps: Int = 0,
    val coreProtectedTaps: Int = 0,
    val lastComputeMs: Double = 0.0,
    val lastResolveMs: Double = 0.0,
    val p99TotalMs: Double = 0.0,
    val incognitoBlockedEvents: Int = 0,
    val recentResolutions: List<TouchResolution> = emptyList()
)

object TaskTelemetry {
    private val _state = MutableStateFlow(TelemetrySnapshot())
    val state: StateFlow<TelemetrySnapshot> = _state.asStateFlow()

    private val latencyRing = DoubleArray(120)
    private var ringCursor = 0
    private var ringCount = 0
    private val recentLog = ArrayDeque<TouchResolution>(16)

    @Synchronized
    fun recordTouch(computeMs: Double, resolution: TouchResolution) {
        val totalMs = computeMs + resolution.latencyMs
        latencyRing[ringCursor] = totalMs
        ringCursor = (ringCursor + 1) % latencyRing.size
        if (ringCount < latencyRing.size) ringCount++

        val sorted = latencyRing.copyOf(ringCount).apply { sort() }
        val p99 = sorted[((sorted.size - 1) * 0.99).toInt().coerceAtLeast(0)]

        if (recentLog.size >= 12) recentLog.removeLast()
        recentLog.addFirst(resolution)

        val prev = _state.value
        _state.value = prev.copy(
            totalTaps = prev.totalTaps + 1,
            rescuedTaps = prev.rescuedTaps + if (resolution.wasRescued) 1 else 0,
            coreProtectedTaps = prev.coreProtectedTaps + if (resolution.protectedByCore) 1 else 0,
            lastComputeMs = computeMs,
            lastResolveMs = resolution.latencyMs,
            p99TotalMs = p99,
            recentResolutions = recentLog.toList()
        )
    }

    @Synchronized
    fun recordIncognitoBlock() {
        val prev = _state.value
        _state.value = prev.copy(incognitoBlockedEvents = prev.incognitoBlockedEvents + 1)
    }

    @Synchronized
    fun reset() {
        ringCursor = 0
        ringCount = 0
        recentLog.clear()
        _state.value = TelemetrySnapshot()
    }
}
