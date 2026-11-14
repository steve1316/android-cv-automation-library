package com.steve1316.automation_library.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure parts of the `[MEM]` log line. */
class MemoryLogTest {
    /** A sample with a distinct value per field, so a swapped field fails the exact-match test. */
    private val sample =
        MemoryLog.MemorySample(
            pssKb = 1024 * 812,
            nativeKb = 1024 * 550,
            javaKb = 1024 * 34,
            graphicsKb = 1024 * 7,
            swapKb = 1024 * 3,
            allocatedKb = 1024 * 350,
            availableKb = 1024 * 1487,
            scanMs = 12,
        )

    @Test
    fun formatLineWritesEveryFieldInMbUnderTheLabel() {
        assertEquals(
            "[MEM] day=12 pss=812MB native=550MB java=34MB graphics=7MB swap=3MB allocated=350MB avail=1487MB scan=12ms",
            MemoryLog.formatLine("day=12", sample),
        )
    }

    @Test
    fun formatLineMarksUnknownFreeMemory() {
        val line = MemoryLog.formatLine("run_end", sample.copy(availableKb = null))
        assertTrue(line, line.startsWith("[MEM] run_end pss=812MB"))
        assertTrue(line, line.contains(" avail=? "))
    }

    @Test
    fun parseMemAvailableKbReadsTheMemAvailableRow() {
        val text = "MemTotal:        4028036 kB\nMemFree:          120000 kB\nMemAvailable:    1487308 kB\nBuffers:            1000 kB\n"
        assertEquals(1487308, MemoryLog.parseMemAvailableKb(text))
    }

    @Test
    fun parseMemAvailableKbReturnsNullWhenTheRowIsMissing() {
        assertNull(MemoryLog.parseMemAvailableKb("MemTotal:        4028036 kB\n"))
    }
}
