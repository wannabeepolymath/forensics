package com.forensics.core.engine

import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.handler.exif.TestExifJpeg
import com.forensics.core.io.InMemoryByteSink
import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.EditResult
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditEngineTest {
    private val handler = JpegExifHandler()
    private val engine = EditEngine(handler)

    private fun bytes() = TestExifJpeg.build(1, "2020:01:02 03:04:05")

    @Test fun inPlaceOrientationEditSucceedsAndKeepsSize() {
        val original = bytes()
        val sink = InMemoryByteSink(original)
        val src = InMemoryByteSource(original)
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace
        val token = Guard.capture(src)

        val result = engine.apply(sink, plan, token, field, Value.Integer(8))

        assertTrue(result is EditResult.Success)
        assertEquals(original.size.toLong(), sink.size())
        val reparsed = handler.parse(InMemoryByteSource(sink.snapshot()))
        assertEquals(Value.Integer(8), reparsed.first { it.key == "Orientation" }.value)
    }

    @Test fun roundTripBackToOriginalIsBitIdentical() {
        val original = bytes()
        val sink = InMemoryByteSink(original)
        var src = InMemoryByteSource(original)
        var field = handler.parse(src).first { it.key == "Orientation" }
        engine.apply(sink, handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace,
            Guard.capture(src), field, Value.Integer(8))
        src = InMemoryByteSource(sink.snapshot())
        field = handler.parse(src).first { it.key == "Orientation" }
        engine.apply(sink, handler.validateEdit(src, field, Value.Integer(1)) as EditPlan.InPlace,
            Guard.capture(src), field, Value.Integer(1))
        assertTrue(original.contentEquals(sink.snapshot()))
    }

    @Test fun abortsWhenSourceModifiedConcurrently() {
        val original = bytes()
        val sink = InMemoryByteSink(original)
        val src = InMemoryByteSource(original)
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace
        val staleToken = Guard.capture(src)
        sink.rewrite(byteArrayOf(0xFF.toByte(), 0xD8.toByte()).inputStream())
        val result = engine.apply(sink, plan, staleToken, field, Value.Integer(8))
        assertTrue(result is EditResult.Failure)
    }

    @Test fun rejectedPlanIsNeverWritten() {
        val sink = InMemoryByteSink(bytes())
        val before = sink.snapshot()
        val result = engine.apply(
            sink, EditPlan.Rejected("nope"),
            Guard.capture(InMemoryByteSource(before)),
            handler.parse(InMemoryByteSource(before)).first(), Value.Integer(2),
        )
        assertTrue(result is EditResult.Failure)
        assertTrue(before.contentEquals(sink.snapshot()))
    }

    @Test fun restoresOriginalWhenVerificationFails() {
        val original = bytes()
        val backing = InMemoryByteSink(original)
        // Models a TRANSIENT write fault: only the FIRST write is corrupted (high byte
        // of the value clobbered to 0x7F). Subsequent restore writes go through cleanly,
        // so verification detects the bad write and the undo buffer can recover the
        // original bit-identically.
        var firstWrite = true
        val faulty = object : com.forensics.core.io.ByteSink by backing {
            override fun writeAt(offset: Long, bytes: ByteArray) {
                if (firstWrite) {
                    firstWrite = false
                    backing.writeAt(offset, bytes)
                    backing.writeAt(offset, byteArrayOf(0x7F)) // corrupt the first write only
                } else {
                    backing.writeAt(offset, bytes) // restore writes go through cleanly
                }
            }
        }
        val src = InMemoryByteSource(original)
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace
        val result = engine.apply(faulty, plan, Guard.capture(src), field, Value.Integer(8))
        assertTrue(result is EditResult.Failure)
        assertTrue(original.contentEquals(backing.snapshot()))
    }

    @Test fun rewriteSucceedsAndReplacesWholeFile() {
        val original = TestExifJpeg.build(1, "2020:01:02 03:04:05", make = "ACME")
        val rebuilt = TestExifJpeg.build(1, "2020:01:02 03:04:05", make = "LongerMakerName")
        val sink = InMemoryByteSink(original)
        val src = InMemoryByteSource(original)
        val field = handler.parse(src).first { it.key == "Make" }
        val expected = Value.Text("LongerMakerName")
        val plan = EditPlan.RequiresRewrite("new text is longer than the original field", rebuilt)

        val result = engine.apply(sink, plan, Guard.capture(src), field, expected)

        assertTrue(result is EditResult.Success)
        assertTrue((result as EditResult.Success).inPlace.not()) // it was a rewrite
        assertTrue(rebuilt.contentEquals(sink.snapshot()))
        assertEquals(expected, handler.parse(InMemoryByteSource(sink.snapshot())).first { it.key == "Make" }.value)
    }

    @Test fun rewriteRejectedWhenRebuiltLacksExpectedValueAndFileUntouched() {
        val original = TestExifJpeg.build(1, "2020:01:02 03:04:05", make = "ACME")
        val sink = InMemoryByteSink(original)
        val before = sink.snapshot()
        val src = InMemoryByteSource(original)
        val field = handler.parse(src).first { it.key == "Make" }
        // rebuilt still has make "ACME", but we claim to expect "Different" -> staging check fails BEFORE any write.
        val plan = EditPlan.RequiresRewrite("longer", original.copyOf())

        val result = engine.apply(sink, plan, Guard.capture(src), field, Value.Text("Different"))

        assertTrue(result is EditResult.Failure)
        assertTrue(before.contentEquals(sink.snapshot())) // original untouched
    }

    @Test fun writeThrowsIsCaughtFileIntactAndRestoreFailureSurfaced() {
        val original = bytes()
        val backing = InMemoryByteSink(original)
        // A sink whose writeAt ALWAYS throws (e.g. disk full). The value write throws before any
        // byte changes; the restore write throws too, which must be surfaced in the failure reason.
        val faulty = object : com.forensics.core.io.ByteSink by backing {
            override fun writeAt(offset: Long, bytes: ByteArray): Unit =
                throw java.io.IOException("disk full")
        }
        val src = InMemoryByteSource(original)
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace

        val result = engine.apply(faulty, plan, Guard.capture(src), field, Value.Integer(8))

        assertTrue(result is EditResult.Failure)
        assertTrue((result as EditResult.Failure).reason.contains("restore ALSO failed"))
        assertTrue(original.contentEquals(backing.snapshot())) // nothing was ever written
    }
}
