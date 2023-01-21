package com.steve1316.automation_library.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.preference.PreferenceManager
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

/**
 * Utility and helper functions for image processing via CV like OpenCV.
 *
 * @property context The application context.
 */
open class ImageUtils(private val context: Context) {
	private val tag: String = "${SharedData.loggerTag}ImageUtils"

	var matchMethod: Int = Imgproc.TM_CCOEFF_NORMED
	private var matchFilePath: String = ""
	protected lateinit var matchLocation: Point
	protected var matchLocations: ArrayList<Point> = arrayListOf()

	protected val decimalFormat = DecimalFormat("#.###", DecimalFormatSymbols(Locale.US))

	private var templatePathName = ""

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// SharedPreferences
	protected val confidence: Double
	protected val confidenceAll: Double
	protected val debugMode: Boolean
	protected var customScale: Double

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Device configuration
	val displayWidth: Int = SharedData.displayWidth
	val displayHeight: Int = SharedData.displayHeight
	protected val is1080p: Boolean = (displayWidth == 1080) || (displayHeight == 1080) // 1080p Portrait or Landscape Mode.
	protected val is720p: Boolean = (displayWidth == 720) || (displayHeight == 720) // 720p
	protected val isTabletPortrait: Boolean = (displayWidth == 1600 && displayHeight == 2560) || (displayWidth == 2560 && displayHeight == 1600) // Galaxy Tab S7 1600x2560 Portrait Mode
	protected val isTabletLandscape: Boolean = (displayWidth == 2560 && displayHeight == 1600) // Galaxy Tab S7 1600x2560 Landscape Mode

	// Scales (in terms of 720p and the dimensions from the Galaxy Tab S7)
	protected val lowerEndScales: MutableList<Double> = mutableListOf(0.60, 0.61, 0.62, 0.63, 0.64, 0.65, 0.67, 0.68, 0.69, 0.70)
	protected val middleEndScales: MutableList<Double> = mutableListOf(
		0.70, 0.71, 0.72, 0.73, 0.74, 0.75, 0.76, 0.77, 0.78, 0.79, 0.80, 0.81, 0.82, 0.83, 0.84, 0.85, 0.87, 0.88, 0.89, 0.90, 0.91, 0.92, 0.93, 0.94, 0.95, 0.96, 0.97, 0.98, 0.99
	)
	protected val tabletPortraitScales: MutableList<Double> = mutableListOf(0.70, 0.71, 0.72, 0.73, 0.74, 0.75)
	protected val tabletLandscapeScales: MutableList<Double> = mutableListOf(0.55, 0.56, 0.57, 0.58, 0.59, 0.60)

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// OCR configuration
	protected lateinit var tessBaseAPI: TessBaseAPI
	protected lateinit var tessDigitsBaseAPI: TessBaseAPI
	protected var mostRecent = 1
	protected lateinit var tesseractSourceBitmap: Bitmap

	init {
		val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

		confidence = sharedPreferences.getFloat("confidence", 0.8f).toDouble() / 100.0
		confidenceAll = sharedPreferences.getFloat("confidenceAll", 0.8f).toDouble() / 100.0
		debugMode = sharedPreferences.getBoolean("debugMode", false)
		customScale = sharedPreferences.getFloat("customScale", 1.0f).toDouble()

		// Set the file path to the /files/temp/ folder.
		val tempMatchFilePath: String = context.getExternalFilesDir(null)?.absolutePath + "/temp"
		matchFilePath = tempMatchFilePath
	}

	/**
	 * Wait the specified seconds.
	 *
	 * @param seconds Number of seconds to pause execution.
	 */
	protected fun wait(seconds: Double) {
		runBlocking {
			delay((seconds * 1000).toLong())
		}
	}

	/**
	 * Sets the path name of the subfolder(s) that will house the template images.
	 *
	 * @param subfolderPath Path name of the subfolder(s).
	 */
	protected fun setTemplateSubfolderPath(subfolderPath: String) {
		templatePathName = subfolderPath
		if (subfolderPath.last() != '/') {
			templatePathName += "/"
		}
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Template matching

	/**
	 * Match between the source Bitmap from /files/temp/ and the template Bitmap from the assets folder.
	 *
	 * @param sourceBitmap Bitmap from the /files/temp/ folder.
	 * @param templateBitmap Bitmap from the assets folder.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param useSingleScale Use a range of range of scales if this is disabled. Otherwise, it will use the customScale value. Defaults to false.
	 * @param customConfidence Specify a custom confidence. Defaults to the confidence set in the app's settings.
	 * @return True if a match was found. False otherwise.
	 */
	protected fun match(sourceBitmap: Bitmap, templateBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), useSingleScale: Boolean = false, customConfidence: Double = 0.0): Boolean {
		// If a custom region was specified, crop the source screenshot.
		val srcBitmap = if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
			Bitmap.createBitmap(sourceBitmap, region[0], region[1], region[2], region[3])
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
			customScale != 1.0 && !useSingleScale -> {
				mutableListOf(customScale - 0.02, customScale - 0.01, customScale, customScale + 0.01, customScale + 0.02, customScale + 0.03, customScale + 0.04)
			}
			customScale != 1.0 && useSingleScale -> {
				mutableListOf(customScale)
			}
			is720p -> {
				lowerEndScales.toMutableList()
			}
			!is1080p && !isTabletPortrait && !isTabletLandscape -> {
				middleEndScales.toMutableList()
			}
			!isTabletPortrait && isTabletLandscape -> {
				tabletLandscapeScales.toMutableList()
			}
			isTabletPortrait && !isTabletLandscape -> {
				tabletPortraitScales.toMutableList()
			}
			else -> {
				mutableListOf(1.0)
			}
		}

		while (scales.isNotEmpty()) {
			val newScale: Double = decimalFormat.format(scales.removeFirst()).toDouble()

			val tmp: Bitmap = if (newScale != 1.0) {
				Bitmap.createScaledBitmap(templateBitmap, (templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt(), true)
			} else {
				templateBitmap
			}

			// Create the Mats of both source and template images.
			val sourceMat = Mat()
			val templateMat = Mat()
			Utils.bitmapToMat(srcBitmap, sourceMat)
			Utils.bitmapToMat(tmp, templateMat)

			// Make the Mats grayscale for the source and the template.
			Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
			Imgproc.cvtColor(templateMat, templateMat, Imgproc.COLOR_BGR2GRAY)

			// Create the result matrix.
			val resultColumns: Int = sourceMat.cols() - templateMat.cols() + 1
			val resultRows: Int = sourceMat.rows() - templateMat.rows() + 1
			val resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)

			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, templateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

			matchLocation = Point()
			var matchCheck = false

			// Format minVal or maxVal.
			val minVal: Double = decimalFormat.format(mmr.minVal).toDouble()
			val maxVal: Double = decimalFormat.format(mmr.maxVal).toDouble()

			// Depending on which matching method was used, the algorithms determine which location was the best.
			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				matchLocation = mmr.minLoc
				matchCheck = true
				if (debugMode) {
					MessageLog.printToLog("[DEBUG] Match found with $minVal <= ${1.0 - setConfidence} at Point $matchLocation using scale: $newScale.", tag)
				}
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				matchLocation = mmr.maxLoc
				matchCheck = true
				if (debugMode) {
					MessageLog.printToLog("[DEBUG] Match found with $maxVal >= $setConfidence at Point $matchLocation using scale: $newScale.", tag)
				}
			} else {
				if (debugMode) {
					if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED)) {
						MessageLog.printToLog("[DEBUG] Match not found with $maxVal not >= $setConfidence at Point ${mmr.maxLoc} using scale $newScale.", tag)
					} else {
						MessageLog.printToLog("[DEBUG] Match not found with $minVal not <= ${1.0 - setConfidence} at Point ${mmr.minLoc} using scale $newScale.", tag)
					}
				}
			}

			if (matchCheck) {
				if (debugMode) {
					// Draw a rectangle around the supposed best matching location and then save the match into a file in /files/temp/ directory. This is for debugging purposes to see if this
					// algorithm found the match accurately or not.
					if (matchFilePath != "") {
						Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 128.0, 0.0), 5)
						Imgcodecs.imwrite("$matchFilePath/match.png", sourceMat)
					}
				}

				// Center the coordinates so that any tap gesture would be directed at the center of that match location instead of the default
				// position of the top left corner of the match location.
				matchLocation.x += (templateMat.cols() / 2)
				matchLocation.y += (templateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (region[0] + matchLocation.x)
					matchLocation.y = sourceBitmap.height - (region[1] + matchLocation.y)
				}

				return true
			}
		}

		return false
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
	protected fun matchAll(sourceBitmap: Bitmap, templateBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), customConfidence: Double = 0.0): ArrayList<Point> {
		// If a custom region was specified, crop the source screenshot.
		val srcBitmap = if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
			Bitmap.createBitmap(sourceBitmap, region[0], region[1], region[2], region[3])
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
			!is1080p && !isTabletPortrait && !isTabletLandscape -> {
				middleEndScales.toMutableList()
			}
			!isTabletPortrait && isTabletLandscape -> {
				tabletLandscapeScales.toMutableList()
			}
			isTabletPortrait && !isTabletLandscape -> {
				tabletPortraitScales.toMutableList()
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

		// Set templateMat at whatever scale it found the very first match for the next while loop.
		while (!matchCheck && scales.isNotEmpty()) {
			newScale = decimalFormat.format(scales.removeFirst()).replace(",", ".").toDouble()

			val tmp: Bitmap = if (newScale != 1.0) {
				Bitmap.createScaledBitmap(templateBitmap, (templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt(), true)
			} else {
				templateBitmap
			}

			// Create the Mats of both source and template images.
			Utils.bitmapToMat(srcBitmap, sourceMat)
			Utils.bitmapToMat(tmp, templateMat)

			// Make the Mats grayscale for the source and the template.
			Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
			Imgproc.cvtColor(templateMat, templateMat, Imgproc.COLOR_BGR2GRAY)

			// Create the result matrix.
			val resultColumns: Int = sourceMat.cols() - templateMat.cols() + 1
			val resultRows: Int = sourceMat.rows() - templateMat.rows() + 1
			if (resultColumns < 0 || resultRows < 0) {
				break
			}

			resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)

			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, templateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

			matchLocation = Point()

			// Depending on which matching method was used, the algorithms determine which location was the best.
			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				matchLocation = mmr.minLoc
				matchCheck = true

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

				// Center the location coordinates and then save it.
				matchLocation.x += (templateMat.cols() / 2)
				matchLocation.y += (templateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = region[0] + matchLocation.x
					matchLocation.y = region[1] + matchLocation.y
				}

				matchLocations.add(matchLocation)
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				matchLocation = mmr.maxLoc
				matchCheck = true

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

				// Center the location coordinates and then save it.
				matchLocation.x += (templateMat.cols() / 2)
				matchLocation.y += (templateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = region[0] + matchLocation.x
					matchLocation.y = region[1] + matchLocation.y
				}

				matchLocations.add(matchLocation)
			}
		}

		// Loop until all other matches are found and break out when there are no more to be found.
		while (matchCheck) {
			if (!SharedData.isRunning) {
				break
			}

			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, templateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

			// Format minVal or maxVal.
			val minVal: Double = decimalFormat.format(mmr.minVal).replace(",", ".").toDouble()
			val maxVal: Double = decimalFormat.format(mmr.maxVal).replace(",", ".").toDouble()

			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				val tempMatchLocation: Point = mmr.minLoc

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, tempMatchLocation, Point(tempMatchLocation.x + templateMat.cols(), tempMatchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

				if (debugMode) {
					MessageLog.printToLog("[DEBUG] Match found with $minVal <= ${1.0 - setConfidence} at Point $tempMatchLocation with scale: $newScale.", tag)
					Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
				}

				// Center the location coordinates and then save it.
				tempMatchLocation.x += (templateMat.cols() / 2)
				tempMatchLocation.y += (templateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					tempMatchLocation.x = region[0] + tempMatchLocation.x
					tempMatchLocation.y = region[1] + tempMatchLocation.y
				}

				if (!matchLocations.contains(tempMatchLocation) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y)) &&
					!matchLocations.contains(Point(tempMatchLocation.x, tempMatchLocation.y + 1.0)) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y + 1.0))
				) {
					matchLocations.add(tempMatchLocation)
				} else if (matchLocations.contains(tempMatchLocation)) {
					// Prevent infinite looping if the same location is found over and over again.
					break
				}
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				val tempMatchLocation: Point = mmr.maxLoc

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(sourceMat, tempMatchLocation, Point(tempMatchLocation.x + templateMat.cols(), tempMatchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

				if (debugMode) {
					MessageLog.printToLog("[DEBUG] Match found with $maxVal >= $setConfidence at Point $tempMatchLocation with scale: $newScale.", tag)
					Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
				}

				// Center the location coordinates and then save it.
				tempMatchLocation.x += (templateMat.cols() / 2)
				tempMatchLocation.y += (templateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					tempMatchLocation.x = region[0] + tempMatchLocation.x
					tempMatchLocation.y = region[1] + tempMatchLocation.y
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
						MessageLog.printToLog("[DEBUG] Match not found with ${mmr.minVal} > ${(1.0 - setConfidence)} at Point $tempMatchLocation with scale: $newScale.", tag)
					} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal < setConfidence) {
						MessageLog.printToLog("[DEBUG] Match not found with ${mmr.maxVal} < $setConfidence at Point $tempMatchLocation with scale: $newScale.", tag)
					}

					Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
				}

				break
			}
		}

		return matchLocations
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Fetching Bitmaps

	/**
	 * Open the source and template image files and return Bitmaps for them.
	 *
	 * @param templateName File name of the template image.
	 * @param templatePath Path name of the subfolder in /assets/ that the template image is in. Defaults to the default template subfolder path name.
	 * @return A Pair of source and template Bitmaps.
	 */
	protected fun getBitmaps(templateName: String, templatePath: String = templatePathName): Pair<Bitmap?, Bitmap?> {
		var sourceBitmap: Bitmap? = null

		// Keep swiping a little bit up and down to trigger a new image for ImageReader to grab.
		while (sourceBitmap == null) {
			sourceBitmap = MediaProjectionService.takeScreenshotNow()

			if (sourceBitmap == null) {
				MyAccessibilityService.getInstance().swipe(500f, 500f, 500f, 400f, 100L)
				MyAccessibilityService.getInstance().swipe(500f, 400f, 500f, 500f, 100L)
				wait(0.5)
			}
		}

		var templateBitmap: Bitmap?

		// Get the Bitmap from the template image file inside the specified folder.
		context.assets?.open("${templatePath}$templateName.webp").use { inputStream ->
			// Get the Bitmap from the template image file and then start matching.
			templateBitmap = BitmapFactory.decodeStream(inputStream)
		}

		return if (templateBitmap != null) {
			Pair(sourceBitmap, templateBitmap)
		} else {
			if (debugMode) {
				MessageLog.printToLog("[ERROR] Template bitmap is null.", tag, isError = true)
			}

			Pair(sourceBitmap, null)
		}
	}

	/**
	 * Gets the source screenshot as a Bitmap.
	 *
	 * @return The source Bitmap.
	 */
	protected fun getSourceScreenshot(): Bitmap {
		while (true) {
			val bitmap = MediaProjectionService.takeScreenshotNow(saveImage = debugMode)
			if (bitmap != null) {
				return bitmap
			} else {
				if (debugMode) MessageLog.printToLog("[DEBUG] Source bitmap is null. Waiting a second before trying again.", tag, isWarning = true)
				wait(1.0)
			}
		}
	}

	/**
	 * Acquire a Bitmap from the URL image file.
	 *
	 * @return A new Bitmap.
	 */
	protected fun getBitmapFromURL(url: URL): Bitmap {
		if (debugMode) {
			MessageLog.printToLog("\n[DEBUG] Starting process to create a Bitmap from the image url: $url", tag)
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
	 * @return Point object containing the location of the match or null if not found.
	 */
	open fun findImage(
		templateName: String, tries: Int = 5, confidence: Double = 0.0, region: IntArray = intArrayOf(0, 0, 0, 0),
		suppressError: Boolean = false, testMode: Boolean = false
	): Point? {
		var numberOfTries = tries

		if (debugMode) {
			MessageLog.printToLog("\n[DEBUG] Starting process to find the ${templateName.uppercase()} image...", tag)
		}

		// If Test Mode is enabled, prepare for it by setting initial scale.
		if (testMode) {
			numberOfTries = 80
			customScale = 0.20
		}

		while (numberOfTries > 0) {
			val (sourceBitmap, templateBitmap) = getBitmaps(templateName)

			if (sourceBitmap != null && templateBitmap != null) {
				val resultFlag: Boolean = match(sourceBitmap, templateBitmap, region, useSingleScale = true, customConfidence = confidence)
				if (!resultFlag) {
					if (testMode) {
						// Increment scale by 0.01 until a match is found if Test Mode is enabled.
						customScale += 0.01
						customScale = decimalFormat.format(customScale).toDouble()
					}

					numberOfTries -= 1
					if (numberOfTries <= 0) {
						if (!suppressError) {
							MessageLog.printToLog("[WARNING] Failed to find the ${templateName.uppercase()} image.", tag)
						}

						break
					}

					if (!testMode) {
						wait(0.5)
					}
				} else {
					if (testMode) {
						// Create a range of scales for user recommendation.
						val scale0: Double = decimalFormat.format(customScale).toDouble()
						val scale1: Double = decimalFormat.format(scale0 + 0.01).toDouble()
						val scale2: Double = decimalFormat.format(scale0 + 0.02).toDouble()
						val scale3: Double = decimalFormat.format(scale0 + 0.03).toDouble()
						val scale4: Double = decimalFormat.format(scale0 + 0.04).toDouble()

						MessageLog.printToLog(
							"[SUCCESS] Found the ${templateName.uppercase()} at $matchLocation with scale $scale0.\n\nRecommended to use scale $scale1, $scale2, $scale3 or $scale4.",
							tag
						)
					} else if (debugMode) {
						MessageLog.printToLog("[SUCCESS] Found the ${templateName.uppercase()} at $matchLocation.", tag)
					}

					return matchLocation
				}
			}
		}

		return null
	}

	/**
	 * Finds all occurrences of the specified image. Has an optional parameter to specify looking in the items folder instead.
	 *
	 * @param templateName File name of the template image.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param confidence Accuracy threshold for matching. Defaults to 0.0 which will use the confidence set in the app's settings.
	 * @return An ArrayList of Point objects containing all the occurrences of the specified image or null if not found.
	 */
	open fun findAll(templateName: String, region: IntArray = intArrayOf(0, 0, 0, 0), confidence: Double = 0.0): ArrayList<Point> {
		if (debugMode) {
			MessageLog.printToLog("\n[DEBUG] Starting process to find all ${templateName.uppercase()} images...", tag)
		}

		val (sourceBitmap, templateBitmap) = getBitmaps(templateName)

		// Clear the ArrayList first before attempting to find all matches.
		matchLocations.clear()

		if (sourceBitmap != null && templateBitmap != null) {
			matchAll(sourceBitmap, templateBitmap, region = region, customConfidence = confidence)
		}

		// Sort the match locations by ascending x and y coordinates.
		matchLocations.sortBy { it.x }
		matchLocations.sortBy { it.y }

		if (debugMode) {
			MessageLog.printToLog("[DEBUG] Found match locations for $templateName: $matchLocations.", tag)
		}

		// Deep copy the list of locations to prevent concurrent modification.
		val locations = matchLocations.map { it.clone() } as ArrayList
		matchLocations.clear()

		return locations
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
		MessageLog.printToLog("[INFO] Now waiting for $templateName to vanish from the screen...", tag)

		var remaining = timeout
		if (findImage(templateName, tries = 1, region = region, suppressError = suppressError) == null) {
			return true
		} else {
			while (findImage(templateName, tries = 1, region = region, suppressError = suppressError) != null) {
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
			MessageLog.printToLog("\n[DEBUG] Starting process to find the specified pixel ($red, $blue, $green)...", tag)
		}

		var x = 0
		var y = 0

		// Iterate through each pixel in the Bitmap and compare RGB values.
		while (x < bitmap.width) {
			while (y < bitmap.height) {
				val pixel = bitmap.getPixel(x, y)

				if (Color.red(pixel) == red && Color.blue(pixel) == blue && Color.green(pixel) == green) {
					if (debugMode) {
						MessageLog.printToLog("[DEBUG] Found matching pixel at ($x, $y).", tag)
					}

					return Pair(x, y)
				}

				y++
			}

			x++
			y = 0
		}

		if (!suppressError) {
			MessageLog.printToLog("[WARNING] Failed to find the specified pixel ($red, $blue, $green).", tag, isWarning = true)
		}

		return Pair(-1, -1)
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Tesseract

	/**
	 * Initialize Tesseract for future OCR operations. Make sure to put your .traineddata inside the root of the /assets/ folder.
	 *
	 * @param traineddataFileName The file name including its extension for the .traineddata of Tesseract.
	 */
	fun initTesseract(traineddataFileName: String) {
		tessBaseAPI = TessBaseAPI()
		tessDigitsBaseAPI = TessBaseAPI()

		val externalFilesDir: File? = context.getExternalFilesDir(null)
		val tempDirectory: String = externalFilesDir?.absolutePath + "/tesseract/tessdata/"
		val newTempDirectory = File(tempDirectory)

		// If the /files/temp/ folder does not exist, create it.
		if (!newTempDirectory.exists()) {
			val successfullyCreated: Boolean = newTempDirectory.mkdirs()

			// If the folder was not able to be created for some reason, log the error and stop the MediaProjection Service.
			if (!successfullyCreated) {
				MessageLog.printToLog("[ERROR] Failed to create the /files/tesseract/tessdata/ folder.", tag, isError = true)
			} else {
				MessageLog.printToLog("[INFO] Successfully created /files/tesseract/tessdata/ folder.", tag)
			}
		} else {
			MessageLog.printToLog("[INFO] /files/tesseract/tessdata/ folder already exists.", tag)
		}

		// If the .traineddata is not in the application folder, copy it there from assets.
		val trainedDataPath = File(tempDirectory, traineddataFileName)
		if (!trainedDataPath.exists()) {
			try {
				MessageLog.printToLog("[INFO] Starting Tesseract initialization.", tag)
				val input = context.assets.open(traineddataFileName)

				val output = FileOutputStream("$tempDirectory/$traineddataFileName")

				val buffer = ByteArray(1024)
				var read: Int
				while (input.read(buffer).also { read = it } != -1) {
					output.write(buffer, 0, read)
				}

				input.close()
				output.flush()
				output.close()

				MessageLog.printToLog("[INFO] Finished Tesseract initialization.", tag)
			} catch (e: IOException) {
				MessageLog.printToLog("[ERROR] Tesseract I/O Exception: ${e.stackTraceToString()}", tag, isError = true)
			}
		}

		tessBaseAPI.init(context.getExternalFilesDir(null)?.absolutePath + "/tesseract/", "eng")
		tessDigitsBaseAPI.init(context.getExternalFilesDir(null)?.absolutePath + "/tesseract/", "eng")

		tessDigitsBaseAPI.setVariable(TessBaseAPI.VAR_CHAR_BLACKLIST, "!?@#\$%&*()<>_-+=/:;'\\\"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
		tessDigitsBaseAPI.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789")
		tessDigitsBaseAPI.setVariable("classify_bln_numeric_mode", "1")

		// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
		tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
		tessDigitsBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
	}

	/**
	 * Perform OCR text detection using Tesseract along with some image manipulation via thresholding to make the cropped screenshot black and white using OpenCV.
	 *
	 * @param x Initial x-coordinate for crop region.
	 * @param y Initial y-coordinate for crop region.
	 * @param width Width of the crop region.
	 * @param height Height of the crop region.
	 * @param thresh Performs thresholding on the cropped region. Defaults to true.
	 * @param threshold Minimum threshold value. Defaults to 130.
	 * @param thresholdMax Maximum threshold value. Defaults to 255.
	 * @param reuseSourceBitmap Reuses the source bitmap from the previous call. Defaults to false which will retake the source bitmap.
	 * @param detectDigitsOnly True if detection should focus on digits only.
	 *
	 * @return The detected String in the cropped region.
	 */
	open fun findTextTesseract(
		x: Int, y: Int, width: Int, height: Int, thresh: Boolean = true, threshold: Double = 130.0, thresholdMax: Double = 255.0, reuseSourceBitmap: Boolean = false, detectDigitsOnly: Boolean = false
	): String {
		val startTime: Long = System.currentTimeMillis()

		val sourceBitmap: Bitmap = if (!reuseSourceBitmap) {
			tesseractSourceBitmap = getSourceScreenshot()
			tesseractSourceBitmap
		} else {
			tesseractSourceBitmap
		}

		if (debugMode) MessageLog.printToLog("\n[TESSERACT] Starting text detection now...", tag)

		// Crop and convert the source bitmap to Mat.
		val croppedBitmap = Bitmap.createBitmap(sourceBitmap, x, y, width, height)
		val cvImage = Mat()
		Utils.bitmapToMat(croppedBitmap, cvImage)

		// Save the cropped image before converting it to black and white in order to troubleshoot issues related to differing device sizes and cropping.
		if (debugMode) {
			Imgcodecs.imwrite("$matchFilePath/tesseract_result_${mostRecent}_a.png", cvImage)
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
				Imgcodecs.imwrite("$matchFilePath/tesseract_result_${mostRecent}_b.png", bwImage)
			}
		}

		// Use either the default Tesseract client or the Tesseract client geared towards digits to set the image to scan.
		if (detectDigitsOnly) {
			tessDigitsBaseAPI.setImage(resultBitmap)
		} else {
			tessBaseAPI.setImage(resultBitmap)
		}

		var result = "empty!"
		try {
			// Finally, detect text on the cropped region.
			result = if (detectDigitsOnly) {
				tessDigitsBaseAPI.utF8Text
			} else {
				tessBaseAPI.utF8Text
			}
		} catch (e: Exception) {
			MessageLog.printToLog("[ERROR] Cannot perform OCR: ${e.stackTraceToString()}", tag, isError = true)
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

		if (debugMode) MessageLog.printToLog("[TESSERACT] Text detection finished in ${System.currentTimeMillis() - startTime}ms.", tag)

		return result
	}
}