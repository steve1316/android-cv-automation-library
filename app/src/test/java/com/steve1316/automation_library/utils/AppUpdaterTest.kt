package com.steve1316.automation_library.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for reading a GitHub release, comparing versions, and picking the device's APK. The dialog and install run on-device. */
class AppUpdaterTest {
    private val markers = AppUpdater.Config("o", "r", "1.0.0").notesEndMarkers
    private val arm = AppUpdater.ReleaseAsset("v5.8.9-App-arm64-v8a-release.apk", 1L, "https://github.com/arm.apk")
    private val x86 = AppUpdater.ReleaseAsset("v5.8.9-App-x86_64-release.apk", 2L, "https://github.com/x86.apk")
    private val defaultMatcher = AppUpdater.Config("o", "r", "1.0.0").isApkFor

    /**
     * Builds release JSON.
     *
     * @param body The release body.
     * @return The JSON string.
     */
    private fun release(body: String): String = JSONObject().put("tag_name", "v5.8.9").put("html_url", "https://github.com/x").put("body", body).toString()

    @Test
    fun parseReleaseReadsTagLinkAndBodyWithLfEndings() {
        val info = AppUpdater.parseRelease(release("v5.8.9 - Changelog\r\n\r\nNew\r\n---\r\n- Add `X`.\r\n"), markers)!!
        assertEquals("5.8.9", info.latestVersion)
        assertEquals("https://github.com/x", info.url)
        assertEquals("v5.8.9 - Changelog\n\nNew\n---\n- Add `X`.", info.releaseNotes)
    }

    @Test
    fun parseReleaseRejectsMissingTagOrLink() {
        assertNull(AppUpdater.parseRelease("""{"html_url": "https://github.com/x", "body": ""}""", markers))
        assertNull(AppUpdater.parseRelease("""{"tag_name": "v5.8.9", "body": ""}""", markers))
    }

    @Test
    fun parseReleaseReadsAssets() {
        val json = """{"tag_name": "v5.8.9", "html_url": "https://github.com/x", "body": "", "assets": [{"name": "a.apk", "size": 114622856, "browser_download_url": "https://github.com/a.apk"}]}"""
        assertEquals(listOf(AppUpdater.ReleaseAsset("a.apk", 114622856L, "https://github.com/a.apk")), AppUpdater.parseRelease(json, markers)!!.assets)
    }

    @Test
    fun parseReleaseCutsGeneratedNotes() {
        val body = "Fixes\r\n---\r\n- Fix X.\r\n\r\n## Pull Requests\r\n* PR by @a in https://github.com/x/pull/1\r\n\r\n**Full Changelog**: https://github.com/x"
        assertEquals("Fixes\n---\n- Fix X.", AppUpdater.parseRelease(release(body), markers)!!.releaseNotes)
        assertEquals("- Fix X.", AppUpdater.parseRelease(release("- Fix X.\n\n**Full Changelog**: https://github.com/x"), markers)!!.releaseNotes)
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(AppUpdater.isNewerVersion("5.8.10", "5.8.9"))
        assertTrue(AppUpdater.isNewerVersion("5.9", "5.8.9"))
        assertFalse(AppUpdater.isNewerVersion("5.8.8", "5.8.8"))
        assertFalse(AppUpdater.isNewerVersion("5.8.7", "5.8.8"))
    }

    @Test
    fun updateIsHeldBackOnlyWhenNewer() {
        assertEquals(AppUpdater.Decision.SHOW, AppUpdater.decide("5.8.9", "5.8.8", holdBack = false))
        assertEquals(AppUpdater.Decision.HOLD, AppUpdater.decide("5.8.9", "5.8.8", holdBack = true))
        assertEquals(AppUpdater.Decision.UP_TO_DATE, AppUpdater.decide("5.8.8", "5.8.8", holdBack = true))
    }

    @Test
    fun pickApkPrefersTheDevicesFirstAbi() {
        assertEquals(arm, AppUpdateInstaller.pickApk(listOf(x86, arm), listOf("arm64-v8a", "armeabi-v7a"), defaultMatcher))
        assertEquals(x86, AppUpdateInstaller.pickApk(listOf(arm, x86), listOf("x86_64", "x86", "arm64-v8a"), defaultMatcher))
        assertEquals(arm, AppUpdateInstaller.pickApk(listOf(arm), listOf("x86_64", "arm64-v8a"), defaultMatcher))
        assertNull(AppUpdateInstaller.pickApk(listOf(arm, x86), listOf("armeabi-v7a"), defaultMatcher))
    }

    @Test
    fun pickApkUsesTheAppsOwnNamingRule() {
        val custom = AppUpdater.ReleaseAsset("bot-x86_64.apk", 3L, "https://github.com/c.apk")
        assertEquals(custom, AppUpdateInstaller.pickApk(listOf(x86, custom), listOf("x86_64"), { name, abi -> name == "bot-$abi.apk" }))
    }
}
