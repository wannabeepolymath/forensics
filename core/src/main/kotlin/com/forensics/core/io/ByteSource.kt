package com.forensics.core.io

import java.io.InputStream

/** Read-only random + streaming access to a file's bytes. */
interface ByteSource {
    fun size(): Long

    /** Reads exactly [length] bytes starting at [offset]. Throws if out of bounds. */
    fun readAt(offset: Long, length: Int): ByteArray

    /** Opens a fresh stream over all bytes (for hashing / strings). Caller closes it. */
    fun openStream(): InputStream
}
