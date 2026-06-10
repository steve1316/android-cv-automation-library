import java.net.URI

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.androidGradleBuildTools)
        classpath(libs.kotlinGradlePlugin)
    }
}

plugins {
    alias(libs.plugins.ktlint) apply false
}

extra["minSdkVersion"] = libs.versions.app.minSdk.get().toInt()
extra["targetSdkVersion"] = libs.versions.app.targetSdk.get().toInt()
extra["compileSdkVersion"] = libs.versions.app.compileSdk.get().toInt()

tasks.register("clean", Delete::class.java) {
    delete(layout.buildDirectory)
}