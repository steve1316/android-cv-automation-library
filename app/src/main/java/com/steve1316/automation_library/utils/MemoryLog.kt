package com.steve1316.automation_library.utils

import android.os.Debug
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import java.io.File

/**
 * Writes `[MEM]` lines to logcat so a run's memory can be followed over time without a profiler.
 *
 * The lines go to logcat only, not the message log, so they never show up in saved logs or the frontend. `BotService` logs one at the start and end of every
 * run, and an app can add its own, such as one per in-game day.
 */
object MemoryLog {
    private const val TAG: String = "${SharedData.loggerTag}MemoryLog"
    private val MEM_AVAILABLE_REGEX = Regex("""^MemAvailable:\s+(\d+)""", RegexOption.MULTILINE)
    private const val KB_PER_MB: Int = 1024

    /** One reading of the process's memory, in KB unless the name says otherwise. */
    internal data class MemorySample(
        /** Physical memory charged to this process. */
        val pssKb: Int,
        /** Native heap share of the total. */
        val nativeKb: Int,
        /** Java heap share of the total. */
        val javaKb: Int,
        /** Graphics share of the total. */
        val graphicsKb: Int,
        /** Memory swapped out, which is not part of the total. */
        val swapKb: Int,
        /** What malloc says is live. A large gap below `nativeKb` points at memory malloc does not track. */
        val allocatedKb: Int,
        /** Free memory on the whole device, or null if it could not be read. */
        val availableKb: Int?,
        /** How long the reading took, because it walks the process's memory map on the calling thread. */
        val scanMs: Long,
    )

    /**
     * Builds the log line. The label comes first so the line reads the same for every kind of reading.
     *
     * @param label What this reading belongs to, such as `day=12`.
     * @param sample The reading.
     * @return The line to log.
     */
    internal fun formatLine(label: String, sample: MemorySample): String {
        val available = sample.availableKb?.let(::mb) ?: "?"
        return "[MEM] $label pss=${mb(sample.pssKb)} native=${mb(sample.nativeKb)} java=${mb(sample.javaKb)} graphics=${mb(sample.graphicsKb)} " +
            "swap=${mb(sample.swapKb)} allocated=${mb(sample.allocatedKb)} avail=$available scan=${sample.scanMs}ms"
    }

    /**
     * Reads the device's free memory from the text of `/proc/meminfo`.
     *
     * @param text The file's contents.
     * @return The MemAvailable value in KB, or null if the row is missing.
     */
    internal fun parseMemAvailableKb(text: String): Int? = MEM_AVAILABLE_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Takes a reading and writes it to logcat. Reading memory is best effort, so a failure is logged and never reaches the caller.
     *
     * @param label What this reading belongs to, such as `day=12` or `run_end`. It must not contain spaces.
     */
    fun log(label: String) {
        runCatching { Log.i(TAG, formatLine(label, readSample())) }.onFailure { Log.w(TAG, "Could not read memory for $label: ${it.message}") }
    }

    /**
     * Formats a KB value as whole MB.
     *
     * @param kb The value in KB.
     * @return The value with its unit.
     */
    private fun mb(kb: Int): String = "${kb / KB_PER_MB}MB"

    /**
     * Reads the process's memory. Free memory is optional, so a failed read leaves it empty rather than stopping the run.
     *
     * @return The reading.
     */
    private fun readSample(): MemorySample {
        val startMs = System.currentTimeMillis()
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val stat = { name: String -> info.getMemoryStat(name)?.toIntOrNull() ?: 0 }
        val available = runCatching { parseMemAvailableKb(File("/proc/meminfo").readText()) }.getOrNull()
        return MemorySample(
            pssKb = info.totalPss,
            nativeKb = stat("summary.native-heap"),
            javaKb = stat("summary.java-heap"),
            graphicsKb = stat("summary.graphics"),
            swapKb = stat("summary.total-swap"),
            allocatedKb = (Debug.getNativeHeapAllocatedSize() / KB_PER_MB).toInt(),
            availableKb = available,
            scanMs = System.currentTimeMillis() - startMs,
        )
    }
}
