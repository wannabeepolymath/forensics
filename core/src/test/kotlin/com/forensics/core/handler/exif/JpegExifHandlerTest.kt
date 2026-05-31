package com.forensics.core.handler.exif

import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.FieldType
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegExifHandlerTest {
    private val handler = JpegExifHandler()

    private fun source(orientation: Int = 1, dateTime: String = "2020:01:02 03:04:05") =
        InMemoryByteSource(TestExifJpeg.build(orientation, dateTime))

    @Test fun canHandleJpegMagic() {
        assertTrue(handler.canHandle(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg"))
    }

    @Test fun rejectsNonJpeg() {
        assertTrue(!handler.canHandle(byteArrayOf(0x00, 0x01), null))
    }

    @Test fun parsesOrientationAsFixedInteger() {
        val fields = handler.parse(source(orientation = 6))
        val orient = fields.first { it.key == "Orientation" }
        assertEquals(Value.Integer(6), orient.value)
        assertEquals(FieldType.FIXED, orient.type)
        assertTrue(orient.editable)
        assertEquals(2, orient.byteLength)
    }

    @Test fun parsesDateTimeAsText() {
        val fields = handler.parse(source(dateTime = "2020:01:02 03:04:05"))
        val dt = fields.first { it.key == "DateTime" }
        assertEquals(Value.Text("2020:01:02 03:04:05"), dt.value)
        assertEquals(FieldType.FIXED, dt.type)
        assertEquals("EXIF", dt.group)
    }

    @Test fun parsesAsciiMakeModel() {
        val fields = handler.parse(source())
        assertEquals(Value.Text("ACME"), fields.first { it.key == "Make" }.value)
        assertEquals(Value.Text("CamX"), fields.first { it.key == "Model" }.value)
    }
}
