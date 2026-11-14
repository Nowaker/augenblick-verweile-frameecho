package com.shangjin.frameecho.app.ui.player.framestep

import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Upper bound on frames folded into one step when the main thread fell behind. */
internal const val MAX_CATCH_UP_FRAMES = 30

/**
 * Runs the repeating part of a held frame-step button until cancelled.
 *
 * The press itself already stepped once; this waits [FrameStepRepeatSchedule.initialDelayMs]
 * and then calls [onStep] whenever the schedule says steps are due. Each wake-up is
 * computed from the repeat start, not from the previous wake-up, so late timers never
 * accumulate. When a wake-up is so late that several steps are due, they are folded into
 * one [onStep] call with a larger frame count: the position keeps pace with the schedule
 * and every call still produces one visible update.
 *
 * Cancel the calling coroutine to stop: on release, pointer leaving the button, the
 * button being disabled, or the lifecycle stopping.
 *
 * @param nowMs monotonic clock in milliseconds.
 * @param onStep called with the number of frames to move (always >= 1).
 */
internal suspend fun runFrameStepRepeat(
    schedule: FrameStepRepeatSchedule,
    nowMs: () -> Long,
    onStep: (frames: Int) -> Unit
): Nothing {
    delay(schedule.initialDelayMs)
    val startMs = nowMs()
    var emitted = 0L
    while (true) {
        val due = schedule.stepsDueAt(nowMs() - startMs)
        if (due > emitted) {
            onStep((due - emitted).coerceAtMost(MAX_CATCH_UP_FRAMES.toLong()).toInt())
            emitted = due
        }
        val nextDueMs = ceil(schedule.stepTimeMs(emitted)).toLong()
        delay((nextDueMs - (nowMs() - startMs)).coerceAtLeast(1L))
    }
}
