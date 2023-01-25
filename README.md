# Automation Library

This library serves to consolidate all necessary code to facilitate a backend for automation purposes on Android devices. Currently, this library can do the following:

-   Uses `MyAccessibilityService` to programmatically execute gestures and `MediaProjectionService` to acquire screenshots for `ImageUtils` to perform image processing operations on.
-   `BotService` handles the display and movement of the floating overlay button to start and stop program execution.
-   Handles connection with Discord and Twitter APIs with `DiscordUtils` and `TwitterUtils` respectively.
-   Loads in a `settings.json` file with `JSONParser` to be further processed in the primary project.
-   Saves a text log of messages with `MessageLog`.
-   Displays a persistent status notification informing the user via `NotificationUtils`.
-   Any messages that needs to be sent from this library to the primary project can be done with the `EventBus` library using the `JSEvent` and `StartEvent` event classes.

## TODO

-   [ ] Create a Wiki and create a page for each class in the `utils` folder, explaining what each of them do in a broad scope and what they offer to the project that will be using them.
