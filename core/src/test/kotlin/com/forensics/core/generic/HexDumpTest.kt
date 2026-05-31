package com.forensics.core.generic

import com.forensics.core.io.InMemoryByteSource
import kotlin.test.Test
import kotlin.test.assertEquals

class HexDumpTest {
    @Test fun rendersOneLineWithAscii() {
        val src = InMemoryByteSource("AB12".toByteArray())
        val lines = HexDump.page(src, offset = 0, length = 4)
        assertEquals(1, lines.size)
        assertEquals("00000000  41 42 31 32                                      AB12", lines[0])
    }

    @Test fun clampsLengthToFileEnd() {
        val src = InMemoryByteSource(byteArrayOf(1, 2))
        val lines = HexDump.page(src, offset = 1, length = 100)
        assertEquals(1, lines.size)
        assertEquals("00000001  02                                               .", lines[0])
    }
}
