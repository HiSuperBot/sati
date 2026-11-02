package com.superbot.sati.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelTest {
    @Test
    fun savedSelectionsRestoreAndUnknownIdsUseTheDefault() {
        AiModel.entries.forEach { assertEquals(it, AiModel.fromId(it.id)) }
        assertEquals(AiModel.Qwen, AiModel.fromId(null))
        assertEquals(AiModel.Qwen, AiModel.fromId("retired-model"))
    }

    @Test
    fun progressUsesSelectedModelSizeAndNanoHasNoInventedPercentage() {
        for (model in listOf(AiModel.Qwen, AiModel.Gemma)) {
            assertEquals(50, OfflineModelState(
                model = model, downloadedBytes = model.downloadBytes / 2,
            ).downloadPercent)
            assertEquals(100, OfflineModelState(
                model = model, downloadedBytes = model.downloadBytes * 2,
            ).downloadPercent)
        }
        assertEquals(0, OfflineModelState(
            model = AiModel.GeminiNano, downloadedBytes = Long.MAX_VALUE,
        ).downloadPercent)
    }

    @Test
    fun nanoCannotBeSelectedUntilAvailabilityIsConfirmed() {
        assertFalse(NanoAvailability.Checking.canSelect)
        assertFalse(NanoAvailability.Unavailable.canSelect)
        assertFalse(NanoAvailability.CheckFailed.canSelect)
        assertTrue(NanoAvailability.Available.canSelect)
        assertTrue(NanoAvailability.Downloadable.canSelect)
        assertTrue(NanoAvailability.Downloading.canSelect)
    }
}
