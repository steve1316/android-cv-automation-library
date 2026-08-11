package com.steve1316.automation_library.utils

import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pauses the bot thread on request, and holds it briefly while the overlay's tray is open.
 *
 * A pause lands only at a safe point the consuming app calls between steps (`awaitIfPaused()`), because many bot loops
 * time themselves with the clock and would time out after a long pause mid-step. The tray hold is checked before every
 * screenshot and gesture, so the tray never shows up in what the bot reads.
 * Lock order is always `BotHold` then `BotStatus`.
 */
object BotHold {
    /** Where a pause stands. */
    enum class PauseState {
        /** Not paused. */
        NONE,

        /** A pause was asked for and lands at the next safe point. */
        REQUESTED,

        /** The bot thread is waiting at a safe point until resume. */
        PAUSED,
    }

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

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

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

    /**
     * Asks the bot to pause at its next safe point. Does nothing when no run is in progress, the run has not reached a safe point yet, or a
     * pause is already pending.
     */
    fun requestPause() {
        if (!isBotRunning() || !hasSafePoint) return
        lock.withLock {
            if (pauseState != PauseState.NONE) return
            pauseState = PauseState.REQUESTED
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
     * The safe point. Turns a pending pause into a real one and waits until `resume()`. Returns right away when no pause is pending.
     *
     * @throws InterruptedException If the bot thread is interrupted while paused, such as when the user stops the bot.
     */
    fun awaitIfPaused() {
        if (hasSafePoint && pauseState == PauseState.NONE) return
        var firstSafePoint = false
        val pausing =
            lock.withLock {
                if (!hasSafePoint) {
                    hasSafePoint = true
                    firstSafePoint = true
                }
                if (pauseState != PauseState.REQUESTED) return@withLock false
                BotStatus.markPaused(true)
                pauseState = PauseState.PAUSED
                true
            }
        // The first safe point also changes what the overlay and the notification offer, since Pause is only shown once a pause can land.
        if (pausing || firstSafePoint) notifyListeners()
        if (!pausing) return
        lock.withLock {
            while (pauseState == PauseState.PAUSED) {
                changed.await()
            }
        }
    }

    /**
     * Clears the pause, the tray flag, and the safe point. Called by `BotService` when a run starts and when it ends.
     */
    fun reset() {
        lock.withLock {
            pauseState = PauseState.NONE
            isTrayOpen = false
            hasSafePoint = false
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
