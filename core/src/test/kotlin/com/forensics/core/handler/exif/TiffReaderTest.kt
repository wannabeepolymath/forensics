package com.forensics.core.handler.exif

import kotlin.test.Test
import kotlin.test.assertEquals

class TiffReaderTest {
    @Test fun readsBigEndian() {
        val r = TiffReader(byteArrayOf(0x12, 0x34, 0x00, 0x01), littleEndian = false)
        assertEquals(0x1234, r.u16(0))
        assertEquals(0x12340001L, r.u32(0))
    }

    @Test fun readsLittleEndian() {
        val r = TiffReader(byteArrayOf(0x34, 0x12, 0x01, 0x00), littleEndian = true)
        assertEquals(0x1234, r.u16(0))
        assertEquals(0x00011234L, r.u32(0))
    }
}
