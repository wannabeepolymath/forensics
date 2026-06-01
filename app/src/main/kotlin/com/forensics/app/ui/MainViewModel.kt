package com.forensics.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forensics.app.io.PfdByteSink
import com.forensics.app.io.PfdByteSource
import com.forensics.app.meta.AndroidFileMetadata
import com.forensics.app.meta.FileIdentity
import com.forensics.app.meta.FileIdentityFields
import com.forensics.core.app.EditClassification
import com.forensics.core.app.FieldGroup
import com.forensics.core.app.MetadataController
import com.forensics.core.app.MetadataGrouping
import com.forensics.core.generic.FoundString
import com.forensics.core.generic.HexDump
import com.forensics.core.generic.HexFocus
import com.forensics.core.generic.Strings
import com.forensics.core.handler.exif.JpegExifHandler
import com.forensics.core.io.ByteSource
import com.forensics.core.model.EditResult
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /** Absolute file offset of the first byte of [hexLines]; lets the hex view label real offsets. */
    val hexPageStart: Long = 0,
    val strings: List<FoundString> = emptyList(),
    /** True when more than [STRINGS_LIMIT] matches exist and the list was capped. */
    val stringsTruncated: Boolean = false,
    /** Current case-insensitive substring filter applied to extracted strings (blank = no filter). */
    val stringsQuery: String = "",
    /** Minimum run length for the strings extractor. */
    val stringsMinLen: Int = DEFAULT_MIN_STRING_LEN,
    /** Byte range to spotlight in the hex view (from a tapped field/string); null = nothing focused. */
    val focusOffset: Long? = null,
    val focusLength: Int = 0,
    val message: String? = null,
    val busy: Boolean = false,
)

private const val HEX_PREVIEW_BYTES = 4096
private const val STRINGS_LIMIT = 200
private const val DEFAULT_MIN_STRING_LEN = 4

/**
 * Streams [source] for printable runs of at least [minLen], keeps those matching [query]
 * (case-insensitive substring; blank = all), and caps at [STRINGS_LIMIT]. Pulls one extra to detect
 * the cap. Filtering happens during extraction so it covers the WHOLE file, not just a loaded slice,
 * while memory stays bounded regardless of file size.
 */
private fun extractStrings(source: ByteSource, minLen: Int, query: String): Pair<List<FoundString>, Boolean> {
    val matches = Strings.extract(source, minLen.coerceAtLeast(1))
        .filter { query.isBlank() || it.text.contains(query, ignoreCase = true) }
        .take(STRINGS_LIMIT + 1)
        .toList()
    return matches.take(STRINGS_LIMIT) to (matches.size > STRINGS_LIMIT)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = MetadataController(listOf(JpegExifHandler()))
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var currentUri: Uri? = null
    private var stringsJob: Job? = null

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
                    val (strings, truncated) = extractStrings(source, DEFAULT_MIN_STRING_LEN, query = "")
                    UiState(
                        identity = identity,
                        handlerName = inspection.handlerName,
                        // Always show generic filesystem metadata + hashes, then any handler fields,
                        // so the metadata view is never empty for a valid file (e.g. a JPEG with no EXIF).
                        groups = listOf(FileIdentityFields.toGroup(identity, inspection.md5, inspection.sha256)) +
                            MetadataGrouping.group(inspection.fields),
                        md5 = inspection.md5,
                        sha256 = inspection.sha256,
                        hexLines = hex,
                        hexPageStart = 0,
                        strings = strings,
                        stringsTruncated = truncated,
                    )
                }
            }
            _state.value = next
        }
    }

    /**
     * Re-extract strings with a new substring [query] and/or [minLen]. Reflects the control change
     * in state immediately, then re-scans the file off the main thread, cancelling any in-flight scan
     * so rapid keystrokes can't land stale results out of order.
     */
    fun setStringsFilter(
        query: String = _state.value.stringsQuery,
        minLen: Int = _state.value.stringsMinLen,
    ) {
        val uri = currentUri ?: return
        _state.value = _state.value.copy(stringsQuery = query, stringsMinLen = minLen)
        stringsJob?.cancel()
        stringsJob = viewModelScope.launch {
            val (strings, truncated) = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                ctx.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                    requireNotNull(pfd) { "could not open file" }
                    extractStrings(PfdByteSource(pfd), minLen, query)
                }
            }
            _state.value = _state.value.copy(strings = strings, stringsTruncated = truncated)
        }
    }

    /**
     * Spotlight the byte range `[offset, offset+length)` in the hex view. If the current hex page
     * already covers it, just records the focus (cheap, synchronous). Otherwise re-pages the hex
     * around [offset] off the main thread so an offset anywhere in the file can be jumped to.
     */
    fun focusBytes(offset: Long, length: Int) {
        val s = _state.value
        if (HexFocus.pageContains(offset, s.hexPageStart, s.hexLines.size)) {
            _state.value = s.copy(focusOffset = offset, focusLength = length)
            return
        }
        val uri = currentUri ?: return
        viewModelScope.launch {
            val paged = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                ctx.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                    requireNotNull(pfd) { "could not open file" }
                    val start = HexFocus.pageStartFor(offset, HEX_PREVIEW_BYTES)
                    HexDump.page(PfdByteSource(pfd), start, HEX_PREVIEW_BYTES) to start
                }
            }
            _state.value = _state.value.copy(
                hexLines = paged.first,
                hexPageStart = paged.second,
                focusOffset = offset,
                focusLength = length,
            )
        }
    }

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
            open(uri)
        }
    }
}
