package com.forensics.core.app

import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.handler.exif.TestExifJpeg
import com.forensics.core.io.InMemoryByteSink
import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.EditResult
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataControllerTest {
    private val controller = MetadataController(listOf(JpegExifHandler()))

    @Test fun inspectReturnsFieldsAndHashes() {
        val src = InMemoryByteSource(TestExifJpeg.build(3, "2020:01:02 03:04:05"))
        val result = controller.inspect(src)
        assertEquals("JPEG/EXIF", result.handlerName)
        assertTrue(result.fields.any { it.key == "Orientation" })
    }

    @Test fun editOrientationSucceedsAndPersists() {
        val bytes = TestExifJpeg.build(1, "2020:01:02 03:04:05")
        val sink = InMemoryByteSink(bytes)
        val field = controller.inspect(InMemoryByteSource(sink.snapshot()))
            .fields.first { it.key == "Orientation" }

        val result = controller.edit(sink, InMemoryByteSource(sink.snapshot()), field, Value.Integer(8))

        assertTrue(result is EditResult.Success)
        val after = controller.inspect(InMemoryByteSource(sink.snapshot()))
        assertEquals(Value.Integer(8), after.fields.first { it.key == "Orientation" }.value)
    }

    @Test fun editWithNoMatchingHandlerFails() {
        val sink = InMemoryByteSink(byteArrayOf(0, 1, 2, 3))
        val field = com.forensics.core.model.MetadataField(
            "X", Value.Integer(1), 0, 2,
            com.forensics.core.model.FieldType.FIXED, true, "EXIF",
        )
        val result = controller.edit(sink, InMemoryByteSource(byteArrayOf(0, 1, 2, 3)), field, Value.Integer(2))
        assertTrue(result is EditResult.Failure)
    }
}
