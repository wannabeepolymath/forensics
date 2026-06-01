package com.forensics.core.io

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileChannelByteSourceTest {
    private val tmp: File = File.createTempFile("source", ".bin")

    @AfterTest fun cleanup() { tmp.delete() }

    private fun sourceOf(data: ByteArray): Pair<RandomAccessFile, FileChannelByteSource> {
        tmp.writeBytes(data)
        val raf = RandomAccessFile(tmp, "r")
        return raf to FileChannelByteSource(raf.channel)
    }

    @Test fun reportsSize() {
        val (raf, src) = sourceOf(byteArrayOf(10, 20, 30, 40, 50))
        raf.use { assertEquals(5L, src.size()) }
    }

    @Test fun readsAtOffset() {
        val (raf, src) = sourceOf(byteArrayOf(10, 20, 30, 40, 50))
        raf.use { assertEquals(listOf<Byte>(20, 30), src.readAt(1, 2).toList()) }
    }

    @Test fun readingPastEndFails() {
        val (raf, src) = sourceOf(byteArrayOf(10, 20, 30, 40, 50))
        raf.use { assertFailsWith<IllegalArgumentException> { src.readAt(4, 2) } }
    }

    @Test fun streamsAllBytesWithoutDisturbingReadAt() {
        val (raf, src) = sourceOf(byteArrayOf(10, 20, 30, 40, 50))
        raf.use {
            assertEquals(listOf<Byte>(10, 20, 30, 40, 50), src.openStream().readBytes().toList())
            assertEquals(listOf<Byte>(30), src.readAt(2, 1).toList())
        }
    }
}
