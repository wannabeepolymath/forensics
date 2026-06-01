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
                    // With an "*/*" picker some files come from read-only providers that never
                    // granted write — persisting rw would throw. Try rw (needed for in-place edits),
                    // fall back to read-only so inspection still works and picking never crashes.
                    val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    try {
                        contentResolver.takePersistableUriPermission(uri, read or write)
                    } catch (_: SecurityException) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, read) }
                    }
                    vm.open(uri)
                }
            }
            MaterialTheme {
                Surface {
                    ForensicsScreen(
                        state = state,
                        onPick = { picker.launch(arrayOf("*/*")) },
                        classify = { field, value -> vm.preview(field, value) },
                        onApplyEdit = { field, value -> vm.applyEdit(field, value) },
                        onFocusBytes = { offset, length -> vm.focusBytes(offset, length) },
                        onStringsFilter = { query, minLen -> vm.setStringsFilter(query, minLen) },
                    )
                }
            }
        }
    }
}
