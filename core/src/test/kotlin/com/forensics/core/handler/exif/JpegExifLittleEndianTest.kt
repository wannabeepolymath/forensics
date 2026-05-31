package com.forensics.core.handler.exif

import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.Value
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test of the handler's little-endian ("II"/Intel) branch.
 * TestExifJpeg only emits big-endian, so this builds a minimal II fixture inline,
 * mirroring its structure but with little-endian byte order.
 */
class JpegExifLittleEndianTest {
    private val handler = JpegExifHandler()

    /** Builds a little-endian EXIF JPEG: IFD0 with Orientation(SHORT inline) + Make(ASCII overflow). */
    private fun buildLittleEndian(orientation: Int, make: String): ByteArray {
        val tiff = ByteArrayOutputStream()
        fun u16le(v: Int) { tiff.write(v and 0xFF); tiff.write((v shr 8) and 0xFF) }
        fun u32le(v: Int) {
            tiff.write(v and 0xFF); tiff.write((v shr 8) and 0xFF)
            tiff.write((v shr 16) and 0xFF); tiff.write((v shr 24) and 0xFF)
        }
        // TIFF header: "II", 0x002A, offset to IFD0 = 8
        tiff.write('I'.code); tiff.write('I'.code)
        u16le(0x002A); u32le(8)

        val makeBytes = (make + " ").toByteArray(Charsets.US_ASCII)
        val entryCount = 2
        val ifdStart = 8
        val overflowStart = ifdStart + 2 + entryCount * 12 + 4
        val makeOffset = overflowStart

        u16le(entryCount)
        // Orientation: SHORT, count 1, inline little-endian, left-aligned in 4-byte cell.
        u16le(ExifTags.ORIENTATION); u16le(ExifType.SHORT.code); u32le(1)
        tiff.write(orientation and 0xFF); tiff.write((orientation shr 8) and 0xFF)
        tiff.write(0); tiff.write(0) // pad cell to 4 bytes
        // Make: ASCII, overflow.
        u16le(ExifTags.MAKE); u16le(ExifType.ASCII.code); u32le(makeBytes.size); u32le(makeOffset)
        u32le(0) // next IFD = none
        tiff.write(makeBytes)
        val tiffBytes = tiff.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8) // SOI
        out.write(0xFF); out.write(0xE1) // APP1
        val exifId = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        val app1Len = 2 + exifId.size + tiffBytes.size
        out.write((app1Len shr 8) and 0xFF); out.write(app1Len and 0xFF)
        out.write(exifId); out.write(tiffBytes)
        out.write(0xFF); out.write(0xD9) // EOI
        return out.toByteArray()
    }

    @Test fun parsesLittleEndianOrientationAndAscii() {
        val fields = handler.parse(InMemoryByteSource(buildLittleEndian(6, "ACME")))

        val orientation = fields.first { it.key == "Orientation" }
        assertEquals(Value.Integer(6L), orientation.value)

        val make = fields.first { it.key == "Make" }
        assertEquals(Value.Text("ACME"), make.value)

        assertTrue(fields.size >= 2)
    }
}
