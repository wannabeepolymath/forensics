package com.forensics.core.app

import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.handler.exif.TestExifJpeg
import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertTrue

class PreviewEditTest {
    private val controller = MetadataController(listOf(JpegExifHandler()))
    private fun source() = InMemoryByteSource(TestExifJpeg.build(1, "2020:01:02 03:04:05"))

    @Test fun sameLengthIntegerEditPreviewsInPlace() {
        val src = source()
        val field = controller.inspect(src).fields.first { it.key == "Orientation" }
        assertTrue(controller.previewEdit(src, field, Value.Integer(8)) is EditClassification.InPlace)
    }

    @Test fun longerTextPreviewsRewrite() {
        val src = source()
        val field = controller.inspect(src).fields.first { it.key == "Make" }
        assertTrue(controller.previewEdit(src, field, Value.Text("A-Much-Longer-Maker")) is EditClassification.Rewrite)
    }

    @Test fun outOfRangePreviewsRejected() {
        val src = source()
        val field = controller.inspect(src).fields.first { it.key == "Orientation" }
        assertTrue(controller.previewEdit(src, field, Value.Integer(99)) is EditClassification.Rejected)
    }

    @Test fun noHandlerPreviewsRejected() {
        val src = InMemoryByteSource(byteArrayOf(0, 1, 2, 3))
        val field = com.forensics.core.model.MetadataField(
            "X", Value.Integer(1), 0, 2,
            com.forensics.core.model.FieldType.FIXED, true, "EXIF",
        )
        assertTrue(controller.previewEdit(src, field, Value.Integer(2)) is EditClassification.Rejected)
    }
}
