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
