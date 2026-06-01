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
            open(uri)
        }
    }
}
