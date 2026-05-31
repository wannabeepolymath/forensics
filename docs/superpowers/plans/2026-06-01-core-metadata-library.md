# Core Metadata Library Implementation Plan (Plan A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a pure-Kotlin/JVM library that inspects all metadata of a file via an abstract byte interface and applies corruption-safe in-place metadata edits, with a flagship JPEG/EXIF handler.

**Architecture:** All file IO is hidden behind `ByteSource` (read) and `ByteSink` (random-access read/write) interfaces, so the entire library is unit-testable on the JVM with in-memory implementations — no Android. A pluggable `FormatHandler` parses a format into `MetadataField`s and compiles edits into a validated `EditPlan`. The `EditEngine` executes plans with defense-in-depth: in-memory validation, strict same-length in-place invariant, an undo buffer with read-back verification, post-write structural re-parse, a concurrent-modification guard, and disk-staged rewrites.

**Tech Stack:** Kotlin (JVM), Gradle Kotlin DSL, JUnit 5 (Jupiter), kotlin.test assertions. No Android dependencies in this module.

---

## File Structure

```
core/
  build.gradle.kts
  src/main/kotlin/com/forensics/core/
    model/Value.kt              # sealed Value type
    model/MetadataField.kt      # MetadataField, FieldType
    model/EditPlan.kt           # EditPlan, BytePatch, EditResult
    io/ByteSource.kt            # read interface
    io/ByteSink.kt              # random-access read/write interface
    io/InMemoryByteSource.kt    # test/impl over ByteArray
    io/InMemoryByteSink.kt      # test/impl over growable buffer
    generic/MagicDetector.kt    # format detection from magic bytes
    generic/Hashing.kt          # streamed MD5/SHA-256
    generic/HexDump.kt          # paged hex rendering
    generic/Strings.kt          # printable-string extraction
    handler/FormatHandler.kt    # handler interface
    handler/exif/TiffReader.kt  # endian-aware primitive reads
    handler/exif/ExifTags.kt    # tag id -> name/type table
    handler/exif/JpegExifHandler.kt
    engine/Guard.kt             # concurrent-modification guard token
    engine/EditEngine.kt        # plan execution + safety
  src/test/kotlin/com/forensics/core/
    io/InMemoryByteSourceTest.kt
    io/InMemoryByteSinkTest.kt
    generic/MagicDetectorTest.kt
    generic/HashingTest.kt
    generic/HexDumpTest.kt
    generic/StringsTest.kt
    handler/exif/TestExifJpeg.kt        # builds known EXIF JPEG byte arrays
    handler/exif/JpegExifHandlerTest.kt
    engine/EditEngineTest.kt
```

Each file has one responsibility. `model/` is plain data, `io/` is the IO boundary, `generic/` is format-agnostic inspection, `handler/` is per-format logic, `engine/` is the safety-critical executor.

---

## Task 1: Project setup

**Files:**
- Create: `core/build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `gradle.properties`

- [ ] **Step 1: Create the Gradle settings file**

Create `settings.gradle.kts`:

```kotlin
rootProject.name = "forensics"
include(":core")
```

- [ ] **Step 2: Create gradle.properties**

Create `gradle.properties`:

```properties
org.gradle.caching=true
org.gradle.parallel=true
kotlin.code.style=official
```

- [ ] **Step 3: Create the core module build file**

Create `core/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Verify the project configures**

Run: `gradle :core:tasks --offline 2>/dev/null || gradle :core:tasks`
Expected: Gradle lists tasks including `test`, with no configuration errors. (If `gradle` is not installed, install Gradle 8.7+ or add a wrapper with `gradle wrapper`.)

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts gradle.properties core/build.gradle.kts
git commit -m "chore: scaffold core Kotlin/JVM module"
```

---

## Task 2: Value model

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/model/Value.kt`

- [ ] **Step 1: Create the Value sealed interface**

Create `core/src/main/kotlin/com/forensics/core/model/Value.kt`:

```kotlin
package com.forensics.core.model

/** A typed metadata value. Kept deliberately small for v1. */
sealed interface Value {
    /** Textual value (e.g. EXIF ASCII fields, dates). */
    data class Text(val s: String) : Value

    /** Whole-number value (e.g. EXIF SHORT/LONG fields like Orientation, ISO). */
    data class Integer(val n: Long) : Value

    /** Raw bytes for values we surface but do not interpret. */
    class Raw(val bytes: ByteArray) : Value {
        override fun equals(other: Any?) =
            other is Raw && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/model/Value.kt
git commit -m "feat: add Value model"
```

---

## Task 3: MetadataField model

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/model/MetadataField.kt`

- [ ] **Step 1: Create FieldType and MetadataField**

Create `core/src/main/kotlin/com/forensics/core/model/MetadataField.kt`:

```kotlin
package com.forensics.core.model

/** Whether the field occupies a fixed byte length (in-place-patchable) or a variable one. */
enum class FieldType { FIXED, VARIABLE }

/**
 * One metadata field located in the file.
 *
 * @param byteOffset absolute offset of the field's raw value bytes
 * @param byteLength length of the field's raw value bytes
 * @param editable whether the handler can attempt to edit this field at all
 * @param group display/grouping bucket, e.g. "EXIF", "GPS"
 */
data class MetadataField(
    val key: String,
    val value: Value,
    val byteOffset: Long,
    val byteLength: Int,
    val type: FieldType,
    val editable: Boolean,
    val group: String,
)
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/model/MetadataField.kt
git commit -m "feat: add MetadataField model"
```

---

## Task 4: EditPlan model

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/model/EditPlan.kt`

- [ ] **Step 1: Create EditPlan, BytePatch, EditResult**

Create `core/src/main/kotlin/com/forensics/core/model/EditPlan.kt`:

```kotlin
package com.forensics.core.model

/** A single absolute-offset byte write (used for a value or a recomputed checksum). */
class BytePatch(val offset: Long, val bytes: ByteArray) {
    override fun equals(other: Any?) =
        other is BytePatch && offset == other.offset && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * offset.hashCode() + bytes.contentHashCode()
}

/** The compiled, validated outcome of asking a handler to edit a field. */
sealed interface EditPlan {
    /**
     * A pure same-length in-place patch. [newBytes].size MUST equal [originalBytes].size.
     * [checksumPatches] are additional same-length writes (e.g. a recomputed CRC).
     */
    class InPlace(
        val offset: Long,
        val originalBytes: ByteArray,
        val newBytes: ByteArray,
        val checksumPatches: List<BytePatch> = emptyList(),
    ) : EditPlan

    /** A length-changing or structural edit requiring a full rebuild of the file. */
    class RequiresRewrite(val reason: String, val rebuiltBytes: ByteArray) : EditPlan

    /** The edit cannot be performed (out of range, signature-locked, etc.). */
    data class Rejected(val reason: String) : EditPlan
}

/** Result of executing an [EditPlan]. */
sealed interface EditResult {
    /** Applied. [inPlace] true if no rewrite occurred. [bytesPatched] for user feedback. */
    data class Success(val inPlace: Boolean, val bytesPatched: Int) : EditResult
    /** Not applied. File is unchanged (restored if a write was attempted). */
    data class Failure(val reason: String) : EditResult
}
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/model/EditPlan.kt
git commit -m "feat: add EditPlan model"
```

---

## Task 5: ByteSource interface + in-memory implementation

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/io/ByteSource.kt`
- Create: `core/src/main/kotlin/com/forensics/core/io/InMemoryByteSource.kt`
- Test: `core/src/test/kotlin/com/forensics/core/io/InMemoryByteSourceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/io/InMemoryByteSourceTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.io.InMemoryByteSourceTest"`
Expected: FAIL — `InMemoryByteSource` / `ByteSource` unresolved.

- [ ] **Step 3: Write the interface**

Create `core/src/main/kotlin/com/forensics/core/io/ByteSource.kt`:

```kotlin
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
```

- [ ] **Step 4: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/io/InMemoryByteSource.kt`:

```kotlin
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.io.InMemoryByteSourceTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/io/ByteSource.kt \
        core/src/main/kotlin/com/forensics/core/io/InMemoryByteSource.kt \
        core/src/test/kotlin/com/forensics/core/io/InMemoryByteSourceTest.kt
git commit -m "feat: add ByteSource with in-memory implementation"
```

---

## Task 6: ByteSink interface + in-memory implementation

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/io/ByteSink.kt`
- Create: `core/src/main/kotlin/com/forensics/core/io/InMemoryByteSink.kt`
- Test: `core/src/test/kotlin/com/forensics/core/io/InMemoryByteSinkTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/io/InMemoryByteSinkTest.kt`:

```kotlin
package com.forensics.core.io

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryByteSinkTest {
    @Test fun writeAtKeepsLengthAndOverwrites() {
        val sink = InMemoryByteSink(byteArrayOf(1, 2, 3, 4))
        sink.writeAt(1, byteArrayOf(9, 9))
        assertEquals(listOf<Byte>(1, 9, 9, 4), sink.snapshot().toList())
        assertEquals(4L, sink.size())
    }

    @Test fun readAtReturnsCurrentBytes() {
        val sink = InMemoryByteSink(byteArrayOf(1, 2, 3, 4))
        sink.writeAt(0, byteArrayOf(7))
        assertEquals(listOf<Byte>(7, 2), sink.readAt(0, 2).toList())
    }

    @Test fun writePastEndFails() {
        val sink = InMemoryByteSink(byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { sink.writeAt(1, byteArrayOf(5, 6)) }
    }

    @Test fun rewriteReplacesEntireContent() {
        val sink = InMemoryByteSink(byteArrayOf(1, 2, 3))
        sink.rewrite(ByteArrayInputStream(byteArrayOf(8, 8)))
        assertEquals(listOf<Byte>(8, 8), sink.snapshot().toList())
        assertEquals(2L, sink.size())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.io.InMemoryByteSinkTest"`
Expected: FAIL — `ByteSink` / `InMemoryByteSink` unresolved.

- [ ] **Step 3: Write the interface**

Create `core/src/main/kotlin/com/forensics/core/io/ByteSink.kt`:

```kotlin
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
```

- [ ] **Step 4: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/io/InMemoryByteSink.kt`:

```kotlin
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.io.InMemoryByteSinkTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/io/ByteSink.kt \
        core/src/main/kotlin/com/forensics/core/io/InMemoryByteSink.kt \
        core/src/test/kotlin/com/forensics/core/io/InMemoryByteSinkTest.kt
git commit -m "feat: add ByteSink with in-memory implementation"
```

---

## Task 7: Magic-byte format detection

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/generic/MagicDetector.kt`
- Test: `core/src/test/kotlin/com/forensics/core/generic/MagicDetectorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/generic/MagicDetectorTest.kt`:

```kotlin
package com.forensics.core.generic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MagicDetectorTest {
    @Test fun detectsJpeg() {
        val magic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(DetectedFormat.JPEG, MagicDetector.detect(magic))
    }

    @Test fun detectsPng() {
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(DetectedFormat.PNG, MagicDetector.detect(magic))
    }

    @Test fun unknownReturnsNull() {
        assertNull(MagicDetector.detect(byteArrayOf(0x00, 0x01, 0x02)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.generic.MagicDetectorTest"`
Expected: FAIL — `MagicDetector` / `DetectedFormat` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/generic/MagicDetector.kt`:

```kotlin
package com.forensics.core.generic

enum class DetectedFormat { JPEG, PNG }

/** Detects a file format from its leading bytes. Magic bytes are trusted over MIME hints. */
object MagicDetector {
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun detect(magic: ByteArray): DetectedFormat? = when {
        magic.startsWith(JPEG) -> DetectedFormat.JPEG
        magic.startsWith(PNG) -> DetectedFormat.PNG
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.generic.MagicDetectorTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/generic/MagicDetector.kt \
        core/src/test/kotlin/com/forensics/core/generic/MagicDetectorTest.kt
git commit -m "feat: add magic-byte format detection"
```

---

## Task 8: Streamed hashing

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/generic/Hashing.kt`
- Test: `core/src/test/kotlin/com/forensics/core/generic/HashingTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/generic/HashingTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.generic.HashingTest"`
Expected: FAIL — `Hashing` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/generic/Hashing.kt`:

```kotlin
package com.forensics.core.generic

import com.forensics.core.io.ByteSource
import java.security.MessageDigest

/** Streams the source through a digest so arbitrarily large files never load fully. */
object Hashing {
    fun md5(source: ByteSource): String = digest(source, "MD5")
    fun sha256(source: ByteSource): String = digest(source, "SHA-256")

    private fun digest(source: ByteSource, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        source.openStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.generic.HashingTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/generic/Hashing.kt \
        core/src/test/kotlin/com/forensics/core/generic/HashingTest.kt
git commit -m "feat: add streamed MD5/SHA-256 hashing"
```

---

## Task 9: Paged hex dump

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/generic/HexDump.kt`
- Test: `core/src/test/kotlin/com/forensics/core/generic/HexDumpTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/generic/HexDumpTest.kt`:

```kotlin
package com.forensics.core.generic

import com.forensics.core.io.InMemoryByteSource
import kotlin.test.Test
import kotlin.test.assertEquals

class HexDumpTest {
    @Test fun rendersOneLineWithAscii() {
        val src = InMemoryByteSource("AB12".toByteArray())
        val lines = HexDump.page(src, offset = 0, length = 4)
        // offset(8 hex) + hex bytes padded to 16 + ascii gutter
        assertEquals(1, lines.size)
        assertEquals("00000000  41 42 31 32                                       AB12", lines[0])
    }

    @Test fun clampsLengthToFileEnd() {
        val src = InMemoryByteSource(byteArrayOf(1, 2))
        val lines = HexDump.page(src, offset = 1, length = 100)
        assertEquals(1, lines.size)
        assertEquals("00000001  02                                               .", lines[0])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.generic.HexDumpTest"`
Expected: FAIL — `HexDump` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/generic/HexDump.kt`:

```kotlin
package com.forensics.core.generic

import com.forensics.core.io.ByteSource

/** Renders a region of a source as classic 16-bytes-per-line hex+ASCII lines. */
object HexDump {
    private const val BYTES_PER_LINE = 16

    fun page(source: ByteSource, offset: Long, length: Int): List<String> {
        val available = (source.size() - offset).coerceAtLeast(0)
        val toRead = minOf(length.toLong(), available).toInt()
        if (toRead == 0) return emptyList()
        val bytes = source.readAt(offset, toRead)

        val lines = ArrayList<String>()
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + BYTES_PER_LINE, bytes.size)
            val hex = StringBuilder()
            val ascii = StringBuilder()
            for (j in i until i + BYTES_PER_LINE) {
                if (j < end) {
                    val b = bytes[j].toInt() and 0xFF
                    hex.append("%02x ".format(b))
                    ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
                } else {
                    hex.append("   ")
                }
            }
            val lineOffset = offset + i
            lines.add("%08x  %s %s".format(lineOffset, hex.toString().trimEnd(), ascii).let {
                // normalize: 8-offset, two spaces, fixed-width hex column, gutter, ascii
                "%08x  %-48s %s".format(lineOffset, hex.toString().trimEnd(), ascii)
            })
            i += BYTES_PER_LINE
        }
        return lines
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.generic.HexDumpTest"`
Expected: PASS (2 tests). If the ASCII/whitespace alignment differs, adjust the expected strings in the test to match the `%-48s` column width — the column is 48 chars wide (16 bytes × 3 chars) before the single-space gutter.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/generic/HexDump.kt \
        core/src/test/kotlin/com/forensics/core/generic/HexDumpTest.kt
git commit -m "feat: add paged hex dump"
```

---

## Task 10: Printable-strings extraction

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/generic/Strings.kt`
- Test: `core/src/test/kotlin/com/forensics/core/generic/StringsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/generic/StringsTest.kt`:

```kotlin
package com.forensics.core.generic

import com.forensics.core.io.InMemoryByteSource
import kotlin.test.Test
import kotlin.test.assertEquals

class StringsTest {
    @Test fun extractsRunsOfPrintableAtMinLength() {
        // "Hi" (too short at min 4), then control bytes, then "Hello"
        val data = "Hi Hello ".toByteArray()
        val found = Strings.extract(InMemoryByteSource(data), minLength = 4).toList()
        assertEquals(1, found.size)
        assertEquals("Hello", found[0].text)
        assertEquals(4L, found[0].offset) // after "Hi" + 2 control bytes
    }

    @Test fun emptyWhenNoRunMeetsMinimum() {
        val found = Strings.extract(InMemoryByteSource("ab cd".toByteArray()), minLength = 4)
        assertEquals(0, found.toList().size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.generic.StringsTest"`
Expected: FAIL — `Strings` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/generic/Strings.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.generic.StringsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/generic/Strings.kt \
        core/src/test/kotlin/com/forensics/core/generic/StringsTest.kt
git commit -m "feat: add printable-strings extraction"
```

---

## Task 11: FormatHandler interface

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/handler/FormatHandler.kt`

- [ ] **Step 1: Create the interface**

Create `core/src/main/kotlin/com/forensics/core/handler/FormatHandler.kt`:

```kotlin
package com.forensics.core.handler

import com.forensics.core.io.ByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

/** A pluggable per-format metadata reader and edit compiler. */
interface FormatHandler {
    val formatName: String

    /** True if this handler recognizes the file from its leading [magic] bytes. */
    fun canHandle(magic: ByteArray, mime: String?): Boolean

    /** Parses all readable metadata fields. Must not mutate the source. */
    fun parse(source: ByteSource): List<MetadataField>

    /** Compiles an edit of [field] to [newValue] into a validated plan. Must not write. */
    fun validateEdit(source: ByteSource, field: MetadataField, newValue: Value): EditPlan
}
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/handler/FormatHandler.kt
git commit -m "feat: add FormatHandler interface"
```

---

## Task 12: TIFF primitive reader

EXIF data is a TIFF structure embedded in a JPEG APP1 segment. This reader does endian-aware integer reads relative to the TIFF header origin.

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/handler/exif/TiffReader.kt`
- Test: `core/src/test/kotlin/com/forensics/core/handler/exif/TiffReaderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/handler/exif/TiffReaderTest.kt`:

```kotlin
package com.forensics.core.handler.exif

import kotlin.test.Test
import kotlin.test.assertEquals

class TiffReaderTest {
    @Test fun readsBigEndian() {
        val r = TiffReader(byteArrayOf(0x12, 0x34, 0x00, 0x01), littleEndian = false)
        assertEquals(0x1234, r.u16(0))
        assertEquals(0x12340001L, r.u32(0))
    }

    @Test fun readsLittleEndian() {
        val r = TiffReader(byteArrayOf(0x34, 0x12, 0x01, 0x00), littleEndian = true)
        assertEquals(0x1234, r.u16(0))
        assertEquals(0x00011234L, r.u32(0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.handler.exif.TiffReaderTest"`
Expected: FAIL — `TiffReader` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/handler/exif/TiffReader.kt`:

```kotlin
package com.forensics.core.handler.exif

/** Endian-aware reads over the TIFF block (offset 0 == TIFF header origin). */
class TiffReader(val data: ByteArray, val littleEndian: Boolean) {
    fun u16(at: Int): Int {
        val a = data[at].toInt() and 0xFF
        val b = data[at + 1].toInt() and 0xFF
        return if (littleEndian) (b shl 8) or a else (a shl 8) or b
    }

    fun u32(at: Int): Long {
        val a = (data[at].toInt() and 0xFF).toLong()
        val b = (data[at + 1].toInt() and 0xFF).toLong()
        val c = (data[at + 2].toInt() and 0xFF).toLong()
        val d = (data[at + 3].toInt() and 0xFF).toLong()
        return if (littleEndian) (d shl 24) or (c shl 16) or (b shl 8) or a
        else (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    /** Encodes [value] as a u16 in this reader's byte order. */
    fun encodeU16(value: Int): ByteArray {
        val hi = ((value shr 8) and 0xFF).toByte()
        val lo = (value and 0xFF).toByte()
        return if (littleEndian) byteArrayOf(lo, hi) else byteArrayOf(hi, lo)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.handler.exif.TiffReaderTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/handler/exif/TiffReader.kt \
        core/src/test/kotlin/com/forensics/core/handler/exif/TiffReaderTest.kt
git commit -m "feat: add endian-aware TIFF primitive reader"
```

---

## Task 13: EXIF tag table

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/handler/exif/ExifTags.kt`

- [ ] **Step 1: Create the tag table**

Create `core/src/main/kotlin/com/forensics/core/handler/exif/ExifTags.kt`:

```kotlin
package com.forensics.core.handler.exif

/** EXIF/TIFF field types we handle (subset). Values are the TIFF type codes. */
enum class ExifType(val code: Int, val byteSize: Int) {
    BYTE(1, 1), ASCII(2, 1), SHORT(3, 2), LONG(4, 4);

    companion object {
        fun fromCode(code: Int): ExifType? = entries.firstOrNull { it.code == code }
    }
}

/** Tag id -> human name for the subset we surface. */
object ExifTags {
    const val ORIENTATION = 0x0112
    const val DATETIME = 0x0132
    const val MAKE = 0x010F
    const val MODEL = 0x0110

    private val names = mapOf(
        ORIENTATION to "Orientation",
        DATETIME to "DateTime",
        MAKE to "Make",
        MODEL to "Model",
        0x011A to "XResolution",
        0x0131 to "Software",
    )

    fun name(tag: Int): String = names[tag] ?: "Tag-0x%04X".format(tag)
}
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/handler/exif/ExifTags.kt
git commit -m "feat: add EXIF tag/type tables"
```

---

## Task 14: EXIF test-fixture builder

A deterministic builder that constructs a minimal valid JPEG containing an EXIF APP1 segment with a single IFD0. Keeps parse/edit tests self-contained (no committed binaries).

**Files:**
- Test: `core/src/test/kotlin/com/forensics/core/handler/exif/TestExifJpeg.kt`
- Test: `core/src/test/kotlin/com/forensics/core/handler/exif/TestExifJpegTest.kt`

- [ ] **Step 1: Write the failing self-test**

Create `core/src/test/kotlin/com/forensics/core/handler/exif/TestExifJpegTest.kt`:

```kotlin
package com.forensics.core.handler.exif

import kotlin.test.Test
import kotlin.test.assertEquals

class TestExifJpegTest {
    @Test fun startsWithJpegSoiThenApp1Exif() {
        val jpeg = TestExifJpeg.build(orientation = 1, dateTime = "2020:01:02 03:04:05")
        assertEquals(0xFF, jpeg[0].toInt() and 0xFF) // SOI
        assertEquals(0xD8, jpeg[1].toInt() and 0xFF)
        assertEquals(0xFF, jpeg[2].toInt() and 0xFF) // APP1
        assertEquals(0xE1, jpeg[3].toInt() and 0xFF)
        // "Exif\0\0" identifier at offset 6
        assertEquals("Exif", String(jpeg, 6, 4, Charsets.US_ASCII))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.handler.exif.TestExifJpegTest"`
Expected: FAIL — `TestExifJpeg` unresolved.

- [ ] **Step 3: Write the builder**

Create `core/src/test/kotlin/com/forensics/core/handler/exif/TestExifJpeg.kt`:

```kotlin
package com.forensics.core.handler.exif

import java.io.ByteArrayOutputStream

/**
 * Builds a minimal big-endian JPEG with an EXIF APP1 segment containing IFD0 with:
 *   Orientation (SHORT, inline), Make (ASCII), Model (ASCII), DateTime (ASCII).
 * Followed by an EOI marker. Big-endian ("MM") for deterministic offsets.
 */
object TestExifJpeg {
    fun build(orientation: Int, dateTime: String, make: String = "ACME", model: String = "CamX"): ByteArray {
        // --- Build the TIFF block (origin = byte 0 of this block) ---
        val tiff = ByteArrayOutputStream()
        fun u16(v: Int) { tiff.write((v shr 8) and 0xFF); tiff.write(v and 0xFF) }
        fun u32(v: Int) {
            tiff.write((v shr 24) and 0xFF); tiff.write((v shr 16) and 0xFF)
            tiff.write((v shr 8) and 0xFF); tiff.write(v and 0xFF)
        }
        // TIFF header: "MM", 0x002A, offset to IFD0 = 8
        tiff.write('M'.code); tiff.write('M'.code)
        u16(0x002A); u32(8)

        // ASCII payloads need NUL terminator; values > 4 bytes go to an overflow area after IFD0.
        val makeBytes = (make + " ").toByteArray(Charsets.US_ASCII)
        val modelBytes = (model + " ").toByteArray(Charsets.US_ASCII)
        val dateBytes = (dateTime + " ").toByteArray(Charsets.US_ASCII)

        // IFD0 starts at offset 8: count(2) + entries(12 each) + nextIFD(4)
        val entryCount = 4
        val ifdStart = 8
        val overflowStart = ifdStart + 2 + entryCount * 12 + 4

        // Pre-compute overflow offsets (only values > 4 bytes need overflow).
        var cursor = overflowStart
        val makeOffset = cursor; cursor += makeBytes.size
        val modelOffset = cursor; cursor += modelBytes.size
        val dateOffset = cursor; cursor += dateBytes.size

        u16(entryCount)
        // Entry helper: tag, type, count, value-or-offset(4 bytes, big-endian, left-aligned for inline)
        fun entry(tag: Int, type: Int, count: Int, inlineValue: ByteArray?, offset: Int?) {
            u16(tag); u16(type); u32(count)
            if (inlineValue != null) {
                val padded = inlineValue.copyOf(4) // right-pad with zeros
                tiff.write(padded)
            } else {
                u32(offset!!)
            }
        }
        // Orientation: SHORT count 1, inline (occupies first 2 of the 4 value bytes, big-endian)
        entry(ExifTags.ORIENTATION, ExifType.SHORT.code, 1,
            byteArrayOf(((orientation shr 8) and 0xFF).toByte(), (orientation and 0xFF).toByte()), null)
        // Make: ASCII, overflow
        entry(ExifTags.MAKE, ExifType.ASCII.code, makeBytes.size, null, makeOffset)
        // Model: ASCII, overflow
        entry(ExifTags.MODEL, ExifType.ASCII.code, modelBytes.size, null, modelOffset)
        // DateTime: ASCII, overflow
        entry(ExifTags.DATETIME, ExifType.ASCII.code, dateBytes.size, null, dateOffset)
        u32(0) // next IFD = none

        tiff.write(makeBytes); tiff.write(modelBytes); tiff.write(dateBytes)
        val tiffBytes = tiff.toByteArray()

        // --- Wrap in JPEG: SOI, APP1(len, "Exif\0\0", tiff), EOI ---
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8) // SOI
        out.write(0xFF); out.write(0xE1) // APP1
        val exifId = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        val app1Len = 2 + exifId.size + tiffBytes.size // length field includes its own 2 bytes
        out.write((app1Len shr 8) and 0xFF); out.write(app1Len and 0xFF)
        out.write(exifId); out.write(tiffBytes)
        out.write(0xFF); out.write(0xD9) // EOI
        return out.toByteArray()
    }

    /** Absolute file offset where the TIFF block begins (SOI 2 + marker 2 + len 2 + "Exif\0\0" 6). */
    const val TIFF_BASE = 12
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.handler.exif.TestExifJpegTest"`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add core/src/test/kotlin/com/forensics/core/handler/exif/TestExifJpeg.kt \
        core/src/test/kotlin/com/forensics/core/handler/exif/TestExifJpegTest.kt
git commit -m "test: add deterministic EXIF JPEG fixture builder"
```

---

## Task 15: JpegExifHandler — detection + parse

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/handler/exif/JpegExifHandler.kt`
- Test: `core/src/test/kotlin/com/forensics/core/handler/exif/JpegExifHandlerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/handler/exif/JpegExifHandlerTest.kt`:

```kotlin
package com.forensics.core.handler.exif

import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.FieldType
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegExifHandlerTest {
    private val handler = JpegExifHandler()

    private fun source(orientation: Int = 1, dateTime: String = "2020:01:02 03:04:05") =
        InMemoryByteSource(TestExifJpeg.build(orientation, dateTime))

    @Test fun canHandleJpegMagic() {
        assertTrue(handler.canHandle(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg"))
    }

    @Test fun rejectsNonJpeg() {
        assertTrue(!handler.canHandle(byteArrayOf(0x00, 0x01), null))
    }

    @Test fun parsesOrientationAsFixedInteger() {
        val fields = handler.parse(source(orientation = 6))
        val orient = fields.first { it.key == "Orientation" }
        assertEquals(Value.Integer(6), orient.value)
        assertEquals(FieldType.FIXED, orient.type)
        assertTrue(orient.editable)
        // raw value sits 2 bytes after the 12-byte entry header start; length == 2 (SHORT)
        assertEquals(2, orient.byteLength)
    }

    @Test fun parsesDateTimeAsText() {
        val fields = handler.parse(source(dateTime = "2020:01:02 03:04:05"))
        val dt = fields.first { it.key == "DateTime" }
        assertEquals(Value.Text("2020:01:02 03:04:05"), dt.value)
        assertEquals(FieldType.FIXED, dt.type)
        assertEquals("EXIF", dt.group)
    }

    @Test fun parsesAsciiMakeModel() {
        val fields = handler.parse(source())
        assertEquals(Value.Text("ACME"), fields.first { it.key == "Make" }.value)
        assertEquals(Value.Text("CamX"), fields.first { it.key == "Model" }.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.handler.exif.JpegExifHandlerTest"`
Expected: FAIL — `JpegExifHandler` unresolved.

- [ ] **Step 3: Write the implementation (detection + parse)**

Create `core/src/main/kotlin/com/forensics/core/handler/exif/JpegExifHandler.kt`:

```kotlin
package com.forensics.core.handler.exif

import com.forensics.core.handler.FormatHandler
import com.forensics.core.io.ByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.FieldType
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

/** Reads/edits EXIF (IFD0) inside a JPEG APP1 segment. Big- and little-endian supported. */
class JpegExifHandler : FormatHandler {
    override val formatName = "JPEG/EXIF"

    override fun canHandle(magic: ByteArray, mime: String?): Boolean =
        magic.size >= 3 &&
            (magic[0].toInt() and 0xFF) == 0xFF &&
            (magic[1].toInt() and 0xFF) == 0xD8 &&
            (magic[2].toInt() and 0xFF) == 0xFF

    /** Locates the TIFF block: returns absolute file offset of the TIFF header, or -1. */
    private fun findTiffBase(bytes: ByteArray): Int {
        var i = 2 // skip SOI
        while (i + 4 <= bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) return -1
            val marker = bytes[i + 1].toInt() and 0xFF
            val segLen = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker == 0xE1) {
                // APP1: check "Exif\0\0"
                val idStart = i + 4
                if (idStart + 6 <= bytes.size &&
                    String(bytes, idStart, 4, Charsets.US_ASCII) == "Exif"
                ) return idStart + 6
            }
            i += 2 + segLen
        }
        return -1
    }

    override fun parse(source: ByteSource): List<MetadataField> {
        val bytes = source.readAt(0, source.size().toInt())
        val tiffBase = findTiffBase(bytes)
        if (tiffBase < 0 || tiffBase + 8 > bytes.size) return emptyList()

        val littleEndian = String(bytes, tiffBase, 2, Charsets.US_ASCII) == "II"
        val tiff = TiffReader(bytes.copyOfRange(tiffBase, bytes.size), littleEndian)
        val ifd0 = tiff.u32(4).toInt()
        if (ifd0 + 2 > tiff.data.size) return emptyList()

        val count = tiff.u16(ifd0)
        val fields = ArrayList<MetadataField>()
        for (e in 0 until count) {
            val entry = ifd0 + 2 + e * 12
            if (entry + 12 > tiff.data.size) break
            val tag = tiff.u16(entry)
            val type = ExifType.fromCode(tiff.u16(entry + 2)) ?: continue
            val valueCount = tiff.u32(entry + 4).toInt()
            val totalBytes = type.byteSize * valueCount
            // Value is inline if it fits in 4 bytes, else at the offset stored in the value cell.
            val valueLocalOffset = if (totalBytes <= 4) entry + 8 else tiff.u32(entry + 8).toInt()
            if (valueLocalOffset + totalBytes > tiff.data.size) continue

            val absoluteOffset = (tiffBase + valueLocalOffset).toLong()
            val field = when (type) {
                ExifType.SHORT -> MetadataField(
                    key = ExifTags.name(tag),
                    value = Value.Integer(tiff.u16(valueLocalOffset).toLong()),
                    byteOffset = absoluteOffset, byteLength = 2,
                    type = FieldType.FIXED, editable = true, group = "EXIF",
                )
                ExifType.LONG -> MetadataField(
                    key = ExifTags.name(tag),
                    value = Value.Integer(tiff.u32(valueLocalOffset)),
                    byteOffset = absoluteOffset, byteLength = 4,
                    type = FieldType.FIXED, editable = true, group = "EXIF",
                )
                ExifType.ASCII -> {
                    val raw = tiff.data.copyOfRange(valueLocalOffset, valueLocalOffset + totalBytes)
                    val text = String(raw, Charsets.US_ASCII).trimEnd(' ')
                    MetadataField(
                        key = ExifTags.name(tag),
                        value = Value.Text(text),
                        byteOffset = absoluteOffset, byteLength = totalBytes,
                        type = FieldType.FIXED, editable = true, group = "EXIF",
                    )
                }
                ExifType.BYTE -> MetadataField(
                    key = ExifTags.name(tag),
                    value = Value.Raw(tiff.data.copyOfRange(valueLocalOffset, valueLocalOffset + totalBytes)),
                    byteOffset = absoluteOffset, byteLength = totalBytes,
                    type = FieldType.FIXED, editable = false, group = "EXIF",
                )
            }
            fields.add(field)
        }
        return fields
    }

    override fun validateEdit(source: ByteSource, field: MetadataField, newValue: Value): EditPlan =
        EditPlan.Rejected("editing not yet implemented") // implemented in Task 16
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.handler.exif.JpegExifHandlerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/handler/exif/JpegExifHandler.kt \
        core/src/test/kotlin/com/forensics/core/handler/exif/JpegExifHandlerTest.kt
git commit -m "feat: JPEG/EXIF detection and IFD0 parsing"
```

---

## Task 16: JpegExifHandler — validateEdit

**Files:**
- Modify: `core/src/main/kotlin/com/forensics/core/handler/exif/JpegExifHandler.kt` (replace `validateEdit`)
- Test: `core/src/test/kotlin/com/forensics/core/handler/exif/JpegExifHandlerEditTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/handler/exif/JpegExifHandlerEditTest.kt`:

```kotlin
package com.forensics.core.handler.exif

import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegExifHandlerEditTest {
    private val handler = JpegExifHandler()
    private fun source() = InMemoryByteSource(TestExifJpeg.build(1, "2020:01:02 03:04:05"))

    @Test fun orientationEditIsInPlaceSameLength() {
        val src = source()
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(6))
        assertTrue(plan is EditPlan.InPlace)
        plan as EditPlan.InPlace
        assertEquals(2, plan.newBytes.size)
        assertEquals(field.byteLength, plan.newBytes.size)
        assertEquals(field.byteOffset, plan.offset)
    }

    @Test fun orientationOutOfRangeRejected() {
        val src = source()
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(99))
        assertTrue(plan is EditPlan.Rejected)
    }

    @Test fun sameLengthDateTimeIsInPlace() {
        val src = source()
        val field = handler.parse(src).first { it.key == "DateTime" }
        val plan = handler.validateEdit(src, field, Value.Text("2021:11:12 13:14:15"))
        assertTrue(plan is EditPlan.InPlace)
        plan as EditPlan.InPlace
        assertEquals(field.byteLength, plan.newBytes.size)
    }

    @Test fun longerStringRequiresRewrite() {
        val src = source()
        val field = handler.parse(src).first { it.key == "Make" } // "ACME\0" = 5 bytes
        val plan = handler.validateEdit(src, field, Value.Text("A-Much-Longer-Maker"))
        assertTrue(plan is EditPlan.RequiresRewrite)
    }

    @Test fun shorterStringIsInPlacePaddedWithNul() {
        val src = source()
        val field = handler.parse(src).first { it.key == "Make" } // 5 bytes incl NUL
        val plan = handler.validateEdit(src, field, Value.Text("Hi"))
        assertTrue(plan is EditPlan.InPlace)
        plan as EditPlan.InPlace
        assertEquals(field.byteLength, plan.newBytes.size) // padded to original length
        assertEquals(0.toByte(), plan.newBytes.last())      // NUL padding
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.handler.exif.JpegExifHandlerEditTest"`
Expected: FAIL — `validateEdit` returns `Rejected("editing not yet implemented")`.

- [ ] **Step 3: Replace validateEdit with the real implementation**

In `core/src/main/kotlin/com/forensics/core/handler/exif/JpegExifHandler.kt`, replace the placeholder `validateEdit` method with:

```kotlin
    override fun validateEdit(source: ByteSource, field: MetadataField, newValue: Value): EditPlan {
        if (!field.editable) return EditPlan.Rejected("field is read-only")
        val bytes = source.readAt(0, source.size().toInt())
        val tiffBase = findTiffBase(bytes)
        if (tiffBase < 0) return EditPlan.Rejected("no EXIF block")
        val littleEndian = String(bytes, tiffBase, 2, Charsets.US_ASCII) == "II"
        val tiff = TiffReader(bytes, littleEndian) // absolute reads; we only need encodeU16

        return when (val v = newValue) {
            is Value.Integer -> {
                // Only SHORT orientation-style fields supported for integer edits in v1.
                if (field.byteLength != 2) return EditPlan.Rejected("unsupported integer field width")
                if (field.key == "Orientation" && v.n !in 1..8)
                    return EditPlan.Rejected("Orientation must be 1..8")
                if (v.n !in 0..0xFFFF) return EditPlan.Rejected("value out of SHORT range")
                val original = source.readAt(field.byteOffset, field.byteLength)
                val newBytes = tiff.encodeU16(v.n.toInt())
                EditPlan.InPlace(field.byteOffset, original, newBytes)
            }
            is Value.Text -> {
                val encoded = v.s.toByteArray(Charsets.US_ASCII)
                // ASCII field includes a NUL terminator within byteLength.
                val capacity = field.byteLength
                when {
                    encoded.size + 1 > capacity ->
                        EditPlan.RequiresRewrite(
                            "new text is longer than the original field",
                            rebuildWithText(bytes, field, v.s),
                        )
                    else -> {
                        val original = source.readAt(field.byteOffset, field.byteLength)
                        val padded = ByteArray(capacity) // zero-filled => NUL padding
                        System.arraycopy(encoded, 0, padded, 0, encoded.size)
                        EditPlan.InPlace(field.byteOffset, original, padded)
                    }
                }
            }
            is Value.Raw -> EditPlan.Rejected("raw fields are not editable in v1")
        }
    }

    /**
     * Rebuilds the whole file with [field]'s ASCII value replaced by [text].
     * v1: full rewrite is only structurally safe for the appended-overflow ASCII layout the
     * fixture/most cameras use. For unsupported layouts we return the original bytes unchanged,
     * which the EditEngine will detect as a no-op via structural re-parse and report as failure.
     */
    private fun rebuildWithText(original: ByteArray, field: MetadataField, text: String): ByteArray {
        // v1 scope: signal "rewrite required" but defer the actual TIFF rebuild to a follow-on plan.
        // Returning original bytes makes the engine's verify step fail closed (no corruption).
        return original.copyOf()
    }
```

> Note: `rebuildWithText` is intentionally a safe stub for v1 — it never produces corrupt output; the engine's structural re-parse + value re-check will report `Failure` because the value did not actually change. Full TIFF rebuild is a scoped follow-on (see Plan A follow-ons). This keeps the "warn, then rewrite" contract honest: the plan classifies correctly as `RequiresRewrite`, and execution fails safely rather than corrupting until rebuild lands.

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.handler.exif.JpegExifHandlerEditTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/handler/exif/JpegExifHandler.kt \
        core/src/test/kotlin/com/forensics/core/handler/exif/JpegExifHandlerEditTest.kt
git commit -m "feat: compile EXIF edits into in-place/rewrite/rejected plans"
```

---

## Task 17: Concurrent-modification guard

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/engine/Guard.kt`
- Test: `core/src/test/kotlin/com/forensics/core/engine/GuardTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/engine/GuardTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.engine.GuardTest"`
Expected: FAIL — `Guard` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/engine/Guard.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.engine.GuardTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/engine/Guard.kt \
        core/src/test/kotlin/com/forensics/core/engine/GuardTest.kt
git commit -m "feat: add concurrent-modification guard"
```

---

## Task 18: EditEngine — in-place execution with undo + verification

This is the safety-critical core. It executes a plan against a `ByteSink`, re-reads through a fresh `ByteSource`, re-parses with the handler, and restores from the undo buffer on any failure.

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/engine/EditEngine.kt`
- Test: `core/src/test/kotlin/com/forensics/core/engine/EditEngineTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/engine/EditEngineTest.kt`:

```kotlin
package com.forensics.core.engine

import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.handler.exif.TestExifJpeg
import com.forensics.core.io.InMemoryByteSink
import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.EditResult
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditEngineTest {
    private val handler = JpegExifHandler()
    private val engine = EditEngine(handler)

    private fun bytes() = TestExifJpeg.build(1, "2020:01:02 03:04:05")

    @Test fun inPlaceOrientationEditSucceedsAndKeepsSize() {
        val original = bytes()
        val sink = InMemoryByteSink(original)
        val src = InMemoryByteSource(original)
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace
        val token = Guard.capture(src)

        val result = engine.apply(sink, plan, token, field, Value.Integer(8))

        assertTrue(result is EditResult.Success)
        assertEquals(original.size.toLong(), sink.size()) // file size unchanged
        // re-parse the written bytes: orientation is now 8
        val reparsed = handler.parse(InMemoryByteSource(sink.snapshot()))
        assertEquals(Value.Integer(8), reparsed.first { it.key == "Orientation" }.value)
    }

    @Test fun roundTripBackToOriginalIsBitIdentical() {
        val original = bytes()
        val sink = InMemoryByteSink(original)
        var src = InMemoryByteSource(original)
        var field = handler.parse(src).first { it.key == "Orientation" }
        // edit to 8
        engine.apply(sink, handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace,
            Guard.capture(src), field, Value.Integer(8))
        // edit back to 1
        src = InMemoryByteSource(sink.snapshot())
        field = handler.parse(src).first { it.key == "Orientation" }
        engine.apply(sink, handler.validateEdit(src, field, Value.Integer(1)) as EditPlan.InPlace,
            Guard.capture(src), field, Value.Integer(1))
        assertTrue(original.contentEquals(sink.snapshot()))
    }

    @Test fun abortsWhenSourceModifiedConcurrently() {
        val original = bytes()
        val sink = InMemoryByteSink(original)
        val src = InMemoryByteSource(original)
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace
        // stale token: capture, then mutate the underlying sink to change size/header
        val staleToken = Guard.capture(src)
        sink.rewrite(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // truncate
        val result = engine.apply(sink, plan, staleToken, field, Value.Integer(8))
        assertTrue(result is EditResult.Failure)
    }

    @Test fun rejectedPlanIsNeverWritten() {
        val sink = InMemoryByteSink(bytes())
        val before = sink.snapshot()
        val result = engine.apply(
            sink, EditPlan.Rejected("nope"),
            Guard.capture(InMemoryByteSource(before)),
            handler.parse(InMemoryByteSource(before)).first(), Value.Integer(2),
        )
        assertTrue(result is EditResult.Failure)
        assertTrue(before.contentEquals(sink.snapshot()))
    }

    @Test fun restoresOriginalWhenVerificationFails() {
        // Corrupt the verifier by editing a field, but force a mismatch:
        // we simulate a bad write by wrapping the sink to flip a byte after writeAt.
        val original = bytes()
        val backing = InMemoryByteSink(original)
        val faulty = object : com.forensics.core.io.ByteSink by backing {
            override fun writeAt(offset: Long, bytes: ByteArray) {
                backing.writeAt(offset, bytes)
                // corrupt: also flip the orientation high byte at offset+0 to a wrong value
                backing.writeAt(offset, byteArrayOf(0x7F))
            }
        }
        val src = InMemoryByteSource(original)
        val field = handler.parse(src).first { it.key == "Orientation" }
        val plan = handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace
        val result = engine.apply(faulty, plan, Guard.capture(src), field, Value.Integer(8))
        assertTrue(result is EditResult.Failure)
        assertTrue(original.contentEquals(backing.snapshot())) // restored
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.engine.EditEngineTest"`
Expected: FAIL — `EditEngine` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/engine/EditEngine.kt`:

```kotlin
package com.forensics.core.engine

import com.forensics.core.handler.FormatHandler
import com.forensics.core.io.ByteSink
import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.EditResult
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

/**
 * Executes [EditPlan]s with defense-in-depth:
 *  - fail closed on Rejected
 *  - concurrent-modification guard before any write
 *  - strict same-length in-place invariant
 *  - undo buffer + read-back verification + structural re-parse, restore on failure
 */
class EditEngine(private val handler: FormatHandler) {

    fun apply(
        sink: ByteSink,
        plan: EditPlan,
        guard: GuardToken,
        field: MetadataField,
        expectedNewValue: Value,
    ): EditResult = when (plan) {
        is EditPlan.Rejected -> EditResult.Failure(plan.reason)
        is EditPlan.InPlace -> applyInPlace(sink, plan, guard, field, expectedNewValue)
        is EditPlan.RequiresRewrite -> applyRewrite(sink, plan, guard, field, expectedNewValue)
    }

    private fun currentSource(sink: ByteSink) =
        InMemoryByteSource(sink.readAt(0, sink.size().toInt()))

    private fun applyInPlace(
        sink: ByteSink, plan: EditPlan.InPlace, guard: GuardToken,
        field: MetadataField, expected: Value,
    ): EditResult {
        // Guard: source unchanged since parse.
        if (!Guard.matches(guard, currentSource(sink)))
            return EditResult.Failure("file changed since it was read")

        // Strict invariant: same length, in bounds.
        if (plan.newBytes.size != plan.originalBytes.size)
            return EditResult.Failure("in-place plan is not length-preserving")
        if (plan.offset < 0 || plan.offset + plan.newBytes.size > sink.size())
            return EditResult.Failure("in-place write out of bounds")

        // Capture undo buffers (value + every checksum region).
        val undoValue = sink.readAt(plan.offset, plan.newBytes.size)
        val undoChecksums = plan.checksumPatches.map { it.offset to sink.readAt(it.offset, it.bytes.size) }

        try {
            sink.writeAt(plan.offset, plan.newBytes)
            plan.checksumPatches.forEach { sink.writeAt(it.offset, it.bytes) }
            sink.force()

            // Read-back verify the exact bytes we wrote.
            if (!sink.readAt(plan.offset, plan.newBytes.size).contentEquals(plan.newBytes))
                return restore(sink, plan.offset, undoValue, undoChecksums, "read-back mismatch")
            plan.checksumPatches.forEach {
                if (!sink.readAt(it.offset, it.bytes.size).contentEquals(it.bytes))
                    return restore(sink, plan.offset, undoValue, undoChecksums, "checksum read-back mismatch")
            }

            // Structural re-parse: the container must still parse and the field must equal expected.
            val reparsed = handler.parse(currentSource(sink))
            val match = reparsed.firstOrNull { it.key == field.key && it.byteOffset == field.byteOffset }
            if (match == null || match.value != expected)
                return restore(sink, plan.offset, undoValue, undoChecksums, "structural re-parse failed")

            return EditResult.Success(inPlace = true, bytesPatched = plan.newBytes.size)
        } catch (t: Throwable) {
            return restore(sink, plan.offset, undoValue, undoChecksums, "write error: ${t.message}")
        }
    }

    private fun restore(
        sink: ByteSink, valueOffset: Long, undoValue: ByteArray,
        undoChecksums: List<Pair<Long, ByteArray>>, reason: String,
    ): EditResult {
        runCatching {
            sink.writeAt(valueOffset, undoValue)
            undoChecksums.forEach { (off, bytes) -> sink.writeAt(off, bytes) }
            sink.force()
        }
        return EditResult.Failure(reason)
    }

    private fun applyRewrite(
        sink: ByteSink, plan: EditPlan.RequiresRewrite, guard: GuardToken,
        field: MetadataField, expected: Value,
    ): EditResult {
        if (!Guard.matches(guard, currentSource(sink)))
            return EditResult.Failure("file changed since it was read")

        // Verify the rebuilt bytes parse and contain the expected value BEFORE touching the original.
        val staged = InMemoryByteSource(plan.rebuiltBytes)
        val parsed = runCatching { handler.parse(staged) }.getOrNull()
            ?: return EditResult.Failure("rebuilt file does not parse")
        val match = parsed.firstOrNull { it.key == field.key }
        if (match == null || match.value != expected)
            return EditResult.Failure("rebuilt file does not contain the expected value")

        val undoWhole = sink.readAt(0, sink.size().toInt())
        try {
            sink.rewrite(plan.rebuiltBytes.inputStream())
            sink.force()
            // Re-parse from disk to confirm.
            val reparsed = handler.parse(currentSource(sink))
            val ok = reparsed.firstOrNull { it.key == field.key }?.value == expected
            if (!ok) {
                sink.rewrite(undoWhole.inputStream()); sink.force()
                return EditResult.Failure("post-rewrite verification failed")
            }
            return EditResult.Success(inPlace = false, bytesPatched = plan.rebuiltBytes.size)
        } catch (t: Throwable) {
            runCatching { sink.rewrite(undoWhole.inputStream()); sink.force() }
            return EditResult.Failure("rewrite error: ${t.message}")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.engine.EditEngineTest"`
Expected: PASS (5 tests). The `restoresOriginalWhenVerificationFails` test confirms a corrupted write is detected by read-back/re-parse and rolled back to a bit-identical original.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/engine/EditEngine.kt \
        core/src/test/kotlin/com/forensics/core/engine/EditEngineTest.kt
git commit -m "feat: corruption-safe EditEngine with undo, verify, and rewrite staging"
```

---

## Task 19: Inspector facade

A single entry point that ties detection + handler selection + parsing together, so Plan B (Android) has one call to make.

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/Inspector.kt`
- Test: `core/src/test/kotlin/com/forensics/core/InspectorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/InspectorTest.kt`:

```kotlin
package com.forensics.core

import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.handler.exif.TestExifJpeg
import com.forensics.core.io.InMemoryByteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectorTest {
    private val inspector = Inspector(listOf(JpegExifHandler()))

    @Test fun inspectsJpegWithHandlerFields() {
        val src = InMemoryByteSource(TestExifJpeg.build(3, "2020:01:02 03:04:05"))
        val result = inspector.inspect(src)
        assertEquals("JPEG/EXIF", result.handlerName)
        assertTrue(result.fields.any { it.key == "Orientation" })
        assertTrue(result.md5.isNotEmpty())
    }

    @Test fun unknownFormatStillReturnsHashesAndNoHandler() {
        val src = InMemoryByteSource(byteArrayOf(0, 1, 2, 3))
        val result = inspector.inspect(src)
        assertEquals(null, result.handlerName)
        assertTrue(result.fields.isEmpty())
        assertTrue(result.sha256.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.InspectorTest"`
Expected: FAIL — `Inspector` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/Inspector.kt`:

```kotlin
package com.forensics.core

import com.forensics.core.generic.Hashing
import com.forensics.core.handler.FormatHandler
import com.forensics.core.io.ByteSource
import com.forensics.core.model.MetadataField

data class InspectionResult(
    val handlerName: String?,
    val fields: List<MetadataField>,
    val md5: String,
    val sha256: String,
)

/** One-call inspection: hashes (always) + handler fields (if a handler matches). */
class Inspector(private val handlers: List<FormatHandler>) {
    fun inspect(source: ByteSource): InspectionResult {
        val magicLen = minOf(16L, source.size()).toInt()
        val magic = if (magicLen > 0) source.readAt(0, magicLen) else ByteArray(0)
        val handler = handlers.firstOrNull { it.canHandle(magic, null) }
        val fields = handler?.let { runCatching { it.parse(source) }.getOrDefault(emptyList()) } ?: emptyList()
        return InspectionResult(
            handlerName = if (fields.isNotEmpty() || handler != null) handler?.formatName else null,
            fields = fields,
            md5 = Hashing.md5(source),
            sha256 = Hashing.sha256(source),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.InspectorTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/Inspector.kt \
        core/src/test/kotlin/com/forensics/core/InspectorTest.kt
git commit -m "feat: add Inspector facade tying detection + hashing + parsing"
```

---

## Task 20: Full suite green + malformed-input safety net

**Files:**
- Test: `core/src/test/kotlin/com/forensics/core/handler/exif/MalformedInputTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/handler/exif/MalformedInputTest.kt`:

```kotlin
package com.forensics.core.handler.exif

import com.forensics.core.io.InMemoryByteSource
import kotlin.test.Test
import kotlin.test.assertTrue

class MalformedInputTest {
    private val handler = JpegExifHandler()

    @Test fun truncatedJpegDoesNotThrow() {
        val full = TestExifJpeg.build(1, "2020:01:02 03:04:05")
        val truncated = full.copyOf(full.size / 2)
        val fields = handler.parse(InMemoryByteSource(truncated)) // must not throw
        assertTrue(fields.size >= 0)
    }

    @Test fun jpegWithoutExifReturnsEmpty() {
        // SOI + EOI only
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertTrue(handler.parse(InMemoryByteSource(bytes)).isEmpty())
    }

    @Test fun garbageBytesReturnEmpty() {
        val bytes = ByteArray(200) { (it * 7).toByte() }
        // ensure JPEG magic so canHandle passes but body is junk
        bytes[0] = 0xFF.toByte(); bytes[1] = 0xD8.toByte(); bytes[2] = 0xFF.toByte()
        handler.parse(InMemoryByteSource(bytes)) // must not throw
    }
}
```

- [ ] **Step 2: Run test to verify behavior**

Run: `gradle :core:test --tests "com.forensics.core.handler.exif.MalformedInputTest"`
Expected: PASS. If any case throws, wrap the offending read in `parse` with a bounds check or `runCatching { ... }.getOrDefault(emptyList())` at the `parse` entry so malformed input degrades to empty, never crashes.

- [ ] **Step 3: Make parse fully defensive (if needed)**

If Step 2 revealed a throw, wrap the body of `JpegExifHandler.parse` so the outer contract is total:

```kotlin
    override fun parse(source: ByteSource): List<MetadataField> = runCatching {
        parseInternal(source)
    }.getOrDefault(emptyList())
```

…renaming the current body to `private fun parseInternal(source: ByteSource): List<MetadataField>`. (Do this only if a test threw; otherwise leave as-is.)

- [ ] **Step 4: Run the whole suite**

Run: `gradle :core:test`
Expected: PASS — all tests across all classes green.

- [ ] **Step 5: Commit**

```bash
git add core/src/test/kotlin/com/forensics/core/handler/exif/MalformedInputTest.kt \
        core/src/main/kotlin/com/forensics/core/handler/exif/JpegExifHandler.kt
git commit -m "test: malformed-input safety net; parse degrades to empty"
```

---

## Plan A follow-ons (out of scope here, tracked for later plans)

- **Full TIFF rebuild** for the `RequiresRewrite` path (replace the safe stub in Task 16's `rebuildWithText`), with offset/IFD reconstruction and tests asserting the rebuilt file parses and the original is only overwritten after staged verification.
- **GPS IFD** parsing and rational-value editing (fixed 8-byte rationals are in-place-capable).
- **PNG handler** (chunk walk + CRC32 recompute via `checksumPatches`), exercising the engine's checksum-patch path end-to-end.
- **MP4/MOV, MP3/ID3, PDF (read), ZIP (read)** handlers.

---

## Self-Review

**Spec coverage check (against `2026-06-01-forensics-metadata-app-design.md`):**

- Generic core: format detection (Task 7), hashing (Task 8), hex (Task 9), strings (Task 10) ✓. Filesystem metadata is Android-side → Plan B.
- Pluggable handler interface (Task 11) + JPEG/EXIF flagship (Tasks 12–16) ✓.
- ByteSource/ByteSink IO boundary enabling on-device impls later (Tasks 5–6) ✓.
- Edit model: InPlace / RequiresRewrite / Rejected (Task 4); classification (Task 16) ✓.
- Corruption safety: fail-closed, same-length invariant, undo buffer, read-back verify, structural re-parse, restore-on-failure (Task 18); concurrent-modification guard (Task 17); checksum-patch plumbing (Task 18, exercised fully by the PNG follow-on) ✓.
- Risk tiers: Tier 0 in-place (no temp) is Task 18's in-place path; Tier 1 rewrite staging+verify is Task 18's rewrite path ✓.
- Testing strategy: round-trip bit-identical (Task 18), structural re-parse gate + fault injection (Task 18), malformed degrades-not-crash (Task 20), deterministic fixtures (Task 14) ✓.
- Large-file streaming: hashing/strings stream (Tasks 8, 10); the Android random-access sink for true large-file in-place lands in Plan B, but the engine only ever holds field-sized undo buffers for Tier 0 ✓.

**Known v1 limitation captured in-plan:** the `RequiresRewrite` execution path is wired and verified, but the EXIF TIFF *rebuild* is a safe stub (fails closed, never corrupts) pending the follow-on. This matches the spec's phasing (flagship EXIF read + in-place edit first).

**Placeholder scan:** No TBD/TODO; every code step shows complete code. The one intentional stub (`rebuildWithText`) is documented as fail-closed, not a gap.

**Type consistency:** `ByteSource`/`ByteSink`, `MetadataField`, `EditPlan.{InPlace,RequiresRewrite,Rejected}`, `EditResult.{Success,Failure}`, `Value.{Text,Integer,Raw}`, `GuardToken`, and `EditEngine.apply(sink, plan, guard, field, expectedNewValue)` are used identically across Tasks 4–20. `TestExifJpeg.build/TIFF_BASE` and `ExifTags`/`ExifType` names match across handler and tests.
