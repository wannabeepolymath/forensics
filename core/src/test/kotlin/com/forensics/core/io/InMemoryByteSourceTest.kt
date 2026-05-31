package com.forensics.core.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryByteSourceTest {
    private val src = InMemoryByteSource(byteArrayOf(10, 20, 30, 40, 50))

    @Test fun reportsSize() = assertEquals(5L, src.size())

    @Test fun readsAtOffset() =
        assertEquals(listOf<Byte>(20, 30), src.readAt(1, 2).toList())

    @Test fun readingPastEndFails() {
        assertFailsWith<IllegalArgumentException> { src.readAt(4, 2) }
    }

    @Test fun streamsAllBytes() =
        assertEquals(listOf<Byte>(10, 20, 30, 40, 50), src.openStream().readBytes().toList())
}
