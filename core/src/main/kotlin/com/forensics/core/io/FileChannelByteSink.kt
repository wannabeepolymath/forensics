package com.forensics.core.io

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A [ByteSink] over a read-write [FileChannel] — the real on-disk in-place editor.
 *
 * All writes are positional, so a shared channel position is never disturbed. The caller
 * owns the channel and is responsible for closing it; this class never closes it. Works with
 * any rw FileChannel: `RandomAccessFile(file, "rw").channel` on the JVM, or a channel derived
 * from a SAF `ParcelFileDescriptor` on Android.
 */
class FileChannelByteSink(private val channel: FileChannel) : ByteSink {

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

    override fun writeAt(offset: Long, bytes: ByteArray) {
        require(offset >= 0 && offset + bytes.size <= channel.size()) {
            "in-place write [$offset, ${offset + bytes.size}) would change length ${channel.size()}"
        }
        val buf = ByteBuffer.wrap(bytes)
        var pos = offset
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos)
        }
    }

    override fun rewrite(content: InputStream) {
        val all = content.readBytes()
        val buf = ByteBuffer.wrap(all)
        var pos = 0L
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos)
        }
        channel.truncate(all.size.toLong())
    }

    override fun force() { channel.force(true) }
}
