package com.shangjin.frameecho.app.ui.settings

import com.shangjin.frameecho.app.settings.PrecisionDialSettings
import com.shangjin.frameecho.app.ui.settings.PrecisionDialSettingsDraft.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class PrecisionDialSettingsDraftTest {

    private val custom = PrecisionDialSettings(
        initialGuideOpacity = 0.6f,
        restingGuideOpacity = 0.375f,
        draggingGuideOpacity = 0.1f,
        guideReturnDelayMs = 1_500L,
        guideColorArgb = 0xFFFFEB3B.toInt(),
        framesPerTurn = 120
    )

    @Test
    fun `fields filled from settings read back as the same settings`() {
        assertEquals(Result.Valid(custom), PrecisionDialSettingsDraft.from(custom).toSettings())
    }

    @Test
    fun `percentages accept a decimal comma and an empty colour follows the theme`() {
        val draft = PrecisionDialSettingsDraft.from(custom).copy(restingOpacityPercent = "37,5", guideColorHex = " ")

        assertEquals(Result.Valid(custom.copy(guideColorArgb = null)), draft.toSettings())
    }

    @Test
    fun `each unusable field is reported on its own`() {
        val draft = PrecisionDialSettingsDraft(
            framesPerTurn = "60.5",
            initialOpacityPercent = "60",
            restingOpacityPercent = "20",
            draggingOpacityPercent = "0",
            returnDelayMs = "soon",
            guideColorHex = "#12345"
        )

        assertEquals(
            Result.Invalid(setOf(PrecisionDialField.FRAMES_PER_TURN, PrecisionDialField.RETURN_DELAY, PrecisionDialField.GUIDE_COLOR)),
            draft.toSettings()
        )
    }

    @Test
    fun `a resting opacity not below the first one is reported on the resting field`() {
        val draft = PrecisionDialSettingsDraft.from(custom).copy(restingOpacityPercent = "60")

        assertEquals(Result.Invalid(setOf(PrecisionDialField.RESTING_OPACITY)), draft.toSettings())
    }

    @Test
    fun `frames per turn outside the allowed range is reported`() {
        val draft = PrecisionDialSettingsDraft.from(custom).copy(framesPerTurn = "361")

        assertEquals(Result.Invalid(setOf(PrecisionDialField.FRAMES_PER_TURN)), draft.toSettings())
    }
}
