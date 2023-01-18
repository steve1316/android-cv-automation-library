package com.steve1316.automation_library.events

/**
 * This event will be picked up by EventBus to communicate with the developer's JS frontend from this module.
 *
 * @property eventName The name of the event to be picked up on as defined in the developer's JS frontend.
 * @property message The message string to pass on.
 */
class JSEvent(val eventName: String, val message: String)