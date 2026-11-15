package com.shangjin.frameecho.app.ui.player.precision

import com.shangjin.frameecho.app.settings.PrecisionDialSettings

/** Where the dial's guide ring is in its show/fade/return cycle. */
enum class GuidePhase {
    /** Never turned yet: the first, brighter showing. */
    INITIAL,
    /** The finger has turned the dial and is still down: the guide is faded out. */
    DRAGGING,
    /** The finger lifted; the guide stays faded until the return delay elapses. */
    RETURNING,
    /** Came back after a turn: the dimmer resting showing. */
    RESTING
}

/**
 * The guide ring's show/fade/return cycle, as an immutable value.
 *
 * Every transition into [GuidePhase.RETURNING] gets a new [returnGeneration]; the delay
 * that brings the guide back reports the generation it was started for, and a stale one
 * (the finger came back, or released again, in the meantime) is ignored. That is what
 * stops an old timer from bringing the guide back in the middle of a new turn.
 *
 * Opacity is read from the current settings on every frame rather than captured at a
 * transition, so a settings change applies at once in whatever phase the dial is in.
 */
data class PrecisionGuideState(
    val phase: GuidePhase = GuidePhase.INITIAL,
    val returnGeneration: Long = 0L
) {
    /** The finger turned the dial while down. */
    fun turned(): PrecisionGuideState =
        if (phase == GuidePhase.DRAGGING) this else copy(phase = GuidePhase.DRAGGING)

    /**
     * The finger came down. During the return delay the guide stays hidden, as if the
     * turn never stopped; otherwise nothing changes until the dial actually turns.
     */
    fun pressed(): PrecisionGuideState =
        if (phase == GuidePhase.RETURNING) copy(phase = GuidePhase.DRAGGING) else this

    /** The finger lifted or the gesture was cancelled. */
    fun released(): PrecisionGuideState =
        if (phase == GuidePhase.DRAGGING) {
            copy(phase = GuidePhase.RETURNING, returnGeneration = returnGeneration + 1)
        } else {
            this
        }

    /** The return delay started for [generation] has elapsed. */
    fun returnElapsed(generation: Long): PrecisionGuideState =
        if (phase == GuidePhase.RETURNING && generation == returnGeneration) copy(phase = GuidePhase.RESTING) else this

    fun opacity(settings: PrecisionDialSettings): Float = when (phase) {
        GuidePhase.INITIAL -> settings.initialGuideOpacity
        GuidePhase.DRAGGING, GuidePhase.RETURNING -> settings.draggingGuideOpacity
        GuidePhase.RESTING -> settings.restingGuideOpacity
    }
}
