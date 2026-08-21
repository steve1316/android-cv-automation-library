package com.steve1316.automation_library.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the status notification's wording, channel choice, and controls at each stage of a run. */
class StatusContentBuilderTest {
    /**
     * Builds a snapshot with Uma-like defaults.
     *
     * @param current The turn.
     * @param label The progress label.
     * @param outcome How the run ended, or null while running.
     * @param reason Why it ended.
     * @param elapsedMs Running time.
     * @return The snapshot.
     */
    private fun snap(current: Int = 34, label: String = "Turn 34/72", outcome: BotStatus.Outcome? = null, reason: String = "", elapsedMs: Long = 4_325_000L) =
        BotStatus.Snapshot(current, 72, label, "Trained Speed", outcome, reason, elapsedMs)

    @Test
    fun readyIsSilentWithStopOnly() {
        val content = StatusContentBuilder.ready()
        assertEquals("Ready", content.title)
        assertEquals("Tap the overlay to start", content.text)
        assertFalse(content.alert)
        assertEquals(PauseAction.NONE, content.pauseAction)
        assertTrue(content.showStop)
    }

    @Test
    fun runningShowsTurnActionProgressAndPause() {
        val content = StatusContentBuilder.running(snap(), BotHold.PauseState.NONE, true)
        assertEquals("Turn 34/72", content.title)
        assertEquals("Trained Speed", content.text)
        assertEquals(34 to 72, content.progress)
        assertEquals(PauseAction.PAUSE, content.pauseAction)
        assertTrue(content.chronometer)
        assertFalse(content.alert)
    }

    @Test
    fun runningWithoutASafePointHidesPause() {
        val content = StatusContentBuilder.running(snap(), BotHold.PauseState.NONE, false)
        assertEquals(PauseAction.NONE, content.pauseAction)
        assertTrue(content.showStop)
        assertEquals("Turn 34/72", content.title)
    }

    @Test
    fun runningWithoutStatusFallsBack() {
        val content = StatusContentBuilder.running(BotStatus.Snapshot(), BotHold.PauseState.NONE, true)
        assertEquals("Running", content.title)
        assertEquals("Automation is running", content.text)
        assertNull(content.progress)
    }

    @Test
    fun finaleTurnsCapTheProgressBar() {
        assertEquals(72 to 72, StatusContentBuilder.running(snap(current = 74, label = "Finale 2/3"), BotHold.PauseState.NONE, true).progress)
    }

    @Test
    fun pausingOffersResumeAndKeepsTheClockRunning() {
        val content = StatusContentBuilder.running(snap(), BotHold.PauseState.REQUESTED, true)
        assertEquals("Turn 34/72 · Pausing", content.title)
        assertEquals("Pausing after the current step", content.text)
        assertEquals(PauseAction.RESUME, content.pauseAction)
        assertTrue(content.chronometer)
    }

    @Test
    fun pausedFreezesTheClock() {
        val content = StatusContentBuilder.running(snap(), BotHold.PauseState.PAUSED, true)
        assertEquals("Turn 34/72 · Paused", content.title)
        assertEquals("Tap Resume when you are back in the game", content.text)
        assertFalse(content.chronometer)
        assertEquals("1:12:05", content.subText)
    }

    @Test
    fun finishedAlerts() {
        val content = StatusContentBuilder.ended(snap(current = 72, outcome = BotStatus.Outcome.FINISHED, reason = "Career complete", elapsedMs = 6_500_000L))
        assertEquals("Finished", content.title)
        assertEquals("Career complete. Ran for 1:48:20", content.text)
        assertTrue(content.alert)
        assertEquals(PauseAction.NONE, content.pauseAction)
    }

    @Test
    fun stoppedByTheBotKeepsTheReasonWithoutADoublePeriod() {
        val content = StatusContentBuilder.ended(snap(current = 41, label = "Turn 41/72", outcome = BotStatus.Outcome.STOPPED_BY_BOT, reason = "Stopping the bot due to failing a mandatory race."))
        assertEquals("Stopped at Turn 41/72", content.title)
        assertEquals("Stopping the bot due to failing a mandatory race. Ran for 1:12:05", content.text)
        assertTrue(content.alert)
    }

    @Test
    fun stoppedByTheUserIsSilent() {
        val content = StatusContentBuilder.ended(snap(outcome = BotStatus.Outcome.STOPPED_BY_USER, reason = "You stopped the bot"))
        assertEquals("Stopped at Turn 34/72", content.title)
        assertEquals("You stopped the bot. Ran for 1:12:05", content.text)
        assertFalse(content.alert)
    }

    @Test
    fun crashAlertsAndPointsToTheApp() {
        val content = StatusContentBuilder.ended(snap(current = 41, label = "Turn 41/72", outcome = BotStatus.Outcome.CRASHED, reason = "IllegalStateException"))
        assertEquals("Bot crashed at Turn 41/72", content.title)
        assertEquals("IllegalStateException. Open the app for details.", content.text)
        assertTrue(content.alert)
    }

    @Test
    fun endedWithoutATurnLeavesTheTurnOut() {
        val content = StatusContentBuilder.ended(BotStatus.Snapshot(outcome = BotStatus.Outcome.STOPPED_BY_BOT, reason = "Device went to sleep"))
        assertEquals("Stopped", content.title)
        assertEquals("Device went to sleep. Ran for 0:00", content.text)
    }

    @Test
    fun sentenceAddsAPeriodOnlyWhenMissing() {
        assertEquals("Done.", StatusContentBuilder.sentence("Done"))
        assertEquals("Done.", StatusContentBuilder.sentence("Done. "))
        assertEquals("Done!", StatusContentBuilder.sentence("Done!"))
        assertEquals("", StatusContentBuilder.sentence(""))
    }
}
