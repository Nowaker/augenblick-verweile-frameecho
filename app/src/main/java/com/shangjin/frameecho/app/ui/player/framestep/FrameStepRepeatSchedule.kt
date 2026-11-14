package com.shangjin.frameecho.app.ui.player.framestep

import kotlin.math.floor

/**
 * One speed of the hold-to-repeat frame step: from [startAfterMs] after repeating began,
 * step [framesPerSecond] frames per second until the next stage starts.
 */
data class RepeatStage(
    val startAfterMs: Long,
    val framesPerSecond: Float
)

/**
 * How the previous/next frame buttons repeat while held.
 *
 * Pressing steps one frame at once. Holding past [initialDelayMs] starts repeating, and
 * [stages] set the speed over time, measured from when repeating started. The default
 * accelerates 5 → 10 → 15 frames per second at 0, 5 and 10 seconds.
 *
 * Step times come from the integral of the speed curve rather than from summing frame
 * intervals, so scheduling never drifts no matter how long the button is held or how
 * late individual timer wake-ups are.
 */
data class FrameStepRepeatSchedule(
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    val stages: List<RepeatStage> = DEFAULT_STAGES
) {
    fun validate(): List<ScheduleIssue> {
        val issues = mutableListOf<ScheduleIssue>()
        if (initialDelayMs !in 0L..MAX_INITIAL_DELAY_MS) {
            issues += ScheduleIssue(null, ScheduleProblem.INITIAL_DELAY_OUT_OF_RANGE)
        }
        if (stages.isEmpty()) issues += ScheduleIssue(null, ScheduleProblem.NO_STAGES)
        if (stages.size > MAX_STAGES) issues += ScheduleIssue(null, ScheduleProblem.TOO_MANY_STAGES)
        stages.forEachIndexed { index, stage ->
            when {
                index == 0 && stage.startAfterMs != 0L ->
                    issues += ScheduleIssue(index, ScheduleProblem.FIRST_STAGE_NOT_AT_ZERO)
                stage.startAfterMs !in 0L..MAX_STAGE_START_MS ->
                    issues += ScheduleIssue(index, ScheduleProblem.START_OUT_OF_RANGE)
                index > 0 && stage.startAfterMs <= stages[index - 1].startAfterMs ->
                    issues += ScheduleIssue(index, ScheduleProblem.START_NOT_INCREASING)
            }
            if (stage.framesPerSecond.isNaN() || stage.framesPerSecond !in MIN_FPS..MAX_FPS) {
                issues += ScheduleIssue(index, ScheduleProblem.FPS_OUT_OF_RANGE)
            }
        }
        return issues
    }

    val isValid: Boolean get() = validate().isEmpty()

    /**
     * Number of repeat steps due once [elapsedMs] have passed since repeating started.
     * The first repeat step is due immediately (at 0 ms).
     */
    fun stepsDueAt(elapsedMs: Long): Long {
        if (elapsedMs < 0L) return 0L
        return floor(phaseAt(elapsedMs.toDouble()) + PHASE_EPSILON).toLong() + 1L
    }

    /** Time, in ms since repeating started, at which zero-based repeat step [index] is due. */
    fun stepTimeMs(index: Long): Double {
        if (index <= 0L) return 0.0
        var phaseAtStageStart = 0.0
        stages.forEachIndexed { i, stage ->
            val next = stages.getOrNull(i + 1)
            val stagePhase = next?.let {
                (it.startAfterMs - stage.startAfterMs) * stage.framesPerSecond / 1000.0
            }
            if (stagePhase == null || index <= phaseAtStageStart + stagePhase) {
                return stage.startAfterMs + (index - phaseAtStageStart) * 1000.0 / stage.framesPerSecond
            }
            phaseAtStageStart += stagePhase
        }
        return Double.MAX_VALUE
    }

    /** Integral of the speed curve: fractional number of steps after [elapsedMs]. */
    private fun phaseAt(elapsedMs: Double): Double {
        var phase = 0.0
        stages.forEachIndexed { i, stage ->
            if (elapsedMs <= stage.startAfterMs) return phase
            val end = stages.getOrNull(i + 1)?.startAfterMs?.toDouble() ?: Double.MAX_VALUE
            phase += (minOf(elapsedMs, end) - stage.startAfterMs) * stage.framesPerSecond / 1000.0
        }
        return phase
    }

    /** Compact, versioned form for preferences, e.g. `v1;500;0:5.0,5000:10.0,10000:15.0`. */
    fun serialize(): String =
        "$FORMAT_VERSION;$initialDelayMs;" + stages.joinToString(",") { "${it.startAfterMs}:${it.framesPerSecond}" }

    companion object {
        const val DEFAULT_INITIAL_DELAY_MS = 500L
        val DEFAULT_STAGES = listOf(
            RepeatStage(0L, 5f),
            RepeatStage(5_000L, 10f),
            RepeatStage(10_000L, 15f)
        )
        val DEFAULT = FrameStepRepeatSchedule()

        const val MAX_INITIAL_DELAY_MS = 5_000L
        const val MAX_STAGE_START_MS = 600_000L
        const val MAX_STAGES = 8
        const val MIN_FPS = 0.5f
        const val MAX_FPS = 60f

        private const val FORMAT_VERSION = "v1"
        private const val PHASE_EPSILON = 1e-9

        /** Returns null for anything malformed or invalid, so callers fall back to [DEFAULT]. */
        fun deserialize(value: String?): FrameStepRepeatSchedule? {
            val parts = value?.split(';') ?: return null
            if (parts.size != 3 || parts[0] != FORMAT_VERSION) return null
            val delay = parts[1].toLongOrNull() ?: return null
            val stages = parts[2].split(',').map { token ->
                val fields = token.split(':')
                if (fields.size != 2) return null
                RepeatStage(
                    startAfterMs = fields[0].toLongOrNull() ?: return null,
                    framesPerSecond = fields[1].toFloatOrNull() ?: return null
                )
            }
            return FrameStepRepeatSchedule(delay, stages).takeIf { it.isValid }
        }
    }
}

enum class ScheduleProblem {
    INITIAL_DELAY_OUT_OF_RANGE,
    NO_STAGES,
    TOO_MANY_STAGES,
    FIRST_STAGE_NOT_AT_ZERO,
    START_OUT_OF_RANGE,
    START_NOT_INCREASING,
    FPS_OUT_OF_RANGE,
    NOT_A_NUMBER
}

/** A validation problem; [stageIndex] is null when it concerns the schedule as a whole. */
data class ScheduleIssue(val stageIndex: Int?, val problem: ScheduleProblem)

/**
 * The settings editor's text fields, before they are a schedule. Times are entered in
 * seconds because that is how people think about "after 5 seconds".
 */
data class ScheduleDraft(
    val initialDelaySeconds: String,
    val stages: List<StageDraft>
) {
    data class StageDraft(val startAfterSeconds: String, val framesPerSecond: String)

    sealed interface Result {
        data class Valid(val schedule: FrameStepRepeatSchedule) : Result
        data class Invalid(val issues: List<ScheduleIssue>) : Result
    }

    fun toSchedule(): Result {
        val issues = mutableListOf<ScheduleIssue>()
        val delayMs = secondsToMs(initialDelaySeconds)
        if (delayMs == null) issues += ScheduleIssue(null, ScheduleProblem.NOT_A_NUMBER)
        val parsed = stages.mapIndexed { index, draft ->
            val start = secondsToMs(draft.startAfterSeconds)
            val fps = draft.framesPerSecond.trim().replace(',', '.').toFloatOrNull()
            if (start == null || fps == null) {
                issues += ScheduleIssue(index, ScheduleProblem.NOT_A_NUMBER)
                null
            } else RepeatStage(start, fps)
        }
        if (issues.isNotEmpty()) return Result.Invalid(issues)
        val schedule = FrameStepRepeatSchedule(delayMs!!, parsed.filterNotNull())
        val problems = schedule.validate()
        return if (problems.isEmpty()) Result.Valid(schedule) else Result.Invalid(problems)
    }

    companion object {
        fun from(schedule: FrameStepRepeatSchedule) = ScheduleDraft(
            initialDelaySeconds = msToSeconds(schedule.initialDelayMs),
            stages = schedule.stages.map {
                StageDraft(msToSeconds(it.startAfterMs), formatFps(it.framesPerSecond))
            }
        )

        private fun secondsToMs(text: String): Long? {
            val seconds = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
            if (seconds.isNaN() || seconds.isInfinite()) return null
            return Math.round(seconds * 1000.0)
        }

        private fun msToSeconds(ms: Long): String = formatNumber(ms / 1000.0)

        private fun formatNumber(value: Double): String =
            if (value == floor(value)) value.toLong().toString() else value.toString()

        /** Float's own shortest form: widening 5.1f to Double would print 5.099999904632568. */
        private fun formatFps(value: Float): String =
            if (value == floor(value)) value.toLong().toString() else value.toString()
    }
}
