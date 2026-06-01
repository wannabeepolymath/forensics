package com.forensics.core.engine

import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.handler.exif.TestExifJpeg
import com.forensics.core.io.FileChannelByteSink
import com.forensics.core.io.FileChannelByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.EditResult
import com.forensics.core.model.Value
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileBackedEditEngineTest {
    private val handler = JpegExifHandler()
    private val tmp: File = File.createTempFile("forensics", ".jpg")

    @AfterTest fun cleanup() { tmp.delete() }

    @Test fun realFileInPlaceEditPersistsAndKeepsSize() {
        tmp.writeBytes(TestExifJpeg.build(orientation = 1, dateTime = "2020:01:02 03:04:05"))
        val originalSize = tmp.length()
        val originalBytes = tmp.readBytes()

        RandomAccessFile(tmp, "rw").use { raf ->
            val channel = raf.channel
            val sink = FileChannelByteSink(channel)
            val src = FileChannelByteSource(channel)
            val field = handler.parse(src).first { it.key == "Orientation" }
            val plan = handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace
            val result = EditEngine(handler).apply(sink, plan, Guard.capture(src), field, Value.Integer(8))
            assertTrue(result is EditResult.Success)
        }

        assertEquals(originalSize, tmp.length())
        RandomAccessFile(tmp, "r").use { raf ->
            val reparsed = handler.parse(FileChannelByteSource(raf.channel))
            assertEquals(Value.Integer(8), reparsed.first { it.key == "Orientation" }.value)
        }
        val newBytes = tmp.readBytes()
        assertEquals(originalSize.toInt(), newBytes.size)
        val diffCount = originalBytes.indices.count { originalBytes[it] != newBytes[it] }
        assertTrue(diffCount in 1..2, "expected a tiny in-place delta, got $diffCount changed bytes")
    }

    @Test fun realFileRoundTripIsBitIdentical() {
        tmp.writeBytes(TestExifJpeg.build(orientation = 1, dateTime = "2020:01:02 03:04:05"))
        val originalBytes = tmp.readBytes()

        RandomAccessFile(tmp, "rw").use { raf ->
            val sink = FileChannelByteSink(raf.channel)
            val engine = EditEngine(handler)
            var src = FileChannelByteSource(raf.channel)
            var field = handler.parse(src).first { it.key == "Orientation" }
            engine.apply(sink, handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace,
                Guard.capture(src), field, Value.Integer(8))
            src = FileChannelByteSource(raf.channel)
            field = handler.parse(src).first { it.key == "Orientation" }
            engine.apply(sink, handler.validateEdit(src, field, Value.Integer(1)) as EditPlan.InPlace,
                Guard.capture(src), field, Value.Integer(1))
        }
        assertTrue(originalBytes.contentEquals(tmp.readBytes()))
    }
}
