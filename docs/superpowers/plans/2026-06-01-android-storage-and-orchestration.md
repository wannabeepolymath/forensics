# Android Storage + Orchestration Implementation Plan (Plan B2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the proven core to real Android storage: a JVM-testable `MetadataController` orchestrating inspect+edit, plus Android `ParcelFileDescriptor`-backed `ByteSource`/`ByteSink` (via `android.system.Os`), a filesystem-metadata provider, and a minimal functional Compose screen that picks a file via SAF and edits its EXIF Orientation in place on disk.

**Architecture:** The orchestration (`MetadataController`) is pure Kotlin in `:core`, depending only on core interfaces, so it is fully JVM-unit-testable. The Android-specific glue lives in `:app`: `PfdByteSink`/`PfdByteSource` use positional `Os.pread`/`Os.pwrite`/`Os.ftruncate`/`Os.fsync` on a SAF read-write PFD (the canonical way to do random read+write on a content URI); a metadata provider reads name/size/MIME/modified from `ContentResolver`. A thin `MainViewModel` exposes UI state; a minimal Compose screen drives pick → inspect → edit. Android code is compile-verified via `:app:assembleDebug` (no emulator available; on-device verification is manual).

**Tech Stack (confirmed working):** AGP 8.13.2, Gradle 9.5.1, Kotlin 2.2.20, Compose compiler plugin 2.2.20, Compose BOM 2024.09.03, compileSdk/targetSdk 34, minSdk 26, JDK Temurin 17. `androidx.activity:activity-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.documentfile:documentfile`.

---

## Context for the implementer

Already done (Plan A + B1, on `main`/this branch; all green):
- `:core` interfaces `com.forensics.core.io.{ByteSource, ByteSink, InMemoryByteSource, InMemoryByteSink, FileChannelByteSource, FileChannelByteSink}`.
- `com.forensics.core.{Inspector, InspectionResult}`; `com.forensics.core.engine.{EditEngine, Guard, GuardToken}`; `com.forensics.core.handler.{FormatHandler}`; `com.forensics.core.handler.exif.{JpegExifHandler, TestExifJpeg}`; `com.forensics.core.model.{Value, MetadataField, EditPlan, EditResult, FieldType}`.
- `Inspector(handlers).inspect(source): InspectionResult` (handlerName, fields, md5, sha256).
- `EditEngine(handler).apply(sink, plan, guard, field, expectedNewValue): EditResult`.
- `FormatHandler` { formatName; canHandle(magic, mime); parse(source); validateEdit(source, field, newValue): EditPlan }.
- **B2-Task 0 (the spike, DONE, commit `b69a965`):** `:app` Android module scaffolds and `:app:assembleDebug` is green; `build.gradle.kts` (root) has `id("com.android.application") version "8.13.2" apply false`; `settings.gradle.kts` has `pluginManagement`/`dependencyResolutionManagement` (`google()`+`mavenCentral()`, `PREFER_PROJECT`) and `include(":app")`; `app/build.gradle.kts` applies AGP + Kotlin Android + Compose, depends on `project(":core")`, uses Compose BOM; `MainActivity` shows a trivial `Text`. `local.properties` (gitignored) has `sdk.dir=/Users/daksh/Library/Android/sdk`.

Commands (ALWAYS prefix `export JAVA_HOME=$(/usr/libexec/java_home -v 17);`):
- Core unit tests: `./gradlew :core:test --console=plain`
- App build: `./gradlew :app:assembleDebug --console=plain`
- App JVM unit tests: `./gradlew :app:testDebugUnitTest --console=plain`

## File Structure

```
core/src/main/kotlin/com/forensics/core/app/
  MetadataController.kt        # pure-Kotlin inspect + edit orchestration (JVM-testable)
core/src/test/kotlin/com/forensics/core/app/
  MetadataControllerTest.kt

app/src/main/kotlin/com/forensics/app/
  io/PfdByteSource.kt          # ByteSource over a SAF ParcelFileDescriptor (Os.pread)
  io/PfdByteSink.kt            # ByteSink over a rw SAF PFD (Os.pwrite/ftruncate/fsync)
  meta/FileIdentity.kt         # filesystem metadata data class
  meta/AndroidFileMetadata.kt  # reads name/size/MIME/modified from ContentResolver
  ui/MainViewModel.kt          # UI state (StateFlow) + actions over MetadataController
  ui/ForensicsScreen.kt        # minimal Compose: pick -> fields -> edit Orientation
  MainActivity.kt              # SAF launcher wiring (modify existing)
```

---

## Task 1: MetadataController (pure-Kotlin orchestration, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/app/MetadataController.kt`
- Test: `core/src/test/kotlin/com/forensics/core/app/MetadataControllerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/app/MetadataControllerTest.kt`:

```kotlin
package com.forensics.core.app

import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.handler.exif.TestExifJpeg
import com.forensics.core.io.InMemoryByteSink
import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.EditResult
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataControllerTest {
    private val controller = MetadataController(listOf(JpegExifHandler()))

    @Test fun inspectReturnsFieldsAndHashes() {
        val src = InMemoryByteSource(TestExifJpeg.build(3, "2020:01:02 03:04:05"))
        val result = controller.inspect(src)
        assertEquals("JPEG/EXIF", result.handlerName)
        assertTrue(result.fields.any { it.key == "Orientation" })
    }

    @Test fun editOrientationSucceedsAndPersists() {
        val bytes = TestExifJpeg.build(1, "2020:01:02 03:04:05")
        val sink = InMemoryByteSink(bytes)
        val field = controller.inspect(InMemoryByteSource(sink.snapshot()))
            .fields.first { it.key == "Orientation" }

        val result = controller.edit(sink, InMemoryByteSource(sink.snapshot()), field, Value.Integer(8))

        assertTrue(result is EditResult.Success)
        val after = controller.inspect(InMemoryByteSource(sink.snapshot()))
        assertEquals(Value.Integer(8), after.fields.first { it.key == "Orientation" }.value)
    }

    @Test fun editWithNoMatchingHandlerFails() {
        val sink = InMemoryByteSink(byteArrayOf(0, 1, 2, 3))
        val field = com.forensics.core.model.MetadataField(
            "X", Value.Integer(1), 0, 2,
            com.forensics.core.model.FieldType.FIXED, true, "EXIF",
        )
        val result = controller.edit(sink, InMemoryByteSource(byteArrayOf(0, 1, 2, 3)), field, Value.Integer(2))
        assertTrue(result is EditResult.Failure)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.app.MetadataControllerTest"` (prefix with JAVA_HOME export).
Expected: FAIL — `MetadataController` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/app/MetadataController.kt`:

```kotlin
package com.forensics.core.app

import com.forensics.core.InspectionResult
import com.forensics.core.Inspector
import com.forensics.core.engine.EditEngine
import com.forensics.core.engine.Guard
import com.forensics.core.handler.FormatHandler
import com.forensics.core.io.ByteSink
import com.forensics.core.io.ByteSource
import com.forensics.core.model.EditResult
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

/**
 * Pure-Kotlin orchestration over the core: inspect a source and apply a corruption-safe edit.
 * Holds no Android dependency, so it is fully JVM-unit-testable. The Android layer supplies the
 * [ByteSource]/[ByteSink] (PFD-backed) and calls these two methods.
 */
class MetadataController(private val handlers: List<FormatHandler>) {
    private val inspector = Inspector(handlers)

    fun inspect(source: ByteSource): InspectionResult = inspector.inspect(source)

    /**
     * Applies an edit of [field] to [newValue], selecting the handler by magic-byte detection,
     * compiling a plan, and executing it through the safety engine. Returns Failure if no handler
     * matches or the plan is rejected — never throws on ordinary failure.
     */
    fun edit(sink: ByteSink, source: ByteSource, field: MetadataField, newValue: Value): EditResult {
        val handler = matchHandler(source)
            ?: return EditResult.Failure("no handler recognizes this file")
        val plan = runCatching { handler.validateEdit(source, field, newValue) }
            .getOrElse { return EditResult.Failure("could not compile edit: ${it.message}") }
        val guard = Guard.capture(source)
        return EditEngine(handler).apply(sink, plan, guard, field, newValue)
    }

    private fun matchHandler(source: ByteSource): FormatHandler? {
        val n = minOf(16L, source.size()).toInt()
        val magic = if (n > 0) source.readAt(0, n) else ByteArray(0)
        return handlers.firstOrNull { it.canHandle(magic, null) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.app.MetadataControllerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/app/MetadataController.kt \
        core/src/test/kotlin/com/forensics/core/app/MetadataControllerTest.kt
git commit -m "feat: add MetadataController orchestration (inspect + safe edit)"
```

---

## Task 2: PfdByteSource + PfdByteSink (Android, PFD-backed)

These mirror the tested `FileChannel*` adapters but use `android.system.Os` positional syscalls on a SAF `ParcelFileDescriptor`, which supports random read AND write on the same fd. Android-only → verified by `:app:assembleDebug` (not JVM-unit-tested).

**Files:**
- Create: `app/src/main/kotlin/com/forensics/app/io/PfdByteSource.kt`
- Create: `app/src/main/kotlin/com/forensics/app/io/PfdByteSink.kt`

- [ ] **Step 1: Create PfdByteSource**

Create `app/src/main/kotlin/com/forensics/app/io/PfdByteSource.kt`:

```kotlin
package com.forensics.app.io

import android.os.ParcelFileDescriptor
import android.system.Os
import com.forensics.core.io.ByteSource
import java.io.FileDescriptor
import java.io.InputStream

/**
 * [ByteSource] over a SAF [ParcelFileDescriptor] using positional reads (`Os.pread`).
 * The caller owns the PFD and is responsible for closing it; this class never closes it.
 */
class PfdByteSource(private val pfd: ParcelFileDescriptor) : ByteSource {
    private val fd: FileDescriptor get() = pfd.fileDescriptor

    override fun size(): Long = pfd.statSize

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= size()) {
            "read [$offset, ${offset + length}) out of bounds for size ${size()}"
        }
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = Os.pread(fd, out, read, length - read, offset + read)
            if (n <= 0) break
            read += n
        }
        require(read == length) { "short read: expected $length, got $read" }
        return out
    }

    override fun openStream(): InputStream = object : InputStream() {
        private var pos = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= size()) return -1
            val n = Os.pread(fd, b, off, len, pos)
            if (n > 0) pos += n
            return if (n <= 0) -1 else n
        }
    }
}
```

- [ ] **Step 2: Create PfdByteSink**

Create `app/src/main/kotlin/com/forensics/app/io/PfdByteSink.kt`:

```kotlin
package com.forensics.app.io

import android.os.ParcelFileDescriptor
import android.system.Os
import com.forensics.core.io.ByteSink
import java.io.FileDescriptor
import java.io.InputStream

/**
 * [ByteSink] over a read-write SAF [ParcelFileDescriptor] (opened with mode "rw") using positional
 * `Os.pwrite`/`Os.pread`, `Os.ftruncate`, and `Os.fsync`. The caller owns the PFD and closes it.
 * `writeAt` enforces the same length-preserving invariant as the in-memory/file sinks.
 */
class PfdByteSink(private val pfd: ParcelFileDescriptor) : ByteSink {
    private val fd: FileDescriptor get() = pfd.fileDescriptor

    override fun size(): Long = pfd.statSize

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= size()) {
            "read [$offset, ${offset + length}) out of bounds for size ${size()}"
        }
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = Os.pread(fd, out, read, length - read, offset + read)
            if (n <= 0) break
            read += n
        }
        require(read == length) { "short read: expected $length, got $read" }
        return out
    }

    override fun writeAt(offset: Long, bytes: ByteArray) {
        require(offset >= 0 && offset + bytes.size <= size()) {
            "in-place write [$offset, ${offset + bytes.size}) would change length ${size()}"
        }
        var written = 0
        while (written < bytes.size) {
            val n = Os.pwrite(fd, bytes, written, bytes.size - written, offset + written)
            if (n <= 0) break
            written += n
        }
        require(written == bytes.size) { "short write: expected ${bytes.size}, got $written" }
    }

    override fun rewrite(content: InputStream) {
        val all = content.readBytes()
        var written = 0
        while (written < all.size) {
            val n = Os.pwrite(fd, all, written, all.size - written, written.toLong())
            if (n <= 0) break
            written += n
        }
        require(written == all.size) { "short write during rewrite" }
        Os.ftruncate(fd, all.size.toLong())
    }

    override fun force() { Os.fsync(fd) }
}
```

- [ ] **Step 3: Verify it compiles into the app**

Run: `gradle :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL (these compile against the Android SDK; no unit test — they need a device).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/forensics/app/io/PfdByteSource.kt \
        app/src/main/kotlin/com/forensics/app/io/PfdByteSink.kt
git commit -m "feat: add PFD-backed ByteSource/ByteSink for SAF (Os positional IO)"
```

---

## Task 3: Filesystem metadata provider (Android)

**Files:**
- Create: `app/src/main/kotlin/com/forensics/app/meta/FileIdentity.kt`
- Create: `app/src/main/kotlin/com/forensics/app/meta/AndroidFileMetadata.kt`

- [ ] **Step 1: Create the data holder**

Create `app/src/main/kotlin/com/forensics/app/meta/FileIdentity.kt`:

```kotlin
package com.forensics.app.meta

/** Filesystem-level metadata about a picked document. */
data class FileIdentity(
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val lastModified: Long?,
)
```

- [ ] **Step 2: Create the provider**

Create `app/src/main/kotlin/com/forensics/app/meta/AndroidFileMetadata.kt`:

```kotlin
package com.forensics.app.meta

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract

/** Reads filesystem metadata for a SAF document Uri from the ContentResolver. */
object AndroidFileMetadata {
    fun of(context: Context, uri: Uri): FileIdentity {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        var modified: Long? = null
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                if (modIdx >= 0 && !c.isNull(modIdx)) modified = c.getLong(modIdx)
            }
        }
        return FileIdentity(
            displayName = name,
            sizeBytes = size,
            mimeType = resolver.getType(uri),
            lastModified = modified,
        )
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `gradle :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/forensics/app/meta/FileIdentity.kt \
        app/src/main/kotlin/com/forensics/app/meta/AndroidFileMetadata.kt
git commit -m "feat: add Android filesystem metadata provider"
```

---

## Task 4: MainViewModel + minimal Compose screen + SAF wiring

A minimal but functional vertical slice: pick a JPEG via SAF, inspect it, list fields, and edit Orientation in place on disk. Verified by `:app:assembleDebug` (run on a device manually).

**Files:**
- Create: `app/src/main/kotlin/com/forensics/app/ui/MainViewModel.kt`
- Create: `app/src/main/kotlin/com/forensics/app/ui/ForensicsScreen.kt`
- Modify: `app/src/main/kotlin/com/forensics/app/MainActivity.kt`
- Modify: `app/build.gradle.kts` (add activity-compose, lifecycle-viewmodel-compose, documentfile deps if not present)

- [ ] **Step 1: Ensure required dependencies**

In `app/build.gradle.kts`, inside `dependencies { }`, ensure these are present (add any missing; keep the existing Compose BOM + `project(":core")`):

```kotlin
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
```

(If the spike already added some of these, do not duplicate — keep one of each.)

- [ ] **Step 2: Create the ViewModel**

Create `app/src/main/kotlin/com/forensics/app/ui/MainViewModel.kt`:

```kotlin
package com.forensics.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forensics.app.io.PfdByteSink
import com.forensics.app.io.PfdByteSource
import com.forensics.app.meta.AndroidFileMetadata
import com.forensics.app.meta.FileIdentity
import com.forensics.core.app.MetadataController
import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.model.EditResult
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val identity: FileIdentity? = null,
    val handlerName: String? = null,
    val fields: List<MetadataField> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = MetadataController(listOf(JpegExifHandler()))
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var currentUri: Uri? = null

    fun open(uri: Uri) {
        currentUri = uri
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val result = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                val identity = AndroidFileMetadata.of(ctx, uri)
                ctx.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                    requireNotNull(pfd) { "could not open file" }
                    val inspection = controller.inspect(PfdByteSource(pfd))
                    Triple(identity, inspection.handlerName, inspection.fields)
                }
            }
            _state.value = UiState(
                identity = result.first,
                handlerName = result.second,
                fields = result.third,
            )
        }
    }

    fun editOrientation(field: MetadataField, newValue: Int) {
        val uri = currentUri ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                ctx.contentResolver.openFileDescriptor(uri, "rw").use { pfd ->
                    requireNotNull(pfd) { "could not open file for writing" }
                    val sink = PfdByteSink(pfd)
                    val source = PfdByteSource(pfd)
                    controller.edit(sink, source, field, Value.Integer(newValue.toLong()))
                }
            }
            val msg = when (outcome) {
                is EditResult.Success ->
                    if (outcome.inPlace) "Patched ${outcome.bytesPatched} bytes in place" else "Rewrote file"
                is EditResult.Failure -> "Edit failed: ${outcome.reason}"
            }
            _state.value = _state.value.copy(busy = false, message = msg)
            open(uri) // re-inspect from disk so the UI reflects the persisted change
        }
    }
}
```

- [ ] **Step 3: Create the Compose screen**

Create `app/src/main/kotlin/com/forensics/app/ui/ForensicsScreen.kt`:

```kotlin
package com.forensics.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ForensicsScreen(
    state: UiState,
    onPick: () -> Unit,
    onEditOrientation: (MetadataField, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onPick) { Text("Open file") }
        state.identity?.let { id ->
            Text(id.displayName ?: "(unnamed)", style = MaterialTheme.typography.titleMedium)
            Text("${id.sizeBytes ?: 0} bytes · ${state.handlerName ?: "unknown format"}")
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Divider(Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(state.fields) { field ->
                MetadataRow(field, onEditOrientation)
                Divider()
            }
        }
    }
}

@Composable
private fun MetadataRow(field: MetadataField, onEditOrientation: (MetadataField, Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${field.key} = ${valueText(field.value)}")
        Text(
            "${field.group} · ${if (field.editable) "in-place ✓" else "read-only"}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (field.key == "Orientation" && field.editable) {
            Button(onClick = {
                val current = (field.value as? Value.Integer)?.n?.toInt() ?: 1
                val next = if (current >= 8) 1 else current + 1 // cycle 1..8
                onEditOrientation(field, next)
            }) { Text("Cycle orientation") }
        }
    }
}

private fun valueText(v: Value): String = when (v) {
    is Value.Text -> v.s
    is Value.Integer -> v.n.toString()
    is Value.Raw -> "<${v.bytes.size} bytes>"
}
```

- [ ] **Step 4: Wire SAF in MainActivity**

Replace `app/src/main/kotlin/com/forensics/app/MainActivity.kt` with:

```kotlin
package com.forensics.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forensics.app.ui.ForensicsScreen
import com.forensics.app.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    vm.open(uri)
                }
            }
            MaterialTheme {
                Surface {
                    ForensicsScreen(
                        state = state,
                        onPick = { picker.launch(arrayOf("image/jpeg")) },
                        onEditOrientation = { field, next -> vm.editOrientation(field, next) },
                    )
                }
            }
        }
    }
}
```

Also add the lifecycle-compose dependency for `collectAsStateWithLifecycle` to `app/build.gradle.kts` dependencies if missing:

```kotlin
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
```

- [ ] **Step 5: Build the app**

Run: `gradle :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL. (Resolve any missing-import/dependency errors by adding the exact androidx artifact; do not change the architecture.)

- [ ] **Step 6: Confirm core still green**

Run: `gradle :core:test --console=plain`
Expected: BUILD SUCCESSFUL (MetadataController test included).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/forensics/app/ui/MainViewModel.kt \
        app/src/main/kotlin/com/forensics/app/ui/ForensicsScreen.kt \
        app/src/main/kotlin/com/forensics/app/MainActivity.kt \
        app/build.gradle.kts
git commit -m "feat: minimal SAF pick -> inspect -> edit-orientation Compose vertical slice"
```

---

## Self-Review

**Spec coverage:**
- "SAF document picker → persistable r/w URI → `"rw"` PFD" — `MainActivity` (OpenDocument + takePersistableUriPermission) + ViewModel `openFileDescriptor(uri, "rw")`. ✓
- "real, writable handle for in-place editing" — `PfdByteSink` via `Os.pwrite`/`ftruncate`/`fsync`; the EditEngine drives it through `MetadataController.edit`. ✓
- "show filesystem metadata (name, size, MIME, timestamps)" — `AndroidFileMetadata`/`FileIdentity` (name, size, MIME, lastModified). ✓
- "grouped metadata + editability indicator + tap-to-edit" — `ForensicsScreen` shows key/value, group, in-place/read-only, and an Orientation editor. (Full grouped accordions + hex viewer + typed editors are Plan B3.) ✓ (minimal slice)
- "verify on disk after edit" — ViewModel re-inspects from the PFD after editing. ✓

**Placeholder scan:** No TBD/TODO. The Android-only files are compile-verified via `:app:assembleDebug` (no emulator available; this is an environment limit, not a plan gap). `MetadataController` is genuinely JVM-unit-tested.

**Type consistency:** `MetadataController(handlers)`/`.inspect`/`.edit(sink, source, field, newValue)` used consistently; `PfdByteSource`/`PfdByteSink` implement the exact `ByteSource`/`ByteSink` signatures; `EditResult.Success(inPlace, bytesPatched)` / `Failure(reason)`, `Value.Integer(Long)`, `MetadataField` fields all match Plan A. Compose deps match the spike's confirmed Compose BOM 2024.09.03.

## Known limitations / Plan B3 follow-ons
- **On-device only:** SAF acquisition, PFD IO, and the UI cannot be auto-tested here (no emulator). `:app:assembleDebug` compile-verifies them; manual device run validates behavior.
- **Plan B3 (rich UI):** grouped collapsible sections, all field types' typed editors (GPS picker, datetime, enum), the paged hex viewer (`HexDump`) + strings, byte-offset → hex jump, rewrite-consent dialog, and the "edits modify your original" safety banner.
- **From the final core review (do in/with B2-B3):** make `EditEngine` re-parse read through the sink for large files; offset-qualify the rewrite-path match before multi-IFD formats.
