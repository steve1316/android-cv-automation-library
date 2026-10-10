package com.steve1316.automation_library.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val GAME = "com.cygames.umamusume"
private const val GAME_ACTIVITY = "jp.co.cygames.umamusume_activity.UmamusumeActivity"
private const val OWN = "com.steve1316.uma_android_automation"
private const val KEYBOARD = "com.sohu.inputmethod.sogou.chuizi"
private const val LAUNCHER = "app.lawnchair"
private const val SETTINGS = "com.android.settings"
private const val SYSTEM_UI = FocusWatch.SYSTEM_UI_PACKAGE

/** Unit tests for judging window changes against the game, this app, keyboards, and System UI in `FocusWatch`. Rows mirror the truth table. */
class FocusWatchTest {
    private val rules = FocusRules(gamePackages = setOf(GAME), ownPackage = OWN, inputMethodPackages = setOf(KEYBOARD))

    @Test
    fun gameWindowIsInFront() {
        assertEquals(FocusVerdict.GAME_IN_FRONT, FocusWatch.classify(WindowChange(GAME, GAME_ACTIVITY, GAME), rules))
    }

    @Test
    fun shadeAndQuickSettingsLeaveTheGame() {
        assertEquals(FocusVerdict.GAME_LEFT, FocusWatch.classify(WindowChange(SYSTEM_UI, "android.widget.FrameLayout", SYSTEM_UI), rules))
        assertEquals(FocusVerdict.GAME_LEFT, FocusWatch.classify(WindowChange(SYSTEM_UI, "android.widget.FrameLayout", null), rules))
    }

    @Test
    fun appSwitcherHomeAndOtherAppsLeaveTheGame() {
        assertEquals(FocusVerdict.GAME_LEFT, FocusWatch.classify(WindowChange(LAUNCHER, "android.widget.ListView", LAUNCHER), rules))
        assertEquals(FocusVerdict.GAME_LEFT, FocusWatch.classify(WindowChange(LAUNCHER, "app.lawnchair.LawnchairLauncher", LAUNCHER), rules))
        assertEquals(FocusVerdict.GAME_LEFT, FocusWatch.classify(WindowChange(SETTINGS, "android.widget.FrameLayout", SETTINGS), rules))
        assertEquals(FocusVerdict.GAME_LEFT, FocusWatch.classify(WindowChange(SETTINGS, "com.android.settings.homepage.SettingsHomepageActivity", SETTINGS), rules))
    }

    @Test
    fun headsUpBannerOverTheGameIsIgnored() {
        assertEquals(FocusVerdict.IGNORE, FocusWatch.classify(WindowChange(SYSTEM_UI, "android.widget.FrameLayout", GAME), rules))
    }

    @Test
    fun keyboardIsIgnored() {
        assertEquals(FocusVerdict.IGNORE, FocusWatch.classify(WindowChange(KEYBOARD, "android.inputmethodservice.SoftInputWindow", GAME), rules))
    }

    @Test
    fun ownAppScreenInFrontLeavesTheGame() {
        assertEquals(FocusVerdict.GAME_LEFT, FocusWatch.classify(WindowChange(OWN, "com.steve1316.uma_android_automation.MainActivity", OWN), rules))
    }

    @Test
    fun ownActivityLeavesTheGameEvenWhenTheActiveWindowIsUnknown() {
        assertEquals(FocusVerdict.GAME_LEFT, FocusWatch.classify(WindowChange(OWN, "com.steve1316.uma_android_automation.MainActivity", null), rules))
    }

    @Test
    fun keyboardOverAnotherAppCountsAsThatApp() {
        // Bringing this app forward with its search box focused only reports the keyboard, so the app under it decides.
        assertEquals(FocusVerdict.GAME_LEFT, FocusWatch.classify(WindowChange(KEYBOARD, "android.inputmethodservice.SoftInputWindow", OWN), rules))
        assertEquals(FocusVerdict.GAME_LEFT, FocusWatch.classify(WindowChange(KEYBOARD, "android.inputmethodservice.SoftInputWindow", SETTINGS), rules))
        assertEquals(FocusVerdict.IGNORE, FocusWatch.classify(WindowChange(KEYBOARD, "android.inputmethodservice.SoftInputWindow", null), rules))
    }

    @Test
    fun ownToastsStartingWindowAndOverlaysAreIgnored() {
        assertEquals(FocusVerdict.IGNORE, FocusWatch.classify(WindowChange(OWN, "android.widget.Toast\$TN", GAME), rules))
        assertEquals(FocusVerdict.IGNORE, FocusWatch.classify(WindowChange(OWN, "android.widget.FrameLayout", GAME), rules))
    }

    @Test
    fun eventsWithoutAPackageAreIgnored() {
        assertEquals(FocusVerdict.IGNORE, FocusWatch.classify(WindowChange(null, null, null), rules))
        assertEquals(FocusVerdict.IGNORE, FocusWatch.classify(WindowChange("", null, GAME), rules))
    }

    @Test
    fun foregroundFollowsRealApps() {
        assertEquals(LAUNCHER, FocusWatch.nextForeground(WindowChange(LAUNCHER, null, LAUNCHER), rules, GAME))
        assertEquals(GAME, FocusWatch.nextForeground(WindowChange(GAME, null, GAME), rules, LAUNCHER))
    }

    @Test
    fun foregroundSkipsSystemUiOwnOverlaysAndKeyboards() {
        assertEquals(GAME, FocusWatch.nextForeground(WindowChange(SYSTEM_UI, null, SYSTEM_UI), rules, GAME))
        assertEquals(GAME, FocusWatch.nextForeground(WindowChange(OWN, null, GAME), rules, GAME))
        assertEquals(GAME, FocusWatch.nextForeground(WindowChange(KEYBOARD, null, GAME), rules, GAME))
        assertNull(FocusWatch.nextForeground(WindowChange(null, null, null), rules, null))
    }

    @Test
    fun foregroundBecomesOwnPackageWhenItsScreenIsFocused() {
        assertEquals(OWN, FocusWatch.nextForeground(WindowChange(OWN, null, OWN), rules, GAME))
        assertEquals(OWN, FocusWatch.nextForeground(WindowChange(OWN, "com.steve1316.uma_android_automation.MainActivity", null), rules, GAME))
    }

    @Test
    fun foregroundFollowsTheAppUnderAKeyboard() {
        assertEquals(OWN, FocusWatch.nextForeground(WindowChange(KEYBOARD, null, OWN), rules, GAME))
        assertEquals(SETTINGS, FocusWatch.nextForeground(WindowChange(KEYBOARD, null, SETTINGS), rules, GAME))
        assertEquals(GAME, FocusWatch.nextForeground(WindowChange(KEYBOARD, null, null), rules, GAME))
    }
}
