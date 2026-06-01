# Android Rich UI Implementation Plan (Plan B3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the minimal vertical slice into a usable forensic UI: grouped collapsible metadata sections with editability badges, a hex viewer and strings list, a safety banner, and a typed edit dialog that previews in-place-vs-rewrite and requires consent for rewrites.

**Architecture:** The logic-bearing pieces are pure Kotlin in `:core` and JVM-unit-tested: field grouping (`MetadataGrouping`) and an edit-classification preview (`MetadataController.previewEdit`). The Compose layer in `:app` consumes them and renders `HexDump`/`Strings` (already in core); it is compile-verified via `:app:assembleDebug` (no emulator here). State stays in `MainViewModel`.

**Tech Stack (confirmed):** AGP 8.13.2, Gradle 9.5.1, Kotlin 2.2.20, Compose BOM 2024.09.03, compileSdk 34, minSdk 26, Temurin 17.

---

## Context for the implementer

Existing (on this branch, green): `:core` with `com.forensics.core.app.MetadataController(handlers)` { `inspect(source): InspectionResult`, `edit(sink, source, field, newValue): EditResult` }; `com.forensics.core.generic.{HexDump, Strings, FoundString}`; `com.forensics.core.model.{Value, MetadataField, EditPlan, EditResult, FieldType}`; `com.forensics.core.handler.exif.{JpegExifHandler, TestExifJpeg}`; `com.forensics.core.io.{InMemoryByteSource, InMemoryByteSink, ByteSource}`.
- `HexDump.page(source: ByteSource, offset: Long, length: Int): List<String>`
- `Strings.extract(source: ByteSource, minLength: Int = 4): Sequence<FoundString>` where `FoundString(offset: Long, text: String)`.
- `:app` (package `com.forensics.app`): `ui/MainViewModel.kt` (`UiState`, `open`, `editOrientation`), `ui/ForensicsScreen.kt`, `io/{PfdByteSource,PfdByteSink}`, `meta/{FileIdentity,AndroidFileMetadata}`, `MainActivity.kt`.

Commands (ALWAYS prefix `export JAVA_HOME=$(/usr/libexec/java_home -v 17);`): `./gradlew :core:test --console=plain`, `./gradlew :app:assembleDebug --console=plain`.

## File Structure

```
core/src/main/kotlin/com/forensics/core/app/
  MetadataGrouping.kt      # group fields by their group, preserving order (JVM-tested)
  EditClassification.kt    # sealed preview result (InPlace/Rewrite/Rejected)
  MetadataController.kt     # ADD previewEdit(...) (JVM-tested)
core/src/test/kotlin/com/forensics/core/app/
  MetadataGroupingTest.kt
  PreviewEditTest.kt
app/src/main/kotlin/com/forensics/app/ui/
  MainViewModel.kt         # MODIFY: hold hex/strings/grouping; add editField + preview
  ForensicsScreen.kt       # MODIFY: grouped sections, badges, banner, tabs
  EditDialog.kt            # typed editor + rewrite-consent
  HexView.kt               # monospace hex + strings rendering
```

---

## Task 1: Field grouping (core, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/app/MetadataGrouping.kt`
- Test: `core/src/test/kotlin/com/forensics/core/app/MetadataGroupingTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/app/MetadataGroupingTest.kt`:

```kotlin
package com.forensics.core.app

import com.forensics.core.model.FieldType
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataGroupingTest {
    private fun field(key: String, group: String) =
        MetadataField(key, Value.Text("x"), 0, 1, FieldType.FIXED, true, group)

    @Test fun groupsByGroupPreservingFirstSeenOrder() {
        val fields = listOf(
            field("a", "EXIF"), field("b", "GPS"), field("c", "EXIF"),
        )
        val groups = MetadataGrouping.group(fields)
        assertEquals(listOf("EXIF", "GPS"), groups.map { it.name })
        assertEquals(listOf("a", "c"), groups.first { it.name == "EXIF" }.fields.map { it.key })
    }

    @Test fun emptyInputYieldsNoGroups() {
        assertEquals(emptyList(), MetadataGrouping.group(emptyList()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.app.MetadataGroupingTest"`
Expected: FAIL — `MetadataGrouping` unresolved.

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/forensics/core/app/MetadataGrouping.kt`:

```kotlin
package com.forensics.core.app

import com.forensics.core.model.MetadataField

/** A named bucket of fields for display. */
data class FieldGroup(val name: String, val fields: List<MetadataField>)

/** Groups fields by their [MetadataField.group], preserving first-seen group order. */
object MetadataGrouping {
    fun group(fields: List<MetadataField>): List<FieldGroup> =
        fields.groupBy { it.group } // LinkedHashMap: preserves encounter order
            .map { (name, groupFields) -> FieldGroup(name, groupFields) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.app.MetadataGroupingTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/app/MetadataGrouping.kt \
        core/src/test/kotlin/com/forensics/core/app/MetadataGroupingTest.kt
git commit -m "feat: add field grouping for display (order-preserving)"
```

---

## Task 2: Edit-classification preview (core, JVM-tested)

**Files:**
- Create: `core/src/main/kotlin/com/forensics/core/app/EditClassification.kt`
- Modify: `core/src/main/kotlin/com/forensics/core/app/MetadataController.kt` (add `previewEdit`)
- Test: `core/src/test/kotlin/com/forensics/core/app/PreviewEditTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/src/test/kotlin/com/forensics/core/app/PreviewEditTest.kt`:

```kotlin
package com.forensics.core.app

import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.handler.exif.TestExifJpeg
import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertTrue

class PreviewEditTest {
    private val controller = MetadataController(listOf(JpegExifHandler()))
    private fun source() = InMemoryByteSource(TestExifJpeg.build(1, "2020:01:02 03:04:05"))

    @Test fun sameLengthIntegerEditPreviewsInPlace() {
        val src = source()
        val field = controller.inspect(src).fields.first { it.key == "Orientation" }
        assertTrue(controller.previewEdit(src, field, Value.Integer(8)) is EditClassification.InPlace)
    }

    @Test fun longerTextPreviewsRewrite() {
        val src = source()
        val field = controller.inspect(src).fields.first { it.key == "Make" } // "ACME " = 5 bytes
        assertTrue(controller.previewEdit(src, field, Value.Text("A-Much-Longer-Maker")) is EditClassification.Rewrite)
    }

    @Test fun outOfRangePreviewsRejected() {
        val src = source()
        val field = controller.inspect(src).fields.first { it.key == "Orientation" }
        assertTrue(controller.previewEdit(src, field, Value.Integer(99)) is EditClassification.Rejected)
    }

    @Test fun noHandlerPreviewsRejected() {
        val src = InMemoryByteSource(byteArrayOf(0, 1, 2, 3))
        val field = com.forensics.core.model.MetadataField(
            "X", Value.Integer(1), 0, 2,
            com.forensics.core.model.FieldType.FIXED, true, "EXIF",
        )
        assertTrue(controller.previewEdit(src, field, Value.Integer(2)) is EditClassification.Rejected)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :core:test --tests "com.forensics.core.app.PreviewEditTest"`
Expected: FAIL — `EditClassification` / `previewEdit` unresolved.

- [ ] **Step 3: Create EditClassification**

Create `core/src/main/kotlin/com/forensics/core/app/EditClassification.kt`:

```kotlin
package com.forensics.core.app

/** A non-destructive preview of what an edit WOULD do, for UI badges and consent dialogs. */
sealed interface EditClassification {
    /** A pure same-length in-place byte patch (cheap, safe). */
    data object InPlace : EditClassification

    /** Applying the edit requires rebuilding the whole file in place ([reason] explains why). */
    data class Rewrite(val reason: String) : EditClassification

    /** The edit cannot be performed ([reason] explains why). */
    data class Rejected(val reason: String) : EditClassification
}
```

- [ ] **Step 4: Add previewEdit to MetadataController**

In `core/src/main/kotlin/com/forensics/core/app/MetadataController.kt`, add this method to the class (after `edit`), and add the import `import com.forensics.core.model.EditPlan` at the top:

```kotlin
    /**
     * Classifies what an edit of [field] to [newValue] WOULD do, WITHOUT writing anything.
     * Drives editability badges and the rewrite-consent dialog.
     */
    fun previewEdit(source: ByteSource, field: MetadataField, newValue: Value): EditClassification {
        val handler = matchHandler(source)
            ?: return EditClassification.Rejected("no handler recognizes this file")
        return when (val plan = runCatching { handler.validateEdit(source, field, newValue) }
            .getOrElse { return EditClassification.Rejected("could not classify: ${it.message}") }) {
            is EditPlan.InPlace -> EditClassification.InPlace
            is EditPlan.RequiresRewrite -> EditClassification.Rewrite(plan.reason)
            is EditPlan.Rejected -> EditClassification.Rejected(plan.reason)
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `gradle :core:test --tests "com.forensics.core.app.PreviewEditTest"`
Expected: PASS (4 tests). Then full `:core:test` → all green.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/forensics/core/app/EditClassification.kt \
        core/src/main/kotlin/com/forensics/core/app/MetadataController.kt \
        core/src/test/kotlin/com/forensics/core/app/PreviewEditTest.kt
git commit -m "feat: add non-destructive edit classification preview"
```

---

## Task 3: ViewModel state for groups, hex, strings, and field editing

**Files:**
- Modify: `app/src/main/kotlin/com/forensics/app/ui/MainViewModel.kt`

- [ ] **Step 1: Replace MainViewModel with the expanded version**

Replace the entire contents of `app/src/main/kotlin/com/forensics/app/ui/MainViewModel.kt` with:

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
import com.forensics.core.app.EditClassification
import com.forensics.core.app.FieldGroup
import com.forensics.core.app.MetadataController
import com.forensics.core.app.MetadataGrouping
import com.forensics.core.generic.HexDump
import com.forensics.core.generic.Strings
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
    val groups: List<FieldGroup> = emptyList(),
    val md5: String = "",
    val sha256: String = "",
    val hexLines: List<String> = emptyList(),
    val strings: List<String> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
)

private const val HEX_PREVIEW_BYTES = 4096
private const val STRINGS_LIMIT = 200

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = MetadataController(listOf(JpegExifHandler()))
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var currentUri: Uri? = null

    fun open(uri: Uri) {
        currentUri = uri
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val next = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                val identity = AndroidFileMetadata.of(ctx, uri)
                ctx.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                    requireNotNull(pfd) { "could not open file" }
                    val source = PfdByteSource(pfd)
                    val inspection = controller.inspect(source)
                    val hex = HexDump.page(source, 0, HEX_PREVIEW_BYTES)
                    val strings = Strings.extract(source, 4).take(STRINGS_LIMIT).map { it.text }.toList()
                    UiState(
                        identity = identity,
                        handlerName = inspection.handlerName,
                        groups = MetadataGrouping.group(inspection.fields),
                        md5 = inspection.md5,
                        sha256 = inspection.sha256,
                        hexLines = hex,
                        strings = strings,
                    )
                }
            }
            _state.value = next
        }
    }

    /** Classifies a proposed edit without writing (for the dialog's in-place/rewrite warning). */
    fun preview(field: MetadataField, newValue: Value): EditClassification {
        val uri = currentUri ?: return EditClassification.Rejected("no file open")
        val ctx = getApplication<Application>()
        return ctx.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
            if (pfd == null) EditClassification.Rejected("could not open file")
            else controller.previewEdit(PfdByteSource(pfd), field, newValue)
        }
    }

    fun applyEdit(field: MetadataField, newValue: Value) {
        val uri = currentUri ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val outcome = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                ctx.contentResolver.openFileDescriptor(uri, "rw").use { pfd ->
                    requireNotNull(pfd) { "could not open file for writing" }
                    controller.edit(PfdByteSink(pfd), PfdByteSource(pfd), field, newValue)
                }
            }
            val msg = when (outcome) {
                is EditResult.Success ->
                    if (outcome.inPlace) "Patched ${outcome.bytesPatched} bytes in place ✓" else "Rewrote file ✓"
                is EditResult.Failure -> "Edit failed: ${outcome.reason}"
            }
            _state.value = _state.value.copy(busy = false, message = msg)
            open(uri) // re-read from disk so the UI reflects the persisted change
        }
    }
}
```

- [ ] **Step 2: Build to verify compile**

Run: `gradle :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL. (ForensicsScreen still references the old `UiState.fields`/`onEditOrientation` — it will be rewritten in Task 4. If the build fails ONLY in ForensicsScreen.kt/MainActivity.kt due to the state shape change, that's expected; proceed to Task 4 which fixes them, then build. If you prefer a green build at each commit, do Tasks 3 and 4 as one commit — acceptable here since they are tightly coupled.)

- [ ] **Step 3: Commit (with Task 4, or after Task 4 builds green)**

Defer committing until Task 4 builds green (the screen consumes the new state). See Task 4 Step 4.

---

## Task 4: Rich Compose UI — grouped sections, badges, banner, hex/strings tabs, edit dialog

**Files:**
- Create: `app/src/main/kotlin/com/forensics/app/ui/EditDialog.kt`
- Create: `app/src/main/kotlin/com/forensics/app/ui/HexView.kt`
- Modify: `app/src/main/kotlin/com/forensics/app/ui/ForensicsScreen.kt`
- Modify: `app/src/main/kotlin/com/forensics/app/MainActivity.kt`

- [ ] **Step 1: Create the edit dialog**

Create `app/src/main/kotlin/com/forensics/app/ui/EditDialog.kt`:

```kotlin
package com.forensics.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forensics.core.app.EditClassification
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

/**
 * Typed editor for a single field. Integer fields get a numeric editor; text fields a text editor.
 * Shows a live classification (in-place / rewrite-with-consent / rejected) computed via [classify].
 */
@Composable
fun EditDialog(
    field: MetadataField,
    classify: (Value) -> EditClassification,
    onDismiss: () -> Unit,
    onConfirm: (Value) -> Unit,
) {
    val isInteger = field.value is Value.Integer
    var text by remember {
        mutableStateOf(
            when (val v = field.value) {
                is Value.Integer -> v.n.toString()
                is Value.Text -> v.s
                is Value.Raw -> ""
            },
        )
    }

    fun parse(): Value? = if (isInteger) text.toLongOrNull()?.let { Value.Integer(it) } else Value.Text(text)
    val parsed = parse()
    val classification = parsed?.let { classify(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${field.key}") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(if (isInteger) "Number" else "Text") },
                )
                val (label, _) = when (classification) {
                    is EditClassification.InPlace -> "Will patch in place ✓" to true
                    is EditClassification.Rewrite -> "Requires rewriting the whole file ⚠ (${classification.reason})" to true
                    is EditClassification.Rejected -> "Cannot apply: ${classification.reason}" to false
                    null -> "Enter a valid value" to false
                }
                Text(label, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            val canApply = parsed != null && classification !is EditClassification.Rejected
            TextButton(
                enabled = canApply,
                onClick = { parsed?.let(onConfirm) },
            ) {
                Text(if (classification is EditClassification.Rewrite) "Rewrite & apply" else "Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 2: Create the hex/strings view**

Create `app/src/main/kotlin/com/forensics/app/ui/HexView.kt`:

```kotlin
package com.forensics.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Monospace renderer for hex dump lines or strings. */
@Composable
fun MonospaceList(lines: List<String>, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth()) {
        items(lines) { line ->
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun LabeledMonospace(title: String, lines: List<String>) {
    Column {
        Text(title, Modifier.padding(8.dp))
        MonospaceList(lines)
    }
}
```

- [ ] **Step 3: Replace ForensicsScreen**

Replace the entire contents of `app/src/main/kotlin/com/forensics/app/ui/ForensicsScreen.kt` with:

```kotlin
package com.forensics.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.forensics.core.app.EditClassification
import com.forensics.core.app.FieldGroup
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

@Composable
fun ForensicsScreen(
    state: UiState,
    onPick: () -> Unit,
    classify: (MetadataField, Value) -> EditClassification,
    onApplyEdit: (MetadataField, Value) -> Unit,
) {
    var editing by remember { mutableStateOf<MetadataField?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Button(onClick = onPick) { Text("Open file") }

        state.identity?.let { id ->
            Text(id.displayName ?: "(unnamed)", style = MaterialTheme.typography.titleMedium)
            Text(
                "${id.sizeBytes ?: 0} bytes · ${id.mimeType ?: "?"} · ${state.handlerName ?: "unknown format"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("sha256: ${state.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }

        if (state.identity != null) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "⚠ Edits modify your original file directly.",
                    Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp)) }

        if (state.identity != null) {
            val titles = listOf("Metadata", "Hex", "Strings")
            ScrollableTabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> MetadataTab(state.groups, onEdit = { editing = it })
                1 -> LabeledMonospace("First ${state.hexLines.size} hex lines", state.hexLines)
                else -> LabeledMonospace("Strings (${state.strings.size})", state.strings)
            }
        }
    }

    editing?.let { field ->
        EditDialog(
            field = field,
            classify = { v -> classify(field, v) },
            onDismiss = { editing = null },
            onConfirm = { v -> editing = null; onApplyEdit(field, v) },
        )
    }
}

@Composable
private fun MetadataTab(groups: List<FieldGroup>, onEdit: (MetadataField) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth()) {
        groups.forEach { group ->
            item {
                Text(
                    group.name,
                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(group.fields) { field ->
                FieldRow(field, onEdit)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FieldRow(field: MetadataField, onEdit: (MetadataField) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${field.key} = ${valueText(field.value)}")
        Text(
            if (field.editable) "in-place ✓ · tap to edit" else "read-only 🔒",
            style = MaterialTheme.typography.bodySmall,
        )
        if (field.editable) {
            Button(onClick = { onEdit(field) }, Modifier.padding(top = 2.dp)) { Text("Edit") }
        }
    }
}

private fun valueText(v: Value): String = when (v) {
    is Value.Text -> v.s
    is Value.Integer -> v.n.toString()
    is Value.Raw -> "<${v.bytes.size} bytes>"
}
```

- [ ] **Step 4: Update MainActivity to pass the new callbacks**

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
                        classify = { field, value -> vm.preview(field, value) },
                        onApplyEdit = { field, value -> vm.applyEdit(field, value) },
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Build both modules**

Run: `gradle :app:assembleDebug --console=plain` → BUILD SUCCESSFUL.
Run: `gradle :core:test --console=plain` → BUILD SUCCESSFUL.
If a Compose symbol is missing (e.g. `ScrollableTabRow`, `Card`, `mutableIntStateOf`), it exists in Compose BOM 2024.09.03 — fix the import; do not downgrade. `mutableIntStateOf` is in `androidx.compose.runtime`.

- [ ] **Step 6: Commit (Tasks 3 + 4 together)**

```bash
git add app/src/main/kotlin/com/forensics/app/ui/MainViewModel.kt \
        app/src/main/kotlin/com/forensics/app/ui/ForensicsScreen.kt \
        app/src/main/kotlin/com/forensics/app/ui/EditDialog.kt \
        app/src/main/kotlin/com/forensics/app/ui/HexView.kt \
        app/src/main/kotlin/com/forensics/app/MainActivity.kt
git commit -m "feat: rich UI — grouped sections, badges, banner, hex/strings tabs, typed edit dialog"
```

---

## Self-Review

**Spec coverage (UI section of the design):**
- Grouped collapsible metadata sections → `MetadataTab` renders per-`FieldGroup` headers + rows (grouping is JVM-tested). ✓ (headers + grouping; full collapse animation is optional polish.)
- Editability badge (in-place ✓ / rewrite ⚠ / read-only 🔒) → `FieldRow` badge + the dialog's live classification from `previewEdit`. ✓
- Tap-to-edit with typed editor → `EditDialog` (numeric vs text) with apply/cancel. ✓
- Rewrite consent → dialog shows "Requires rewriting the whole file ⚠" and the confirm button reads "Rewrite & apply"; rejected disables apply. ✓
- Hex viewer + strings → `HexView`/`MonospaceList` rendering `HexDump.page` + `Strings.extract` (computed in the ViewModel). ✓
- Safety banner ("Edits modify your original file directly") → `Card` banner. ✓
- Verify-after-edit reflected in UI → `applyEdit` re-`open`s from disk. ✓

**Placeholder scan:** No TBD/TODO. The Compose files are compile-verified via `assembleDebug` (no emulator). Logic (`MetadataGrouping`, `previewEdit`) is JVM-tested.

**Type consistency:** `UiState` (new shape: groups/md5/sha256/hexLines/strings) is produced by `MainViewModel.open` and consumed by `ForensicsScreen`; `classify: (MetadataField, Value) -> EditClassification` and `onApplyEdit: (MetadataField, Value) -> Unit` wired identically in `MainActivity`. `EditClassification.{InPlace, Rewrite(reason), Rejected(reason)}`, `MetadataController.previewEdit`, `FieldGroup(name, fields)` consistent across core + app. `HexDump.page(source, Long, Int)` and `Strings.extract(source, Int): Sequence<FoundString>` match core.

## Known limitations / follow-ons
- Compose UI is compile-verified, not device-run (no emulator here) — verify the pick→edit→hex flow on a device.
- Polish deferred: collapse/expand animation, byte-offset→hex jump, GPS/datetime specialized editors, dark theme, large-file hex paging beyond the first 4 KB.
- Core follow-ons still tracked: large-file read-through re-parse; offset-qualified rewrite match; more format handlers (PNG/CRC, full TIFF rebuild, MP4, PDF).
