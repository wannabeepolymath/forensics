package com.forensics.core.generic

import com.forensics.core.io.ByteSource

/** Renders a region of a source as classic 16-bytes-per-line hex+ASCII lines. */
object HexDump {
    private const val BYTES_PER_LINE = 16

    fun page(source: ByteSource, offset: Long, length: Int): List<String> {
        val available = (source.size() - offset).coerceAtLeast(0)
        val toRead = minOf(length.toLong(), available).toInt()
        if (toRead == 0) return emptyList()
        val bytes = source.readAt(offset, toRead)

        val lines = ArrayList<String>()
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + BYTES_PER_LINE, bytes.size)
            val hex = StringBuilder()
            val ascii = StringBuilder()
            for (j in i until i + BYTES_PER_LINE) {
                if (j < end) {
                    val b = bytes[j].toInt() and 0xFF
                    hex.append("%02x ".format(b))
                    ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
                } else {
                    hex.append("   ")
                }
            }
            val lineOffset = offset + i
            lines.add("%08x  %-48s %s".format(lineOffset, hex.toString().trimEnd(), ascii))
            i += BYTES_PER_LINE
        }
        return lines
    }
}
