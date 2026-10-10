package com.steve1316.automation_library.utils

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Unit tests for the run timer, the first-outcome-wins rule, and listener fan-out in `BotStatus`. */
class BotStatusTest {
    private var now = 0L

    @Before
    fun setUp() {
        now = 1_000L
        BotStatus.clock = { now }
        BotStatus.reset()
    }

    @Test
    fun elapsedCountsRunningTime() {
        now += 5_000L
        assertEquals(5_000L, BotStatus.elapsedMs)
    }

    @Test
    fun elapsedLeavesOutPausedTime() {
        now += 2_000L
        BotStatus.markPaused(true)
        now += 10_000L
        assertEquals(2_000L, BotStatus.elapsedMs)
        BotStatus.markPaused(false)
        now += 3_000L
        assertEquals(5_000L, BotStatus.elapsedMs)
    }

    @Test
    fun firstOutcomeWins() {
        BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_BOT, "Device went to sleep")
        BotStatus.setOutcome(BotStatus.Outcome.STOPPED_BY_USER, "You stopped the bot")
        val snapshot = BotStatus.snapshot()
        assertEquals(BotStatus.Outcome.STOPPED_BY_BOT, snapshot.outcome)
        assertEquals("Device went to sleep", snapshot.reason)
    }

    @Test
    fun outcomeFreezesTheTimer() {
        now += 4_000L
        BotStatus.setOutcome(BotStatus.Outcome.FINISHED, "Run ended")
        now += 60_000L
        assertEquals(4_000L, BotStatus.elapsedMs)
    }

    @Test
    fun resetClearsEverything() {
        BotStatus.setProgress(34, 72, "Turn 34/72")
        BotStatus.setDetail("Trained Speed")
        BotStatus.setOutcome(BotStatus.Outcome.FINISHED, "Run ended")
        now += 1_000L
        BotStatus.reset()
        assertEquals(BotStatus.Snapshot(), BotStatus.snapshot())
    }

    @Test
    fun fractionHandlesUnknownAndOverflowingTotals() {
        assertEquals(0f, BotStatus.Snapshot().fraction, 0f)
        assertEquals(0.5f, BotStatus.Snapshot(current = 36, total = 72).fraction, 0.0001f)
        assertEquals(1f, BotStatus.Snapshot(current = 74, total = 72).fraction, 0f)
    }

    @Test
    fun listenersHearChangesUntilRemoved() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        BotStatus.addListener(listener)
        BotStatus.setDetail("Raced")
        BotStatus.removeListener(listener)
        BotStatus.setDetail("Rested")
        assertEquals(1, calls)
    }

    @Test
    fun repeatedIdenticalDetailNotifiesOnce() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        BotStatus.addListener(listener)
        BotStatus.setDetail("Raced")
        BotStatus.setDetail("Raced")
        BotStatus.removeListener(listener)
        assertEquals(1, calls)
    }

    private fun crash(vararg frames: StackTraceElement): Throwable = IllegalStateException("boom").apply { stackTrace = arrayOf(*frames) }

    @Test
    fun errorKeepsAppFramesFirst() {
        val e =
            crash(
                StackTraceElement("java.lang.Thread", "run", "Thread.java", 1),
                StackTraceElement("com.example.app.bot.Training", "analyze", "Training.kt", 10),
                StackTraceElement("com.example.app.bot.Campaign", "handle", "Campaign.kt", 20),
            )
        BotStatus.setError(e, "com.example.app")
        val error = BotStatus.lastError()!!
        assertEquals("IllegalStateException", error.className)
        assertEquals("boom", error.message)
        assertEquals(listOf("Training.analyze (Training.kt:10)", "Campaign.handle (Campaign.kt:20)"), error.frames)
    }

    @Test
    fun errorFallsBackToRawFramesWithoutAppFrames() {
        val e = crash(StackTraceElement("java.lang.Thread", "run", "Thread.java", 1), StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 5))
        BotStatus.setError(e, "com.example.app")
        assertEquals(listOf("Thread.run (Thread.java:1)", "ArrayList.get (ArrayList.java:5)"), BotStatus.lastError()!!.frames)
    }

    @Test
    fun firstErrorWinsAndResetClearsIt() {
        BotStatus.setError(crash(StackTraceElement("a.B", "c", "B.kt", 1)), "a")
        BotStatus.setError(IllegalArgumentException("second"), "a")
        assertEquals("IllegalStateException", BotStatus.lastError()!!.className)
        BotStatus.reset()
        assertEquals(null, BotStatus.lastError())
    }

    @Test
    fun frameWithoutFileNameSaysUnknownSource() {
        BotStatus.setError(crash(StackTraceElement("com.example.app.StartModule\$\$Lambda", "run", null, -1)), "com.example.app")
        assertEquals(listOf("StartModule\$\$Lambda.run (Unknown Source)"), BotStatus.lastError()!!.frames)
    }
}
