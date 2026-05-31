package com.forensics.core.generic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MagicDetectorTest {
    @Test fun detectsJpeg() {
        val magic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(DetectedFormat.JPEG, MagicDetector.detect(magic))
    }

    @Test fun detectsPng() {
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(DetectedFormat.PNG, MagicDetector.detect(magic))
    }

    @Test fun unknownReturnsNull() {
        assertNull(MagicDetector.detect(byteArrayOf(0x00, 0x01, 0x02)))
    }
}
