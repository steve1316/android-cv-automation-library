package com.steve1316.automation_library.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private val GAME_PACKAGES = setOf("com.example.game")

/** Unit tests for the pause watchdog, the pause reason, and the focus-loss pause in `BotHold`. */
class BotHoldWatchdogTest {
    /** A bot thread started by a test, and what happened to it. */
    private class BotRun(
        /** The bot thread. */
        val thread: Thread,
        /** Set when an interrupt or abort reached the bot thread's body. */
        val interrupted: AtomicBoolean,
        /** Whether the abort was raised at the moment the interrupt arrived. */
        val abortSeen: AtomicBoolean,
    )

    private var now = 0L

    @Before
    fun setUp() {
        BotHold.isMainThread = { false }
        BotHold.isBotRunning = { true }
        BotHold.traySettleMs = 0L
        BotHold.watchdogDelayMs = 0L
        BotHold.reset()
        now = 10_000L
        BotHold.clock = { now }
        BotHold.log = {}
        BotStatus.clock = { now }
        BotStatus.reset()
    }

    @After
    fun tearDown() {
        BotHold.disableMidStepPause()
        BotHold.watchdogDelayMs = 0L
        Thread.interrupted()
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

    /**
     * Starts a bot thread that opts in, runs the body, and records whether an interrupt reached it.
     *
     * @param body What the bot thread does after opting in. It blocks or spins until the test lets it finish.
     * @return The started run.
     */
    private fun startBot(body: () -> Unit): BotRun {
        val interrupted = AtomicBoolean(false)
        val abortSeen = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        val bot =
            thread {
                BotHold.enableMidStepPause(GAME_PACKAGES)
                ready.countDown()
                try {
                    body()
                } catch (_: InterruptedException) {
                    abortSeen.set(BotHold.isAbortRaised)
                    interrupted.set(true)
                }
                BotHold.acknowledgeAbort()
                BotHold.disableMidStepPause()
            }
        ready.await(1, TimeUnit.SECONDS)
        return BotRun(bot, interrupted, abortSeen)
    }

    @Test
    fun watchdogInterruptsABotThreadStuckWaiting() {
        reachSafePoint()
        val blocker = CountDownLatch(1)
        val run = startBot { blocker.await() }
        waitUntil { run.thread.state == Thread.State.WAITING }
        BotHold.requestPause()
        assertFalse(BotHold.watchdogTick())
        run.thread.join(1_000L)
        assertTrue(run.interrupted.get())
        assertTrue(run.abortSeen.get())
    }

    @Test
    fun watchdogNeverInterruptsARunningThread() {
        reachSafePoint()
        val stop = AtomicBoolean(false)
        val sawInterrupt = AtomicBoolean(false)
        val run =
            startBot {
                while (!stop.get()) {
                    if (Thread.currentThread().isInterrupted) sawInterrupt.set(true)
                }
            }
        waitUntil { run.thread.state == Thread.State.RUNNABLE }
        BotHold.requestPause()
        assertTrue(BotHold.watchdogTick())
        assertFalse(BotHold.isAbortRaised)
        stop.set(true)
        run.thread.join(1_000L)
        assertFalse(sawInterrupt.get())
    }

    @Test
    fun watchdogWaitsWhileADeferPauseBlockIsOpen() {
        reachSafePoint()
        val blocker = CountDownLatch(1)
        val thrownAfterBlock = AtomicReference<Throwable?>()
        val run =
            startBot {
                try {
                    BotHold.deferPause { blocker.await() }
                } catch (e: StepAbortedException) {
                    thrownAfterBlock.set(e)
                }
            }
        waitUntil { run.thread.state == Thread.State.WAITING }
        BotHold.requestPause()
        assertTrue(BotHold.watchdogTick())
        assertFalse(BotHold.isAbortRaised)
        blocker.countDown()
        run.thread.join(1_000L)
        assertFalse(run.interrupted.get())
        assertTrue(thrownAfterBlock.get() is StepAbortedException)
    }

    @Test
    fun watchdogDoesNotInterruptTwice() {
        reachSafePoint()
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val caught = CountDownLatch(1)
        val abortSeenInBot = AtomicBoolean(false)
        val secondInterrupt = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        val bot =
            thread {
                BotHold.enableMidStepPause(GAME_PACKAGES)
                ready.countDown()
                try {
                    first.await()
                } catch (_: InterruptedException) {
                    // Record the abort from inside the bot and clear the interrupt, without acknowledging, so the abort stays raised.
                    abortSeenInBot.set(BotHold.isAbortRaised)
                    Thread.interrupted()
                    caught.countDown()
                }
                try {
                    second.await()
                } catch (_: InterruptedException) {
                    secondInterrupt.set(true)
                }
                BotHold.acknowledgeAbort()
                BotHold.disableMidStepPause()
            }
        ready.await(1, TimeUnit.SECONDS)
        waitUntil { bot.state == Thread.State.WAITING }
        BotHold.requestPause()
        assertFalse(BotHold.watchdogTick())
        assertTrue(caught.await(1, TimeUnit.SECONDS))
        assertTrue(abortSeenInBot.get())
        // The bot is alive and waiting again with the pause still pending. Only the raised abort keeps the watchdog from interrupting it.
        waitUntil { bot.state == Thread.State.WAITING }
        assertFalse(BotHold.watchdogTick())
        assertFalse(bot.isInterrupted)
        second.countDown()
        bot.join(1_000L)
        assertFalse(bot.isAlive)
        assertFalse(secondInterrupt.get())
    }

    @Test
    fun watchdogIgnoresABotThreadQueuedOnTheLock() {
        reachSafePoint()
        val go = CountDownLatch(1)
        val run =
            startBot {
                go.await()
                BotHold.awaitIfPaused()
            }
        BotHold.requestPause()
        val lock = BotHold.lockForTest
        lock.lock()
        try {
            go.countDown()
            waitUntil { run.thread.state == Thread.State.WAITING && lock.hasQueuedThread(run.thread) }
            assertTrue(BotHold.watchdogTick())
            assertFalse(BotHold.isAbortRaised)
            assertFalse(run.thread.isInterrupted)
        } finally {
            lock.unlock()
        }
        waitUntil { BotHold.pauseState == BotHold.PauseState.PAUSED }
        BotHold.resume()
        run.thread.join(1_000L)
        assertFalse(run.thread.isAlive)
        assertFalse(run.interrupted.get())
    }

    @Test
    fun watchdogStopsOnceThePauseIsCancelled() {
        reachSafePoint()
        val blocker = CountDownLatch(1)
        val run = startBot { blocker.await() }
        BotHold.requestPause()
        BotHold.resume()
        assertFalse(BotHold.watchdogTick())
        blocker.countDown()
        run.thread.join(1_000L)
        assertFalse(run.interrupted.get())
    }

    @Test
    fun watchdogRunsByItselfAfterTheDelay() {
        reachSafePoint()
        BotHold.watchdogDelayMs = 50L
        val blocker = CountDownLatch(1)
        val run = startBot { blocker.await() }
        waitUntil { run.thread.state == Thread.State.WAITING }
        BotHold.requestPause()
        run.thread.join(2_000L)
        assertTrue(run.interrupted.get())
        assertTrue(run.abortSeen.get())
    }

    @Test
    fun watchdogNeedsAnOptIn() {
        reachSafePoint()
        BotHold.requestPause()
        assertFalse(BotHold.watchdogTick())
    }

    @Test
    fun requestPauseKeepsItsReasonUntilResume() {
        reachSafePoint()
        BotHold.requestPause("Open the game, then tap Resume")
        assertEquals("Open the game, then tap Resume", BotHold.pauseReason)
        BotHold.resume()
        assertEquals("", BotHold.pauseReason)
    }

    @Test
    fun focusLossPausesAnOptedInRun() {
        BotHold.enableMidStepPause(GAME_PACKAGES)
        reachSafePoint()
        assertTrue(BotHold.pauseForFocusLoss())
        assertEquals(BotHold.PauseState.REQUESTED, BotHold.pauseState)
        assertEquals(BotHold.FOCUS_LOSS_REASON, BotHold.pauseReason)
    }

    @Test
    fun focusLossNeedsAnOptInWithGamePackages() {
        reachSafePoint()
        assertFalse(BotHold.pauseForFocusLoss())
        BotHold.enableMidStepPause()
        assertFalse(BotHold.pauseForFocusLoss())
        assertEquals(BotHold.PauseState.NONE, BotHold.pauseState)
    }

    @Test
    fun focusLossRightAfterAResumeIsIgnored() {
        BotHold.enableMidStepPause(GAME_PACKAGES)
        reachSafePoint()
        BotHold.requestPause()
        BotHold.resume()
        now += 1_000L
        assertFalse(BotHold.pauseForFocusLoss())
        now += 600L
        assertTrue(BotHold.pauseForFocusLoss())
    }

    @Test
    fun focusLossDoesNotReplaceAPendingPause() {
        BotHold.enableMidStepPause(GAME_PACKAGES)
        reachSafePoint()
        BotHold.requestPause()
        assertFalse(BotHold.pauseForFocusLoss())
        assertEquals("", BotHold.pauseReason)
    }

    @Test
    fun resetClearsTheReasonAndTheResumeTime() {
        BotHold.enableMidStepPause(GAME_PACKAGES)
        reachSafePoint()
        BotHold.requestPause("Open the game, then tap Resume")
        BotHold.reset()
        assertEquals("", BotHold.pauseReason)

        BotHold.enableMidStepPause(GAME_PACKAGES)
        reachSafePoint()
        BotHold.requestPause()
        BotHold.resume()
        BotHold.reset()
        BotHold.enableMidStepPause(GAME_PACKAGES)
        reachSafePoint()
        // No grace is left over from a resume before the reset.
        assertTrue(BotHold.pauseForFocusLoss())
    }
}
