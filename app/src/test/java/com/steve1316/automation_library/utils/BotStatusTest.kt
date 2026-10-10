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
}
