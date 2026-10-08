package com.dnlfx.gallery.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollMathTest {
    @Test
    fun fractionRunsFromTopToBottom() {
        assertEquals(0f, scrollFraction(0, 40, 10_040), 0f)
        assertEquals(0.5f, scrollFraction(5_000, 40, 10_040), 0f)
        assertEquals(1f, scrollFraction(10_000, 40, 10_040), 0f)
        assertEquals(0f, scrollFraction(0, 40, 30), 0f)
    }

    @Test
    fun indexForFractionInvertsTheFraction() {
        assertEquals(0, indexForFraction(0f, 40, 10_040))
        assertEquals(5_000, indexForFraction(0.5f, 40, 10_040))
        assertEquals(10_000, indexForFraction(1f, 40, 10_040))
        assertEquals(10_000, indexForFraction(2f, 40, 10_040))
        assertEquals(0, indexForFraction(0.5f, 40, 0))
    }
}
