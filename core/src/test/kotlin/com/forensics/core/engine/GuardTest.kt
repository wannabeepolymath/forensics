package com.forensics.core.engine

import com.forensics.core.io.InMemoryByteSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardTest {
    @Test fun matchesUnchangedSource() {
        val src = InMemoryByteSource(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        val token = Guard.capture(src)
        assertTrue(Guard.matches(token, src))
    }

    @Test fun detectsSizeChange() {
        val token = Guard.capture(InMemoryByteSource(byteArrayOf(1, 2, 3)))
        assertFalse(Guard.matches(token, InMemoryByteSource(byteArrayOf(1, 2, 3, 4))))
    }

    @Test fun detectsHeaderChange() {
        val token = Guard.capture(InMemoryByteSource(byteArrayOf(1, 2, 3, 4)))
        assertFalse(Guard.matches(token, InMemoryByteSource(byteArrayOf(9, 2, 3, 4))))
    }
}
