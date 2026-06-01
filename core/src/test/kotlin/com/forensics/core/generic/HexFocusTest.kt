package com.forensics.core.generic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexFocusTest {

    @Test fun lineIndexIsOffsetDividedBySixteenWithinPage() {
        assertEquals(0, HexFocus.lineIndexFor(offset = 0, pageStart = 0))
        assertEquals(0, HexFocus.lineIndexFor(offset = 15, pageStart = 0))
        assertEquals(1, HexFocus.lineIndexFor(offset = 16, pageStart = 0))
        assertEquals(2, HexFocus.lineIndexFor(offset = 40, pageStart = 0))
    }

    @Test fun lineIndexIsRelativeToPageStart() {
        // page starts at 0x100; an offset of 0x110 is the second line of the page.
        assertEquals(1, HexFocus.lineIndexFor(offset = 0x110, pageStart = 0x100))
    }

    @Test fun lineIndexClampsOffsetsBeforePageStartToZero() {
        assertEquals(0, HexFocus.lineIndexFor(offset = 5, pageStart = 0x100))
    }

    @Test fun pageContainsRespectsBothBounds() {
        // page covers bytes [256, 256 + 4*16) = [256, 320)
        assertTrue(HexFocus.pageContains(offset = 256, pageStart = 256, lineCount = 4))
        assertTrue(HexFocus.pageContains(offset = 319, pageStart = 256, lineCount = 4))
        assertFalse(HexFocus.pageContains(offset = 320, pageStart = 256, lineCount = 4))
        assertFalse(HexFocus.pageContains(offset = 255, pageStart = 256, lineCount = 4))
        assertFalse(HexFocus.pageContains(offset = 100, pageStart = 256, lineCount = 0))
    }

    @Test fun pageStartCentersTargetAndStaysAligned() {
        val start = HexFocus.pageStartFor(offset = 10_000, pageBytes = 4096)
        // a quarter of 4096 before the target, rounded down to a 16-byte boundary
        assertEquals(8976, start)
        assertEquals(0L, start % 16)
        assertTrue(start <= 10_000)
    }

    @Test fun pageStartNeverGoesNegativeNearStartOfFile() {
        assertEquals(0L, HexFocus.pageStartFor(offset = 8, pageBytes = 4096))
    }

    @Test fun lineIntersectsWhenRangeOverlapsLine() {
        // line covers [16, 32). A field at offset 20, length 4 -> [20, 24) overlaps.
        assertTrue(HexFocus.lineIntersects(lineStart = 16, focusStart = 20, focusLength = 4))
        // range straddling the boundary into this line
        assertTrue(HexFocus.lineIntersects(lineStart = 16, focusStart = 14, focusLength = 4))
    }

    @Test fun lineIntersectsFalseWhenDisjointOrEmpty() {
        assertFalse(HexFocus.lineIntersects(lineStart = 16, focusStart = 32, focusLength = 4))
        assertFalse(HexFocus.lineIntersects(lineStart = 16, focusStart = 0, focusLength = 16))
        assertFalse(HexFocus.lineIntersects(lineStart = 16, focusStart = 20, focusLength = 0))
    }
}
