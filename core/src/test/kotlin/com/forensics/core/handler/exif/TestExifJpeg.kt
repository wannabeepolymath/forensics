package com.forensics.core.handler.exif

import java.io.ByteArrayOutputStream

/**
 * Builds a minimal big-endian JPEG with an EXIF APP1 segment containing IFD0 with:
 *   Orientation (SHORT, inline), Make (ASCII), Model (ASCII), DateTime (ASCII).
 * Followed by an EOI marker. Big-endian ("MM") for deterministic offsets.
 */
object TestExifJpeg {
    fun build(orientation: Int, dateTime: String, make: String = "ACME", model: String = "CamX"): ByteArray {
        // --- Build the TIFF block (origin = byte 0 of this block) ---
        val tiff = ByteArrayOutputStream()
        fun u16(v: Int) { tiff.write((v shr 8) and 0xFF); tiff.write(v and 0xFF) }
        fun u32(v: Int) {
            tiff.write((v shr 24) and 0xFF); tiff.write((v shr 16) and 0xFF)
            tiff.write((v shr 8) and 0xFF); tiff.write(v and 0xFF)
        }
        // TIFF header: "MM", 0x002A, offset to IFD0 = 8
        tiff.write('M'.code); tiff.write('M'.code)
        u16(0x002A); u32(8)

        // ASCII payloads need NUL terminator; values > 4 bytes go to an overflow area after IFD0.
        val makeBytes = (make + " ").toByteArray(Charsets.US_ASCII)
        val modelBytes = (model + " ").toByteArray(Charsets.US_ASCII)
        val dateBytes = (dateTime + " ").toByteArray(Charsets.US_ASCII)

        // IFD0 starts at offset 8: count(2) + entries(12 each) + nextIFD(4)
        val entryCount = 4
        val ifdStart = 8
        val overflowStart = ifdStart + 2 + entryCount * 12 + 4

        // Pre-compute overflow offsets (only values > 4 bytes need overflow).
        var cursor = overflowStart
        val makeOffset = cursor; cursor += makeBytes.size
        val modelOffset = cursor; cursor += modelBytes.size
        val dateOffset = cursor; cursor += dateBytes.size

        u16(entryCount)
        // Entry helper: tag, type, count, value-or-offset(4 bytes, big-endian, left-aligned for inline)
        fun entry(tag: Int, type: Int, count: Int, inlineValue: ByteArray?, offset: Int?) {
            u16(tag); u16(type); u32(count)
            if (inlineValue != null) {
                val padded = inlineValue.copyOf(4) // right-pad with zeros
                tiff.write(padded)
            } else {
                u32(offset!!)
            }
        }
        // Orientation: SHORT count 1, inline (occupies first 2 of the 4 value bytes, big-endian)
        entry(ExifTags.ORIENTATION, ExifType.SHORT.code, 1,
            byteArrayOf(((orientation shr 8) and 0xFF).toByte(), (orientation and 0xFF).toByte()), null)
        // Make: ASCII, overflow
        entry(ExifTags.MAKE, ExifType.ASCII.code, makeBytes.size, null, makeOffset)
        // Model: ASCII, overflow
        entry(ExifTags.MODEL, ExifType.ASCII.code, modelBytes.size, null, modelOffset)
        // DateTime: ASCII, overflow
        entry(ExifTags.DATETIME, ExifType.ASCII.code, dateBytes.size, null, dateOffset)
        u32(0) // next IFD = none

        tiff.write(makeBytes); tiff.write(modelBytes); tiff.write(dateBytes)
        val tiffBytes = tiff.toByteArray()

        // --- Wrap in JPEG: SOI, APP1(len, "Exif\0\0", tiff), EOI ---
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8) // SOI
        out.write(0xFF); out.write(0xE1) // APP1
        val exifId = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        val app1Len = 2 + exifId.size + tiffBytes.size // length field includes its own 2 bytes
        out.write((app1Len shr 8) and 0xFF); out.write(app1Len and 0xFF)
        out.write(exifId); out.write(tiffBytes)
        out.write(0xFF); out.write(0xD9) // EOI
        return out.toByteArray()
    }

    /** Absolute file offset where the TIFF block begins (SOI 2 + marker 2 + len 2 + "Exif\0\0" 6). */
    const val TIFF_BASE = 12
}
