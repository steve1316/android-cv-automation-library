package com.steve1316.automation_library.data

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

		private var _overlayButtonSizeDP: Float? = null
		// Adjust this value to control the floating overlay button size in dp (density-independent pixels).
		var overlayButtonSizeDP: Float
			get() = _overlayButtonSizeDP ?: if (displayDPI >= 400) 40f else 50f
			set(value) { _overlayButtonSizeDP = value }

		private var _overlayDismissButtonSizeDP: Float? = null
		// Adjust this value to control the dismiss target button size in dp (density-independent pixels).
		var overlayDismissButtonSizeDP: Float
			get() = _overlayDismissButtonSizeDP ?: if (displayDPI >= 400) 50f else 72f
			set(value) { _overlayDismissButtonSizeDP = value }

		// Guidance regions for overlay placement: array of [x, y, width, height] based on 1080x2340 450DPI.
		// These will be scaled to the user's device resolution automatically. Empty means full screen allowed.
		var guidanceRegions: Array<IntArray> = arrayOf()
	}
}