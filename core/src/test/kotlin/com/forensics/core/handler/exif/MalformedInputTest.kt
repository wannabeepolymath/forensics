package com.forensics.core.handler.exif

import com.forensics.core.io.InMemoryByteSource
import kotlin.test.Test
import kotlin.test.assertTrue

class MalformedInputTest {
    private val handler = JpegExifHandler()

    @Test fun truncatedJpegDoesNotThrow() {
        val full = TestExifJpeg.build(1, "2020:01:02 03:04:05")
        val truncated = full.copyOf(full.size / 2)
        val fields = handler.parse(InMemoryByteSource(truncated)) // must not throw
        assertTrue(fields.size >= 0)
    }

    @Test fun jpegWithoutExifReturnsEmpty() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertTrue(handler.parse(InMemoryByteSource(bytes)).isEmpty())
    }

    @Test fun garbageBytesReturnEmptyOrNoThrow() {
        val bytes = ByteArray(200) { (it * 7).toByte() }
        bytes[0] = 0xFF.toByte(); bytes[1] = 0xD8.toByte(); bytes[2] = 0xFF.toByte()
        handler.parse(InMemoryByteSource(bytes)) // must not throw
    }

    @Test fun corruptEntryWithHugeOffsetDoesNotThrow() {
        // Take a valid EXIF JPEG and corrupt the DateTime entry's value-offset to a huge (negative-as-Int) u32.
        val jpeg = TestExifJpeg.build(1, "2020:01:02 03:04:05").copyOf()
        val base = TestExifJpeg.TIFF_BASE
        val ifd0 = 8
        // DateTime is entry index 3; its value/offset cell is at entry+8.
        val e3 = base + ifd0 + 2 + 3 * 12
        val cell = e3 + 8
        // write 0xFFFFFFFF -> as Int = -1
        jpeg[cell] = 0xFF.toByte(); jpeg[cell + 1] = 0xFF.toByte()
        jpeg[cell + 2] = 0xFF.toByte(); jpeg[cell + 3] = 0xFF.toByte()
        handler.parse(InMemoryByteSource(jpeg)) // MUST NOT THROW (negative offset must be guarded)
    }
}
