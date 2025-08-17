include(":app")
rootProject.name = "Automation Library"

// Centralized repository management - configures all dependency sources upfront
// for consistent resolution across all modules, preventing conflicts.
// Particularly for my OpenCV SDK on JitPack.
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// Automatic provisioning of a compatible JVM toolchain.
// Convention plugin fetches a jdk into the gradle home directory
// if it doesn't find any compatible ones in its canonical OS search paths.
plugins {
    // Settings plugins cannot be declared in version catalog.
    id("org.gradle.toolchains.foojay-resolver-convention").version("1.0.0")
}
