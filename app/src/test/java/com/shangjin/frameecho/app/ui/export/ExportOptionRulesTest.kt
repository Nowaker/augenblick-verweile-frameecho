package com.shangjin.frameecho.app.ui.export

import com.shangjin.frameecho.core.model.CapturedFrame
import com.shangjin.frameecho.core.model.ColorSpaceInfo
import com.shangjin.frameecho.core.model.ExportConfig
import com.shangjin.frameecho.core.model.ExportFileName
import com.shangjin.frameecho.core.model.ExportFormat
import com.shangjin.frameecho.core.model.VideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportOptionRulesTest {
    @Test
    fun `incomplete and unknown tokens never reach export configuration`() {
        val cases = mapOf(
            "{" to FileNameTemplateIssue.INCOMPLETE_TOKEN,
            "prefix_{filename" to FileNameTemplateIssue.INCOMPLETE_TOKEN,
            "{filename}}" to FileNameTemplateIssue.INCOMPLETE_TOKEN,
            "{{filename}" to FileNameTemplateIssue.INCOMPLETE_TOKEN,
            "{}" to FileNameTemplateIssue.UNKNOWN_TOKEN,
            "{Filename}" to FileNameTemplateIssue.UNKNOWN_TOKEN,
            "{filename}_{unknown}" to FileNameTemplateIssue.UNKNOWN_TOKEN
        )

        cases.forEach { (draft, expected) -> assertEquals(draft, expected, fileNameTemplateIssue(draft)) }
    }

    @Test
    fun `automatic literal and complete template edits are accepted`() {
        val drafts = listOf("", "  ", "holiday.JPG", "旅行", "{filename}_{timestamp}_{position}_{frame}")

        drafts.forEach { draft -> assertNull(draft, fileNameTemplateIssue(draft)) }
    }

    @Test
    fun `filename edits honor the model boundary without silently truncating input`() {
        assertNull(fileNameTemplateIssue("a".repeat(80)))
        assertEquals(FileNameTemplateIssue.TOO_LONG, fileNameTemplateIssue("a".repeat(81)))
    }

    @Test
    fun `copy availability accepts only supported primary still MIME types`() {
        val cases = mapOf(
            "image/jpeg" to ExportFormat.JPEG,
            "IMAGE/JPEG" to ExportFormat.JPEG,
            "image/heic" to ExportFormat.HEIC,
            "image/heif" to ExportFormat.HEIC,
            "image/png" to null,
            "video/mp4" to null,
            null to null
        )

        cases.forEach { (mime, expected) -> assertEquals(mime, expected, originalStillFormat(mime)) }
    }

    @Test
    fun `original preview follows source MIME rather than encoder selection or motion suffix`() {
        val frame = CapturedFrame(1_234_567, 1920, 1080, ColorSpaceInfo(), VideoMetadata(sourceFileName = "IMG_1234456.JPG"))
        val config = ExportConfig(originalImage = true, motionPhoto = true, format = ExportFormat.JPEG,
            customFileName = SOURCE_POSITION_TEMPLATE, fileNameTimestampMs = 1700000000000)

        val previewConfig = config.forFileNamePreview("image/heif")
        val name = ExportFileName.generate(frame, previewConfig, 5, isMotion = previewConfig.motionPhoto)

        assertEquals("IMG_1234456_1700000000000_000001234567.heic", name)
    }

    @Test
    fun `encoded motion preview retains the JPEG output despite HEIC selection`() {
        val frame = CapturedFrame(1_234_567, 1920, 1080, ColorSpaceInfo(), VideoMetadata(sourceFileName = "IMG_1234456.JPG"))
        val config = ExportConfig(format = ExportFormat.HEIC, motionPhoto = true,
            customFileName = SOURCE_FRAME_TEMPLATE, fileNameTimestampMs = 1700000000000)

        val previewConfig = config.forFileNamePreview("image/heic")
        val name = ExportFileName.generate(frame, previewConfig, 5, isMotion = previewConfig.motionPhoto)

        assertEquals("IMG_1234456_1700000000000_00000000.jpg", name)
    }
}
