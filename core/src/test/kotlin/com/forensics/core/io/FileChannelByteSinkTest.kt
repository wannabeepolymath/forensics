package com.forensics.core.io

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileChannelByteSinkTest {
    private val tmp: File = File.createTempFile("sink", ".bin")

    @AfterTest fun cleanup() { tmp.delete() }

    private fun sinkOf(initial: ByteArray): Pair<RandomAccessFile, FileChannelByteSink> {
        tmp.writeBytes(initial)
        val raf = RandomAccessFile(tmp, "rw")
        return raf to FileChannelByteSink(raf.channel)
    }

    @Test fun writeAtKeepsLengthAndOverwrites() {
        val (raf, sink) = sinkOf(byteArrayOf(1, 2, 3, 4))
        raf.use {
            sink.writeAt(1, byteArrayOf(9, 9))
            sink.force()
            assertEquals(listOf<Byte>(1, 9, 9, 4), sink.readAt(0, 4).toList())
            assertEquals(4L, sink.size())
        }
        assertEquals(listOf<Byte>(1, 9, 9, 4), tmp.readBytes().toList())
    }

    @Test fun writePastEndFails() {
        val (raf, sink) = sinkOf(byteArrayOf(1, 2))
        raf.use {
            assertFailsWith<IllegalArgumentException> { sink.writeAt(1, byteArrayOf(5, 6)) }
        }
    }

    @Test fun rewriteShorterTruncates() {
        val (raf, sink) = sinkOf(byteArrayOf(1, 2, 3, 4, 5))
        raf.use {
            sink.rewrite(ByteArrayInputStream(byteArrayOf(8, 8)))
            sink.force()
            assertEquals(2L, sink.size())
            assertEquals(listOf<Byte>(8, 8), sink.readAt(0, 2).toList())
        }
        assertEquals(listOf<Byte>(8, 8), tmp.readBytes().toList())
    }

    @Test fun rewriteLongerGrows() {
        val (raf, sink) = sinkOf(byteArrayOf(1, 2))
        raf.use {
            sink.rewrite(ByteArrayInputStream(byteArrayOf(7, 7, 7, 7)))
            sink.force()
            assertEquals(4L, sink.size())
            assertEquals(listOf<Byte>(7, 7, 7, 7), sink.readAt(0, 4).toList())
        }
    }
}
