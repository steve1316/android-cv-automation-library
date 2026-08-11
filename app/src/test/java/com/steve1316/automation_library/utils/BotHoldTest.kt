package com.steve1316.automation_library.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread

/** Unit tests for pausing at the safe point, the bounded tray hold, and stopping while held in `BotHold`. */
class BotHoldTest {
    private var now = 0L

    @Before
    fun setUp() {
        BotHold.isMainThread = { false }
        BotHold.isBotRunning = { true }
        BotHold.trayHoldMaxMs = 3_000L
        BotHold.traySettleMs = 0L
        BotHold.reset()
        now = 0L
        BotStatus.clock = { now }
        BotStatus.reset()
    }

    /**
     * Reaches the safe point once with no pause pending, as a run does before a pause can be requested.
     */
    private fun reachSafePoint() {
        BotHold.awaitIfPaused()
    }

    /**
     * Polls until the condition is true.
     *
     * @param condition The condition to wait for.
     */
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 1_000L
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the condition." }
            Thread.sleep(5L)
        }
    }

    @Test
    fun pauseOnlyLandsAtTheSafePoint() {
        reachSafePoint()
        BotHold.requestPause()
        assertEquals(BotHold.PauseState.REQUESTED, BotHold.pauseState)
        val worker = thread { BotHold.awaitIfPaused() }
        waitUntil { BotHold.pauseState == BotHold.PauseState.PAUSED }
        assertTrue(worker.isAlive)
        BotHold.resume()
        worker.join(1_000L)
        assertFalse(worker.isAlive)
        assertEquals(BotHold.PauseState.NONE, BotHold.pauseState)
    }

    @Test
    fun awaitIfPausedReturnsRightAwayWithoutARequest() {
        BotHold.awaitIfPaused()
        assertEquals(BotHold.PauseState.NONE, BotHold.pauseState)
    }

    @Test
    fun resumeWhileRequestedCancelsThePause() {
        reachSafePoint()
        BotHold.requestPause()
        BotHold.resume()
        assertEquals(BotHold.PauseState.NONE, BotHold.pauseState)
        BotHold.awaitIfPaused()
    }

    @Test
    fun requestPauseIsIgnoredWhenTheBotIsNotRunning() {
        reachSafePoint()
        BotHold.isBotRunning = { false }
        BotHold.requestPause()
        assertEquals(BotHold.PauseState.NONE, BotHold.pauseState)
    }

    @Test
    fun stopWakesAPausedThread() {
        reachSafePoint()
        BotHold.requestPause()
        var caught = false
        val worker =
            thread {
                try {
                    BotHold.awaitIfPaused()
                } catch (_: InterruptedException) {
                    caught = true
                }
            }
        waitUntil { BotHold.pauseState == BotHold.PauseState.PAUSED }
        worker.interrupt()
        worker.join(1_000L)
        assertTrue(caught)
    }

    @Test
    fun pausedTimeIsLeftOutOfTheTimer() {
        reachSafePoint()
        BotHold.requestPause()
        val worker = thread { BotHold.awaitIfPaused() }
        waitUntil { BotHold.pauseState == BotHold.PauseState.PAUSED }
        now += 10_000L
        BotHold.resume()
        worker.join(1_000L)
        assertEquals(0L, BotStatus.elapsedMs)
    }

    @Test
    fun trayHoldReleasesWhenTheTrayCloses() {
        BotHold.setTrayOpen(true)
        val worker = thread { BotHold.awaitTrayClosed() }
        Thread.sleep(50L)
        assertTrue(worker.isAlive)
        BotHold.setTrayOpen(false)
        worker.join(1_000L)
        assertFalse(worker.isAlive)
    }

    @Test
    fun trayHoldGivesUpAfterTheLimit() {
        BotHold.trayHoldMaxMs = 100L
        BotHold.setTrayOpen(true)
        val start = System.nanoTime()
        BotHold.awaitTrayClosed()
        val waitedMs = (System.nanoTime() - start) / 1_000_000L
        assertTrue("Waited ${waitedMs}ms", waitedMs in 90L..1_000L)
    }

    @Test
    fun stopWakesAThreadHeldByTheTray() {
        BotHold.setTrayOpen(true)
        var caught = false
        val worker =
            thread {
                try {
                    BotHold.awaitTrayClosed()
                } catch (_: InterruptedException) {
                    caught = true
                }
            }
        Thread.sleep(50L)
        worker.interrupt()
        worker.join(1_000L)
        assertTrue(caught)
    }

    @Test
    fun trayHoldNeverBlocksTheMainThread() {
        BotHold.isMainThread = { true }
        BotHold.setTrayOpen(true)
        val start = System.nanoTime()
        BotHold.awaitTrayClosed()
        assertTrue((System.nanoTime() - start) / 1_000_000L < 50L)
    }

    @Test
    fun resetClearsAStalePauseRequest() {
        reachSafePoint()
        BotHold.requestPause()
        BotHold.setTrayOpen(true)
        BotHold.reset()
        assertEquals(BotHold.PauseState.NONE, BotHold.pauseState)
        assertFalse(BotHold.isTrayOpen)
        assertFalse(BotHold.hasSafePoint)
        BotHold.awaitIfPaused()
    }

    @Test
    fun listenersHearPauseAndTrayChanges() {
        reachSafePoint()
        var calls = 0
        val listener: () -> Unit = { calls++ }
        BotHold.addListener(listener)
        BotHold.requestPause()
        BotHold.setTrayOpen(true)
        BotHold.removeListener(listener)
        BotHold.setTrayOpen(false)
        assertEquals(2, calls)
    }

    @Test
    fun requestPauseNeedsASafePoint() {
        BotHold.requestPause()
        assertEquals(BotHold.PauseState.NONE, BotHold.pauseState)
        assertFalse(BotHold.hasSafePoint)
        reachSafePoint()
        assertTrue(BotHold.hasSafePoint)
        BotHold.requestPause()
        assertEquals(BotHold.PauseState.REQUESTED, BotHold.pauseState)
    }

    @Test
    fun resetClearsTheSafePoint() {
        reachSafePoint()
        BotHold.reset()
        assertFalse(BotHold.hasSafePoint)
        BotHold.requestPause()
        assertEquals(BotHold.PauseState.NONE, BotHold.pauseState)
    }

    @Test
    fun listenersHearTheFirstSafePointOnly() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        BotHold.addListener(listener)
        BotHold.awaitIfPaused()
        BotHold.awaitIfPaused()
        BotHold.removeListener(listener)
        assertEquals(1, calls)
    }

    @Test
    fun trayHoldWaitsForTheScreenToSettleAfterClosing() {
        BotHold.traySettleMs = 100L
        BotHold.setTrayOpen(true)
        var returnedAtNs = 0L
        val worker =
            thread {
                BotHold.awaitTrayClosed()
                returnedAtNs = System.nanoTime()
            }
        Thread.sleep(50L)
        assertTrue(worker.isAlive)
        val closedAtNs = System.nanoTime()
        BotHold.setTrayOpen(false)
        worker.join(1_000L)
        assertFalse(worker.isAlive)
        val settledMs = (returnedAtNs - closedAtNs) / 1_000_000L
        assertTrue("Settled after ${settledMs}ms", settledMs >= 90L)
    }

    @Test
    fun noSettleWhenTheTrayIsAlreadyClosed() {
        BotHold.traySettleMs = 100L
        val start = System.nanoTime()
        BotHold.awaitTrayClosed()
        assertTrue((System.nanoTime() - start) / 1_000_000L < 50L)
    }
}
