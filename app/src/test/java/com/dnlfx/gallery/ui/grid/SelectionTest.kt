package com.dnlfx.gallery.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionTest {
    private val ids = listOf(10L, 11L, 12L, 13L, 14L)

    @Test
    fun holdingSelectsJustThatItem() {
        assertEquals(setOf(12L), dragSelection(emptySet(), ids, anchor = 2, current = 2, adding = true))
    }

    @Test
    fun slidingSelectsEverythingBetweenInEitherDirection() {
        assertEquals(setOf(12L, 13L, 14L), dragSelection(emptySet(), ids, anchor = 2, current = 4, adding = true))
        assertEquals(setOf(10L, 11L, 12L), dragSelection(emptySet(), ids, anchor = 2, current = 0, adding = true))
    }

    @Test
    fun slidingBackShrinksTheRangeButKeepsEarlierPicks() {
        val before = setOf(10L)
        assertEquals(setOf(10L, 13L, 14L), dragSelection(before, ids, anchor = 3, current = 4, adding = true))
        assertEquals(setOf(10L, 13L), dragSelection(before, ids, anchor = 3, current = 3, adding = true))
    }

    @Test
    fun startingOnASelectedItemDeselects() {
        val before = setOf(10L, 11L, 12L, 13L)
        assertEquals(setOf(10L, 13L), dragSelection(before, ids, anchor = 1, current = 2, adding = false))
    }

    @Test
    fun indexesPastTheEndAreClamped() {
        assertEquals(setOf(13L, 14L), dragSelection(emptySet(), ids, anchor = 3, current = 9, adding = true))
        assertEquals(emptySet<Long>(), dragSelection(emptySet(), emptyList(), anchor = 0, current = 0, adding = true))
    }

    @Test
    fun daysLeftRoundsAPartDayUp() {
        val now = 1_000_000L
        assertEquals(30, daysLeft(now + 30 * 86_400, now))
        assertEquals(30, daysLeft(now + 29 * 86_400 + 1, now))
        assertEquals(1, daysLeft(now + 60, now))
        assertEquals(0, daysLeft(now - 60, now))
    }
}
