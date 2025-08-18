import org.gradle.api.publish.maven.MavenPublication

plugins {
	id("com.android.library")
	id("org.jetbrains.kotlin.android")
	id("maven-publish")
}

android {
	namespace = "com.steve1316.automation_library"
	compileSdk = libs.versions.app.compileSdk.get().toInt()
	buildToolsVersion = libs.versions.app.buildToolsVersion.get()

	defaultConfig {
		minSdk = libs.versions.app.minSdk.get().toInt()
		consumerProguardFiles("consumer-rules.pro")
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

afterEvaluate {
	publishing {
		publications {
			create<MavenPublication>("release") {
				from(components.getByName("release"))
				groupId = "com.github.steve1316"
				artifactId = "automation_library"
				version = libs.versions.app.versionName.get()
			}
		}
	}
}

dependencies {
	//////// Dependencies available to the project ////////

	api(libs.bundles.androidApp)

	// OpenCV Android for image processing.
	api(libs.opencv.android.sdk)

	// Tesseract4Android for OCR text recognition.
	api(libs.tesseract4android)

	// string-similarity to compare the string from OCR to the strings in data.
	api(libs.stringSimilarity)

	// Javacord for Discord integration.
	api(libs.javacord)

	// Klaxon to parse JSON data files.
	api(libs.klaxon)

	// EventBus to communicate between modules and to the Javascript frontend.
	api(libs.eventbus)

	// Google's Firebase Machine Learning OCR for Text Detection.
	api(libs.mlkitTextRecognition)

	// Twitter4j is used to connect to the Twitter API.
	api(libs.twitter4j.core)

	// AppUpdater for notifying users when there is a new update available.
	api(libs.appUpdater)
}

kotlin {
	jvmToolchain {
		languageVersion.set(JavaLanguageVersion.of(libs.versions.app.jvm.toolchain.get().toInt()))
	}
}