package com.steve1316.automation_library.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for when `CaptureGcBudget` asks for a garbage collection. */
class CaptureGcBudgetTest {
    @Test
    fun firesOnlyWhenTheLimitIsReached() {
        val budget = CaptureGcBudget(limitBytes = 100L)
        assertFalse(budget.add(40L))
        assertFalse(budget.add(59L))
        assertTrue(budget.add(1L))
    }

    @Test
    fun startsOverAfterFiring() {
        val budget = CaptureGcBudget(limitBytes = 100L)
        assertTrue(budget.add(150L))
        assertFalse(budget.add(99L))
        assertTrue(budget.add(1L))
    }
}
