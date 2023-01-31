package com.steve1316.automation_library.events

/**
 * This event will be picked up by EventBus to communicate with this library to update the notification that an exception had occurred.
 *
 * @property eventName The name of the event to be picked up.
 * @property message The message string to pass on.
 */
class ExceptionEvent(val exception: Exception)