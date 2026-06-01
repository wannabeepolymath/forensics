package com.forensics.core.generic

/**
 * Pure geometry for linking a byte offset (from a metadata field or a found string) to a position
 * in a [HexDump] page: which line holds it, whether the current page covers it, where to re-page so
 * it lands comfortably in view, and which lines a byte range highlights. Kept dependency-free and
 * unit-testable so the Android hex view can stay a thin renderer.
 */
object HexFocus {
    const val BYTES_PER_LINE = 16

    /** Index, within a page starting at [pageStart], of the line containing absolute [offset]. */
    fun lineIndexFor(offset: Long, pageStart: Long): Int =
        ((offset - pageStart).coerceAtLeast(0) / BYTES_PER_LINE).toInt()

    /** True if the page `[pageStart, pageStart + lineCount*16)` contains absolute [offset]. */
    fun pageContains(offset: Long, pageStart: Long, lineCount: Int): Boolean =
        offset >= pageStart && offset < pageStart + lineCount.toLong() * BYTES_PER_LINE

    /**
     * A 16-byte-aligned page start such that [offset] sits roughly a quarter into a [pageBytes]
     * window — so the target has context before it, not pinned to the very top. Never negative.
     */
    fun pageStartFor(offset: Long, pageBytes: Int): Long {
        val raw = (offset - pageBytes / 4).coerceAtLeast(0)
        return raw - (raw % BYTES_PER_LINE)
    }

    /**
     * True if the byte range `[focusStart, focusStart + focusLength)` overlaps the hex line covering
     * `[lineStart, lineStart + 16)`. Drives per-line highlighting. A non-positive length never matches.
     */
    fun lineIntersects(lineStart: Long, focusStart: Long, focusLength: Int): Boolean {
        if (focusLength <= 0) return false
        val lineEnd = lineStart + BYTES_PER_LINE
        val focusEnd = focusStart + focusLength
        return focusStart < lineEnd && lineStart < focusEnd
    }
}
