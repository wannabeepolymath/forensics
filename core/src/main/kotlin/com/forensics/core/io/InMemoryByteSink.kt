package com.forensics.core.io

import java.io.InputStream

/** Backed by a mutable ByteArray. Mirrors the invariants of the Android FileChannel sink. */
class InMemoryByteSink(initial: ByteArray) : ByteSink {
    private var data: ByteArray = initial.copyOf()

    override fun size(): Long = data.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "read [$offset, ${offset + length}) out of bounds for size ${data.size}"
        }
        return data.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    override fun writeAt(offset: Long, bytes: ByteArray) {
        require(offset >= 0 && offset + bytes.size <= data.size) {
            "in-place write [$offset, ${offset + bytes.size}) would change length ${data.size}"
        }
        System.arraycopy(bytes, 0, data, offset.toInt(), bytes.size)
    }

    override fun rewrite(content: InputStream) {
        data = content.readBytes()
    }

    override fun force() { /* no-op in memory */ }

    /** Test-only view of current bytes. */
    fun snapshot(): ByteArray = data.copyOf()
}
