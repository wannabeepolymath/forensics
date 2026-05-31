package com.forensics.core.generic

import com.forensics.core.io.InMemoryByteSource
import kotlin.test.Test
import kotlin.test.assertEquals

class HashingTest {
    private val abc = InMemoryByteSource("abc".toByteArray())

    @Test fun md5OfAbc() =
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Hashing.md5(abc))

    @Test fun sha256OfAbc() = assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        Hashing.sha256(abc),
    )
}
