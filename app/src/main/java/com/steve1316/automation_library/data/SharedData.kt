package com.steve1316.automation_library.data

import com.steve1316.automation_library.utils.SettingsHelper

/**
 * Contains various shared variables to be used across a variety of objects, including from the developer's module.
 *
 */
class SharedData {
	companion object {
		const val loggerTag = "[AUTO_LIB]"

		var templateSubfolderPathName = "/"
		var templateImageExt = "png"

        // This is the baseline device configuration that the framework is based off of.
        val baselineWidth = 1080f
        val baselineHeight = 2340f
        val baselineDPI = 450

		// These are set in the MediaProjectionService upon successful creation of the Virtual Display.
		var mainPackagePath: String = ""
		var displayWidth: Int = 0
		var displayHeight: Int = 0
		var displayDPI: Int = 0
        var displayDensity: Float = 0F

		// The floating overlay button size in dp (density-independent pixels).
		val overlayButtonSizeDP: Float
			get() {
				val defaultValue = if (displayDPI >= 400) 40f else 50f
				return SettingsHelper.getFloatSettingSafe("misc", "overlayButtonSizeDP", defaultValue)
			}

		// The dismiss target button size in dp (density-independent pixels).
		val overlayDismissButtonSizeDP: Float
			get() {
				val defaultValue = if (displayDPI >= 400) 50f else 72f
				return SettingsHelper.getFloatSettingSafe("misc", "overlayDismissButtonSizeDP", defaultValue)
			}

		// Guidance regions for overlay placement: array of [x, y, width, height] based on 1080x2340 450DPI.
		// These will be scaled to the user's device resolution automatically. Empty means full screen allowed.
		var guidanceRegions: Array<IntArray> = arrayOf()

		// Recording configuration for MediaProjectionRecording.
		val enableScreenRecording: Boolean
			get() = SettingsHelper.getBooleanSettingSafe("debug", "enableScreenRecording", false)

		val recordingBitRate: Int
			get() {
				val storedValue = SettingsHelper.getIntSettingSafe("debug", "recordingBitRate", 6)
				return if (storedValue <= 100) storedValue * 1_000_000 else storedValue
			}

		val recordingFrameRate: Int
			get() = SettingsHelper.getIntSettingSafe("debug", "recordingFrameRate", 30)

		// Resolution scale for recording (0.25 to 1.0). Lower values = smaller files.
		val recordingResolutionScale: Float
			get() {
				val scale = SettingsHelper.getFloatSettingSafe("debug", "recordingResolutionScale", 1.0f)
				return scale.coerceIn(0.25f, 1.0f)
			}
	}
}