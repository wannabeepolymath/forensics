package com.forensics.core.generic

import com.forensics.core.io.InMemoryByteSource
import kotlin.test.Test
import kotlin.test.assertEquals

class StringsTest {
    @Test fun extractsRunsOfPrintableAtMinLength() {
        // "Hi" (len 2 < 4, skipped), two NUL separators, then "Hello" (len 5) starting at offset 4.
        val data = "Hi".toByteArray() + byteArrayOf(0, 0) + "Hello".toByteArray() + byteArrayOf(0)
        val found = Strings.extract(InMemoryByteSource(data), minLength = 4).toList()
        assertEquals(1, found.size)
        assertEquals("Hello", found[0].text)
        assertEquals(4L, found[0].offset)
    }

    @Test fun emptyWhenNoRunMeetsMinimum() {
        // Two printable runs "ab" and "cd" (each len 2 < 4) separated by a NUL: no run qualifies.
        val data = "ab".toByteArray() + byteArrayOf(0) + "cd".toByteArray()
        val found = Strings.extract(InMemoryByteSource(data), minLength = 4)
        assertEquals(0, found.toList().size)
    }
}
