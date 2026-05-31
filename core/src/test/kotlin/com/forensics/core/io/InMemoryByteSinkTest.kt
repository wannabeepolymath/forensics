package com.forensics.core.io

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryByteSinkTest {
    @Test fun writeAtKeepsLengthAndOverwrites() {
        val sink = InMemoryByteSink(byteArrayOf(1, 2, 3, 4))
        sink.writeAt(1, byteArrayOf(9, 9))
        assertEquals(listOf<Byte>(1, 9, 9, 4), sink.snapshot().toList())
        assertEquals(4L, sink.size())
    }

    @Test fun readAtReturnsCurrentBytes() {
        val sink = InMemoryByteSink(byteArrayOf(1, 2, 3, 4))
        sink.writeAt(0, byteArrayOf(7))
        assertEquals(listOf<Byte>(7, 2), sink.readAt(0, 2).toList())
    }

    @Test fun writePastEndFails() {
        val sink = InMemoryByteSink(byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { sink.writeAt(1, byteArrayOf(5, 6)) }
    }

    @Test fun rewriteReplacesEntireContent() {
        val sink = InMemoryByteSink(byteArrayOf(1, 2, 3))
        sink.rewrite(ByteArrayInputStream(byteArrayOf(8, 8)))
        assertEquals(listOf<Byte>(8, 8), sink.snapshot().toList())
        assertEquals(2L, sink.size())
    }
}
