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
			isMinifyEnabled = true
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

	implementation(libs.bundles.androidApp)

	// OpenCV Android for image processing.
	implementation(libs.opencv.android.sdk)

	// Tesseract4Android for OCR text recognition.
	implementation(libs.tesseract4android)

	// string-similarity to compare the string from OCR to the strings in data.
	implementation(libs.stringSimilarity)

	// Javacord for Discord integration.
	implementation(libs.javacord)

	// Klaxon to parse JSON data files.
	implementation(libs.klaxon)

	// EventBus to communicate between modules and to the Javascript frontend.
	implementation(libs.eventbus)

	// Google's Firebase Machine Learning OCR for Text Detection.
	implementation(libs.mlkitTextRecognition)

	// Twitter4j is used to connect to the Twitter API.
	implementation(libs.twitter4j.core)

	// AppUpdater for notifying users when there is a new update available.
	implementation(libs.appUpdater)
}

kotlin {
	jvmToolchain {
		languageVersion.set(JavaLanguageVersion.of(libs.versions.app.jvm.toolchain.get().toInt()))
	}
}