package com.steve1316.automation_library.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Saves numbered, annotated debug images for the current run and bundles them with the run's log at the end.
 *
 * Images live in `filesDir/debug/current/` and are named `NNNNN_HH-MM-SS-mmm_<tag>.jpg`, where the number is a run-wide sequence and the time is
 * the MessageLog elapsed time. Callers append the returned `[img #NNNNN]` id to their log line so every image maps back to one log line.
 */
object DebugCapture {
    private const val TAG: String = "${SharedData.loggerTag}DebugCapture"

    // Oldest images are deleted once either cap is passed.
    private const val MAX_IMAGES: Int = 1000
    private const val MAX_BYTES: Long = 150L * 1024 * 1024

    /** JPEG quality from 1 to 100 used for every saved image. Lower values shrink the bundle at the cost of blurrier crops. */
    @Volatile
    var jpegQuality: Int = 50

    /** How many saves pass between prune sweeps, so the folder is not listed and every file stat-ed on every image. */
    private const val PRUNE_EVERY: Int = 50

    private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9_-]")

    val GREEN: Scalar = Scalar(0.0, 200.0, 0.0)
    val RED: Scalar = Scalar(0.0, 0.0, 230.0)

    /** Whether images are saved at all. Set from the debug mode setting at run start. */
    @Volatile
    var enabled: Boolean = false

    /** File name tags that are never saved, such as routine checks that run after every tap. Set once at app start. */
    @Volatile
    var ignoredTags: Set<String> = emptySet()

    @Volatile
    private var directory: File? = null
    private val sequence = AtomicInteger(0)
    private val writeLock = Any()

    /**
     * Clears the previous run's images and resets the sequence. Call once at run start.
     *
     * @param context The context for the application.
     * @param debugMode Whether debug images should be saved this run.
     */
    fun start(context: Context, debugMode: Boolean) {
        enabled = debugMode
        sequence.set(0)
        val dir = File(context.filesDir, "debug/current")
        synchronized(writeLock) {
            dir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
            directory = dir
        }
    }

    /**
     * Builds the image file name.
     *
     * @param seq The run-wide sequence number.
     * @param elapsed The MessageLog elapsed time such as "00:01:15.760".
     * @param tag A short label such as the template name.
     * @return The file name such as "00042_00-01-15-760_view_results.jpg".
     */
    fun fileName(seq: Int, elapsed: String, tag: String): String {
        val time = elapsed.replace(':', '-').replace('.', '-')
        val safeTag = tag.substringAfterLast('/').replace(UNSAFE_FILE_CHARS, "_").take(60)
        return "%05d_%s_%s.jpg".format(seq, time, safeTag)
    }

    /**
     * Picks which files to delete so that the remaining ones fit under both caps. Names sort oldest-first because they start with the sequence.
     *
     * @param files Pairs of file name and size in bytes.
     * @param maxCount The most files to keep.
     * @param maxBytes The most total bytes to keep.
     * @return The names to delete, oldest first.
     */
    fun namesToPrune(files: List<Pair<String, Long>>, maxCount: Int, maxBytes: Long): List<String> {
        val sorted = files.sortedBy { it.first }
        var count = sorted.size
        var bytes = sorted.sumOf { it.second }
        val doomed = mutableListOf<String>()
        for ((name, size) in sorted) {
            if (count <= maxCount && bytes <= maxBytes) break
            doomed.add(name)
            count--
            bytes -= size
        }
        return doomed
    }

    /**
     * Formats an image id for the end of a log line.
     *
     * @param id The id returned by `save`, or null.
     * @return " [img #00042]", or an empty string when no image was saved.
     */
    fun suffix(id: String?): String = if (id != null) " [img $id]" else ""

    /**
     * Saves a color image after letting the caller draw on it.
     *
     * @param tag A short label for the file name.
     * @param bitmap The image to save. It is not modified.
     * @param annotate Draws onto a BGR copy of the image before it is written.
     * @return The id such as "#00042" to put in the log line, or null when capture is off or the write failed.
     */
    fun save(tag: String, bitmap: Bitmap, annotate: (Mat) -> Unit = {}): String? {
        if (!enabled || tag in ignoredTags) return null
        val mat = Mat()
        return try {
            Utils.bitmapToMat(bitmap, mat)
            save(tag, mat, annotate)
        } finally {
            mat.release()
        }
    }

    /**
     * Saves a Mat after letting the caller draw on it. RGBA and grayscale Mats are converted to BGR first so colors come out right.
     *
     * @param tag A short label for the file name.
     * @param mat The image to save. It is not modified.
     * @param annotate Draws onto a BGR copy of the image before it is written.
     * @return The id such as "#00042" to put in the log line, or null when capture is off or the write failed.
     */
    fun save(tag: String, mat: Mat, annotate: (Mat) -> Unit = {}): String? {
        if (!enabled || tag in ignoredTags || mat.empty()) return null
        val dir = directory ?: return null
        val bgr = Mat()
        try {
            when (mat.channels()) {
                4 -> Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)
                1 -> Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_GRAY2BGR)
                else -> mat.copyTo(bgr)
            }
            annotate(bgr)

            val seq = sequence.incrementAndGet()
            val file = File(dir, fileName(seq, MessageLog.getElapsedTimeString(), tag))
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality.coerceIn(1, 100))
            try {
                synchronized(writeLock) {
                    if (!Imgcodecs.imwrite(file.absolutePath, bgr, params)) {
                        Log.w(TAG, "Failed to write ${file.name}.")
                        return null
                    }
                    if (seq % PRUNE_EVERY == 0) prune(dir)
                }
            } finally {
                params.release()
            }
            return "#%05d".format(seq)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save debug image for $tag: ${e.message}")
            return null
        } finally {
            bgr.release()
        }
    }

    /**
     * Draws a labeled box onto a BGR image.
     *
     * @param mat The BGR image to draw on.
     * @param rect The box to draw.
     * @param color The box and label color.
     * @param label Text drawn just above the box, or inside it when there is no room above.
     */
    fun drawBox(mat: Mat, rect: Rect, color: Scalar, label: String = "") {
        Imgproc.rectangle(mat, rect.tl(), rect.br(), color, 4)
        if (label.isNotEmpty()) {
            val y = if (rect.y > 30) rect.y - 8.0 else rect.y + 28.0
            drawText(mat, label, Point(rect.x.toDouble(), y), color)
        }
    }

    /**
     * Draws a banner across the top of a BGR image so the label stays readable on any background.
     *
     * @param mat The BGR image to draw on.
     * @param text The text to show.
     * @param color The text color.
     */
    fun drawBanner(mat: Mat, text: String, color: Scalar) {
        Imgproc.rectangle(mat, Point(0.0, 0.0), Point(mat.cols().toDouble(), 40.0), Scalar(0.0, 0.0, 0.0), -1)
        drawText(mat, text, Point(8.0, 30.0), color)
    }

    /**
     * Draws outlined text so it reads on light and dark backgrounds.
     *
     * @param mat The BGR image to draw on.
     * @param text The text to draw.
     * @param origin The bottom-left corner of the text.
     * @param color The text color.
     */
    private fun drawText(mat: Mat, text: String, origin: Point, color: Scalar) {
        Imgproc.putText(mat, text, origin, Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, Scalar(0.0, 0.0, 0.0), 5)
        Imgproc.putText(mat, text, origin, Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, color, 2)
    }

    /**
     * Deletes the oldest images once the folder passes either cap.
     *
     * @param dir The capture folder.
     */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_IMAGES && files.sumOf { it.length() } <= MAX_BYTES) return
        for (name in namesToPrune(files.map { it.name to it.length() }, MAX_IMAGES, MAX_BYTES)) {
            File(dir, name).delete()
        }
    }

    /**
     * Writes the run's log and every captured image into one zip in the logs folder.
     *
     * @param context The context for the application.
     * @param logFileName The saved log's file name such as "log @ 2026-10-15 09_47_39.txt".
     * @return The zip's file name, or null when there was nothing to bundle or the write failed.
     */
    fun writeBundle(context: Context, logFileName: String): String? {
        val dir = directory ?: return null
        val images = synchronized(writeLock) { dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } }
        if (images.isNullOrEmpty()) return null

        val storage = UserStorageManager.getInstance(context)
        val zipName = logFileName.removeSuffix(".txt") + ".zip"
        val out = storage.openOutputStream("logs", zipName, "application/zip") ?: return null
        return try {
            ZipOutputStream(out.buffered()).use { zip ->
                // Add the saved log first so the zip opens to it.
                storage.openInputStream("logs", logFileName)?.use { input ->
                    zip.putNextEntry(ZipEntry("log.txt"))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
                // JPEGs are already compressed, so deflating them again only costs CPU.
                zip.setLevel(Deflater.NO_COMPRESSION)
                for (image in images) {
                    zip.putNextEntry(ZipEntry("images/${image.name}"))
                    image.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            Log.d(TAG, "Bundled ${images.size} debug image(s) into $zipName.")
            zipName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug bundle $zipName: ${e.message}")
            null
        }
    }
}
