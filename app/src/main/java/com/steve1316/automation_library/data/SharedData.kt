package com.steve1316.automation_library.data

/**
 * Contains various shared variables to be used across a variety of objects, including from the developer's module.
 *
 */
class SharedData {
	companion object {
		const val loggerTag = "[AUTO_LIB]"

		var isRunning = false
		var mainPackagePath: String = ""

		var displayWidth: Int = 0
		var displayHeight: Int = 0
	}
}