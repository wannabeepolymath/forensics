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
