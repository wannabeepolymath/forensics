package com.forensics.core.io

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A [ByteSource] over a [FileChannel]. All reads are positional, so [openStream] tracks its own
 * cursor and never disturbs [readAt] (or other readers sharing the channel). The caller owns the
 * channel and is responsible for closing it; this class never closes it.
 */
class FileChannelByteSource(private val channel: FileChannel) : ByteSource {

    override fun size(): Long = channel.size()

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= channel.size()) {
            "read [$offset, ${offset + length}) out of bounds for size ${channel.size()}"
        }
        val buf = ByteBuffer.allocate(length)
        var read = 0
        while (read < length) {
            val n = channel.read(buf, offset + read)
            if (n < 0) break
            read += n
        }
        require(read == length) { "short read: expected $length, got $read" }
        return buf.array()
    }

    override fun openStream(): InputStream = object : InputStream() {
        private var pos = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= channel.size()) return -1
            val buf = ByteBuffer.wrap(b, off, len)
            val n = channel.read(buf, pos)
            if (n > 0) pos += n
            return n
        }
    }
}
