package com.forensics.core.generic

import com.forensics.core.io.ByteSource

data class FoundString(val offset: Long, val text: String)

/** Extracts runs of printable ASCII (0x20..0x7E) at least [minLength] long, streaming. */
object Strings {
    fun extract(source: ByteSource, minLength: Int = 4): Sequence<FoundString> = sequence {
        source.openStream().use { stream ->
            val current = StringBuilder()
            var index = 0L
            var runStart = 0L
            while (true) {
                val b = stream.read()
                if (b < 0) break
                if (b in 0x20..0x7E) {
                    if (current.isEmpty()) runStart = index
                    current.append(b.toChar())
                } else {
                    if (current.length >= minLength) yield(FoundString(runStart, current.toString()))
                    current.setLength(0)
                }
                index++
            }
            if (current.length >= minLength) yield(FoundString(runStart, current.toString()))
        }
    }
}
