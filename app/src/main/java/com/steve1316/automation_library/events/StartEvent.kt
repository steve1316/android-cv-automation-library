package com.steve1316.automation_library.events

/**
 * This event will be picked up by EventBus to communicate with the developer's module from this module. Most often to signal the start of that module's entry point.
 *
 * @property message The message string to pass on.
 */
class StartEvent(val message: String)