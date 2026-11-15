package com.shangjin.frameecho.app.ui.settings

import com.shangjin.frameecho.app.settings.PrecisionDialProblem
import com.shangjin.frameecho.app.settings.PrecisionDialSettings
import kotlin.math.floor
import kotlin.math.roundToLong

/** A text field of the precision dial section that holds a value the user typed. */
enum class PrecisionDialField { FRAMES_PER_TURN, INITIAL_OPACITY, RESTING_OPACITY, DRAGGING_OPACITY, RETURN_DELAY, GUIDE_COLOR }

/**
 * The precision dial section's text fields, before they are settings. Opacities are typed
 * as percentages, the delay in milliseconds and the colour as `#RRGGBB`; an empty colour
 * means the theme colour.
 */
data class PrecisionDialSettingsDraft(
    val framesPerTurn: String,
    val initialOpacityPercent: String,
    val restingOpacityPercent: String,
    val draggingOpacityPercent: String,
    val returnDelayMs: String,
    val guideColorHex: String
) {
    sealed interface Result {
        data class Valid(val settings: PrecisionDialSettings) : Result
        data class Invalid(val fields: Set<PrecisionDialField>) : Result
    }

    fun toSettings(): Result {
        val unparsed = mutableSetOf<PrecisionDialField>()
        fun <T> parse(field: PrecisionDialField, value: T?): T? = value.also { if (it == null) unparsed += field }

        val frames = parse(PrecisionDialField.FRAMES_PER_TURN, framesPerTurn.trim().toIntOrNull())
        val initial = parse(PrecisionDialField.INITIAL_OPACITY, percentToFraction(initialOpacityPercent))
        val resting = parse(PrecisionDialField.RESTING_OPACITY, percentToFraction(restingOpacityPercent))
        val dragging = parse(PrecisionDialField.DRAGGING_OPACITY, percentToFraction(draggingOpacityPercent))
        val delay = parse(PrecisionDialField.RETURN_DELAY, returnDelayMs.trim().toLongOrNull())
        val color = parseColor(guideColorHex)
        if (color == ColorParse.Invalid) unparsed += PrecisionDialField.GUIDE_COLOR
        if (unparsed.isNotEmpty()) return Result.Invalid(unparsed)

        val settings = PrecisionDialSettings(
            initialGuideOpacity = initial!!,
            restingGuideOpacity = resting!!,
            draggingGuideOpacity = dragging!!,
            guideReturnDelayMs = delay!!,
            guideColorArgb = (color as ColorParse.Parsed).argb,
            framesPerTurn = frames!!
        )
        val problems = settings.validate()
        return if (problems.isEmpty()) Result.Valid(settings) else Result.Invalid(problems.map(::fieldOf).toSet())
    }

    companion object {
        fun from(settings: PrecisionDialSettings) = PrecisionDialSettingsDraft(
            framesPerTurn = settings.framesPerTurn.toString(),
            initialOpacityPercent = fractionToPercent(settings.initialGuideOpacity),
            restingOpacityPercent = fractionToPercent(settings.restingGuideOpacity),
            draggingOpacityPercent = fractionToPercent(settings.draggingGuideOpacity),
            returnDelayMs = settings.guideReturnDelayMs.toString(),
            guideColorHex = settings.guideColorArgb?.let(::formatColor).orEmpty()
        )

        /** `#RRGGBB` for an opaque colour; the guide's own opacity is set separately. */
        fun formatColor(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

        private fun fieldOf(problem: PrecisionDialProblem): PrecisionDialField = when (problem) {
            PrecisionDialProblem.INITIAL_OPACITY_OUT_OF_RANGE -> PrecisionDialField.INITIAL_OPACITY
            PrecisionDialProblem.RESTING_OPACITY_OUT_OF_RANGE,
            PrecisionDialProblem.RESTING_NOT_DIMMER -> PrecisionDialField.RESTING_OPACITY
            PrecisionDialProblem.DRAGGING_OPACITY_OUT_OF_RANGE -> PrecisionDialField.DRAGGING_OPACITY
            PrecisionDialProblem.RETURN_DELAY_OUT_OF_RANGE -> PrecisionDialField.RETURN_DELAY
            PrecisionDialProblem.FRAMES_PER_TURN_OUT_OF_RANGE -> PrecisionDialField.FRAMES_PER_TURN
        }

        private fun percentToFraction(text: String): Float? {
            val percent = text.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull() ?: return null
            if (percent.isNaN() || percent.isInfinite()) return null
            return (percent / 100.0).toFloat()
        }

        /** One decimal place at most: 0.6f prints as 60, not 60.000004. */
        private fun fractionToPercent(fraction: Float): String {
            val tenths = (fraction * 1000.0).roundToLong()
            val percent = tenths / 10.0
            return if (percent == floor(percent)) percent.toLong().toString() else percent.toString()
        }

        private sealed interface ColorParse {
            data class Parsed(val argb: Int?) : ColorParse
            data object Invalid : ColorParse
        }

        private fun parseColor(text: String): ColorParse {
            val hex = text.trim().removePrefix("#")
            if (hex.isEmpty()) return ColorParse.Parsed(null)
            if (hex.length != 6) return ColorParse.Invalid
            val rgb = hex.toIntOrNull(16) ?: return ColorParse.Invalid
            return ColorParse.Parsed(0xFF000000.toInt() or rgb)
        }
    }
}
