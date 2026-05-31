package com.forensics.core

import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.handler.exif.TestExifJpeg
import com.forensics.core.io.InMemoryByteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectorTest {
    private val inspector = Inspector(listOf(JpegExifHandler()))

    @Test fun inspectsJpegWithHandlerFields() {
        val src = InMemoryByteSource(TestExifJpeg.build(3, "2020:01:02 03:04:05"))
        val result = inspector.inspect(src)
        assertEquals("JPEG/EXIF", result.handlerName)
        assertTrue(result.fields.any { it.key == "Orientation" })
        assertTrue(result.md5.isNotEmpty())
    }

    @Test fun unknownFormatStillReturnsHashesAndNoHandler() {
        val src = InMemoryByteSource(byteArrayOf(0, 1, 2, 3))
        val result = inspector.inspect(src)
        assertEquals(null, result.handlerName)
        assertTrue(result.fields.isEmpty())
        assertTrue(result.sha256.isNotEmpty())
    }
}
