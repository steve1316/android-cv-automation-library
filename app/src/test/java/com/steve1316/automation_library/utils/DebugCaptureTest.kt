package com.steve1316.automation_library.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the file naming and pruning rules in `DebugCapture`. */
class DebugCaptureTest {
    @Test
    fun fileNameUsesSequenceElapsedTimeAndTemplateName() {
        assertEquals("00042_00-01-15-760_view_results.jpg", DebugCapture.fileName(42, "00:01:15.760", "components/button/view_results"))
    }

    @Test
    fun fileNameReplacesUnsafeCharacters() {
        assertEquals("00001_------------_ocr_event_title_.jpg", DebugCapture.fileName(1, "--:--:--.---", "ocr event title!"))
    }

    @Test
    fun fileNamesSortInSequenceOrder() {
        val names = listOf(DebugCapture.fileName(10, "00:00:09.000", "b"), DebugCapture.fileName(9, "00:00:10.000", "a"))
        assertEquals(names.reversed(), names.sorted())
    }

    @Test
    fun pruneKeepsEverythingUnderBothCaps() {
        val files = listOf("00001_a.jpg" to 10L, "00002_b.jpg" to 10L)
        assertTrue(DebugCapture.namesToPrune(files, maxCount = 5, maxBytes = 100).isEmpty())
    }

    @Test
    fun pruneDropsOldestOverCountCap() {
        val files = listOf("00003_c.jpg" to 1L, "00001_a.jpg" to 1L, "00002_b.jpg" to 1L)
        assertEquals(listOf("00001_a.jpg"), DebugCapture.namesToPrune(files, maxCount = 2, maxBytes = 100))
    }

    @Test
    fun pruneDropsOldestOverByteCap() {
        val files = listOf("00001_a.jpg" to 60L, "00002_b.jpg" to 30L, "00003_c.jpg" to 30L)
        assertEquals(listOf("00001_a.jpg"), DebugCapture.namesToPrune(files, maxCount = 10, maxBytes = 70))
    }
}
