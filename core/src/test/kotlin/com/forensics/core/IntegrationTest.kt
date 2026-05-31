package com.forensics.core

import com.forensics.core.engine.EditEngine
import com.forensics.core.engine.Guard
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

/**
 * End-to-end exercise of the full public consumption pattern an Android UI will copy:
 * Inspector.inspect (discover fields) -> handler.validateEdit (compile plan) ->
 * EditEngine.apply (corruption-safe write) -> re-inspect (confirm on disk).
 */
class IntegrationTest {
    private val handler = JpegExifHandler()
    private val inspector = Inspector(listOf(handler))

    @Test fun inspectThenEditOrientationInPlaceThenReInspect() {
        val original = TestExifJpeg.build(orientation = 1, dateTime = "2020:01:02 03:04:05")
        val sink = InMemoryByteSink(original)

        // 1. Inspect (what the UI shows the user).
        val before = inspector.inspect(InMemoryByteSource(sink.snapshot()))
        assertEquals("JPEG/EXIF", before.handlerName)
        val orientation = before.fields.first { it.key == "Orientation" }
        assertEquals(Value.Integer(1), orientation.value)

        // 2. The user edits Orientation -> 8. Compile + apply through the safety engine.
        val src = InMemoryByteSource(sink.snapshot())
        val plan = handler.validateEdit(src, orientation, Value.Integer(8))
        assertTrue(plan is EditPlan.InPlace)
        val result = EditEngine(handler).apply(sink, plan, Guard.capture(src), orientation, Value.Integer(8))
        assertTrue(result is EditResult.Success)

        // 3. Re-inspect the on-disk bytes: the change is persisted, size is unchanged,
        //    and the file still parses cleanly (no corruption).
        assertEquals(original.size.toLong(), sink.size())
        val after = inspector.inspect(InMemoryByteSource(sink.snapshot()))
        assertEquals(Value.Integer(8), after.fields.first { it.key == "Orientation" }.value)
        // Hashes recomputed over the genuinely-modified file differ from the original.
        assertTrue(after.sha256 != before.sha256)
    }
}
