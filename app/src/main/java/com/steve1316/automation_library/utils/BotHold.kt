package com.steve1316.automation_library.utils

import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thrown at a checkpoint when a pause aborts the current step. It extends `InterruptedException`, so code that already unwinds on an
 * interrupt unwinds on it too. The consuming app catches it at the top of its loop and calls `BotHold.acknowledgeAbort()`.
 */
class StepAbortedException : InterruptedException("The current step was aborted for a pause.")

/**
 * Pauses the bot on request, and holds it briefly while the overlay's tray is open.
 *
 * By default a pause lands only at the safe point the consuming app calls between steps (`awaitIfPaused()`), because many bot loops time
 * themselves with the clock and would time out after a long pause mid-step. An app that calls `enableMidStepPause()` on its bot thread also
 * lets a pause abort the current step at the next screenshot, gesture, or wait (`checkpoint()`). The step unwinds to the app's loop, which
 * waits at the safe point and then decides afresh from the screen. The tray hold is checked before every screenshot and gesture, so the
 * tray never shows up in what the bot reads.
 * Lock order is always `BotHold` then `BotStatus`.
 */
object BotHold {
    /** Where a pause stands. */
    enum class PauseState {
        /** Not paused. */
        NONE,

        /** A pause was asked for. It lands at the next safe point, or at the next checkpoint on an opted-in run. */
        REQUESTED,

        /** The bot thread is waiting at a safe point until resume. */
        PAUSED,
    }

    /** Marks a thread as part of the bot's work. Worker threads inherit it from the thread that created them. */
    private class Lineage(
        /** The abort epoch the thread was created in. A worker whose epoch is behind the current one belongs to an aborted step. */
        val epoch: Long,
    )

    /** How long the tray stays open before it closes by itself, in milliseconds. Also the longest the tray can hold the bot. */
    const val TRAY_HOLD_MS = 3_000L

    /** Longest time the tray can hold the bot, in milliseconds. Matches the tray's auto-close. Changed in unit tests. */
    internal var trayHoldMaxMs: Long = TRAY_HOLD_MS

    /** True when called on the main thread. Replaced in unit tests, where the Android main looper does not exist. */
    internal var isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }

    /** True while a run is in progress. Replaced in unit tests. */
    internal var isBotRunning: () -> Boolean = { BotService.isRunning }

    /** Time to let the screen redraw without the tray after a tray hold ends, in milliseconds. Changed in unit tests. */
    internal var traySettleMs: Long = 150L

    /** Current time in milliseconds. Replaced in unit tests. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Writes one line to the run's message log. Replaced in unit tests, where the message log is not set up. */
    internal var log: (String) -> Unit = { MessageLog.i(message = it) }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Set on the opted-in bot thread and inherited by the workers it creates. Unset on every other thread. */
    private val lineage = InheritableThreadLocal<Lineage?>()

    /** How many `deferPause` blocks are open on the calling thread. `ThreadLocal.withInitial` needs API 26, so this overrides `initialValue`. */
    private val deferDepth =
        object : ThreadLocal<Int>() {
            override fun initialValue(): Int = 0
        }

    /** Current pause state. */
    @Volatile
    var pauseState: PauseState = PauseState.NONE
        private set

    /** True while the overlay's tray is open. */
    @Volatile
    var isTrayOpen: Boolean = false
        private set

    /** True once the current run has reached a safe point, so a pause can land. Apps that never call `awaitIfPaused()` cannot be paused. */
    @Volatile
    var hasSafePoint: Boolean = false
        private set

    /** True while the consuming app has opted in to mid-step pause for the current run. */
    @Volatile
    var isMidStepEnabled: Boolean = false
        private set

    /** True once a checkpoint or the watchdog has aborted the current step, until the app calls `acknowledgeAbort()`. */
    @Volatile
    var isAbortRaised: Boolean = false
        private set

    /** Package names of the game the opted-in app plays. Empty when the app supplied none or has not opted in. */
    @Volatile
    var gamePackages: Set<String> = emptySet()
        private set

    /** The opted-in bot thread, or null. */
    @Volatile
    private var botThread: Thread? = null

    /** Bumped by every acknowledged abort and every reset, so workers from an aborted step can tell they are stale. */
    @Volatile
    private var epoch: Long = 0L

    /** True while a `deferPause` block is open on the bot thread. The watchdog reads it, since it cannot see the bot thread's own depth. */
    @Volatile
    private var botDeferred: Boolean = false

    /** When the pending pause was requested, in `clock()` time, or null when none is pending. */
    @Volatile
    private var requestedAtMs: Long? = null

    /**
     * Asks the bot to pause at its next safe point, or at its next checkpoint on an opted-in run. Does nothing when no run is in progress, the
     * run has not reached a safe point yet, or a pause is already pending.
     */
    fun requestPause() {
        if (!isBotRunning() || !hasSafePoint) return
        lock.withLock {
            if (pauseState != PauseState.NONE) return
            pauseState = PauseState.REQUESTED
            requestedAtMs = clock()
        }
        notifyListeners()
    }

    /**
     * Resumes a paused bot, or cancels a pause that has not landed yet.
     */
    fun resume() {
        lock.withLock {
            if (pauseState == PauseState.NONE) return
            if (pauseState == PauseState.PAUSED) BotStatus.markPaused(false)
            pauseState = PauseState.NONE
            requestedAtMs = null
            changed.signalAll()
        }
        notifyListeners()
    }

    /**
     * Records whether the overlay's tray is open. Closing it wakes any thread held by `awaitTrayClosed()`.
     *
     * @param open True when the tray just opened, false when it closed.
     */
    fun setTrayOpen(open: Boolean) {
        lock.withLock {
            if (isTrayOpen == open) return
            isTrayOpen = open
            changed.signalAll()
        }
        notifyListeners()
    }

    /**
     * Waits while the tray is open, for at most `trayHoldMaxMs`. Returns right away on the main thread, which the tray needs in order to close.
     * After a wait it also sleeps `traySettleMs` so the next screenshot or gesture comes after the tray is gone from the screen.
     *
     * @throws InterruptedException If the bot thread is interrupted while waiting, such as when the user stops the bot.
     */
    fun awaitTrayClosed() {
        if (!isTrayOpen || isMainThread()) return
        lock.withLock {
            var remainingNs = TimeUnit.MILLISECONDS.toNanos(trayHoldMaxMs)
            while (isTrayOpen && remainingNs > 0L) {
                remainingNs = changed.awaitNanos(remainingNs)
            }
        }
        if (traySettleMs > 0L) Thread.sleep(traySettleMs)
    }

    /**
     * Opts the calling thread in to mid-step pause for this run. Call it on the bot thread itself, before its loop starts. Worker threads
     * this thread creates afterwards inherit its lineage, so their checkpoints can tell when their step was aborted.
     *
     * @param gamePackages Package names of the game the bot plays. A window change to any other app pauses the run. Empty turns that off.
     */
    fun enableMidStepPause(gamePackages: Set<String> = emptySet()) {
        lock.withLock {
            botThread = Thread.currentThread()
            isMidStepEnabled = true
            isAbortRaised = false
            botDeferred = false
            this.gamePackages = gamePackages
            lineage.set(Lineage(epoch))
        }
    }

    /**
     * Turns mid-step pause off again. Call it on the bot thread when its loop ends, so the cleanup after the loop is never aborted.
     */
    fun disableMidStepPause() {
        lock.withLock { clearMidStepStateLocked() }
        lineage.remove()
    }

    /**
     * Clears the mid-step opt-in, the abort flag, the bot thread, the deferral flag, and the game packages, then starts a new epoch. Shared by
     * `reset()` and `disableMidStepPause()`. The caller must hold `lock`.
     */
    private fun clearMidStepStateLocked() {
        isMidStepEnabled = false
        isAbortRaised = false
        botThread = null
        botDeferred = false
        gamePackages = emptySet()
        epoch++
    }

    /**
     * Runs before every screenshot, gesture, and wait. Holds for the open tray as `awaitTrayClosed()` does. On an opted-in run it also aborts
     * the current step when a pause is pending: the bot thread throws unless a `deferPause` block is open, and its workers throw once their
     * step is stale, the abort is raised, or the pause has landed. The main thread and threads outside the bot's lineage are never aborted.
     *
     * @throws StepAbortedException If a pause aborts the current step.
     * @throws InterruptedException If the run was stopped, which always wins over a pause.
     */
    fun checkpoint() {
        awaitTrayClosed()
        if (!isMidStepEnabled || isMainThread()) return
        if (Thread.currentThread() === botThread) {
            if (!isBotRunning()) throw InterruptedException("The bot was stopped.")
            if (isAbortRaised) throw StepAbortedException()
            if (pauseState != PauseState.REQUESTED || deferDepth.get() > 0) return
            val aborting =
                lock.withLock {
                    if (pauseState == PauseState.REQUESTED) isAbortRaised = true
                    isAbortRaised
                }
            if (aborting) throw StepAbortedException()
            return
        }
        val mine = lineage.get() ?: return
        if (!isBotRunning()) throw InterruptedException("The bot was stopped.")
        if (mine.epoch != epoch || isAbortRaised || pauseState == PauseState.PAUSED) throw StepAbortedException()
    }

    /**
     * Clears an abort once the app's loop has caught it, so the loop can restart. On the bot thread it also clears the interrupt flag, which
     * the watchdog may have set, and moves the thread to the new epoch so workers left over from the aborted step fail at their next checkpoint.
     *
     * @return True if an abort was pending, false if the interrupt or failure came from something else, such as a stop.
     */
    fun acknowledgeAbort(): Boolean {
        val newEpoch =
            lock.withLock {
                if (!isAbortRaised) return false
                isAbortRaised = false
                epoch++
                epoch
            }
        if (Thread.currentThread() === botThread) {
            Thread.interrupted()
            lineage.set(Lineage(newEpoch))
        }
        return true
    }

    /**
     * Runs a short sequence of taps that must finish together, holding a pending pause back on this thread until the block ends. A checkpoint
     * runs when the outermost block exits normally, so a pause that came in during the block lands right after it. Blocks can nest.
     *
     * @param block The taps and short waits to run without being aborted.
     * @return The block's result.
     */
    fun <T> deferPause(block: () -> T): T {
        val depth = deferDepth.get()
        setDeferDepth(depth + 1)
        val result =
            try {
                block()
            } finally {
                setDeferDepth(depth)
            }
        if (depth == 0) checkpoint()
        return result
    }

    /**
     * Sets the calling thread's `deferPause` depth, and mirrors it for the watchdog when this is the bot thread.
     *
     * @param depth The new depth.
     */
    private fun setDeferDepth(depth: Int) {
        deferDepth.set(depth)
        if (Thread.currentThread() === botThread) botDeferred = depth > 0
    }

    /**
     * The safe point. Turns a pending pause into a real one and waits until `resume()`. Returns right away when no pause is pending.
     * Logs how long the pause took to land, measured from the request.
     *
     * @return True if the bot was paused here, so it may need to re-read the screen before going on.
     * @throws InterruptedException If the bot thread is interrupted while paused, such as when the user stops the bot.
     */
    fun awaitIfPaused(): Boolean {
        if (hasSafePoint && pauseState == PauseState.NONE) return false
        var firstSafePoint = false
        var landedAfterMs: Long? = null
        val pausing =
            lock.withLock {
                if (!hasSafePoint) {
                    hasSafePoint = true
                    firstSafePoint = true
                }
                if (pauseState != PauseState.REQUESTED) return@withLock false
                BotStatus.markPaused(true)
                pauseState = PauseState.PAUSED
                landedAfterMs = requestedAtMs?.let { clock() - it }
                requestedAtMs = null
                true
            }
        landedAfterMs?.let { log("[PAUSE] landed after ${it}ms") }
        // The first safe point also changes what the overlay and the notification offer, since Pause is only shown once a pause can land.
        if (pausing || firstSafePoint) notifyListeners()
        if (!pausing) return false
        lock.withLock {
            while (pauseState == PauseState.PAUSED) {
                changed.await()
            }
        }
        return true
    }

    /**
     * Clears the pause, the tray flag, the safe point, and the mid-step opt-in. Called by `BotService` when a run starts and when it ends.
     */
    fun reset() {
        lock.withLock {
            pauseState = PauseState.NONE
            isTrayOpen = false
            hasSafePoint = false
            clearMidStepStateLocked()
            requestedAtMs = null
            changed.signalAll()
        }
        notifyListeners()
    }

    /**
     * Registers a listener for pause, tray, and safe point changes.
     *
     * @param listener Called on the thread that made the change.
     */
    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    /**
     * Removes a listener added with `addListener()`.
     *
     * @param listener The same instance that was added.
     */
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Calls every listener.
     */
    private fun notifyListeners() {
        listeners.forEach { it() }
    }
}
