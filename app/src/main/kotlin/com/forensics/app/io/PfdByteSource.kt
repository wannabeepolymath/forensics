package com.forensics.app.io

import android.os.ParcelFileDescriptor
import android.system.Os
import com.forensics.core.io.ByteSource
import java.io.FileDescriptor
import java.io.InputStream

/**
 * [ByteSource] over a SAF [ParcelFileDescriptor] using positional reads (`Os.pread`).
 * The caller owns the PFD and is responsible for closing it; this class never closes it.
 */
class PfdByteSource(private val pfd: ParcelFileDescriptor) : ByteSource {
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

    override fun openStream(): InputStream = object : InputStream() {
        private var pos = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= size()) return -1
            val n = Os.pread(fd, b, off, len, pos)
            if (n > 0) pos += n
            return if (n <= 0) -1 else n
        }
    }
}
