package com.steve1316.automation_library.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import com.steve1316.automation_library.data.SharedData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.graphics.createBitmap

/**
 * Screen recorder that captures frames from an ImageReader at a fixed FPS.
 * Uses a dedicated capture thread for smooth video recording.
 * Does not require a second VirtualDisplay, avoiding the Android 14+ consent issue.
 *
 * This implementation is the result of iterating through several failed approaches:
 * 1. MediaRecorder with a second VirtualDisplay: This triggered a second user consent prompt on Android 14+
 *    and invalidated the original VirtualDisplay used for screenshots.
 * 2. Passive ImageReader capture: Feeding frames from the screenshot process (onFrame) was too choppy
 *    and inconsistent as it depended entirely on the bot's screenshot frequency.
 *
 * This current approach works by actively pulling frames from the shared ImageReader using a
 * dedicated background thread at a fixed 30/60 FPS. This ensures smooth video, avoids the
 * second consent prompt, and allows screenshots to continue functioning normally.
 *
 * @param context The application context.
 * @param width The width of the output video.
 * @param height The height of the output video.
 * @param imageReader The ImageReader to capture frames from.
 */
class ScreenRecorder(private val context: Context, private val width: Int, private val height: Int, private val imageReader: ImageReader) : AutoCloseable {
	companion object {
		private const val tag: String = "${SharedData.loggerTag}ScreenRecorder"
		private const val MIME_TYPE = "video/avc"
		private const val TIMEOUT_US = 10000L
	}

	val outputFile: File
	private val _isRecording = AtomicBoolean(false)
	private val targetFrameRate: Int = SharedData.recordingFrameRate

	// Encoder components.
	private var mediaCodec: MediaCodec? = null
	private var mediaMuxer: MediaMuxer? = null
	private var inputSurface: Surface? = null
	private var trackIndex: Int = -1
	private var muxerStarted: Boolean = false
	private var encoderThread: Thread? = null
	private val frameQueue = LinkedBlockingQueue<Bitmap>(10)

	// Capture thread.
	private var captureThread: Thread? = null

    /**
	 * Checks if recording is active.
	 *
	 * @return True if recording is in progress, false otherwise.
	 */
	val isRecording: Boolean get() = _isRecording.get()

	init {
		if (targetFrameRate != 30 && targetFrameRate != 60) {
			Log.w(tag, "Frame rate $targetFrameRate not in [30, 60]. Using 30 FPS.")
		}

		// Create recordings directory.
		val recordingsDir = File(context.getExternalFilesDir(null), "recordings")
		if (!recordingsDir.exists()) {
			val created = recordingsDir.mkdirs()
			if (created) {
				Log.d(tag, "Created recordings directory: ${recordingsDir.absolutePath}")
			}
		}

		// Generate unique filename.
		val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
		outputFile = File(recordingsDir, "recording_$timestamp.mp4")

		// Initialize encoder and start threads.
		setupEncoder()
		startEncoderThread()
		startCaptureThread()

		_isRecording.set(true)
		MessageLog.i(tag, "Recording started. Output: ${outputFile.absolutePath}")
	}

	/**
	 * Sets up the MediaCodec encoder and MediaMuxer.
	 *
	 * MediaCodec is used to compress raw pixel data into a video stream (H.264).
	 * MediaMuxer is then used to package that stream into a playable MP4 container.
	 */
	private fun setupEncoder() {
		val bitRate = SharedData.recordingBitRate

		val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
			setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
			setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
			setInteger(MediaFormat.KEY_FRAME_RATE, targetFrameRate)
			setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
		}

		mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
			configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
			inputSurface = createInputSurface()
			start()
		}

		mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

		MessageLog.d(tag, "Encoder configured: ${width}x${height}, ${bitRate / 1_000_000}Mbps, ${targetFrameRate}fps, H.264")
	}

	/**
	 * Starts the encoder thread that processes the output from MediaCodec.
	 */
	private fun startEncoderThread() {
		encoderThread = Thread({
			val bufferInfo = MediaCodec.BufferInfo()

			while (_isRecording.get() || !frameQueue.isEmpty()) {
				// Process any pending frames.
				val bitmap = frameQueue.poll()
				if (bitmap != null) {
					drawBitmapToSurface(bitmap)
					bitmap.recycle()
				}

				// Drain encoder output.
				drainEncoder(bufferInfo, false)
			}

			// Drain any remaining output.
			drainEncoder(bufferInfo, true)
		}, "VideoEncoderThread").apply {
			start()
		}
	}

	/**
	 * Starts the dedicated capture thread that grabs frames from ImageReader.
	 */
	private fun startCaptureThread() {
		// Calculate the time each frame should take to maintain the target FPS.
		val frameIntervalMs = 1000L / targetFrameRate

		captureThread = Thread({
			Log.d(tag, "Capture thread started at ${targetFrameRate}fps (${frameIntervalMs}ms interval)")

			while (_isRecording.get()) {
				val startTime = System.currentTimeMillis()

				try {
					// Always acquire the latest image from the reader. This ensures we stay 
					// synchronized with the screen even if the thread is slightly delayed, 
					// as older intermediate frames are automatically dropped by the system.
					val image = imageReader.acquireLatestImage()
					var bitmap: Bitmap? = null

					if (image != null) {
						try {
							// Convert the raw Image data to a usable Bitmap.
							bitmap = imageToBitmap(image)
						} finally {
							image.close()
						}
					}

					if (bitmap != null) {
						// Add the frame to the queue for the encoder thread. 
						// If the queue is full (encoder can't keep up), we drop the frame 
						// and recycle the bitmap to prevent memory leaks.
						if (!frameQueue.offer(bitmap)) {
							// The queue is full so drop the frame.
							bitmap.recycle()
						}
					}
				} catch (e: Exception) {
					Log.w(tag, "Error capturing frame: ${e.message}")
				}

				// Sleep for the remainder of the frame interval to maintain the target FPS.
				val elapsed = System.currentTimeMillis() - startTime
				val sleepTime = frameIntervalMs - elapsed
				if (sleepTime > 0) {
					try {
						Thread.sleep(sleepTime)
					} catch (_: InterruptedException) {
						// Thread was interrupted (likely during shutdown).
						break
					}
				}
			}

			Log.d(tag, "Capture thread stopped")
		}, "VideoCaptureThread").apply {
			start()
		}
	}

	/**
	 * Converts an Image from ImageReader to a Bitmap.
	 *
	 * @param image The Image to convert.
	 * @return The converted Bitmap, or null if conversion failed.
	 */
	private fun imageToBitmap(image: android.media.Image): Bitmap? {
		return try {
			val planes = image.planes
			val buffer = planes[0].buffer
			val pixelStride = planes[0].pixelStride
			val rowStride = planes[0].rowStride
			val rowPadding = rowStride - pixelStride * image.width

			val bitmap = createBitmap(image.width + rowPadding / pixelStride, image.height)
			bitmap.copyPixelsFromBuffer(buffer)
			bitmap
		} catch (e: Exception) {
			Log.w(tag, "Failed to convert image to bitmap: ${e.message}")
			null
		}
	}

	/**
	 * Draws device info text overlay at top-left corner of the canvas while accounting for the resolution scale.
	 *
	 * @param canvas The canvas to draw the overlay on.
	 */
	private fun drawTextOverlay(canvas: Canvas) {
		// Calculate scale factor relative to full resolution.
		val scaleFactor = width.toFloat() / SharedData.displayWidth.toFloat()

		// Build device info text.
		val deviceInfo = "${SharedData.displayWidth}x${SharedData.displayHeight} DPI ${SharedData.displayDPI} Density ${SharedData.displayDensity}"

		// Create text paint with size scaled to resolution.
		val textPaint = Paint().apply {
			color = Color.WHITE
			textSize = 24f * scaleFactor
			isAntiAlias = true
			textAlign = Paint.Align.LEFT
			typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
			setShadowLayer(2f * scaleFactor, 1f * scaleFactor, 1f * scaleFactor, Color.BLACK)
		}

		// Create background paint.
		val bgPaint = Paint().apply {
			color = Color.argb(180, 0, 0, 0)
		}

		// Measure the text dimensions.
		val textWidth = textPaint.measureText(deviceInfo)
		val textHeight = textPaint.descent() - textPaint.ascent()
		val padding = 8f * scaleFactor
		val offset = 10f * scaleFactor

		// Draw semi-transparent background with offset.
		canvas.drawRect(offset, 0f, offset + textWidth + padding * 2, textHeight + padding * 2, bgPaint)

		// Draw the text.
		val xPos = offset + padding
		val yPos = offset + padding - textPaint.ascent()
		canvas.drawText(deviceInfo, xPos, yPos, textPaint)
	}

	/**
	 * Draws a bitmap to the encoder's input surface, scaling to fit if needed.
	 *
	 * @param bitmap The bitmap to draw.
	 */
	private fun drawBitmapToSurface(bitmap: Bitmap) {
		val surface = inputSurface ?: return
		try {
			val canvas = surface.lockCanvas(null)

			// Calculate the source rect to crop out ImageReader padding.
			val srcRect = android.graphics.Rect(0, 0, SharedData.displayWidth, SharedData.displayHeight)
			val dstRect = android.graphics.Rect(0, 0, width, height)

			// Scale the screen content to fit the encoder's target resolution.
			canvas.drawBitmap(bitmap, srcRect, dstRect, null)

			// Draw the text overlay on top of the scaled scene.
			drawTextOverlay(canvas)

			surface.unlockCanvasAndPost(canvas)
		} catch (e: Exception) {
			Log.w(tag, "Failed to draw bitmap to surface: ${e.message}")
		}
	}

	/**
	 * Drains compressed data from the encoder's output buffers and writes it to the muxer.
	 *
	 * @param bufferInfo Metadata about the current buffer (size, timestamp, flags).
	 * @param endOfStream Whether we are finishing the recording and signal EOS.
	 */
	private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
		val encoder = mediaCodec ?: return
		val muxer = mediaMuxer ?: return

		if (endOfStream) {
			// Signal to the encoder that no more data will be provided.
			encoder.signalEndOfInputStream()
		}

		while (true) {
			// Pull an output buffer index from the encoder.
			val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

			when {
				outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
					// No output available yet, exit the loop if we're not finishing.
					if (!endOfStream) break
				}
				outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
					// The encoder format has been finalized. We must add the track and start the muxer before we can write any data.
					if (!muxerStarted) {
						val newFormat = encoder.outputFormat
						trackIndex = muxer.addTrack(newFormat)
						muxer.start()
						muxerStarted = true
						Log.d(tag, "Muxer started with format: $newFormat")
					}
				}
				outputBufferIndex >= 0 -> {
					// We have a valid index to an encoded output buffer.
					val encodedData = encoder.getOutputBuffer(outputBufferIndex) ?: throw RuntimeException("Encoder output buffer was null")

					// If this is a codec configuration buffer (SPS/PPS info), it's already handled by addTrack, so we can ignore its data contents.
					if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
						bufferInfo.size = 0
					}

					// Write the compressed sample to the muxer if it's been started.
					if (bufferInfo.size != 0 && muxerStarted) {
						encodedData.position(bufferInfo.offset)
						encodedData.limit(bufferInfo.offset + bufferInfo.size)
						muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
					}

					// Return the buffer to the encoder so it can be reused for new data.
					encoder.releaseOutputBuffer(outputBufferIndex, false)

					// If we've reached the end of the stream, we're done draining.
					if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
						break
					}
				}
			}
		}
	}

	/**
	 * Stops recording and releases resources.
	 */
	override fun close() {
		if (!_isRecording.getAndSet(false)) {
			return
		}

		Log.d(tag, "Stopping recording...")

		// Stop capture thread first.
		try {
			captureThread?.join(2000)
		} catch (_: InterruptedException) {
			Log.w(tag, "Capture thread join interrupted.")
		}
		captureThread = null

		// Wait for encoder thread to finish.
		try {
			encoderThread?.join(5000)
		} catch (_: InterruptedException) {
			Log.w(tag, "Encoder thread join interrupted.")
		}
		encoderThread = null

		// Release resources.
		try {
			mediaCodec?.stop()
			mediaCodec?.release()
		} catch (e: Exception) {
			Log.w(tag, "Error releasing MediaCodec: ${e.message}")
		}
		mediaCodec = null

		try {
			if (muxerStarted) {
				mediaMuxer?.stop()
			}
			mediaMuxer?.release()
		} catch (e: Exception) {
			Log.w(tag, "Error releasing MediaMuxer: ${e.message}")
		}
		mediaMuxer = null

		inputSurface?.release()
		inputSurface = null

		// Clear any remaining frames.
		while (true) {
			val bitmap = frameQueue.poll() ?: break
			bitmap.recycle()
		}

        MessageLog.i(tag, "Recording saved: ${outputFile.name} at ${outputFile.absolutePath}")
	}
}
