package com.forensics.core.io

import java.io.InputStream

/** Random-access read/write access plus a full-rewrite path. */
interface ByteSink {
    fun size(): Long

    /** Reads [length] bytes at [offset] (for undo capture and read-back verification). */
    fun readAt(offset: Long, length: Int): ByteArray

    /** Overwrites bytes at [offset]. MUST NOT change file length. Throws if it would. */
    fun writeAt(offset: Long, bytes: ByteArray)

    /** Replaces the entire content (the rewrite path). */
    fun rewrite(content: InputStream)

    /** Flushes pending writes durably (fsync). No-op for in-memory. */
    fun force()
}
