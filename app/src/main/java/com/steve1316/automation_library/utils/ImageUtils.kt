package com.steve1316.automation_library.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import com.steve1316.automation_library.data.SharedData
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.max
import androidx.core.graphics.get
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Utility and helper functions for image processing via CV like OpenCV.
 *
 * @property context The application context.
 */
open class ImageUtils(protected val context: Context) {
	private val tag: String = "${SharedData.loggerTag}ImageUtils"

	protected open var matchMethod: Int = Imgproc.TM_CCOEFF_NORMED
	protected open var matchFilePath: String = ""
	protected open val decimalFormat = DecimalFormat("#.###", DecimalFormatSymbols(Locale.US))

	// Coordinates for swipe behavior to generate new images.
	private var oldXSwipe: Float = 500f
	private var oldYSwipe: Float = 500f
	private var newXSwipe: Float = 500f
	private var newYSwipe: Float = 400f
	private var durationSwipe: Long = 100L

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Use SharedPreferences or something else to set these values to what you want.
	open var confidence: Double = 0.8
	open var confidenceAll: Double = 0.8
	open var debugMode: Boolean = false
	open var customScale: Double = 1.0

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Device configuration
	open val displayWidth: Int = SharedData.displayWidth
	open val displayHeight: Int = SharedData.displayHeight
	open val is1080p: Boolean = (displayWidth == 1080) // 1080p Portrait
	open val is720p: Boolean = (displayWidth == 720) // 720p Portrait
	open val isTabletPortrait: Boolean = (displayWidth == 1600) // Galaxy Tab S7 1600x2560 Portrait Mode
	open val isTabletLandscape: Boolean = (displayWidth == 2560) // Galaxy Tab S7 1600x2560 Landscape Mode
	open val isTablet: Boolean = isTabletPortrait || isTabletLandscape

	// Scales (in terms of 720p and the dimensions from the Galaxy Tab S7)
	protected open val lowerEndScales: MutableList<Double> = generateSequence(0.50) { it + 0.01 }
		.takeWhile { it <= 0.70 }
		.toMutableList()
	protected open val middleEndScales: MutableList<Double> = generateSequence(0.50) { it + 0.01 }
		.takeWhile { it <= 3.00 }
		.toMutableList()
	protected open val tabletScales: MutableList<Double> = generateSequence(1.00) { it + 0.01 }
		.takeWhile { it <= 2.00 }
		.toMutableList()

	// Define template matching regions of the screen.
	open val regionTopHalf: IntArray = intArrayOf(0, 0, displayWidth, displayHeight / 2)
	open val regionBottomHalf: IntArray = intArrayOf(0, displayHeight / 2, displayWidth, displayHeight / 2)
	open val regionMiddle: IntArray = intArrayOf(0, displayHeight / 4, displayWidth, displayHeight / 2)

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// OCR configuration
	protected val googleTextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	protected lateinit var tessBaseAPI: TessBaseAPI
	protected lateinit var tessDigitsBaseAPI: TessBaseAPI
	protected var mostRecent = 1
	protected lateinit var tesseractSourceBitmap: Bitmap

	init {
		// Set the file path to the /files/temp/ folder.
		val tempMatchFilePath: String = context.getExternalFilesDir(null)?.absolutePath + "/temp"
		Log.d(tag, "Setting the temp file path for ImageUtils to \"$tempMatchFilePath\".")
		matchFilePath = tempMatchFilePath
	}

	/**
	 * Wait the specified seconds.
	 *
	 * @param seconds Number of seconds to pause execution.
	 */
	protected open fun wait(seconds: Double) {
		runBlocking {
			delay((seconds * 1000).toLong())
		}
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	data class ScaleConfidenceResult(
		val scale: Double,
		val confidence: Double
	)

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	/**
	 * Starts a test to determine what scales are working on this device by looping through some template images.
	 *
	 * @param mapping A mapping of template image names used to test and their lists of working scales to be modified in-place.
	 * @return A mapping of template image names used to test and their lists of working scales.
	 */
	open fun startTemplateMatchingTest(mapping: MutableMap<String, MutableList<ScaleConfidenceResult>>): MutableMap<String, MutableList<ScaleConfidenceResult>> {
		val defaultConfidence = 0.8
		val testScaleDecimalFormat = DecimalFormat("#.##")
		val testConfidenceDecimalFormat = DecimalFormat("#.##")

		for (key in mapping.keys) {
			val (sourceBitmap, templateBitmap) = getBitmaps(key)

			// First, try the default values of 1.0 for scale and 0.8 for confidence.
			val (success, _) = match(sourceBitmap, templateBitmap!!, key, useSingleScale = true, customConfidence = defaultConfidence, testScale = 1.0)
			if (success) {
				MessageLog.d(tag, "[TEST] Initial test for $key succeeded at the default values.")
				mapping[key]?.add(ScaleConfidenceResult(1.0, defaultConfidence))
				continue // If it works, skip to the next template.
			}

			// If not, try all scale/confidence combinations.
			val scalesToTest = mutableListOf<Double>()
			var scale = 0.5
			while (scale <= 3.0) {
				scalesToTest.add(testScaleDecimalFormat.format(scale).toDouble())
				scale += 0.1
			}

			for (testScale in scalesToTest) {
				var confidence = 0.6
				while (confidence <= 1.0) {
					val formattedConfidence = testConfidenceDecimalFormat.format(confidence).toDouble()
					val (testSuccess, _) = match(sourceBitmap, templateBitmap, key, useSingleScale = true, customConfidence = formattedConfidence, testScale = testScale)
					if (testSuccess) {
						MessageLog.d(tag, "[TEST] Test for $key succeeded at scale $testScale and confidence $formattedConfidence.")
						mapping[key]?.add(ScaleConfidenceResult(testScale, formattedConfidence))
					}
					confidence += 0.1
				}
			}
		}

		return mapping
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Template matching

	/**
	 * Match between the source Bitmap from /files/temp/ and the template Bitmap from the assets folder.
	 *
	 * @param sourceBitmap Bitmap from the /files/temp/ folder.
	 * @param templateBitmap Bitmap from the assets folder.
	 * @param templateName Name of the template image to use in debugging log messages.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param useSingleScale Whether to use only the single custom scale or to use a range based off of it. Otherwise, it will use the customScale value. Defaults to false.
	 * @param customConfidence Specify a custom confidence. Defaults to the confidence set in the app's settings.
	 * @param testScale Scale used by testing. Defaults to 0.0 which will fallback to the other scale conditions.
	 * @return Pair of (success: Boolean, location: Point?) where success indicates if a match was found and location contains the match coordinates if found.
	 */
	protected open fun match(sourceBitmap: Bitmap, templateBitmap: Bitmap, templateName: String, region: IntArray = intArrayOf(0, 0, 0, 0), useSingleScale: Boolean = false, customConfidence: Double = 0.0, testScale: Double = 0.0): Pair<Boolean, Point?> {
		// If a custom region was specified, crop the source screenshot.
		val srcBitmap = if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
			// Validate region bounds to prevent IllegalArgumentException with creating a crop area that goes beyond the source Bitmap.
			val x = max(0, region[0].coerceAtMost(sourceBitmap.width))
			val y = max(0, region[1].coerceAtMost(sourceBitmap.height))
			val width = region[2].coerceAtMost(sourceBitmap.width - x)
			val height = region[3].coerceAtMost(sourceBitmap.height - y)

			createSafeBitmap(sourceBitmap, x, y, width, height, "match region crop") ?: sourceBitmap
		} else {
			sourceBitmap
		}

		val setConfidence: Double = if (customConfidence == 0.0) {
			confidence
		} else {
			customConfidence
		}

		// Scale images if the device is not 1080p which is supported by default.
		val scales: MutableList<Double> = when {
			testScale != 0.0 -> {
				mutableListOf(testScale)
			}
			customScale != 1.0 && !useSingleScale -> {
				mutableListOf(customScale - 0.02, customScale - 0.01, customScale, customScale + 0.01, customScale + 0.02, customScale + 0.03, customScale + 0.04)
			}
			customScale != 1.0 && useSingleScale -> {
				mutableListOf(customScale)
			}
			is720p -> {
				lowerEndScales.toMutableList()
			}
			!is720p && !is1080p && !isTablet -> {
				middleEndScales.toMutableList()
			}
			isTablet -> {
				tabletScales.toMutableList()
			}
			else -> {
				mutableListOf(1.0)
			}
		}

		while (scales.isNotEmpty()) {
			if (!BotService.isRunning) {
				throw InterruptedException()
			}

			val newScale: Double = decimalFormat.format(scales.removeAt(0)).toDouble()

			val tmp: Bitmap = if (newScale != 1.0) {
				templateBitmap.scale((templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt())
			} else {
				templateBitmap
			}

			// Create the Mats of both source and template images.
			val sourceMat = Mat()
			val templateMat = Mat()
			Utils.bitmapToMat(srcBitmap, sourceMat)
			Utils.bitmapToMat(tmp, templateMat)

			// Clamp template dimensions to source dimensions if template is too large.
			val clampedTemplateMat = if (templateMat.cols() > sourceMat.cols() || templateMat.rows() > sourceMat.rows()) {
				Log.d(tag, "Image sizes for match assertion failed - sourceMat: ${sourceMat.size()}, templateMat: ${templateMat.size()}")
				// Create a new Mat with clamped dimensions.
				val clampedWidth = minOf(templateMat.cols(), sourceMat.cols())
				val clampedHeight = minOf(templateMat.rows(), sourceMat.rows())
				Mat(templateMat, Rect(0, 0, clampedWidth, clampedHeight))
			} else {
				templateMat
			}

			// Make the Mats grayscale for the source and the template.
			Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
			Imgproc.cvtColor(clampedTemplateMat, clampedTemplateMat, Imgproc.COLOR_BGR2GRAY)

			// Create the result matrix.
			val resultColumns: Int = sourceMat.cols() - clampedTemplateMat.cols() + 1
			val resultRows: Int = sourceMat.rows() - clampedTemplateMat.rows() + 1
			val resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)

			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

			var matchLocation = Point()
			var matchCheck = false

			// Format minVal or maxVal.
			val minVal: Double = decimalFormat.format(mmr.minVal).toDouble()
			val maxVal: Double = decimalFormat.format(mmr.maxVal).toDouble()

			// Depending on which matching method was used, the algorithms determine which location was the best.
			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				matchLocation = mmr.minLoc
				matchCheck = true
				if (debugMode) {
					MessageLog.d(tag, "[DEBUG] Match found for \"$templateName\" with $minVal <= ${1.0 - setConfidence} at Point $matchLocation using scale: $newScale.")
				}
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				matchLocation = mmr.maxLoc
				matchCheck = true
				if (debugMode) {
					MessageLog.d(tag, "[DEBUG] Match found for \"$templateName\" with $maxVal >= $setConfidence at Point $matchLocation using scale: $newScale.")
				}
			} else {
				if (debugMode) {
					if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED)) {
						MessageLog.d(tag, "[DEBUG] Match not found for \"$templateName\" with $maxVal not >= $setConfidence at Point ${mmr.maxLoc} using scale $newScale.")
					} else {
						MessageLog.d(tag, "[DEBUG] Match not found for \"$templateName\" with $minVal not <= ${1.0 - setConfidence} at Point ${mmr.minLoc} using scale $newScale.")
					}
				}
			}

			if (matchCheck) {
				if (debugMode) {
					// Draw a rectangle around the supposed best matching location and then save the match into a file in /files/temp/ directory. This is for debugging purposes to see if this
					// algorithm found the match accurately or not.
					if (matchFilePath != "") {
						Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 128.0, 0.0), 10)
						Imgcodecs.imwrite("$matchFilePath/match.png", sourceMat)
					}
				}

				// Center the coordinates so that any tap gesture would be directed at the center of that match location instead of the default
				// position of the top left corner of the match location.
				matchLocation.x += (templateMat.cols() / 2)
				matchLocation.y += (templateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
					matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
				}

				return Pair(true, matchLocation)
			}

			sourceMat.release()
			templateMat.release()
			clampedTemplateMat.release()
			resultMat.release()
		}

		return Pair(false, null)
	}

	/**
	 * Search through the whole source screenshot for all matches to the template image.
	 *
	 * @param sourceBitmap Bitmap from the /files/temp/ folder.
	 * @param templateBitmap Bitmap from the assets folder.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param customConfidence Specify a custom confidence. Defaults to the confidence set in the app's settings.
	 * @return ArrayList of Point objects that represents the matches found on the source screenshot.
	 */
	protected open fun matchAll(sourceBitmap: Bitmap, templateBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), customConfidence: Double = 0.0): ArrayList<Point> {
		// Create a local matchLocations list for this method
		var matchLocation: Point
        val matchLocations = arrayListOf<Point>()

		// If a custom region was specified, crop the source screenshot.
		val srcBitmap = if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
			// Validate region bounds to prevent IllegalArgumentException with creating a crop area that goes beyond the source Bitmap.
			val x = max(0, region[0].coerceAtMost(sourceBitmap.width))
			val y = max(0, region[1].coerceAtMost(sourceBitmap.height))
			val width = region[2].coerceAtMost(sourceBitmap.width - x)
			val height = region[3].coerceAtMost(sourceBitmap.height - y)

			createSafeBitmap(sourceBitmap, x, y, width, height, "matchAll region crop") ?: sourceBitmap
		} else {
			sourceBitmap
		}

		// Scale images if the device is not 1080p which is supported by default.
		val scales: MutableList<Double> = when {
			customScale != 1.0 -> {
				mutableListOf(customScale - 0.02, customScale - 0.01, customScale, customScale + 0.01, customScale + 0.02, customScale + 0.03, customScale + 0.04)
			}
			is720p -> {
				lowerEndScales.toMutableList()
			}
			!is720p && !is1080p && !isTablet -> {
				middleEndScales.toMutableList()
			}
			isTablet -> {
				tabletScales.toMutableList()
			}
			else -> {
				mutableListOf(1.0)
			}
		}

		val setConfidence: Double = if (customConfidence == 0.0) {
			confidenceAll
		} else {
			customConfidence
		}

		var matchCheck = false
		var newScale = 0.0
		val sourceMat = Mat()
		val templateMat = Mat()
		var resultMat = Mat()
		var clampedTemplateMat: Mat? = null

		// Set templateMat at whatever scale it found the very first match for the next while loop.
		while (!matchCheck && scales.isNotEmpty()) {
			if (!BotService.isRunning) {
				throw InterruptedException()
			}

			newScale = decimalFormat.format(scales.removeAt(0)).toDouble()

			val tmp: Bitmap = if (newScale != 1.0) {
				templateBitmap.scale((templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt())
			} else {
				templateBitmap
			}

			// Create the Mats of both source and template images.
			Utils.bitmapToMat(srcBitmap, sourceMat)
			Utils.bitmapToMat(tmp, templateMat)

			// Clamp template dimensions to source dimensions if template is too large.
			clampedTemplateMat = if (templateMat.cols() > sourceMat.cols() || templateMat.rows() > sourceMat.rows()) {
				Log.d(tag, "Image sizes for matchAll assertion failed - sourceMat: ${sourceMat.size()}, templateMat: ${templateMat.size()}")
				// Create a new Mat with clamped dimensions.
				val clampedWidth = minOf(templateMat.cols(), sourceMat.cols())
				val clampedHeight = minOf(templateMat.rows(), sourceMat.rows())
				Mat(templateMat, Rect(0, 0, clampedWidth, clampedHeight))
			} else {
				templateMat
			}

			// Make the Mats grayscale for the source and the template.
			Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
			Imgproc.cvtColor(clampedTemplateMat, clampedTemplateMat, Imgproc.COLOR_BGR2GRAY)

			// Create the result matrix.
			val resultColumns: Int = sourceMat.cols() - clampedTemplateMat.cols() + 1
			val resultRows: Int = sourceMat.rows() - clampedTemplateMat.rows() + 1
			if (resultColumns < 0 || resultRows < 0) {
				break
			}

			resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)

			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

			// Depending on which matching method was used, the algorithms determine which location was the best.
			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				matchLocation = mmr.minLoc
				matchCheck = true

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + clampedTemplateMat.cols(), matchLocation.y + clampedTemplateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

				// Center the location coordinates and then save it.
				matchLocation.x += (clampedTemplateMat.cols() / 2)
				matchLocation.y += (clampedTemplateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
					matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
				}

				matchLocations.add(matchLocation)
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				matchLocation = mmr.maxLoc
				matchCheck = true

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + clampedTemplateMat.cols(), matchLocation.y + clampedTemplateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

				// Center the location coordinates and then save it.
				matchLocation.x += (clampedTemplateMat.cols() / 2)
				matchLocation.y += (clampedTemplateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
					matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
				}

				matchLocations.add(matchLocation)
			}
		}

		// Loop until all other matches are found and break out when there are no more to be found.
		while (matchCheck) {
			if (!BotService.isRunning) {
				throw InterruptedException()
			}

			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

			// Format minVal or maxVal.
			val minVal: Double = decimalFormat.format(mmr.minVal).toDouble()
			val maxVal: Double = decimalFormat.format(mmr.maxVal).toDouble()

			if (clampedTemplateMat != null && (matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				val tempMatchLocation: Point = mmr.minLoc

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, tempMatchLocation, Point(tempMatchLocation.x + clampedTemplateMat.cols(), tempMatchLocation.y + clampedTemplateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

				if (debugMode) {
					MessageLog.d(tag, "[DEBUG] Match found with $minVal <= ${1.0 - setConfidence} at Point $tempMatchLocation with scale: $newScale.")
					Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
				}

				// Center the location coordinates and then save it.
				tempMatchLocation.x += (clampedTemplateMat.cols() / 2)
				tempMatchLocation.y += (clampedTemplateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					tempMatchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + tempMatchLocation.x))
					tempMatchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + tempMatchLocation.y))
				}

				if (!matchLocations.contains(tempMatchLocation) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y)) &&
					!matchLocations.contains(Point(tempMatchLocation.x, tempMatchLocation.y + 1.0)) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y + 1.0))
				) {
					matchLocations.add(tempMatchLocation)
				} else if (matchLocations.contains(tempMatchLocation)) {
					// Prevent infinite looping if the same location is found over and over again.
					break
				}
			} else if (clampedTemplateMat != null && (matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				val tempMatchLocation: Point = mmr.maxLoc

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, tempMatchLocation, Point(tempMatchLocation.x + clampedTemplateMat.cols(), tempMatchLocation.y + clampedTemplateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

				if (debugMode) {
					MessageLog.d(tag, "[DEBUG] Match found with $maxVal >= $setConfidence at Point $tempMatchLocation with scale: $newScale.")
					Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
				}

				// Center the location coordinates and then save it.
				tempMatchLocation.x += (clampedTemplateMat.cols() / 2)
				tempMatchLocation.y += (clampedTemplateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					tempMatchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + tempMatchLocation.x))
					tempMatchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + tempMatchLocation.y))
				}

				if (!matchLocations.contains(tempMatchLocation) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y)) &&
					!matchLocations.contains(Point(tempMatchLocation.x, tempMatchLocation.y + 1.0)) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y + 1.0))
				) {
					matchLocations.add(tempMatchLocation)
				} else if (matchLocations.contains(tempMatchLocation)) {
					// Prevent infinite looping if the same location is found over and over again.
					break
				}
			} else {
				val tempMatchLocation = if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
					mmr.minLoc
				} else {
					mmr.maxLoc
				}

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, tempMatchLocation, Point(tempMatchLocation.x + templateMat.cols(), tempMatchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

				if (debugMode) {
					if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal > (1.0 - setConfidence)) {
						MessageLog.d(tag, "[DEBUG] Match not found with ${mmr.minVal} > ${(1.0 - setConfidence)} at Point $tempMatchLocation with scale: $newScale.")
					} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal < setConfidence) {
						MessageLog.d(tag, "[DEBUG] Match not found with ${mmr.maxVal} < $setConfidence at Point $tempMatchLocation with scale: $newScale.")
					}

					Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
				}

				break
			}
		}

		sourceMat.release()
		templateMat.release()
		clampedTemplateMat?.release()
		resultMat.release()

		return matchLocations
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Relative coordinate translation

	/**
	 * Convert absolute x-coordinate on 1080p to relative coordinate on different resolutions for the width.
	 *
	 * @param oldX The old absolute x-coordinate based off of the 1080p resolution.
	 * @return The new relative x-coordinate based off of the current resolution.
	 */
	open fun relWidth(oldX: Int): Int {
		return if (is1080p) {
			oldX
		} else {
			(oldX.toDouble() * (displayWidth.toDouble() / 1080.0)).toInt()
		}
	}

	/**
	 * Convert absolute y-coordinate on 1080p to relative coordinate on different resolutions for the height.
	 *
	 * @param oldY The old absolute y-coordinate based off of the 1080p resolution.
	 * @return The new relative y-coordinate based off of the current resolution.
	 */
	open fun relHeight(oldY: Int): Int {
		return if (is1080p) {
			oldY
		} else {
			(oldY.toDouble() * (displayHeight.toDouble() / 2340.0)).toInt()
		}
	}

	/**
	 * Helper function to calculate the x-coordinate with relative offset.
	 *
	 * @param baseX The base x-coordinate.
	 * @param offset The offset to add/subtract from the base coordinate and to make relative to.
	 * @return The calculated relative x-coordinate.
	 */
	open fun relX(baseX: Double, offset: Int): Int {
		return baseX.toInt() + relWidth(offset)
	}

	/**
	 * Helper function to calculate relative y-coordinate with relative offset.
	 *
	 * @param baseY The base y-coordinate.
	 * @param offset The offset to add/subtract from the base coordinate and to make relative to.
	 * @return The calculated relative y-coordinate.
	 */
	open fun relY(baseY: Double, offset: Int): Int {
		return baseY.toInt() + relHeight(offset)
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Fetching Bitmaps

	/**
	 * Open the source and template image files and return Bitmaps for them. Also executes swipes in order to generate new images if necessary.
	 *
	 * @param templateName File name of the template image.
	 * @param templatePath Path name of the subfolder in /assets/ that the template image is in. Defaults to the default template subfolder path name.
	 * @return A Pair of source and template Bitmaps.
	 */
	open fun getBitmaps(templateName: String, templatePath: String = SharedData.templateSubfolderPathName): Pair<Bitmap, Bitmap?> {
		var sourceBitmap: Bitmap? = null

		// Keep swiping a little bit up and down to trigger a new image for ImageReader to grab.
		while (sourceBitmap == null) {
			sourceBitmap = MediaProjectionService.takeScreenshotNow()

			if (sourceBitmap == null) {
				MyAccessibilityService.getInstance().swipe(oldXSwipe, oldYSwipe, newXSwipe, newYSwipe, durationSwipe)
				MyAccessibilityService.getInstance().swipe(oldXSwipe, newYSwipe, newXSwipe, oldYSwipe, durationSwipe)
				wait(0.25)
			}
		}

		var templateBitmap: Bitmap?
		val newTemplatePath = if (templatePath.last() != '/') {
			"$templatePath/"
		} else {
			templatePath
		}

		// Get the Bitmap from the template image file inside the specified folder.
		val assetFilePath = "${newTemplatePath}$templateName.${SharedData.templateImageExt}"
		context.assets?.open(assetFilePath).use { inputStream ->
			// Get the Bitmap from the template image file and then start matching.
			templateBitmap = BitmapFactory.decodeStream(inputStream)
		}

		return if (templateBitmap != null) {
			Pair(sourceBitmap, templateBitmap)
		} else {
			if (debugMode) {
				MessageLog.e(tag, "[ERROR] The template Bitmap is null.")
			}

			Pair(sourceBitmap, null)
		}
	}

	/**
	 * Safely creates a bitmap with bounds checking to prevent IllegalArgumentException.
	 * Clamps individual dimensions to source bitmap bounds if they exceed limits.
	 *
	 * @param sourceBitmap The source bitmap to crop from.
	 * @param x The x coordinate for the crop.
	 * @param y The y coordinate for the crop.
	 * @param width The width of the crop.
	 * @param height The height of the crop.
	 * @param context String describing the context for error logging.
	 * @return The cropped bitmap or null if bounds are still invalid after clamping.
	 */
	open fun createSafeBitmap(sourceBitmap: Bitmap, x: Int, y: Int, width: Int, height: Int, context: String): Bitmap? {
		// Clamp individual dimensions to source bitmap bounds.
		val clampedX = x.coerceIn(0, sourceBitmap.width)
		val clampedY = y.coerceIn(0, sourceBitmap.height)
		val clampedWidth = width.coerceIn(1, sourceBitmap.width - clampedX)
		val clampedHeight = height.coerceIn(1, sourceBitmap.height - clampedY)

		// Check if any dimensions were clamped and log a warning.
		if (x != clampedX || y != clampedY || width != clampedWidth || height != clampedHeight) {
			MessageLog.w(tag, "[WARNING] Clamped bounds for $context: original(x=$x, y=$y, width=$width, height=$height) -> clamped(x=$clampedX, y=$clampedY, width=$clampedWidth, height=$clampedHeight), sourceBitmap=${sourceBitmap.width}x${sourceBitmap.height}")
		}

		// Final validation to ensure the clamped dimensions are still valid.
		if (clampedX < 0 || clampedY < 0 || clampedWidth <= 0 || clampedHeight <= 0 ||
			clampedX + clampedWidth > sourceBitmap.width || clampedY + clampedHeight > sourceBitmap.height) {
			MessageLog.e(tag, "[ERROR] Invalid bounds for $context after clamping: x=$clampedX, y=$clampedY, width=$clampedWidth, height=$clampedHeight, sourceBitmap=${sourceBitmap.width}x${sourceBitmap.height}")
			return null
		}

		return Bitmap.createBitmap(sourceBitmap, clampedX, clampedY, clampedWidth, clampedHeight)
	}

	/**
	 * Adjusts the coordinates for the swiping behavior to generate a new image for getBitmaps().
	 *
	 * @param oldX The x coordinate of the old position. Defaults to 500f.
	 * @param oldY The y coordinate of the old position. Defaults to 500f.
	 * @param newX The x coordinate of the new position. Defaults to 500f.
	 * @param newY The y coordinate of the new position. Defaults to 400f
	 * @param duration How long the swipe should take. Defaults to 100L.
	 */
	protected open fun adjustTriggerNewImageSwipeBehavior(oldX: Float, oldY: Float, newX: Float, newY: Float, duration: Long = 100L) {
		oldXSwipe = oldX
		oldYSwipe = oldY
		newXSwipe = newX
		newYSwipe = newY
		durationSwipe = duration
	}

	/**
	 * Acquire the Bitmap for only the source screenshot.
	 *
	 * @return Bitmap of the source screenshot.
	 */
	protected open fun getSourceBitmap(): Bitmap {
		while (true) {
			val bitmap = MediaProjectionService.takeScreenshotNow(saveImage = debugMode)
			if (bitmap != null) {
				return bitmap
			} else {
				if (debugMode) MessageLog.w(tag, "[DEBUG] Source bitmap is null. Moving the screen a bit and waiting a second before trying again.")

				MyAccessibilityService.getInstance().swipe(oldXSwipe, oldYSwipe, newXSwipe, newYSwipe, durationSwipe)
				MyAccessibilityService.getInstance().swipe(oldXSwipe, newYSwipe, newXSwipe, oldYSwipe, durationSwipe)
				wait(0.25)
			}
		}
	}

	/**
	 * Acquire a Bitmap from the URL image file.
	 *
	 * @return A new Bitmap.
	 */
	protected open fun getBitmapFromURL(url: URL): Bitmap {
		if (debugMode) {
			MessageLog.d(tag, "\n[DEBUG] Starting process to create a Bitmap from the image url: $url")
		}

		// Open up a HTTP connection to the URL.
		val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
		connection.doInput = true
		connection.connect()

		// Download the image from the URL.
		val input: InputStream = connection.inputStream
		connection.disconnect()

		return BitmapFactory.decodeStream(input)
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Finder functions

	/**
	 * Finds the location of the specified image inside assets.
	 *
	 * @param templateName File name of the template image.
	 * @param tries Number of tries before failing. Defaults to 5.
	 * @param confidence Custom confidence for template matching. Defaults to 0.0 which will use the confidence set in the app's settings.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param suppressError Whether or not to suppress saving error messages to the log. Defaults to false.
	 * @param testMode Flag to test and get a valid scale for device compatibility.
	 * @return Pair object consisting of the Point object containing the location of the match and the source screenshot. Can be null.
	 */
	open fun findImage(
		templateName: String, tries: Int = 5, confidence: Double = 0.0, region: IntArray = intArrayOf(0, 0, 0, 0),
		suppressError: Boolean = false, testMode: Boolean = false
	): Pair<Point?, Bitmap> {
		var numberOfTries = tries

		if (debugMode) {
			MessageLog.d(tag, "\n[DEBUG] Starting process to find the ${templateName.uppercase()} image...")
		}

		// If Test Mode is enabled, prepare for it by setting initial scale.
		if (testMode) {
			numberOfTries = 80
			customScale = 0.20
		}

		val (sourceBitmap, templateBitmap) = getBitmaps(templateName)

		while (numberOfTries > 0) {
			if (templateBitmap != null) {
				val (resultFlag, matchLocation) = match(sourceBitmap, templateBitmap, templateName, region, useSingleScale = true, customConfidence = confidence)
				if (!resultFlag) {
					if (testMode) {
						// Increment scale by 0.01 until a match is found if Test Mode is enabled.
						customScale += 0.01
						customScale = decimalFormat.format(customScale).toDouble()
					}

					numberOfTries -= 1
					if (numberOfTries <= 0) {
						if (!suppressError) {
							MessageLog.w(tag, "[WARNING] Failed to find the ${templateName.uppercase()} image.")
						}

						break
					}

					if (!testMode) {
						wait(0.1)
					}
				} else {
					if (testMode) {
						// Create a range of scales for user recommendation.
						val scale0: Double = decimalFormat.format(customScale).toDouble()
						val scale1: Double = decimalFormat.format(scale0 + 0.01).toDouble()
						val scale2: Double = decimalFormat.format(scale0 + 0.02).toDouble()
						val scale3: Double = decimalFormat.format(scale0 + 0.03).toDouble()
						val scale4: Double = decimalFormat.format(scale0 + 0.04).toDouble()

						MessageLog.i(
                            tag,
							"[SUCCESS] Found the ${templateName.uppercase()} at $matchLocation with scale $scale0.\n\nRecommended to use scale $scale1, $scale2, $scale3 or $scale4.",
						)
					} else if (debugMode) {
						MessageLog.d(tag, "[SUCCESS] Found the ${templateName.uppercase()} at $matchLocation.")
					}

					return Pair(matchLocation, sourceBitmap)
				}
			}
		}

		return Pair(null, sourceBitmap)
	}

	/**
	 * Finds all occurrences of the specified image.
	 *
	 * @param templateName File name of the template image.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param confidence Accuracy threshold for matching. Defaults to 0.0 which will use the confidence set in the app's settings.
	 * @return An ArrayList of Point objects containing all the occurrences of the specified image or null if not found.
	 */
	open fun findAll(templateName: String, region: IntArray = intArrayOf(0, 0, 0, 0), confidence: Double = 0.0): ArrayList<Point> {
		if (debugMode) {
			MessageLog.d(tag, "\n[DEBUG] Starting process to find all ${templateName.uppercase()} images...")
		}

		val (sourceBitmap, templateBitmap) = getBitmaps(templateName)

		if (templateBitmap != null) {
			val matchLocations = matchAll(sourceBitmap, templateBitmap, region = region, customConfidence = confidence)

			// Sort the match locations by ascending x and y coordinates.
			matchLocations.sortBy { it.x }
			matchLocations.sortBy { it.y }

			if (debugMode) {
				MessageLog.d(tag, "[DEBUG] Found match locations for $templateName: $matchLocations.")
			} else {
				Log.d(tag, "[DEBUG] Found match locations for $templateName: $matchLocations.")
			}

			return matchLocations
		}

		return arrayListOf()
	}

	/**
	 * Waits for the specified image to vanish from the screen.
	 *
	 * @param templateName File name of the template image.
	 * @param timeout Amount of time to wait before timing out. Default is 5 seconds.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param suppressError Whether or not to suppress saving error messages to the log.
	 * @return True if the specified image vanished from the screen. False otherwise.
	 */
	open fun waitVanish(templateName: String, timeout: Int = 5, region: IntArray = intArrayOf(0, 0, 0, 0), suppressError: Boolean = false): Boolean {
		MessageLog.i(tag, "[INFO] Now waiting for $templateName to vanish from the screen...")

		var remaining = timeout
		if (findImage(templateName, tries = 1, region = region, suppressError = suppressError).first == null) {
			return true
		} else {
			while (findImage(templateName, tries = 1, region = region, suppressError = suppressError).first != null) {
				wait(1.0)
				remaining -= 1
				if (remaining <= 0) {
					return false
				}
			}

			return true
		}
	}

	/**
	 * Pixel search by its RGB value.
	 *
	 * @param bitmap Bitmap of the image to search for the specific pixel.
	 * @param red The pixel's Red value.
	 * @param blue The pixel's Blue value.
	 * @param green The pixel's Green value.
	 * @return A Pair object of the (x,y) coordinates on the Bitmap for the matched pixel.
	 */
	open fun pixelSearch(bitmap: Bitmap, red: Int, blue: Int, green: Int, suppressError: Boolean = false): Pair<Int, Int> {
		if (debugMode) {
			MessageLog.d(tag, "\n[DEBUG] Starting process to find the specified pixel ($red, $blue, $green)...")
		}

		var x = 0
		var y = 0

		// Iterate through each pixel in the Bitmap and compare RGB values.
		while (x < bitmap.width) {
			while (y < bitmap.height) {
				val pixel = bitmap[x, y]

				if (Color.red(pixel) == red && Color.blue(pixel) == blue && Color.green(pixel) == green) {
					if (debugMode) {
						MessageLog.d(tag, "[DEBUG] Found matching pixel at ($x, $y).")
					}

					return Pair(x, y)
				}

				y++
			}

			x++
			y = 0
		}

		if (!suppressError) {
			MessageLog.w(tag, "[WARNING] Failed to find the specified pixel ($red, $blue, $green).")
		}

		return Pair(-1, -1)
	}

	/**
	 * Check if the color at the specified coordinates matches the given RGB value.
	 *
	 * @param x X coordinate to check.
	 * @param y Y coordinate to check.
	 * @param rgb Expected RGB values as red, blue and green (0-255).
	 * @param tolerance Tolerance for color matching (0-255). Defaults to 0 for exact match.
	 * @return True if the color at the coordinates matches the expected RGB values within tolerance, false otherwise.
	 */
	open fun checkColorAtCoordinates(x: Int, y: Int, rgb: IntArray, tolerance: Int = 0): Boolean {
		val sourceBitmap = getSourceBitmap()

		// Check if coordinates are within bounds.
		if (x < 0 || y < 0 || x >= sourceBitmap.width || y >= sourceBitmap.height) {
			if (debugMode) MessageLog.w(tag, "[WARNING] Coordinates ($x, $y) are out of bounds for bitmap size ${sourceBitmap.width}x${sourceBitmap.height}")
			return false
		}

		// Get the pixel color at the specified coordinates.
		val pixel = sourceBitmap[x, y]

		// Extract RGB values from the pixel.
		val actualRed = Color.red(pixel)
		val actualGreen = Color.green(pixel)
		val actualBlue = Color.blue(pixel)

		// Check if the colors match within the specified tolerance.
		val redMatch = abs(actualRed - rgb[0]) <= tolerance
		val greenMatch = abs(actualGreen - rgb[1]) <= tolerance
		val blueMatch = abs(actualBlue - rgb[2]) <= tolerance

		if (debugMode) {
			MessageLog.d(tag, "[DEBUG] Color check at ($x, $y): Expected RGB(${rgb[0]}, ${rgb[1]}, ${rgb[2]}), Actual RGB($actualRed, $actualGreen, $actualBlue), Match: ${redMatch && greenMatch && blueMatch}")
		}

		return redMatch && greenMatch && blueMatch
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// OCR with Tesseract and Google ML Kit

	/**
	 * Checks for Tesseract initialization and if it was not, initialize it.
	 *
	 * @param traineddataFileName The file name including its extension for the .traineddata of Tesseract.
	 * @return True if both Tesseract client objects were initialized.
	 */
	protected fun checkTesseractInitialization(traineddataFileName: String): Boolean {
		return if (!this::tessBaseAPI.isInitialized || !this::tessDigitsBaseAPI.isInitialized) {
			MessageLog.w(tag, "[WARNING] Check failed for Tesseract initialization. Starting process to initialize Tesseract now...")
			initTesseract(traineddataFileName)
		} else {
			true
		}
	}

	/**
	 * Initialize Tesseract for future OCR operations. Make sure to put your .traineddata inside the root of the /assets/ folder.
	 *
	 * @param traineddataFileName The file name including its extension for the .traineddata of Tesseract.
	 * @return True if both Tesseract client objects were initialized.
	 */
	protected fun initTesseract(traineddataFileName: String): Boolean {
		tessBaseAPI = TessBaseAPI()
		tessDigitsBaseAPI = TessBaseAPI()

		val fileName = if (!traineddataFileName.contains(".traineddata")) {
			MessageLog.d(tag, "[TESSERACT] Developer did not include the correct extension when initializing Tesseract so appending it for them.")
			"$traineddataFileName.traineddata"
		} else traineddataFileName

		val externalFilesDir: File? = context.getExternalFilesDir(null)
		val tempDirectory: String = externalFilesDir?.absolutePath + "/tesseract/tessdata/"
		val newTempDirectory = File(tempDirectory)

		// If the /files/temp/ folder does not exist, create it.
		if (!newTempDirectory.exists()) {
			val successfullyCreated: Boolean = newTempDirectory.mkdirs()

			// If the folder was not able to be created for some reason, log the error and stop the MediaProjection Service.
			if (!successfullyCreated) {
				MessageLog.e(tag, "[ERROR] Failed to create the /files/tesseract/tessdata/ folder.")
			} else {
				MessageLog.d(tag, "[TESSERACT] Successfully created /files/tesseract/tessdata/ folder.")
			}
		} else {
			MessageLog.d(tag, "[TESSERACT] /files/tesseract/tessdata/ folder already exists.")
		}

		// If the .traineddata is not in the application folder, copy it there from assets.
		val trainedDataPath = File(tempDirectory, fileName)
		if (!trainedDataPath.exists()) {
			try {
				MessageLog.d(tag, "[TESSERACT] Starting Tesseract initialization.")
				val input = context.assets.open(fileName)

				val output = FileOutputStream("$tempDirectory/$fileName")
				Log.d(tag, "Moving $fileName to $tempDirectory/$fileName for Tesseract initialization.")

				val buffer = ByteArray(1024)
				var read: Int
				while (input.read(buffer).also { read = it } != -1) {
					output.write(buffer, 0, read)
				}

				input.close()
				output.flush()
				output.close()

				MessageLog.d(tag, "[TESSERACT] Finished Tesseract initialization.")
			} catch (e: IOException) {
				MessageLog.e(tag, "[ERROR] Tesseract I/O Exception: ${e.stackTraceToString()}")
			}
		}

		tessBaseAPI.init(context.getExternalFilesDir(null)?.absolutePath + "/tesseract/", "eng")
		tessDigitsBaseAPI.init(context.getExternalFilesDir(null)?.absolutePath + "/tesseract/", "eng")

		tessDigitsBaseAPI.setVariable(TessBaseAPI.VAR_CHAR_BLACKLIST, "!?@#$%&*()<>_-+=/:;'\\\"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
		tessDigitsBaseAPI.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789")
		tessDigitsBaseAPI.setVariable("classify_bln_numeric_mode", "1")

		// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
		tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
		tessDigitsBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

		return this::tessBaseAPI.isInitialized && this::tessDigitsBaseAPI.isInitialized
	}

	/**
	 * Perform OCR text detection along with some image manipulation via thresholding to make the cropped screenshot black and white using OpenCV.
	 *
	 * @param cropRegion The region consisting of (x, y, width, height) of the cropped region.
	 * @param thresh Performs thresholding on the cropped region. Defaults to true.
	 * @param threshold Minimum threshold value. Defaults to 130.
	 * @param thresholdMax Maximum threshold value. Defaults to 255.
	 * @param reuseSourceBitmap Reuses the source bitmap from the previous call. Defaults to false which will retake the source bitmap.
	 * @param detectDigitsOnly True if detection should focus on digits only.
	 *
	 * @return The detected String in the cropped region.
	 */
	open fun findText(
		cropRegion: IntArray, thresh: Boolean = true, threshold: Double = 130.0, thresholdMax: Double = 255.0, reuseSourceBitmap: Boolean = false, detectDigitsOnly: Boolean = false
	): String {
		val startTime: Long = System.currentTimeMillis()
		var result = "empty!"

		val sourceBitmap: Bitmap = if (!reuseSourceBitmap) {
			getSourceBitmap()
		} else {
			tesseractSourceBitmap
		}

		if (debugMode) MessageLog.d(tag, "\n[TEXT_DETECTION] Starting text detection now...")

		// Crop and convert the source bitmap to Mat.
		val (x, y, width, height) = cropRegion
		val croppedBitmap = Bitmap.createBitmap(sourceBitmap, x, y, width, height)
		val cvImage = Mat()
		Utils.bitmapToMat(croppedBitmap, cvImage)

		// Save the cropped image before converting it to black and white in order to troubleshoot issues related to differing device sizes and cropping.
		if (debugMode) {
			Imgcodecs.imwrite("$matchFilePath/ocr_${mostRecent}_cropped.png", cvImage)
		}

		// Grayscale the cropped image.
		val grayImage = Mat()
		Imgproc.cvtColor(cvImage, grayImage, Imgproc.COLOR_RGB2GRAY)

		// Thresh the grayscale cropped image to make black and white.
		val resultBitmap: Bitmap = croppedBitmap
		if (thresh) {
			val bwImage = Mat()
			Imgproc.threshold(grayImage, bwImage, threshold, thresholdMax, Imgproc.THRESH_BINARY)
			Utils.matToBitmap(bwImage, resultBitmap)

			// Save the cropped image before converting it to black and white in order to troubleshoot issues related to differing device sizes and cropping.
			if (debugMode) {
				Imgcodecs.imwrite("$matchFilePath/ocr_${mostRecent}_threshold.png", bwImage)
			}
		}

		// Create a InputImage object for Google's ML OCR.
		val googleInputImageBitmap = createBitmap(cvImage.cols(), cvImage.rows())
		Utils.matToBitmap(cvImage, googleInputImageBitmap)
		val inputImage: InputImage = InputImage.fromBitmap(googleInputImageBitmap, 0)

		// Use CountDownLatch to make the async operation synchronous.
		val latch = CountDownLatch(1)
		var mlKitFailed = false

		googleTextRecognizer.process(inputImage)
			.addOnSuccessListener { text ->
				if (text.textBlocks.isNotEmpty()) {
					for (block in text.textBlocks) {
						MessageLog.d(tag, "[TEXT_DETECTION] Detected text with Google ML Kit: ${block.text}")
						result = block.text
					}
				}
				latch.countDown()
			}
			.addOnFailureListener {
				MessageLog.e(tag, "[ERROR] Failed to do text detection via Google's ML Kit. Falling back to Tesseract.")
				mlKitFailed = true
				latch.countDown()
			}

		// Wait for the async operation to complete.
		try {
			latch.await(5, TimeUnit.SECONDS)
		} catch (_: InterruptedException) {
			MessageLog.e(tag, "[ERROR] Google ML Kit operation timed out.")
		}

		// Fallback to Tesseract if ML Kit failed or didn't find result.
		if (mlKitFailed || result == "") {
			// Use either the default Tesseract client or the Tesseract client geared towards digits to set the image to scan.
			if (detectDigitsOnly) {
				tessDigitsBaseAPI.setImage(resultBitmap)
			} else {
				tessBaseAPI.setImage(resultBitmap)
			}

			try {
				// Finally, detect text on the cropped region.
				result = if (detectDigitsOnly) {
					tessDigitsBaseAPI.utF8Text
				} else {
					tessBaseAPI.utF8Text
				}
				MessageLog.d(tag, "[TEXT_DETECTION] Detected text with Tesseract: $result")
			} catch (e: Exception) {
				MessageLog.e(tag, "[ERROR] Cannot perform OCR: ${e.stackTraceToString()}")
			}

			// Stop Tesseract operations.
			if (detectDigitsOnly) {
				tessDigitsBaseAPI.stop()
			} else {
				tessBaseAPI.stop()
			}

			mostRecent++
			if (mostRecent > 10) {
				mostRecent = 1
			}

			tessBaseAPI.clear()
			tessDigitsBaseAPI.clear()
		}

		if (debugMode) MessageLog.d(tag, "[TEXT_DETECTION] Text detection finished in ${System.currentTimeMillis() - startTime}ms.")

		cvImage.release()
		grayImage.release()

		return result
	}
}