package com.forensics.app.meta

import com.forensics.core.app.FieldGroup
import com.forensics.core.model.FieldType
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value
import java.time.Instant

/**
 * Builds the always-present "File" group of generic filesystem metadata + hashes, so the metadata
 * view is meaningful for ANY file — including images with no EXIF and formats with no handler.
 * These are informational (read-only) rows; they don't map to byte ranges, so offset/length are 0.
 */
object FileIdentityFields {
    fun toGroup(identity: FileIdentity, md5: String, sha256: String): FieldGroup {
        val rows = buildList {
            identity.displayName?.let { add(row("Name", it)) }
            identity.sizeBytes?.let { add(row("Size", "$it bytes")) }
            identity.mimeType?.let { add(row("MIME", it)) }
            identity.lastModified?.let { add(row("Modified", Instant.ofEpochMilli(it).toString())) }
            if (md5.isNotEmpty()) add(row("MD5", md5))
            if (sha256.isNotEmpty()) add(row("SHA-256", sha256))
        }
        return FieldGroup("File", rows)
    }

    private fun row(key: String, value: String) =
        MetadataField(
            key = key,
            value = Value.Text(value),
            byteOffset = 0,
            byteLength = 0,
            type = FieldType.FIXED,
            editable = false,
            group = "File",
        )
}
