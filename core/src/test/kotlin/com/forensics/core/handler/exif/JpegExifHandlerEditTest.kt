package com.forensics.core.handler.exif

import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegExifHandlerEditTest {
    private val handler = JpegExifHandler()
    private fun source() = InMemoryByteSource(TestExifJpeg.build(1, "2020:01:02 03:04:05"))

    @Test fun orientationEditIsInPlaceSameLength() {
        val src = source()
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(6))
        assertTrue(plan is EditPlan.InPlace)
        plan as EditPlan.InPlace
        assertEquals(2, plan.newBytes.size)
        assertEquals(field.byteLength, plan.newBytes.size)
        assertEquals(field.byteOffset, plan.offset)
    }

    @Test fun orientationOutOfRangeRejected() {
        val src = source()
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(99))
        assertTrue(plan is EditPlan.Rejected)
    }

    @Test fun sameLengthDateTimeIsInPlace() {
        val src = source()
        val field = handler.parse(src).first { it.key == "DateTime" }
        val plan = handler.validateEdit(src, field, Value.Text("2021:11:12 13:14:15"))
        assertTrue(plan is EditPlan.InPlace)
        plan as EditPlan.InPlace
        assertEquals(field.byteLength, plan.newBytes.size)
    }

    @Test fun longerStringRequiresRewrite() {
        val src = source()
        val field = handler.parse(src).first { it.key == "Make" } // "ACME " = 5 bytes
        val plan = handler.validateEdit(src, field, Value.Text("A-Much-Longer-Maker"))
        assertTrue(plan is EditPlan.RequiresRewrite)
    }

    @Test fun shorterStringIsInPlacePaddedWithNul() {
        val src = source()
        val field = handler.parse(src).first { it.key == "Make" } // 5 bytes
        val plan = handler.validateEdit(src, field, Value.Text("Hi"))
        assertTrue(plan is EditPlan.InPlace)
        plan as EditPlan.InPlace
        assertEquals(field.byteLength, plan.newBytes.size) // padded to original length
        assertEquals(0.toByte(), plan.newBytes.last())      // NUL padding
    }
}
