package com.forensics.core.handler.exif

import kotlin.test.Test
import kotlin.test.assertEquals

class TestExifJpegTest {
    @Test fun startsWithJpegSoiThenApp1Exif() {
        val jpeg = TestExifJpeg.build(orientation = 1, dateTime = "2020:01:02 03:04:05")
        assertEquals(0xFF, jpeg[0].toInt() and 0xFF) // SOI
        assertEquals(0xD8, jpeg[1].toInt() and 0xFF)
        assertEquals(0xFF, jpeg[2].toInt() and 0xFF) // APP1
        assertEquals(0xE1, jpeg[3].toInt() and 0xFF)
        // "Exif\0\0" identifier at offset 6
        assertEquals("Exif", String(jpeg, 6, 4, Charsets.US_ASCII))
    }

    @Test fun tiffStructureIsSelfConsistent() {
        val orientation = 6
        val date = "2020:01:02 03:04:05"
        val jpeg = TestExifJpeg.build(orientation = orientation, dateTime = date)
        val base = TestExifJpeg.TIFF_BASE
        // Byte order "MM" (big-endian) at TIFF base
        assertEquals("MM", String(jpeg, base, 2, Charsets.US_ASCII))
        // 0x002A magic
        assertEquals(0x002A, ((jpeg[base + 2].toInt() and 0xFF) shl 8) or (jpeg[base + 3].toInt() and 0xFF))
        // IFD0 offset == 8
        val ifd0 = readU32BE(jpeg, base + 4).toInt()
        assertEquals(8, ifd0)
        // entry count == 4
        val count = ((jpeg[base + ifd0].toInt() and 0xFF) shl 8) or (jpeg[base + ifd0 + 1].toInt() and 0xFF)
        assertEquals(4, count)
        // First entry is Orientation, SHORT, count 1, inline value == orientation
        val e0 = base + ifd0 + 2
        val tag0 = ((jpeg[e0].toInt() and 0xFF) shl 8) or (jpeg[e0 + 1].toInt() and 0xFF)
        assertEquals(ExifTags.ORIENTATION, tag0)
        val type0 = ((jpeg[e0 + 2].toInt() and 0xFF) shl 8) or (jpeg[e0 + 3].toInt() and 0xFF)
        assertEquals(ExifType.SHORT.code, type0)
        val inlineOrient = ((jpeg[e0 + 8].toInt() and 0xFF) shl 8) or (jpeg[e0 + 9].toInt() and 0xFF)
        assertEquals(orientation, inlineOrient)
        // DateTime entry (4th, index 3) points to an overflow offset whose bytes decode to the date
        val e3 = base + ifd0 + 2 + 3 * 12
        val dtTag = ((jpeg[e3].toInt() and 0xFF) shl 8) or (jpeg[e3 + 1].toInt() and 0xFF)
        assertEquals(ExifTags.DATETIME, dtTag)
        val dtCount = readU32BE(jpeg, e3 + 4).toInt()
        val dtLocalOffset = readU32BE(jpeg, e3 + 8).toInt()
        val dtAbs = base + dtLocalOffset
        val dtText = String(jpeg, dtAbs, dtCount, Charsets.US_ASCII).trimEnd(' ')
        assertEquals(date, dtText)
    }

    private fun readU32BE(b: ByteArray, at: Int): Long {
        return ((b[at].toInt() and 0xFF).toLong() shl 24) or
            ((b[at + 1].toInt() and 0xFF).toLong() shl 16) or
            ((b[at + 2].toInt() and 0xFF).toLong() shl 8) or
            (b[at + 3].toInt() and 0xFF).toLong()
    }
}
