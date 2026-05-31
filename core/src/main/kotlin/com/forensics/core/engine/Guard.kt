package com.forensics.core.engine

import com.forensics.core.io.ByteSource

/** A cheap fingerprint of a source captured at parse time to detect external modification. */
data class GuardToken(val size: Long, val headerHash: Int)

object Guard {
    private const val HEADER_BYTES = 64

    fun capture(source: ByteSource): GuardToken {
        val n = minOf(HEADER_BYTES.toLong(), source.size()).toInt()
        val header = source.readAt(0, n)
        return GuardToken(source.size(), header.contentHashCode())
    }

    fun matches(token: GuardToken, source: ByteSource): Boolean {
        if (source.size() != token.size) return false
        val n = minOf(HEADER_BYTES.toLong(), source.size()).toInt()
        return source.readAt(0, n).contentHashCode() == token.headerHash
    }
}
