# Automation Library [![](https://jitpack.io/v/steve1316/android-cv-automation-library.svg)](https://jitpack.io/#steve1316/android-cv-automation-library)

This library serves to consolidate all necessary code to facilitate a backend for automation purposes on Android devices. The OpenCV Android SDK is being imported from this [OpenCV Android SDK repo](https://github.com/steve1316/opencv-android-sdk). Currently, this library can do the following:

-   Uses `MyAccessibilityService` to programmatically execute gestures and `MediaProjectionService` to acquire screenshots for `ImageUtils` to perform image processing operations on.
-   `BotService` handles the display and movement of the floating overlay button to start and stop program execution.
-   Handles connection with Discord and Twitter APIs with `DiscordUtils` and `TwitterUtils` respectively.
-   Loads in a `settings.json` file with `JSONParser` to be further processed in the primary project.
-   Saves a text log of messages with `MessageLog`.
-   Displays a persistent status notification informing the user via `NotificationUtils`.
-   Any messages that needs to be sent from this library to the primary project can be done with the `EventBus` library using the `JSEvent` and `StartEvent` event classes.
-   `ScreenStateReceiver` gracefully stops the bot when the device screen turns off to prevent gestures from being dispatched on a sleeping device.

## Local Testing (Before Publishing to JitPack)

To test changes to this library locally without publishing to JitPack:

1. **Change the versionName to include "-SNAPSHOT"** in `gradle/libs.versions.toml`:
   - Update `app-versionName` to include "-SNAPSHOT" (e.g., `app-versionName = "2.1.1-SNAPSHOT"`).
   - This will ensure gradle will always pull the latest version from the local repository for your app to use.

2. **Publish to Maven Local** (from the library root directory):
```bash
./gradlew publishToMavenLocal
# Or on Windows:
gradlew.bat publishToMavenLocal
# You can then confirm the local publication by looking in your local Maven repository (typically located at `C:\Users\username\.m2\repository`).
# If we continue with the example from above, there will be 5 files generated in the 2.1.1-SNAPSHOT folder:
# - automation_library-2.1.1-SNAPSHOT.aar
# - automation_library-2.1.1-SNAPSHOT.module
# - automation_library-2.1.1-SNAPSHOT.pom
# - automation_library-2.1.1-SNAPSHOT-sources.jar
# - maven-metadata-local.xml
```

3. **In your app's `build.gradle.kts`, add Maven Local repository**:
```kotlin
allprojects {
    repositories {
        maven { url "https://www.jitpack.io" }
        mavenLocal() // Add this line for local testing. Make sure it is above every other repository.
        google()
        mavenCentral()
    }
}
```

4. **Add the dependency to your app**:
```kotlin
dependencies {
    // If you are using a local version, you will need to use the -SNAPSHOT version here.
    implementation("com.github.steve1316:automation_library:2.1.1-SNAPSHOT")
}
```

The library will be loaded from your local Maven repository (typically `~/.m2/repository` or `C:\Users\username\.m2\repository`).

## Installation

```
// Project-level build.gradle
allprojects {
    repositories {
        maven { url 'https://www.jitpack.io' }
    }
}
```

```
// App-level build.gradle
dependencies {
        implementation("com.github.steve1316:android-cv-automation-library:Tag")
}
```

## TODO

-   [ ] Create a Wiki and create a page for each class in the `utils` folder, explaining what each of them do in a broad scope and what they offer to the project that will be using them.
