package com.forensics.core.handler.exif

/** Endian-aware reads over the TIFF block (offset 0 == TIFF header origin). */
class TiffReader(val data: ByteArray, val littleEndian: Boolean) {
    fun u16(at: Int): Int {
        val a = data[at].toInt() and 0xFF
        val b = data[at + 1].toInt() and 0xFF
        return if (littleEndian) (b shl 8) or a else (a shl 8) or b
    }

    fun u32(at: Int): Long {
        val a = (data[at].toInt() and 0xFF).toLong()
        val b = (data[at + 1].toInt() and 0xFF).toLong()
        val c = (data[at + 2].toInt() and 0xFF).toLong()
        val d = (data[at + 3].toInt() and 0xFF).toLong()
        return if (littleEndian) (d shl 24) or (c shl 16) or (b shl 8) or a
        else (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    /** Encodes [value] as a u16 in this reader's byte order. */
    fun encodeU16(value: Int): ByteArray {
        val hi = ((value shr 8) and 0xFF).toByte()
        val lo = (value and 0xFF).toByte()
        return if (littleEndian) byteArrayOf(lo, hi) else byteArrayOf(hi, lo)
    }
}
