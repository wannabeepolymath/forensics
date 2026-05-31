package com.forensics.core.io

import java.io.ByteArrayInputStream
import java.io.InputStream

class InMemoryByteSource(private val data: ByteArray) : ByteSource {
    override fun size(): Long = data.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "read [$offset, ${offset + length}) out of bounds for size ${data.size}"
        }
        return data.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    override fun openStream(): InputStream = ByteArrayInputStream(data)
}
