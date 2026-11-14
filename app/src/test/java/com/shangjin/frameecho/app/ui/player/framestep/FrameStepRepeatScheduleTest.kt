package com.shangjin.frameecho.app.ui.player.framestep

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FrameStepRepeatScheduleTest {

    private val default = FrameStepRepeatSchedule.DEFAULT

    @Test
    fun `default schedule matches the documented curve`() {
        assertEquals(500L, default.initialDelayMs)
        assertEquals(
            listOf(RepeatStage(0, 5f), RepeatStage(5_000, 10f), RepeatStage(10_000, 15f)),
            default.stages
        )
        assertTrue(default.isValid)
    }

    @Test
    fun `steps follow 5 then 10 then 15 fps from repeat start`() {
        assertEquals(1L, default.stepsDueAt(0))
        assertEquals(1L, default.stepsDueAt(199))
        assertEquals(2L, default.stepsDueAt(200))
        // 5 s at 5 fps = 25 intervals, then 10 fps.
        assertEquals(26L, default.stepsDueAt(5_000))
        assertEquals(27L, default.stepsDueAt(5_100))
        // 25 + 50 intervals by 10 s, then 15 fps.
        assertEquals(76L, default.stepsDueAt(10_000))
        assertEquals(91L, default.stepsDueAt(11_000))
    }

    @Test
    fun `step times invert the step count`() {
        assertEquals(0.0, default.stepTimeMs(0), 1e-9)
        assertEquals(200.0, default.stepTimeMs(1), 1e-9)
        assertEquals(5_000.0, default.stepTimeMs(25), 1e-9)
        assertEquals(5_100.0, default.stepTimeMs(26), 1e-9)
        assertEquals(10_000.0 + 1000.0 / 15.0, default.stepTimeMs(76), 1e-6)
    }

    @Test
    fun `validation rejects incoherent schedules`() {
        fun problems(schedule: FrameStepRepeatSchedule) = schedule.validate().map { it.problem }.toSet()

        assertEquals(
            setOf(ScheduleProblem.INITIAL_DELAY_OUT_OF_RANGE),
            problems(default.copy(initialDelayMs = -1))
        )
        assertEquals(setOf(ScheduleProblem.NO_STAGES), problems(default.copy(stages = emptyList())))
        assertEquals(
            setOf(ScheduleProblem.FIRST_STAGE_NOT_AT_ZERO),
            problems(default.copy(stages = listOf(RepeatStage(100, 5f))))
        )
        assertEquals(
            listOf(ScheduleIssue(2, ScheduleProblem.START_NOT_INCREASING)),
            default.copy(
                stages = listOf(RepeatStage(0, 5f), RepeatStage(5_000, 10f), RepeatStage(5_000, 15f))
            ).validate()
        )
        assertEquals(
            listOf(ScheduleIssue(1, ScheduleProblem.FPS_OUT_OF_RANGE)),
            default.copy(stages = listOf(RepeatStage(0, 5f), RepeatStage(1_000, 0f))).validate()
        )
        assertEquals(
            setOf(ScheduleProblem.FPS_OUT_OF_RANGE),
            problems(default.copy(stages = listOf(RepeatStage(0, 120f))))
        )
        assertEquals(
            setOf(ScheduleProblem.TOO_MANY_STAGES),
            problems(default.copy(stages = (0 until 9).map { RepeatStage(it * 1_000L, 5f) }))
        )
    }

    @Test
    fun `serialization round-trips and rejects garbage`() {
        val custom = FrameStepRepeatSchedule(250, listOf(RepeatStage(0, 2.5f), RepeatStage(3_000, 30f)))

        assertEquals(custom, FrameStepRepeatSchedule.deserialize(custom.serialize()))
        assertEquals(default, FrameStepRepeatSchedule.deserialize(default.serialize()))
        assertNull(FrameStepRepeatSchedule.deserialize(null))
        assertNull(FrameStepRepeatSchedule.deserialize("v2;500;0:5"))
        assertNull(FrameStepRepeatSchedule.deserialize("v1;500;0:x"))
        assertNull(FrameStepRepeatSchedule.deserialize("v1;500;100:5"))
    }

    @Test
    fun `draft converts seconds text and reports unparseable fields`() {
        val draft = ScheduleDraft(
            initialDelaySeconds = "0,75",
            stages = listOf(
                ScheduleDraft.StageDraft("0", "4"),
                ScheduleDraft.StageDraft("2.5", "12.5")
            )
        )
        assertEquals(
            ScheduleDraft.Result.Valid(
                FrameStepRepeatSchedule(750, listOf(RepeatStage(0, 4f), RepeatStage(2_500, 12.5f)))
            ),
            draft.toSchedule()
        )

        val broken = draft.copy(stages = listOf(ScheduleDraft.StageDraft("0", "fast")))
        assertEquals(
            ScheduleDraft.Result.Invalid(listOf(ScheduleIssue(0, ScheduleProblem.NOT_A_NUMBER))),
            broken.toSchedule()
        )

        assertEquals(ScheduleDraft.Result.Valid(default), ScheduleDraft.from(default).toSchedule())
    }

    @Test
    fun `draft shows decimal speeds as typed and round-trips them`() {
        val schedule = FrameStepRepeatSchedule(1_500, listOf(RepeatStage(0, 5.1f), RepeatStage(2_250, 12.5f)))
        val draft = ScheduleDraft.from(schedule)

        assertEquals(listOf("5.1", "12.5"), draft.stages.map { it.framesPerSecond })
        assertEquals(listOf("0", "2.25"), draft.stages.map { it.startAfterSeconds })
        assertEquals("1.5", draft.initialDelaySeconds)
        assertEquals(ScheduleDraft.Result.Valid(schedule), draft.toSchedule())
    }

    @Test
    fun `repeater waits the initial delay then steps on schedule without drift`() = runTest {
        val stepTimes = mutableListOf<Long>()
        val job = launch {
            runFrameStepRepeat(default, { testScheduler.currentTime }) { frames ->
                repeat(frames) { stepTimes += testScheduler.currentTime }
            }
        }
        advanceTimeBy(499)
        runCurrent()
        assertTrue(stepTimes.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(500L), stepTimes)

        advanceTimeBy(500L + 11_000L)
        runCurrent()
        job.cancel()

        // Repeat started at 500 ms; by 11.5 s after it, the schedule says 98 steps.
        assertEquals(default.stepsDueAt(11_500), stepTimes.size.toLong())
        assertEquals(700L, stepTimes[1])
        assertEquals(5_500L, stepTimes[25])
        assertEquals(5_600L, stepTimes[26])
        assertEquals(10_567L, stepTimes[76])
    }

    @Test
    fun `cancelling the repeater stops further steps`() = runTest {
        var steps = 0
        val job = launch { runFrameStepRepeat(default, { testScheduler.currentTime }) { steps += it } }
        advanceTimeBy(1_001)
        runCurrent()
        job.cancel()
        val stepsAtCancel = steps
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(3, stepsAtCancel)
        assertEquals(stepsAtCancel, steps)
    }

    @Test
    fun `late wake-ups fold missed steps into one update`() = runTest {
        val updates = mutableListOf<Int>()
        var clockSkewMs = 0L
        val job = launch {
            runFrameStepRepeat(default, { testScheduler.currentTime + clockSkewMs }) { frames ->
                updates += frames
                // Simulate the main thread stalling for 650 ms after the first step.
                if (updates.size == 1) clockSkewMs = 650L
            }
        }
        advanceTimeBy(600)
        runCurrent()
        job.cancel()

        assertEquals(listOf(1, 3), updates)
    }
}
