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
        assertNull(GameTarget.startBlockReason(name, emptySet(), installed = false))
    }

    @Test
    fun gameNotInstalledBlocksWithItsNameAndPackage() {
        assertEquals("Umamusume Pretty Derby (com.cygames.umamusume) is not installed", GameTarget.startBlockReason(name, packages, installed = false))
    }

    @Test
    fun installedGameStarts() {
        assertNull(GameTarget.startBlockReason(name, packages, installed = true))
    }

    @Test
    fun notInstalledMessageListsEveryPackage() {
        GameTarget.configure("Game", linkedSetOf("a.b", "c.d"))
        assertEquals("Game (a.b, c.d) is not installed", GameTarget.notInstalledMessage())
        GameTarget.configure("", emptySet())
    }
}
