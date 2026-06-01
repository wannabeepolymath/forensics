# File-Backed IO Adapters Implementation Plan (Plan B1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `FileChannelByteSource` and `FileChannelByteSink` — real-file implementations of the core's IO interfaces over a `java.nio` `FileChannel` — and prove, with a real temp file, that the `EditEngine` performs genuine corruption-safe in-place edits on disk.

**Architecture:** Both adapters operate on a `FileChannel` supplied by the caller (the caller owns the channel's lifecycle). All reads/writes are positional (`channel.read(buf, position)` / `channel.write(buf, position)`), so they never disturb a shared channel position and multiple readers are safe. `writeAt` enforces the same length-preserving invariant as the in-memory sink. This is pure `java.nio` — no Android types — so it lives in `core`, is JVM-unit-testable against `RandomAccessFile(tmp, "rw").channel`, and on Android the same classes accept a read-write `FileChannel` obtained from a SAF `ParcelFileDescriptor` (Plan B2 supplies that channel).

**Tech Stack:** Kotlin (JVM), `java.nio.channels.FileChannel`, `java.nio.ByteBuffer`, JUnit 5. Same `core` module as Plan A.

---

## Context for the implementer

The `core` module already contains (Plan A, all tested):
- `com.forensics.core.io.ByteSource` { `size(): Long`; `readAt(offset: Long, length: Int): ByteArray`; `openStream(): InputStream` }
- `com.forensics.core.io.ByteSink` { `size(): Long`; `readAt(offset: Long, length: Int): ByteArray`; `writeAt(offset: Long, bytes: ByteArray)` [length-preserving, throws if it would change length]; `rewrite(content: InputStream)`; `force()` }
- `com.forensics.core.engine.{EditEngine, Guard}`, `com.forensics.core.handler.exif.{JpegExifHandler, TestExifJpeg}`, `com.forensics.core.model.{EditPlan, EditResult, Value}`.

Test command (a Temurin 17 JDK is required — ALWAYS prefix):
`export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :core:test --console=plain` (single class: `--tests "FQCN"`).

## File Structure

```
core/src/main/kotlin/com/forensics/core/io/
  FileChannelByteSource.kt   # ByteSource over a FileChannel (positional reads + streaming)
  FileChannelByteSink.kt     # ByteSink over a rw FileChannel (positional writes, length-preserving)
core/src/test/kotlin/com/forensics/core/io/
  FileChannelByteSourceTest.kt
  FileChannelByteSinkTest.kt
core/src/test/kotlin/com/forensics/core/engine/
  FileBackedEditEngineTest.kt   # real temp-file end-to-end: in-place edit + rewrite
```

---

## Task 1: FileChannelByteSink

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/io/FileChannelByteSink.kt`
- Test: `core/src/test/kotlin/com/forensics/core/io/FileChannelByteSinkTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/io/FileChannelByteSinkTest.kt`:

```kotlin
package com.forensics.core.io

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileChannelByteSinkTest {
    private val tmp: File = File.createTempFile("sink", ".bin")

    @AfterTest fun cleanup() { tmp.delete() }

    private fun sinkOf(initial: ByteArray): Pair<RandomAccessFile, FileChannelByteSink> {
        tmp.writeBytes(initial)
        val raf = RandomAccessFile(tmp, "rw")
        return raf to FileChannelByteSink(raf.channel)
    }

    @Test fun writeAtKeepsLengthAndOverwrites() {
        val (raf, sink) = sinkOf(byteArrayOf(1, 2, 3, 4))
        raf.use {
            sink.writeAt(1, byteArrayOf(9, 9))
            sink.force()
            assertEquals(listOf<Byte>(1, 9, 9, 4), sink.readAt(0, 4).toList())
            assertEquals(4L, sink.size())
        }
        assertEquals(listOf<Byte>(1, 9, 9, 4), tmp.readBytes().toList()) // persisted on disk
    }

    @Test fun writePastEndFails() {
        val (raf, sink) = sinkOf(byteArrayOf(1, 2))
        raf.use {
            assertFailsWith<IllegalArgumentException> { sink.writeAt(1, byteArrayOf(5, 6)) }
        }
    }

    @Test fun rewriteShorterTruncates() {
        val (raf, sink) = sinkOf(byteArrayOf(1, 2, 3, 4, 5))
        raf.use {
            sink.rewrite(ByteArrayInputStream(byteArrayOf(8, 8)))
            sink.force()
            assertEquals(2L, sink.size())
            assertEquals(listOf<Byte>(8, 8), sink.readAt(0, 2).toList())
        }
        assertEquals(listOf<Byte>(8, 8), tmp.readBytes().toList())
    }

    @Test fun rewriteLongerGrows() {
        val (raf, sink) = sinkOf(byteArrayOf(1, 2))
        raf.use {
            sink.rewrite(ByteArrayInputStream(byteArrayOf(7, 7, 7, 7)))
            sink.force()
            assertEquals(4L, sink.size())
            assertEquals(listOf<Byte>(7, 7, 7, 7), sink.readAt(0, 4).toList())
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.io.FileChannelByteSinkTest"` (prefix with the JAVA_HOME export).
Expected: FAIL — `FileChannelByteSink` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/io/FileChannelByteSink.kt`:

```kotlin
package com.forensics.core.io

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A [ByteSink] over a read-write [FileChannel] — the real on-disk in-place editor.
 *
 * All writes are positional, so a shared channel position is never disturbed. The caller
 * owns the channel and is responsible for closing it; this class never closes it. Works with
 * any rw FileChannel: `RandomAccessFile(file, "rw").channel` on the JVM, or a channel derived
 * from a SAF `ParcelFileDescriptor` on Android.
 */
class FileChannelByteSink(private val channel: FileChannel) : ByteSink {

    override fun size(): Long = channel.size()

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= channel.size()) {
            "read [$offset, ${offset + length}) out of bounds for size ${channel.size()}"
        }
        val buf = ByteBuffer.allocate(length)
        var read = 0
        while (read < length) {
            val n = channel.read(buf, offset + read)
            if (n < 0) break
            read += n
        }
        require(read == length) { "short read: expected $length, got $read" }
        return buf.array()
    }

    override fun writeAt(offset: Long, bytes: ByteArray) {
        require(offset >= 0 && offset + bytes.size <= channel.size()) {
            "in-place write [$offset, ${offset + bytes.size}) would change length ${channel.size()}"
        }
        val buf = ByteBuffer.wrap(bytes)
        var pos = offset
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos)
        }
    }

    override fun rewrite(content: InputStream) {
        val all = content.readBytes()
        val buf = ByteBuffer.wrap(all)
        var pos = 0L
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos)
        }
        // Shrink away any leftover tail if the new content is shorter than the old file.
        channel.truncate(all.size.toLong())
    }

    override fun force() { channel.force(true) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.io.FileChannelByteSinkTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/io/FileChannelByteSink.kt \
        core/src/test/kotlin/com/forensics/core/io/FileChannelByteSinkTest.kt
git commit -m "feat: add FileChannelByteSink (real on-disk in-place writer)"
```

---

## Task 2: FileChannelByteSource

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/io/FileChannelByteSource.kt`
- Test: `core/src/test/kotlin/com/forensics/core/io/FileChannelByteSourceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/io/FileChannelByteSourceTest.kt`:

```kotlin
package com.forensics.core.io

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileChannelByteSourceTest {
    private val tmp: File = File.createTempFile("source", ".bin")

    @AfterTest fun cleanup() { tmp.delete() }

    private fun sourceOf(data: ByteArray): Pair<RandomAccessFile, FileChannelByteSource> {
        tmp.writeBytes(data)
        val raf = RandomAccessFile(tmp, "r")
        return raf to FileChannelByteSource(raf.channel)
    }

    @Test fun reportsSize() {
        val (raf, src) = sourceOf(byteArrayOf(10, 20, 30, 40, 50))
        raf.use { assertEquals(5L, src.size()) }
    }

    @Test fun readsAtOffset() {
        val (raf, src) = sourceOf(byteArrayOf(10, 20, 30, 40, 50))
        raf.use { assertEquals(listOf<Byte>(20, 30), src.readAt(1, 2).toList()) }
    }

    @Test fun readingPastEndFails() {
        val (raf, src) = sourceOf(byteArrayOf(10, 20, 30, 40, 50))
        raf.use { assertFailsWith<IllegalArgumentException> { src.readAt(4, 2) } }
    }

    @Test fun streamsAllBytesWithoutDisturbingReadAt() {
        val (raf, src) = sourceOf(byteArrayOf(10, 20, 30, 40, 50))
        raf.use {
            assertEquals(listOf<Byte>(10, 20, 30, 40, 50), src.openStream().readBytes().toList())
            // positional readAt still works after streaming
            assertEquals(listOf<Byte>(30), src.readAt(2, 1).toList())
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.io.FileChannelByteSourceTest"`
Expected: FAIL — `FileChannelByteSource` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/io/FileChannelByteSource.kt`:

```kotlin
package com.forensics.core.io

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A [ByteSource] over a [FileChannel]. All reads are positional, so [openStream] tracks its own
 * cursor and never disturbs [readAt] (or other readers sharing the channel). The caller owns the
 * channel and is responsible for closing it; this class never closes it.
 */
class FileChannelByteSource(private val channel: FileChannel) : ByteSource {

    override fun size(): Long = channel.size()

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= channel.size()) {
            "read [$offset, ${offset + length}) out of bounds for size ${channel.size()}"
        }
        val buf = ByteBuffer.allocate(length)
        var read = 0
        while (read < length) {
            val n = channel.read(buf, offset + read)
            if (n < 0) break
            read += n
        }
        require(read == length) { "short read: expected $length, got $read" }
        return buf.array()
    }

    override fun openStream(): InputStream = object : InputStream() {
        private var pos = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= channel.size()) return -1
            val buf = ByteBuffer.wrap(b, off, len)
            val n = channel.read(buf, pos)
            if (n > 0) pos += n
            return n
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.io.FileChannelByteSourceTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/io/FileChannelByteSource.kt \
        core/src/test/kotlin/com/forensics/core/io/FileChannelByteSourceTest.kt
git commit -m "feat: add FileChannelByteSource (positional file reads + streaming)"
```

---

## Task 3: Real-file end-to-end EditEngine test (the proof)

This is the highest-value task: it proves the whole stack performs a genuine corruption-safe in-place edit on a real file on disk, then verifies the change persisted by reopening a fresh channel.

**Files:**
- Test: `core/src/test/kotlin/com/forensics/core/engine/FileBackedEditEngineTest.kt`

- [ ] **Step 1: Write the test**

Create `core/src/test/kotlin/com/forensics/core/engine/FileBackedEditEngineTest.kt`:

```kotlin
package com.forensics.core.engine

import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.handler.exif.TestExifJpeg
import com.forensics.core.io.FileChannelByteSink
import com.forensics.core.io.FileChannelByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.EditResult
import com.forensics.core.model.Value
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileBackedEditEngineTest {
    private val handler = JpegExifHandler()
    private val tmp: File = File.createTempFile("forensics", ".jpg")

    @AfterTest fun cleanup() { tmp.delete() }

    @Test fun realFileInPlaceEditPersistsAndKeepsSize() {
        tmp.writeBytes(TestExifJpeg.build(orientation = 1, dateTime = "2020:01:02 03:04:05"))
        val originalSize = tmp.length()
        val originalBytes = tmp.readBytes()

        // Edit Orientation 1 -> 8 on the real file via a read-write channel.
        RandomAccessFile(tmp, "rw").use { raf ->
            val channel = raf.channel
            val sink = FileChannelByteSink(channel)
            val src = FileChannelByteSource(channel)
            val field = handler.parse(src).first { it.key == "Orientation" }
            val plan = handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace
            val result = EditEngine(handler).apply(sink, plan, Guard.capture(src), field, Value.Integer(8))
            assertTrue(result is EditResult.Success)
        }

        // Reopen a FRESH channel and confirm the change is on disk, size unchanged.
        assertEquals(originalSize, tmp.length())
        RandomAccessFile(tmp, "r").use { raf ->
            val reparsed = handler.parse(FileChannelByteSource(raf.channel))
            assertEquals(Value.Integer(8), reparsed.first { it.key == "Orientation" }.value)
        }
        // Exactly two bytes differ from the original (the SHORT orientation value).
        val newBytes = tmp.readBytes()
        assertEquals(originalSize.toInt(), newBytes.size)
        val diffCount = originalBytes.indices.count { originalBytes[it] != newBytes[it] }
        assertTrue(diffCount in 1..2, "expected a tiny in-place delta, got $diffCount changed bytes")
    }

    @Test fun realFileRoundTripIsBitIdentical() {
        tmp.writeBytes(TestExifJpeg.build(orientation = 1, dateTime = "2020:01:02 03:04:05"))
        val originalBytes = tmp.readBytes()

        RandomAccessFile(tmp, "rw").use { raf ->
            val sink = FileChannelByteSink(raf.channel)
            val engine = EditEngine(handler)
            // 1 -> 8
            var src = FileChannelByteSource(raf.channel)
            var field = handler.parse(src).first { it.key == "Orientation" }
            engine.apply(sink, handler.validateEdit(src, field, Value.Integer(8)) as EditPlan.InPlace,
                Guard.capture(src), field, Value.Integer(8))
            // 8 -> 1
            src = FileChannelByteSource(raf.channel)
            field = handler.parse(src).first { it.key == "Orientation" }
            engine.apply(sink, handler.validateEdit(src, field, Value.Integer(1)) as EditPlan.InPlace,
                Guard.capture(src), field, Value.Integer(1))
        }
        assertTrue(originalBytes.contentEquals(tmp.readBytes()))
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.engine.FileBackedEditEngineTest"`
Expected: PASS (2 tests). If `realFileInPlaceEditPersistsAndKeepsSize` fails on the `diffCount` assertion, print the two byte arrays' differing indices to diagnose — but with the fixture's big-endian inline SHORT, exactly the high+low orientation bytes change (1 or 2 bytes depending on whether the high byte differs).

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/com/forensics/core/engine/FileBackedEditEngineTest.kt
git commit -m "test: prove real on-disk in-place edit + bit-identical round-trip"
```

---

## Task 4: Real-file rewrite path test

Proves the rewrite path works against a real file (truncate/grow), complementing the in-memory rewrite tests from Plan A.

**Files:**
- Test: `core/src/test/kotlin/com/forensics/core/engine/FileBackedEditEngineTest.kt` (add a method)

- [ ] **Step 1: Add the test method**

Add this method inside `FileBackedEditEngineTest` (same file as Task 3):

```kotlin
    @Test fun realFileRewriteReplacesWholeFile() {
        val original = TestExifJpeg.build(orientation = 1, dateTime = "2020:01:02 03:04:05", make = "ACME")
        val rebuilt = TestExifJpeg.build(orientation = 1, dateTime = "2020:01:02 03:04:05", make = "LongerMakerName")
        tmp.writeBytes(original)

        RandomAccessFile(tmp, "rw").use { raf ->
            val sink = FileChannelByteSink(raf.channel)
            val src = FileChannelByteSource(raf.channel)
            val field = handler.parse(src).first { it.key == "Make" }
            val expected = Value.Text("LongerMakerName")
            val plan = EditPlan.RequiresRewrite("longer", rebuilt)
            val result = EditEngine(handler).apply(sink, plan, Guard.capture(src), field, expected)
            assertTrue(result is EditResult.Success)
        }

        // On disk: file now equals the rebuilt bytes (size grew), and re-parses to the new value.
        assertTrue(rebuilt.contentEquals(tmp.readBytes()))
        RandomAccessFile(tmp, "r").use { raf ->
            assertEquals(
                Value.Text("LongerMakerName"),
                handler.parse(FileChannelByteSource(raf.channel)).first { it.key == "Make" }.value,
            )
        }
    }
```

- [ ] **Step 2: Run the whole class + full suite**

Run: `gradle :core:test --tests "com.forensics.core.engine.FileBackedEditEngineTest"` → 3 tests pass.
Then full: `gradle :core:test` → all green.

- [ ] **Step 3: Commit**

```bash
git add core/src/test/kotlin/com/forensics/core/engine/FileBackedEditEngineTest.kt
git commit -m "test: prove real on-disk rewrite (whole-file replace) path"
```

---

## Self-Review

**Spec coverage (against `2026-06-01-forensics-metadata-app-design.md`):**
- "File acquisition → `"rw"` `ParcelFileDescriptor` → `FileChannel` (random access: seek to an offset, overwrite N bytes)" — `FileChannelByteSink` IS that random-access writer; `FileChannelByteSource` is the positional reader. The PFD→FileChannel acquisition itself is Plan B2 (Android), but the adapters that consume the channel are delivered and proven here. ✓
- Corruption safety on a real file: Task 3 proves an in-place edit persists to disk with a tiny byte delta and unchanged size, plus a bit-identical round-trip; the `EditEngine`'s undo/verify/restore all run against the real channel. ✓
- Large-file readiness: positional reads + a self-cursored `openStream` mean hashing/strings stream without loading the whole file via these adapters. (The `EditEngine`'s own whole-file re-parse remains the documented v1 tradeoff, tracked for B2.) ✓
- Rewrite path on disk (truncate/grow): Task 4. ✓

**Placeholder scan:** No TBD/TODO; every code step is complete. No "add error handling" hand-waves — bounds and length invariants are concrete `require(...)`s.

**Type consistency:** `FileChannelByteSink`/`FileChannelByteSource` implement the exact `ByteSink`/`ByteSource` signatures from Plan A (`size(): Long`, `readAt(Long, Int): ByteArray`, `writeAt(Long, ByteArray)`, `rewrite(InputStream)`, `force()`, `openStream(): InputStream`). `EditEngine.apply(sink, plan, guard, field, expectedNewValue)`, `EditPlan.InPlace`, `Guard.capture`, `TestExifJpeg.build(orientation, dateTime, make, model)`, and `Value.{Integer, Text}` all match their Plan A definitions.

## Follow-on (Plan B2 / B3, out of scope here)
- **Plan B2 (Android module):** SAF `ACTION_OPEN_DOCUMENT` → `takePersistableUriPermission` → `contentResolver.openFileDescriptor(uri, "rw")` → a read-write `FileChannel` handed to these adapters; a `DocumentFile`/`ContentResolver`-based filesystem-metadata provider; min SDK + AGP + Compose scaffold; `local.properties` → `~/Library/Android/sdk`. Also fold in the final-review items: have `EditEngine` re-parse read *through* the sink for large files, and offset-qualify the rewrite-path match before adding multi-IFD formats.
- **Plan B3 (Compose UI):** pick → summary → grouped metadata sections with in-place/rewrite/read-only badges, tap-to-edit dialogs (typed editors), and the paged hex viewer (`HexDump`) + strings (`Strings`).
