package com.forensics.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

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
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(state.fields) { field ->
                MetadataRow(field, onEditOrientation)
                HorizontalDivider()
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
                val next = if (current >= 8) 1 else current + 1
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
