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

    val parsed: Value? = if (isInteger) text.toLongOrNull()?.let { Value.Integer(it) } else Value.Text(text)
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
                val label = when (classification) {
                    is EditClassification.InPlace -> "Will patch in place ✓"
                    is EditClassification.Rewrite -> "Requires rewriting the whole file ⚠ (${classification.reason})"
                    is EditClassification.Rejected -> "Cannot apply: ${classification.reason}"
                    null -> "Enter a valid value"
                }
                Text(label, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            val canApply = parsed != null && classification !is EditClassification.Rejected
            TextButton(enabled = canApply, onClick = { parsed?.let(onConfirm) }) {
                Text(if (classification is EditClassification.Rewrite) "Rewrite & apply" else "Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
