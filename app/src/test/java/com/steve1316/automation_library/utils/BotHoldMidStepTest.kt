package com.steve1316.automation_library.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private val GAME_PACKAGES = setOf("com.example.game")

/** Unit tests for aborting a step midway, the sticky abort, worker lineage, `deferPause`, and Stop winning over a pause in `BotHold`. */
class BotHoldMidStepTest {
    private var now = 0L
    private val logged = mutableListOf<String>()

    @Before
    fun setUp() {
        BotHold.isMainThread = { false }
        BotHold.isBotRunning = { true }
        BotHold.trayHoldMaxMs = 3_000L
        BotHold.traySettleMs = 0L
        BotHold.reset()
        now = 0L
        BotHold.clock = { now }
        BotHold.log = { synchronized(logged) { logged.add(it) } }
        BotStatus.clock = { now }
        BotStatus.reset()
        synchronized(logged) { logged.clear() }
    }

    @After
    fun tearDown() {
        // The test thread acts as the bot thread in most tests, so drop its opt-in and any interrupt left behind.
        BotHold.disableMidStepPause()
        Thread.interrupted()
    }

    /**
     * Opts the calling thread in, the way the consuming app does at the top of its loop.
     */
    private fun optIn() {
        BotHold.enableMidStepPause(GAME_PACKAGES)
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
     * Starts a thread that runs one checkpoint once the gate opens. The thread inherits the calling thread's lineage at this moment.
     *
     * @param gate Opened by the test when the thread should run its checkpoint.
     * @return The thread, and a holder for what its checkpoint threw (null when it returned normally).
     */
    private fun checkpointOnNewThread(gate: CountDownLatch): Pair<Thread, AtomicReference<Throwable?>> {
        val thrown = AtomicReference<Throwable?>()
        val worker =
            thread {
                gate.await()
                thrown.set(runCatching { BotHold.checkpoint() }.exceptionOrNull())
            }
        return worker to thrown
    }

    @Test
    fun abortIsAnInterruptedException() {
        assertTrue(InterruptedException::class.java.isAssignableFrom(StepAbortedException::class.java))
    }

    @Test
    fun noOptInNeverAborts() {
        reachSafePoint()
        BotHold.requestPause()
        BotHold.checkpoint()
        assertFalse(BotHold.isAbortRaised)
        assertEquals(BotHold.PauseState.REQUESTED, BotHold.pauseState)
    }

    @Test
    fun enableRecordsTheGamePackages() {
        optIn()
        assertTrue(BotHold.isMidStepEnabled)
        assertEquals(GAME_PACKAGES, BotHold.gamePackages)
    }

    @Test
    fun checkpointPassesWithoutAPause() {
        optIn()
        reachSafePoint()
        BotHold.checkpoint()
        assertFalse(BotHold.isAbortRaised)
    }

    @Test
    fun checkpointAbortsTheBotThreadWhileRequested() {
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        assertThrows(StepAbortedException::class.java) { BotHold.checkpoint() }
        assertTrue(BotHold.isAbortRaised)
    }

    @Test
    fun abortIsStickyUntilAcknowledged() {
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        assertThrows(StepAbortedException::class.java) { BotHold.checkpoint() }
        // A catch that swallowed the first abort gets it again at the next checkpoint, even after the pause was cancelled.
        BotHold.resume()
        assertThrows(StepAbortedException::class.java) { BotHold.checkpoint() }
        assertTrue(BotHold.acknowledgeAbort())
        BotHold.checkpoint()
        assertFalse(BotHold.acknowledgeAbort())
    }

    @Test
    fun acknowledgeClearsTheInterruptFlag() {
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        assertThrows(StepAbortedException::class.java) { BotHold.checkpoint() }
        Thread.currentThread().interrupt()
        assertTrue(BotHold.acknowledgeAbort())
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun resumeBeforeTheAbortLandsMeansNoRestart() {
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        BotHold.resume()
        BotHold.checkpoint()
        assertFalse(BotHold.acknowledgeAbort())
        assertEquals(BotHold.PauseState.NONE, BotHold.pauseState)
    }

    @Test
    fun deferPauseHoldsTheAbortUntilTheBlockEnds() {
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        var taps = 0
        assertThrows(StepAbortedException::class.java) {
            BotHold.deferPause {
                BotHold.checkpoint()
                taps++
                BotHold.checkpoint()
                taps++
            }
        }
        assertEquals(2, taps)
        assertTrue(BotHold.isAbortRaised)
    }

    @Test
    fun nestedDeferPauseChecksOnlyWhenTheOuterBlockEnds() {
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        var afterInner = false
        assertThrows(StepAbortedException::class.java) {
            BotHold.deferPause {
                BotHold.deferPause { BotHold.checkpoint() }
                afterInner = true
            }
        }
        assertTrue(afterInner)
    }

    @Test
    fun deferPauseReturnsTheBlockResultWithoutAPause() {
        optIn()
        reachSafePoint()
        assertEquals(42, BotHold.deferPause { 42 })
    }

    @Test
    fun staleWorkerThrowsAndFreshWorkerDoesNot() {
        optIn()
        reachSafePoint()
        val staleGate = CountDownLatch(1)
        val (stale, staleThrown) = checkpointOnNewThread(staleGate)
        BotHold.requestPause()
        assertThrows(StepAbortedException::class.java) { BotHold.checkpoint() }
        assertTrue(BotHold.acknowledgeAbort())
        staleGate.countDown()
        stale.join(1_000L)
        assertFalse(stale.isAlive)
        assertTrue(staleThrown.get() is StepAbortedException)

        // Once the pause is cancelled and nothing is raised, a stale worker (such as a pool thread) is left alone.
        BotHold.resume()
        val lateGate = CountDownLatch(1)
        val (late, lateThrown) = checkpointOnNewThread(lateGate)
        lateGate.countDown()
        late.join(1_000L)
        assertFalse(late.isAlive)
        assertNull(lateThrown.get())

        val freshGate = CountDownLatch(1)
        val (fresh, freshThrown) = checkpointOnNewThread(freshGate)
        freshGate.countDown()
        fresh.join(1_000L)
        assertFalse(fresh.isAlive)
        assertNull(freshThrown.get())
    }

    @Test
    fun workersFailFastOnceTheAbortIsRaised() {
        optIn()
        reachSafePoint()
        val gate = CountDownLatch(1)
        val (worker, thrown) = checkpointOnNewThread(gate)
        BotHold.requestPause()
        assertThrows(StepAbortedException::class.java) { BotHold.checkpoint() }
        gate.countDown()
        worker.join(1_000L)
        assertFalse(worker.isAlive)
        assertTrue(thrown.get() is StepAbortedException)
    }

    @Test
    fun workersKeepGoingWhileThePauseIsOnlyRequested() {
        optIn()
        reachSafePoint()
        val gate = CountDownLatch(1)
        val (worker, thrown) = checkpointOnNewThread(gate)
        BotHold.requestPause()
        gate.countDown()
        worker.join(1_000L)
        assertFalse(worker.isAlive)
        assertNull(thrown.get())
    }

    @Test
    fun threadsOutsideTheLineageAreNeverAborted() {
        val gate = CountDownLatch(1)
        // Created before the opt-in, so it carries no lineage.
        val (outsider, thrown) = checkpointOnNewThread(gate)
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        assertThrows(StepAbortedException::class.java) { BotHold.checkpoint() }
        gate.countDown()
        outsider.join(1_000L)
        assertFalse(outsider.isAlive)
        assertNull(thrown.get())
    }

    @Test
    fun theMainThreadIsNeverAborted() {
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        BotHold.isMainThread = { true }
        BotHold.checkpoint()
        assertFalse(BotHold.isAbortRaised)
    }

    @Test
    fun stopWinsOverAPendingPause() {
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        BotHold.isBotRunning = { false }
        Thread.currentThread().interrupt()
        val thrown = assertThrows(InterruptedException::class.java) { BotHold.checkpoint() }
        assertFalse(thrown is StepAbortedException)
        assertFalse(BotHold.isAbortRaised)
    }

    @Test
    fun acknowledgeLeavesAPendingStopInterrupted() {
        reachSafePoint()
        val result = AtomicReference<Boolean?>()
        val stillInterrupted = AtomicReference<Boolean?>()
        val acknowledger =
            thread {
                BotHold.enableMidStepPause(GAME_PACKAGES)
                BotHold.requestPause()
                runCatching { BotHold.checkpoint() }
                // Stop sets the outcome first, then interrupts, so the abort can still be raised when the interrupt arrives.
                BotHold.isBotRunning = { false }
                Thread.currentThread().interrupt()
                result.set(BotHold.acknowledgeAbort())
                stillInterrupted.set(Thread.currentThread().isInterrupted)
                BotHold.disableMidStepPause()
            }
        acknowledger.join(1_000L)
        assertFalse(acknowledger.isAlive)
        assertEquals(false, result.get())
        assertEquals(true, stillInterrupted.get())
    }

    @Test
    fun stopWhilePausedIsAPlainStop() {
        reachSafePoint()
        BotHold.requestPause()
        val thrown = AtomicReference<Throwable?>()
        val bot =
            thread {
                BotHold.enableMidStepPause(GAME_PACKAGES)
                try {
                    BotHold.awaitIfPaused()
                } catch (e: InterruptedException) {
                    thrown.set(e)
                }
                BotHold.disableMidStepPause()
            }
        waitUntil { BotHold.pauseState == BotHold.PauseState.PAUSED }
        BotHold.isBotRunning = { false }
        bot.interrupt()
        bot.join(1_000L)
        assertFalse(bot.isAlive)
        assertTrue(thrown.get() is InterruptedException)
        assertFalse(thrown.get() is StepAbortedException)
    }

    @Test
    fun disableStopsAborting() {
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        BotHold.disableMidStepPause()
        BotHold.checkpoint()
        assertFalse(BotHold.isMidStepEnabled)
        assertFalse(BotHold.isAbortRaised)
    }

    @Test
    fun resetClearsTheMidStepState() {
        optIn()
        reachSafePoint()
        BotHold.requestPause()
        assertThrows(StepAbortedException::class.java) { BotHold.checkpoint() }
        BotHold.reset()
        assertFalse(BotHold.isMidStepEnabled)
        assertFalse(BotHold.isAbortRaised)
        assertTrue(BotHold.gamePackages.isEmpty())
        BotHold.checkpoint()
        assertFalse(BotHold.acknowledgeAbort())
    }

    @Test
    fun awaitIfPausedReportsWhetherItPaused() {
        assertFalse(BotHold.awaitIfPaused())
        BotHold.requestPause()
        val paused = AtomicReference<Boolean>()
        val worker = thread { paused.set(BotHold.awaitIfPaused()) }
        waitUntil { BotHold.pauseState == BotHold.PauseState.PAUSED }
        BotHold.resume()
        worker.join(1_000L)
        assertFalse(worker.isAlive)
        assertEquals(true, paused.get())
    }

    @Test
    fun pauseLandingIsLogged() {
        reachSafePoint()
        now = 1_000L
        BotHold.requestPause()
        now = 1_412L
        val worker = thread { BotHold.awaitIfPaused() }
        waitUntil { BotHold.pauseState == BotHold.PauseState.PAUSED }
        BotHold.resume()
        worker.join(1_000L)
        assertFalse(worker.isAlive)
        assertEquals(listOf("[PAUSE] landed after 412ms"), synchronized(logged) { logged.toList() })
    }
}
