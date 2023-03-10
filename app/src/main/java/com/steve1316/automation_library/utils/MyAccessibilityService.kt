package com.steve1316.automation_library.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.Toast
import com.steve1316.automation_library.R
import com.steve1316.automation_library.data.SharedData
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * Contains the Accessibility service that will allow the app to programmatically perform gestures on the screen.
 *
 * AccessibilityService by itself has a native bug when force-stopped: https://stackoverflow.com/questions/67410929/accessibility-service-does-not-restart-when-manually-re-enabled-after-app-force
 */
class MyAccessibilityService : AccessibilityService() {
	private var appName: String = ""
	private lateinit var myContext: Context

	companion object {
		private const val tag: String = "${SharedData.loggerTag}MyAccessibilityService"

		// Other classes need this static reference to this service as calling dispatchGesture() would not work.
		@SuppressLint("StaticFieldLeak")
		private lateinit var instance: MyAccessibilityService

		var enableTextToPaste: Boolean = false
		var textToPaste: String = ""
		var imageSubFolder = "images/"

		/**
		 * Returns a static reference to this class.
		 *
		 * @return Static reference to MyAccessibilityService.
		 */
		fun getInstance(): MyAccessibilityService {
			return if (this::instance.isInitialized) {
				instance
			} else {
				Log.w(tag, "WARNING: This instance of MyAccessibilityService is being returned improperly! Do not use this any further until accessibility permissions are granted properly.")
				MyAccessibilityService()
			}
		}

		/**
		 * Check if this service is alive and running.
		 *
		 * @param context The application context.
		 * @return True if the service is alive.
		 */
		fun checkStatus(context: Context): Boolean {
			val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
			for (serviceInfo in manager.getRunningServices(Integer.MAX_VALUE)) {
				if (serviceInfo.service.className.contains("MyAccessibilityService")) {
					return true
				}
			}
			return false
		}
	}

	override fun onServiceConnected() {
		instance = this
		myContext = this
		appName = myContext.getString(R.string.app_name)

		Log.d(tag, "Accessibility Service for $appName is now running.")
		Toast.makeText(myContext, "Accessibility Service for $appName is now running.", Toast.LENGTH_SHORT).show()
	}

	override fun onAccessibilityEvent(event: AccessibilityEvent?) {
		if (enableTextToPaste && event?.source != null && textToPaste != "" && (event.source?.className.toString().contains(EditText::class.java.simpleName) ||
					event.source?.className.toString().contains("android.widget.EditText"))
		) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				Log.d(tag, "Copying the text $textToPaste to paste into the EditText android component.")

				// Create the argument object to get ready to paste the text.
				val arguments = Bundle()
				arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToPaste)

				if (event.source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
					Log.d(tag, "Successfully pasted the text $textToPaste")
				} else {
					Log.e(tag, "Failed to paste the text $textToPaste")
				}
			} else {
				Log.d(tag, "[LEGACY] Copying the text $textToPaste to paste into the EditText android component.")
				event.source.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

				val clipboard = myContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
				val clip = ClipData.newPlainText("Text to Paste", textToPaste)
				clipboard.setPrimaryClip(clip)

				Log.d(tag, "[LEGACY] Clipboard contents to paste: ${clipboard.primaryClip}")

				if (event.source.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
					Log.d(tag, "[LEGACY] Successfully pasted the text $textToPaste")
				} else {
					Log.e(tag, "[LEGACY] Failed to paste the text $textToPaste")
				}
			}

			// Now reset the text to paste to prevent looping on onAccessibilityEvent().
			textToPaste = ""
		}

		return
	}

	override fun onInterrupt() {
		return
	}

	override fun onDestroy() {
		super.onDestroy()

		Log.d(tag, "Accessibility Service for $appName is now stopped.")
		Toast.makeText(myContext, "Accessibility Service for $appName is now stopped.", Toast.LENGTH_SHORT).show()
	}

	/**
	 * This receiver will wait the specified seconds to account for ping or loading.
	 */
	private fun Double.wait() {
		runBlocking {
			delay((this@wait * 1000).toLong())
		}
	}

	/**
	 * Randomizes the tap location to be within the dimensions of the specified image.
	 *
	 * @param x The original x location for the tap gesture.
	 * @param y The original y location for the tap gesture.
	 * @param imageName The name of the image to acquire its dimensions for tap location randomization. Defaults to an empty string which will then default to a region of 25x25.
	 * @return Pair of integers that represent the newly randomized tap location.
	 */
	private fun randomizeTapLocation(x: Double, y: Double, imageName: String = ""): Pair<Int, Int> {
		// Get the Bitmap from the template image file inside the specified folder.
		val templateBitmap: Bitmap
		val dimensions: Pair<Int, Int> = try {
			val newImageSubFolder = if (imageSubFolder.last() != '/') {
				"$imageSubFolder/"
			} else {
				imageSubFolder
			}

			myContext.assets?.open("$newImageSubFolder$imageName.webp").use { inputStream ->
				// Get the Bitmap from the template image file and then start matching.
				templateBitmap = BitmapFactory.decodeStream(inputStream)
			}
			Pair(templateBitmap.width, templateBitmap.height)
		} catch (e: FileNotFoundException) {
			Log.e(tag, "Cannot find the image asset file: $e")
			Log.w(tag, "Using a region of 25x25 as a fallback in order to proceed with tap location randomization.")
			Pair(25, 25)
		}

		val width = dimensions.first
		val height = dimensions.second

		// Randomize the tapping location.
		val x0: Int = (x - (width / 2)).toInt()
		val x1: Int = (x + (width / 2)).toInt()
		val y0: Int = (y - (height / 2)).toInt()
		val y1: Int = (y + (height / 2)).toInt()

		var newX: Int
		var newY: Int

		while (true) {
			// Start acquiring randomized coordinates at least 25% and at most 75% of the width and height until a valid set of coordinates has been acquired.
			val newWidth: Int = ((width * 0.25).toInt()..(width * 0.75).toInt()).random()
			val newHeight: Int = ((height * 0.25).toInt()..(height * 0.75).toInt()).random()

			newX = x0 + newWidth
			newY = y0 + newHeight

			// If the new coordinates are within the bounds of the template image, break out of the loop.
			if (newX > x0 || newX < x1 || newY > y0 || newY < y1) {
				break
			}
		}

		return Pair(newX, newY)
	}

	/**
	 * Creates a tap gesture on the specified point on the screen.
	 *
	 * @param x The x coordinate of the point.
	 * @param y The y coordinate of the point.
	 * @param imageName The file name of the image to tap in order to extract its dimensions to perform tap randomization calculations. Defaults to an empty string.
	 * @param longPress Whether or not to long press. Defaults to false.
	 * @param taps How many taps to execute. Defaults to a single tap.
	 * @return True if the tap gesture was executed successfully. False otherwise.
	 */
	fun tap(x: Double, y: Double, imageName: String = "", longPress: Boolean = false, taps: Int = 1): Boolean {
		// Randomize the tapping location.
		val (newX, newY) = randomizeTapLocation(x, y, imageName)
		Log.d(tag, "Tapping $newX, $newY for image: ${imageName.uppercase()}")

		// Construct the tap gesture.
		val tapPath = Path().apply {
			moveTo(newX.toFloat(), newY.toFloat())
		}

		val gesture: GestureDescription = if (longPress) {
			// Long press for 1000ms.
			GestureDescription.Builder().apply {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					addStroke(GestureDescription.StrokeDescription(tapPath, 0, 1000, true))
				} else {
					addStroke(GestureDescription.StrokeDescription(tapPath, 0, 1000))
				}
			}.build()
		} else {
			GestureDescription.Builder().apply {
				addStroke(GestureDescription.StrokeDescription(tapPath, 0, 1))
			}.build()
		}

		val dispatchResult = dispatchGesture(gesture, null, null)
		var tries = taps - 1

		while (tries > 0) {
			dispatchGesture(gesture, null, null)
			0.25.wait()

			tries -= 1
		}

		0.25.wait()

		return dispatchResult
	}

	/**
	 * Creates a scroll gesture either scrolling up or down the screen depending on the given action.
	 *
	 * @param scrollDown The scrolling action, either up or down the screen. Defaults to true which is scrolling down.
	 * @param duration How long the scroll should take. Defaults to 500L.
	 * @return True if the scroll gesture was executed successfully. False otherwise.
	 */
	fun scroll(scrollDown: Boolean = true, duration: Long = 500L): Boolean {
		val scrollPath = Path()

		// Get certain portions of the screen's dimensions.
		val displayMetrics = Resources.getSystem().displayMetrics

		// Set different scroll paths for different screen sizes.
		val top: Float
		val middle: Float
		val bottom: Float
		when (displayMetrics.widthPixels) {
			1600 -> {
				top = (displayMetrics.heightPixels * 0.60).toFloat()
				middle = (displayMetrics.widthPixels * 0.20).toFloat()
				bottom = (displayMetrics.heightPixels * 0.40).toFloat()
			}
			2650 -> {
				top = (displayMetrics.heightPixels * 0.60).toFloat()
				middle = (displayMetrics.widthPixels * 0.20).toFloat()
				bottom = (displayMetrics.heightPixels * 0.40).toFloat()
			}
			else -> {
				top = (displayMetrics.heightPixels * 0.75).toFloat()
				middle = (displayMetrics.widthPixels / 2).toFloat()
				bottom = (displayMetrics.heightPixels * 0.25).toFloat()
			}
		}

		if (scrollDown) {
			// Create a Path to scroll the screen down starting from the top and swiping to the bottom.
			scrollPath.apply {
				moveTo(middle, top)
				lineTo(middle, bottom)
			}
		} else {
			// Create a Path to scroll the screen up starting from the bottom and swiping to the top.
			scrollPath.apply {
				moveTo(middle, bottom)
				lineTo(middle, top)
			}
		}

		val gesture = GestureDescription.Builder().apply {
			addStroke(GestureDescription.StrokeDescription(scrollPath, 0, duration))
		}.build()

		val dispatchResult = dispatchGesture(gesture, null, null)
		(duration.toDouble() / 1000).wait()

		if (!dispatchResult) {
			Log.e(tag, "Failed to dispatch scroll gesture.")
		} else {
			val direction: String = if (scrollDown) {
				"down"
			} else {
				"up"
			}
			Log.d(tag, "Scrolling $direction.")
		}

		return dispatchResult
	}

	/**
	 * Creates a swipe gesture from the old coordinates to the new coordinates on the screen.
	 *
	 * @param oldX The x coordinate of the old position.
	 * @param oldY The y coordinate of the old position.
	 * @param newX The x coordinate of the new position.
	 * @param newY The y coordinate of the new position.
	 * @param duration How long the swipe should take. Defaults to 500L.
	 * @return True if the swipe gesture was executed successfully. False otherwise.
	 */
	fun swipe(oldX: Float, oldY: Float, newX: Float, newY: Float, duration: Long = 500L): Boolean {
		// Set up the Path by swiping from the old position coordinates to the new position coordinates.
		val swipePath = Path().apply {
			moveTo(oldX, oldY)
			lineTo(newX, newY)
		}

		val gesture = GestureDescription.Builder().apply {
			addStroke(GestureDescription.StrokeDescription(swipePath, 0, duration))
		}.build()

		val dispatchResult = dispatchGesture(gesture, null, null)

		// Wait for the stroke gesture to actually complete.
		(duration.toDouble() / 1000).wait()

		return dispatchResult
	}
}