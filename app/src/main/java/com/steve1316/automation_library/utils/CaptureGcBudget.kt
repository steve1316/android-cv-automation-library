package com.steve1316.automation_library.utils

/**
 * Counts the bytes of new screen capture bitmaps and says when to run a garbage collection.
 *
 * A bitmap's pixels live in native memory and are only freed after the Java object is collected. The Java heap barely grows per capture, so the
 * collector rarely runs on its own and old frames can pile up to gigabytes. Collecting once per budget keeps them bounded without changing who
 * owns a frame.
 *
 * @property limitBytes How many new bitmap bytes may build up before a collection is due.
 */
class CaptureGcBudget(private val limitBytes: Long) {
    /** Bytes of new bitmaps since the last collection. */
    private var pendingBytes = 0L

    /**
     * Adds the size of a new bitmap.
     *
     * @param bytes The bitmap's allocation size.
     * @return True when the budget is spent and a collection should run. The count starts over then.
     */
    @Synchronized
    fun add(bytes: Long): Boolean {
        pendingBytes += bytes
        if (pendingBytes < limitBytes) return false
        pendingBytes = 0L
        return true
    }
}
