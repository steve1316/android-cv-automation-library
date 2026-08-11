package com.steve1316.automation_library.utils

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live status of the current run, read by the floating overlay and the notification.
 *
 * The consuming app pushes the step progress, the last action, and why the run ended. The library owns the running-time timer, which leaves out paused time.
 * Listeners are called on the thread that made the change, so UI code should post to the main thread.
 */
object BotStatus {
    /** How a run ended. */
    enum class Outcome {
        /** The run completed everything it set out to do. */
        FINISHED,

        /** The bot stopped itself, such as at a breakpoint or when the device went to sleep. */
        STOPPED_BY_BOT,

        /** The user stopped the bot from the overlay, the tray, or the notification. */
        STOPPED_BY_USER,

        /** The bot thread threw an exception. */
        CRASHED,
    }

    /** Status of the run at one moment. */
    data class Snapshot(
        /** Current step, for example a turn or a quest number, or 0 when the app has not reported one. */
        val current: Int = 0,
        /** Total steps, or 0 when the app has not reported one. */
        val total: Int = 0,
        /** Progress label such as "Turn 34/72" or "Quest 3/10", or empty when the app has not reported one. */
        val label: String = "",
        /** Last action such as "Trained Speed" or "Cleared Chapter 5", or empty when the app has not reported one. */
        val detail: String = "",
        /** How the run ended, or null while it is still going. */
        val outcome: Outcome? = null,
        /** Why the run ended, or empty while it is still going. */
        val reason: String = "",
        /** Running time in milliseconds, leaving out paused time. */
        val elapsedMs: Long = 0L,
    ) {
        /** Progress from 0 to 1, or 0 when the total is unknown. Steps past the total cap it at 1. */
        val fraction: Float
            get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Millisecond clock used for the timer. Replaced in unit tests. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var current = 0
    private var total = 0
    private var label = ""
    private var detail = ""
    private var outcome: Outcome? = null
    private var reason = ""
    private var startedAtMs: Long? = null
    private var pausedAtMs: Long? = null
    private var pausedTotalMs = 0L
    private var frozenElapsedMs: Long? = null

    /** Running time in milliseconds, leaving out paused time. Stops counting once an outcome is set. */
    val elapsedMs: Long
        get() = synchronized(lock) { elapsedLocked() }

    /**
     * Clears progress, detail, and outcome, and restarts the timer. Called by `BotService` when a run starts.
     */
    fun reset() {
        synchronized(lock) {
            current = 0
            total = 0
            label = ""
            detail = ""
            outcome = null
            reason = ""
            startedAtMs = clock()
            pausedAtMs = null
            pausedTotalMs = 0L
            frozenElapsedMs = null
        }
        notifyListeners()
    }

    /**
     * Sets the step progress.
     *
     * @param current The current step, for example a turn or a quest number.
     * @param total The total steps. The progress ring is full at this step.
     * @param label The progress label, such as "Turn 34/72" or "Quest 3/10".
     */
    fun setProgress(current: Int, total: Int, label: String) {
        synchronized(lock) {
            if (this.current == current && this.total == total && this.label == label) return
            this.current = current
            this.total = total
            this.label = label
        }
        notifyListeners()
    }

    /**
     * Sets the short description of the last action.
     *
     * @param text The description, such as "Trained Speed" or "Cleared Chapter 5".
     */
    fun setDetail(text: String) {
        synchronized(lock) {
            if (detail == text) return
            detail = text
        }
        notifyListeners()
    }

    /**
     * Records how the run ended and freezes the timer. The first call in a run wins, so a reason set before interrupting the bot is not replaced.
     *
     * @param outcome How the run ended.
     * @param reason Why it ended, shown in the tray and the notification.
     */
    fun setOutcome(outcome: Outcome, reason: String) {
        synchronized(lock) {
            if (this.outcome != null) return
            this.outcome = outcome
            this.reason = reason
            frozenElapsedMs = elapsedLocked()
        }
        notifyListeners()
    }

    /**
     * Reads every field at once.
     *
     * @return The current status.
     */
    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(current, total, label, detail, outcome, reason, elapsedLocked()) }

    /**
     * Starts or ends a paused span, which the timer leaves out. Called by `BotHold` only.
     *
     * @param paused True when the bot thread just paused, false when it resumed.
     */
    internal fun markPaused(paused: Boolean) {
        synchronized(lock) {
            val now = clock()
            if (paused) {
                if (pausedAtMs == null) pausedAtMs = now
            } else {
                pausedAtMs?.let { pausedTotalMs += now - it }
                pausedAtMs = null
            }
        }
    }

    /**
     * Registers a listener for any status change.
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
     * Computes the running time. Must be called while holding `lock`.
     *
     * @return Elapsed milliseconds, leaving out paused time.
     */
    private fun elapsedLocked(): Long {
        frozenElapsedMs?.let { return it }
        val start = startedAtMs ?: return 0L
        val now = clock()
        val pausedNow = pausedAtMs?.let { now - it } ?: 0L
        return (now - start - pausedTotalMs - pausedNow).coerceAtLeast(0L)
    }

    /**
     * Calls every listener.
     */
    private fun notifyListeners() {
        listeners.forEach { it() }
    }
}
