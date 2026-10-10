package com.steve1316.automation_library.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the check `GameTarget` runs before a run starts. */
class GameTargetTest {
    private val name = "Umamusume Pretty Derby"
    private val packages = setOf("com.cygames.umamusume")

    @Test
    fun noGameConfiguredNeverBlocks() {
        assertNull(GameTarget.startBlockReason(name, emptySet(), installed = false, foreground = "com.other.app"))
    }

    @Test
    fun gameNotInstalledBlocksWithItsNameAndPackage() {
        assertEquals(
            "Umamusume Pretty Derby (com.cygames.umamusume) is not installed",
            GameTarget.startBlockReason(name, packages, installed = false, foreground = null),
        )
    }

    @Test
    fun notInstalledWinsOverAnotherAppInFront() {
        assertEquals(
            "Umamusume Pretty Derby (com.cygames.umamusume) is not installed",
            GameTarget.startBlockReason(name, packages, installed = false, foreground = "com.other.app"),
        )
    }

    @Test
    fun anotherAppInFrontBlocks() {
        assertEquals(GameTarget.NOT_ON_SCREEN_REASON, GameTarget.startBlockReason(name, packages, installed = true, foreground = "com.other.app"))
    }

    @Test
    fun gameInFrontStarts() {
        assertNull(GameTarget.startBlockReason(name, packages, installed = true, foreground = "com.cygames.umamusume"))
    }

    @Test
    fun unknownAppInFrontStarts() {
        assertNull(GameTarget.startBlockReason(name, packages, installed = true, foreground = null))
    }

    @Test
    fun notInstalledMessageListsEveryPackage() {
        GameTarget.configure("Game", linkedSetOf("a.b", "c.d"))
        assertEquals("Game (a.b, c.d) is not installed", GameTarget.notInstalledMessage())
        GameTarget.configure("", emptySet())
    }
}
