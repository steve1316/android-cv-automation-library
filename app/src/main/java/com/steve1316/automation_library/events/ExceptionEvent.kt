package com.steve1316.automation_library.events

/**
 * This event will be picked up by EventBus to communicate with this library to update the notification that an exception had occurred.
 *
 * @property exception The exception or throwable that occurred.
 */
class ExceptionEvent(val exception: Throwable)