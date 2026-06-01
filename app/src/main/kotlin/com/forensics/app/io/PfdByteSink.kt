package com.forensics.app.io

import android.os.ParcelFileDescriptor
import android.system.Os
import com.forensics.core.io.ByteSink
import java.io.FileDescriptor
import java.io.InputStream

/**
 * [ByteSink] over a read-write SAF [ParcelFileDescriptor] (opened with mode "rw") using positional
 * `Os.pwrite`/`Os.pread`, `Os.ftruncate`, and `Os.fsync`. The caller owns the PFD and closes it.
 * `writeAt` enforces the same length-preserving invariant as the in-memory/file sinks.
 */
class PfdByteSink(private val pfd: ParcelFileDescriptor) : ByteSink {
    private val fd: FileDescriptor get() = pfd.fileDescriptor

    override fun size(): Long = pfd.statSize

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= size()) {
            "read [$offset, ${offset + length}) out of bounds for size ${size()}"
        }
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = Os.pread(fd, out, read, length - read, offset + read)
            if (n <= 0) break
            read += n
        }
        require(read == length) { "short read: expected $length, got $read" }
        return out
    }

    override fun writeAt(offset: Long, bytes: ByteArray) {
        require(offset >= 0 && offset + bytes.size <= size()) {
            "in-place write [$offset, ${offset + bytes.size}) would change length ${size()}"
        }
        var written = 0
        while (written < bytes.size) {
            val n = Os.pwrite(fd, bytes, written, bytes.size - written, offset + written)
            if (n <= 0) break
            written += n
        }
        require(written == bytes.size) { "short write: expected ${bytes.size}, got $written" }
    }

    override fun rewrite(content: InputStream) {
        val all = content.readBytes()
        var written = 0
        while (written < all.size) {
            val n = Os.pwrite(fd, all, written, all.size - written, written.toLong())
            if (n <= 0) break
            written += n
        }
        require(written == all.size) { "short write during rewrite" }
        Os.ftruncate(fd, all.size.toLong())
    }

    override fun force() { Os.fsync(fd) }
}
