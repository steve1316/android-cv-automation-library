package com.steve1316.automation_library.utils

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Unit tests for building, saving, and loading the run-end report in `RunReport`. */
class RunReportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 0L

    @Before
    fun setUp() {
        now = 1_000L
        BotStatus.clock = { now }
        BotStatus.reset()
        RunReport.setSummaryProvider(null)
    }

    @After
    fun tearDown() {
        RunReport.setSummaryProvider(null)
    }

    @Test
    fun buildCopiesTheStatus() {
        BotStatus.setProgress(41, 72, "Turn 41/72")
        now += 65_000L
        BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_BOT, "Mandatory race detected. Stopping bot...")
        val report = RunReport.build("log @ x.txt", endedAtMs = 42L)
        assertEquals(RunReport.SCHEMA_VERSION, report.getInt("schemaVersion"))
        assertEquals("STOPPED_BY_BOT", report.getString("outcome"))
        assertEquals("Mandatory race detected. Stopping bot...", report.getString("reason"))
        assertEquals(41, report.getInt("turn"))
        assertEquals(72, report.getInt("totalTurns"))
        assertEquals(65_000L, report.getLong("runtimeMs"))
        assertEquals(42L, report.getLong("endedAt"))
        assertEquals("log @ x.txt", report.getString("logFile"))
        assertFalse(report.has("error"))
        assertFalse(report.has("summary"))
    }

    @Test
    fun buildAddsTheErrorWithItsFrames() {
        val e =
            IllegalStateException("boom").apply {
                stackTrace = arrayOf(StackTraceElement("a.b.One", "x", "One.kt", 1), StackTraceElement("a.b.Two", "y", "Two.kt", 2), StackTraceElement("a.b.Three", "z", "Three.kt", 3))
            }
        BotStatus.setError(e, "a.b")
        BotStatus.setOutcome(BotStatus.Outcome.CRASHED, "IllegalStateException")
        val error = RunReport.build(null).getJSONObject("error")
        assertEquals("IllegalStateException", error.getString("className"))
        assertEquals("boom", error.getString("message"))
        assertEquals(3, error.getJSONArray("frames").length())
        assertEquals("Two.y (Two.kt:2)", error.getJSONArray("frames").getString(1))
    }

    @Test
    fun buildWithoutOutcomeSaysFinishedAndEmptyLogFile() {
        val report = RunReport.build(null)
        assertEquals("FINISHED", report.getString("outcome"))
        assertEquals("", report.getString("logFile"))
    }

    @Test
    fun buildAddsTheSummaryFromTheProvider() {
        RunReport.setSummaryProvider { JSONObject().put("fans", 1234) }
        assertEquals(1234, RunReport.build(null).getJSONObject("summary").getInt("fans"))
    }

    @Test
    fun throwingProviderStillGivesAReport() {
        RunReport.setSummaryProvider { throw ConcurrentModificationException() }
        val report = RunReport.build(null)
        assertFalse(report.has("summary"))
        assertEquals("FINISHED", report.getString("outcome"))
    }

    @Test
    fun saveLoadAndClearRoundTrip() {
        val dir = tmp.root
        BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_USER, "You stopped the bot")
        RunReport.save(dir, RunReport.build("a.txt"))
        val loaded = JSONObject(RunReport.load(dir)!!)
        assertEquals("STOPPED_BY_USER", loaded.getString("outcome"))
        RunReport.clear(dir)
        assertNull(RunReport.load(dir))
    }

    @Test
    fun loadRejectsCorruptAndOldFiles() {
        val dir = tmp.root
        val file = File(dir, "last_run.json")
        file.writeText("{\"schemaVersion\":1,\"outc")
        assertNull(RunReport.load(dir))
        file.writeText("{\"schemaVersion\":99,\"outcome\":\"FINISHED\"}")
        assertNull(RunReport.load(dir))
        assertTrue(file.exists())
    }
}
