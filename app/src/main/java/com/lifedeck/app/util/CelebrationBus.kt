package com.lifedeck.app.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Something worth celebrating: a milestone, project or goal completing. */
data class Celebration(
    val kind: Kind,
    val title: String,
    val message: String,
    val progressPercent: Int = 100,
) {
    enum class Kind { TASK_DONE, STREAK, MILESTONE, PROJECT, GOAL }
}

/**
 * One-shot celebration events. The UI collects these and shows a tasteful,
 * configurable overlay (never blocking, always dismissible).
 */
object CelebrationBus {
    private val _events = MutableSharedFlow<Celebration>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun emit(event: Celebration) {
        _events.tryEmit(event)
    }
}
