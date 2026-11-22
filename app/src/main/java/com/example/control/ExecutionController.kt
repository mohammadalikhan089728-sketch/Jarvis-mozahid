package com.example.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates continuous automation loops and instant stop requests from either:
 * - Voice commands ("Stop" / "Rehne do")
 * - Red "Stop" button on the top floating pill
 * - Tapping the AI Orb
 */
object ExecutionController {
    private val stopFlag = AtomicBoolean(false)

    private val _isControllingPhone = MutableStateFlow(false)
    val isControllingPhone: StateFlow<Boolean> = _isControllingPhone.asStateFlow()

    private val _lastAutoReplyReport = MutableStateFlow<String?>(null)
    val lastAutoReplyReport: StateFlow<String?> = _lastAutoReplyReport.asStateFlow()

    private var onStopListeners = mutableListOf<() -> Unit>()

    fun beginControlSession() {
        stopFlag.set(false)
        _isControllingPhone.value = true
    }

    fun endControlSession() {
        _isControllingPhone.value = false
    }

    fun resetStopFlag() {
        stopFlag.set(false)
    }

    fun isStopRequested(): Boolean = stopFlag.get()

    fun shouldContinue(): Boolean = !stopFlag.get()

    fun requestStop() {
        stopFlag.set(true)
        _isControllingPhone.value = false
        onStopListeners.toList().forEach { runCatching { it.invoke() } }
    }

    fun addStopListener(listener: () -> Unit) {
        if (!onStopListeners.contains(listener)) {
            onStopListeners.add(listener)
        }
    }

    fun removeStopListener(listener: () -> Unit) {
        onStopListeners.remove(listener)
    }

    fun postAutoReplyReport(report: String) {
        _lastAutoReplyReport.value = report
    }
}
