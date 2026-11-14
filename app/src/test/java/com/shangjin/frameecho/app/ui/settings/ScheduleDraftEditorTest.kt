package com.shangjin.frameecho.app.ui.settings

import androidx.compose.runtime.saveable.SaverScope
import com.shangjin.frameecho.app.ui.player.framestep.ScheduleDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleDraftEditorTest {
    @Test
    fun `removing the first stage resets only the replacement start to zero`() {
        val draft = ScheduleDraft("1,25", listOf(
            ScheduleDraft.StageDraft("0", "5"),
            ScheduleDraft.StageDraft("5", "10"),
            ScheduleDraft.StageDraft("10", "15")
        ))

        val edited = draft.withoutStage(0)

        assertEquals(ScheduleDraft("1,25", listOf(
            ScheduleDraft.StageDraft("0", "10"),
            ScheduleDraft.StageDraft("10", "15")
        )), edited)
    }

    @Test
    fun `removing a later stage preserves unfinished edits in the first stage`() {
        val first = ScheduleDraft.StageDraft("0,", "unfinished")
        val draft = ScheduleDraft("", listOf(first, ScheduleDraft.StageDraft("5", "10")))

        val edited = draft.withoutStage(1)

        assertEquals(ScheduleDraft("", listOf(first)), edited)
    }

    @Test
    fun `the final stage cannot be removed`() {
        val draft = ScheduleDraft("0.5", listOf(ScheduleDraft.StageDraft("0", "5")))

        val edited = draft.withoutStage(0)

        assertEquals(draft, edited)
    }

    @Test
    fun `saved UI state restores raw invalid draft fields without parsing them`() {
        val draft = ScheduleDraft("0,", listOf(
            ScheduleDraft.StageDraft("", "not a number"),
            ScheduleDraft.StageDraft("12.500", "0,75")
        ))

        val saved = with(ScheduleDraftSaver) { SaverScope { true }.save(draft) }
        val restored = ScheduleDraftSaver.restore(requireNotNull(saved))

        assertEquals(draft, restored)
    }

    @Test
    fun `external saved changes refresh an untouched draft`() {
        val previous = ScheduleDraft("0.5", listOf(ScheduleDraft.StageDraft("0", "5")))
        val current = previous.copy(initialDelaySeconds = "0.75")

        val refreshed = previous.reconcileSavedDraft(previous, current)

        assertEquals(current, refreshed)
    }

    @Test
    fun `external saved changes preserve equivalent numeric formatting still being edited`() {
        val previous = ScheduleDraft("0.5", listOf(ScheduleDraft.StageDraft("0", "5")))
        val current = previous.copy(initialDelaySeconds = "0.75")
        val edited = previous.copy(initialDelaySeconds = "0.500")

        val refreshed = edited.reconcileSavedDraft(previous, current)

        assertEquals(edited, refreshed)
    }
}
