package com.shangjin.frameecho.app.settings

/**
 * How the rotary precision dial looks and how far one turn moves.
 *
 * The dial's guide ring starts at [initialGuideOpacity], fades to [draggingGuideOpacity]
 * once the finger starts turning, stays there for [guideReturnDelayMs] after the finger
 * lifts and then comes back at [restingGuideOpacity], which is dimmer than the first
 * showing so a returning user is not distracted by it. The thumb stays visible
 * throughout. Opacities are fractions from 0 to 1.
 *
 * [guideColorArgb] is a 0xAARRGGBB colour, or null to follow the theme's primary colour.
 * One full clockwise turn moves [framesPerTurn] frames forward; counterclockwise moves
 * back, with no limit on how many turns.
 */
data class PrecisionDialSettings(
    val initialGuideOpacity: Float = DEFAULT_INITIAL_OPACITY,
    val restingGuideOpacity: Float = DEFAULT_RESTING_OPACITY,
    val draggingGuideOpacity: Float = DEFAULT_DRAGGING_OPACITY,
    val guideReturnDelayMs: Long = DEFAULT_RETURN_DELAY_MS,
    val guideColorArgb: Int? = null,
    val framesPerTurn: Int = DEFAULT_FRAMES_PER_TURN
) {
    fun validate(): List<PrecisionDialProblem> = buildList {
        if (!initialGuideOpacity.isOpacityIn(MIN_INITIAL_OPACITY)) add(PrecisionDialProblem.INITIAL_OPACITY_OUT_OF_RANGE)
        if (!restingGuideOpacity.isOpacityIn(MIN_RESTING_OPACITY)) add(PrecisionDialProblem.RESTING_OPACITY_OUT_OF_RANGE)
        if (!draggingGuideOpacity.isOpacityIn(0f)) add(PrecisionDialProblem.DRAGGING_OPACITY_OUT_OF_RANGE)
        if (restingGuideOpacity >= initialGuideOpacity) add(PrecisionDialProblem.RESTING_NOT_DIMMER)
        if (guideReturnDelayMs !in 0L..MAX_RETURN_DELAY_MS) add(PrecisionDialProblem.RETURN_DELAY_OUT_OF_RANGE)
        if (framesPerTurn !in MIN_FRAMES_PER_TURN..MAX_FRAMES_PER_TURN) add(PrecisionDialProblem.FRAMES_PER_TURN_OUT_OF_RANGE)
    }

    val isValid: Boolean get() = validate().isEmpty()

    companion object {
        const val DEFAULT_INITIAL_OPACITY = 0.6f
        const val DEFAULT_RESTING_OPACITY = 0.25f
        const val DEFAULT_DRAGGING_OPACITY = 0f
        const val DEFAULT_RETURN_DELAY_MS = 1_000L
        const val DEFAULT_FRAMES_PER_TURN = 60

        /** A first showing below 10% would be invisible over most video. */
        const val MIN_INITIAL_OPACITY = 0.1f
        const val MIN_RESTING_OPACITY = 0.05f
        const val MAX_RETURN_DELAY_MS = 10_000L
        const val MIN_FRAMES_PER_TURN = 10
        const val MAX_FRAMES_PER_TURN = 360

        val DEFAULT = PrecisionDialSettings()

        /**
         * Builds settings from stored values, replacing each missing or out-of-range field
         * with its default, so one corrupt key can't disable the dial or reset the others.
         * A resting opacity that is not dimmer than the initial one is halved from it.
         */
        fun fromStored(
            initialGuideOpacity: Float?,
            restingGuideOpacity: Float?,
            draggingGuideOpacity: Float?,
            guideReturnDelayMs: Long?,
            guideColorArgb: Int?,
            framesPerTurn: Int?
        ): PrecisionDialSettings {
            val initial = initialGuideOpacity?.takeIf { it.isOpacityIn(MIN_INITIAL_OPACITY) } ?: DEFAULT_INITIAL_OPACITY
            val resting = restingGuideOpacity?.takeIf { it.isOpacityIn(MIN_RESTING_OPACITY) && it < initial }
                ?: DEFAULT_RESTING_OPACITY.takeIf { it < initial }
                ?: (initial / 2f)
            return PrecisionDialSettings(
                initialGuideOpacity = initial,
                restingGuideOpacity = resting,
                draggingGuideOpacity = draggingGuideOpacity?.takeIf { it.isOpacityIn(0f) } ?: DEFAULT_DRAGGING_OPACITY,
                guideReturnDelayMs = guideReturnDelayMs?.takeIf { it in 0L..MAX_RETURN_DELAY_MS } ?: DEFAULT_RETURN_DELAY_MS,
                guideColorArgb = guideColorArgb,
                framesPerTurn = framesPerTurn?.takeIf { it in MIN_FRAMES_PER_TURN..MAX_FRAMES_PER_TURN }
                    ?: DEFAULT_FRAMES_PER_TURN
            )
        }

        private fun Float.isOpacityIn(min: Float): Boolean = !isNaN() && this in min..1f
    }
}

enum class PrecisionDialProblem {
    INITIAL_OPACITY_OUT_OF_RANGE,
    RESTING_OPACITY_OUT_OF_RANGE,
    DRAGGING_OPACITY_OUT_OF_RANGE,
    RESTING_NOT_DIMMER,
    RETURN_DELAY_OUT_OF_RANGE,
    FRAMES_PER_TURN_OUT_OF_RANGE
}
