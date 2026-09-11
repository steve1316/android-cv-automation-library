package com.steve1316.automation_library.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the rules that map run state to what the overlay draws and what a tap does. */
class OverlayStateLogicTest {
    private val running = BotStatus.Snapshot(current = 34, total = 72, label = "Turn 34/72", detail = "Trained Speed", elapsedMs = 4_325_000L)
    private val none = BotHold.PauseState.NONE

    @Test
    fun visualFollowsRunPauseAndOutcome() {
        assertEquals(OverlayVisual.READY, OverlayStateLogic.visualFor(false, false, none, null))
        assertEquals(OverlayVisual.RUNNING, OverlayStateLogic.visualFor(true, false, none, null))
        assertEquals(OverlayVisual.PAUSING, OverlayStateLogic.visualFor(true, false, BotHold.PauseState.REQUESTED, null))
        assertEquals(OverlayVisual.PAUSED, OverlayStateLogic.visualFor(true, false, BotHold.PauseState.PAUSED, null))
        assertEquals(OverlayVisual.STOPPING, OverlayStateLogic.visualFor(true, true, none, null))
        assertEquals(OverlayVisual.FINISHED, OverlayStateLogic.visualFor(false, false, none, BotStatus.Outcome.FINISHED))
        assertEquals(OverlayVisual.STOPPED, OverlayStateLogic.visualFor(false, false, none, BotStatus.Outcome.STOPPED_BY_BOT))
        assertEquals(OverlayVisual.STOPPED, OverlayStateLogic.visualFor(false, false, none, BotStatus.Outcome.CRASHED))
    }

    @Test
    fun aUserStopGoesBackToReady() {
        assertEquals(OverlayVisual.READY, OverlayStateLogic.visualFor(false, false, none, BotStatus.Outcome.STOPPED_BY_USER))
    }

    @Test
    fun trayStyleOpensTheTrayWhileRunningOrEnded() {
        assertEquals(OverlayTapAction.START, OverlayStateLogic.tapActionFor(OverlayStyle.TRAY, OverlayVisual.READY))
        assertEquals(OverlayTapAction.OPEN_TRAY, OverlayStateLogic.tapActionFor(OverlayStyle.TRAY, OverlayVisual.RUNNING))
        assertEquals(OverlayTapAction.OPEN_TRAY, OverlayStateLogic.tapActionFor(OverlayStyle.TRAY, OverlayVisual.PAUSING))
        assertEquals(OverlayTapAction.OPEN_TRAY, OverlayStateLogic.tapActionFor(OverlayStyle.TRAY, OverlayVisual.PAUSED))
        assertEquals(OverlayTapAction.OPEN_TRAY, OverlayStateLogic.tapActionFor(OverlayStyle.TRAY, OverlayVisual.FINISHED))
        assertEquals(OverlayTapAction.OPEN_TRAY, OverlayStateLogic.tapActionFor(OverlayStyle.TRAY, OverlayVisual.STOPPED))
        assertEquals(OverlayTapAction.NONE, OverlayStateLogic.tapActionFor(OverlayStyle.TRAY, OverlayVisual.STOPPING))
    }

    @Test
    fun simpleStyleStartsStopsAndResumes() {
        assertEquals(OverlayTapAction.START, OverlayStateLogic.tapActionFor(OverlayStyle.SIMPLE, OverlayVisual.READY))
        assertEquals(OverlayTapAction.STOP, OverlayStateLogic.tapActionFor(OverlayStyle.SIMPLE, OverlayVisual.RUNNING))
        assertEquals(OverlayTapAction.RESUME, OverlayStateLogic.tapActionFor(OverlayStyle.SIMPLE, OverlayVisual.PAUSING))
        assertEquals(OverlayTapAction.RESUME, OverlayStateLogic.tapActionFor(OverlayStyle.SIMPLE, OverlayVisual.PAUSED))
        assertEquals(OverlayTapAction.START, OverlayStateLogic.tapActionFor(OverlayStyle.SIMPLE, OverlayVisual.FINISHED))
        assertEquals(OverlayTapAction.START, OverlayStateLogic.tapActionFor(OverlayStyle.SIMPLE, OverlayVisual.STOPPED))
        assertEquals(OverlayTapAction.NONE, OverlayStateLogic.tapActionFor(OverlayStyle.SIMPLE, OverlayVisual.STOPPING))
    }

    @Test
    fun trayButtonsMatchTheState() {
        assertEquals(listOf(TrayButton.PAUSE, TrayButton.STOP), OverlayStateLogic.trayButtonsFor(OverlayVisual.RUNNING, true))
        assertEquals(listOf(TrayButton.RESUME, TrayButton.STOP), OverlayStateLogic.trayButtonsFor(OverlayVisual.PAUSING, true))
        assertEquals(listOf(TrayButton.RESUME, TrayButton.STOP), OverlayStateLogic.trayButtonsFor(OverlayVisual.PAUSED, true))
        assertEquals(listOf(TrayButton.START), OverlayStateLogic.trayButtonsFor(OverlayVisual.FINISHED, true))
        assertEquals(listOf(TrayButton.START), OverlayStateLogic.trayButtonsFor(OverlayVisual.STOPPED, true))
        assertTrue(OverlayStateLogic.trayButtonsFor(OverlayVisual.READY, true).isEmpty())
        assertTrue(OverlayStateLogic.trayButtonsFor(OverlayVisual.STOPPING, true).isEmpty())
    }

    @Test
    fun runningWithoutASafePointOffersStopOnly() {
        assertEquals(listOf(TrayButton.STOP), OverlayStateLogic.trayButtonsFor(OverlayVisual.RUNNING, false))
    }

    @Test
    fun ringShowsProgressAndStateColor() {
        assertEquals(OrbRing(34f / 72f, OverlayColors.GREEN, false), OverlayStateLogic.ringFor(OverlayVisual.RUNNING, running))
        assertEquals(OrbRing(34f / 72f, OverlayColors.AMBER, true), OverlayStateLogic.ringFor(OverlayVisual.PAUSING, running))
        assertEquals(OrbRing(34f / 72f, OverlayColors.AMBER, false), OverlayStateLogic.ringFor(OverlayVisual.PAUSED, running))
        assertEquals(OrbRing(0f, OverlayColors.GREEN, true), OverlayStateLogic.ringFor(OverlayVisual.STOPPING, running))
        assertEquals(OrbRing(1f, OverlayColors.GREEN, false), OverlayStateLogic.ringFor(OverlayVisual.FINISHED, running))
        assertEquals(OrbRing(1f, OverlayColors.RED, false), OverlayStateLogic.ringFor(OverlayVisual.STOPPED, running))
        assertEquals(OrbRing(0f, OverlayColors.GREEN, false), OverlayStateLogic.ringFor(OverlayVisual.READY, running))
    }

    @Test
    fun orbCenterShowsTheTurnOnlyInTrayStyleOnceKnown() {
        assertNull(OverlayStateLogic.orbCenterFor(OverlayStyle.TRAY, OverlayVisual.RUNNING, running))
        assertEquals(Glyph.STOP, OverlayStateLogic.orbCenterFor(OverlayStyle.SIMPLE, OverlayVisual.RUNNING, running))
        assertEquals(Glyph.STOP, OverlayStateLogic.orbCenterFor(OverlayStyle.TRAY, OverlayVisual.RUNNING, BotStatus.Snapshot()))
        assertEquals(Glyph.PLAY, OverlayStateLogic.orbCenterFor(OverlayStyle.TRAY, OverlayVisual.READY, running))
        assertEquals(Glyph.PAUSE, OverlayStateLogic.orbCenterFor(OverlayStyle.TRAY, OverlayVisual.PAUSED, running))
        assertEquals(Glyph.CHECK, OverlayStateLogic.orbCenterFor(OverlayStyle.TRAY, OverlayVisual.FINISHED, running))
        assertEquals(Glyph.ALERT, OverlayStateLogic.orbCenterFor(OverlayStyle.TRAY, OverlayVisual.STOPPED, running))
    }

    @Test
    fun orbCenterFallsBackToTheGlyphWhenTheStepNumberIsTooWide() {
        assertEquals(Glyph.STOP, OverlayStateLogic.orbCenterFor(OverlayStyle.TRAY, OverlayVisual.RUNNING, BotStatus.Snapshot(current = 1000)))
        assertNull(OverlayStateLogic.orbCenterFor(OverlayStyle.TRAY, OverlayVisual.RUNNING, BotStatus.Snapshot(current = 999)))
    }

    @Test
    fun onlyRunningShowsTheBreathingDot() {
        assertTrue(OverlayStateLogic.showsBreathingDot(OverlayVisual.RUNNING))
        assertFalse(OverlayStateLogic.showsBreathingDot(OverlayVisual.PAUSED))
        assertFalse(OverlayStateLogic.showsBreathingDot(OverlayVisual.READY))
    }

    @Test
    fun headlineTagsTheState() {
        assertEquals(TrayHeadline("Turn 34/72", null, OverlayColors.WHITE), OverlayStateLogic.headlineFor(OverlayVisual.RUNNING, running))
        assertEquals(TrayHeadline("Turn 34/72", "PAUSING", OverlayColors.AMBER), OverlayStateLogic.headlineFor(OverlayVisual.PAUSING, running))
        assertEquals(TrayHeadline("Turn 34/72", "PAUSED", OverlayColors.AMBER), OverlayStateLogic.headlineFor(OverlayVisual.PAUSED, running))
        assertEquals(TrayHeadline("Turn 34/72", "DONE", OverlayColors.GREEN), OverlayStateLogic.headlineFor(OverlayVisual.FINISHED, running))
        assertEquals(TrayHeadline("Turn 34/72", "STOPPED", OverlayColors.RED), OverlayStateLogic.headlineFor(OverlayVisual.STOPPED, running))
        assertEquals("Automation", OverlayStateLogic.headlineFor(OverlayVisual.RUNNING, BotStatus.Snapshot()).text)
    }

    @Test
    fun detailShowsTimeAndActionOrReason() {
        assertEquals("1:12:05 · Trained Speed", OverlayStateLogic.trayDetailFor(OverlayVisual.RUNNING, running))
        val stopped = running.copy(outcome = BotStatus.Outcome.STOPPED_BY_BOT, reason = "Mandatory race failed")
        assertEquals("1:12:05 · Mandatory race failed", OverlayStateLogic.trayDetailFor(OverlayVisual.STOPPED, stopped))
        assertEquals("1:05", OverlayStateLogic.trayDetailFor(OverlayVisual.RUNNING, BotStatus.Snapshot(elapsedMs = 65_000L)))
    }

    @Test
    fun trayDetailShowsThePauseReasonWhilePausingOrPaused() {
        assertEquals("1:12:05 · Paused: the game left the screen", OverlayStateLogic.trayDetailFor(OverlayVisual.PAUSED, running, BotHold.FOCUS_LOSS_REASON))
        assertEquals("1:12:05 · Paused: the game left the screen", OverlayStateLogic.trayDetailFor(OverlayVisual.PAUSING, running, BotHold.FOCUS_LOSS_REASON))
        assertEquals("1:12:05 · Trained Speed", OverlayStateLogic.trayDetailFor(OverlayVisual.PAUSED, running))
        assertEquals("1:12:05 · Trained Speed", OverlayStateLogic.trayDetailFor(OverlayVisual.RUNNING, running, BotHold.FOCUS_LOSS_REASON))
    }

    @Test
    fun formatElapsedDropsHoursUnderAnHour() {
        assertEquals("0:00", OverlayStateLogic.formatElapsed(0L))
        assertEquals("0:59", OverlayStateLogic.formatElapsed(59_999L))
        assertEquals("12:05", OverlayStateLogic.formatElapsed(725_000L))
        assertEquals("1:00:00", OverlayStateLogic.formatElapsed(3_600_000L))
    }

    @Test
    fun styleSettingDefaultsToTray() {
        assertEquals(OverlayStyle.SIMPLE, OverlayStyle.fromSetting("simple"))
        assertEquals(OverlayStyle.SIMPLE, OverlayStyle.fromSetting("SIMPLE"))
        assertEquals(OverlayStyle.TRAY, OverlayStyle.fromSetting("tray"))
        assertEquals(OverlayStyle.TRAY, OverlayStyle.fromSetting(""))
        assertEquals(OverlayStyle.TRAY, OverlayStyle.fromSetting("anything"))
    }

    @Test
    fun descriptionsMatchWhatATapDoes() {
        assertEquals("Start automation", OverlayStateLogic.describe(OverlayStyle.TRAY, OverlayVisual.READY))
        assertEquals("Automation running. Tap for controls", OverlayStateLogic.describe(OverlayStyle.TRAY, OverlayVisual.RUNNING))
        assertEquals("Stop automation", OverlayStateLogic.describe(OverlayStyle.SIMPLE, OverlayVisual.RUNNING))
        assertEquals("Resume automation", OverlayStateLogic.describe(OverlayStyle.SIMPLE, OverlayVisual.PAUSED))
    }
}
