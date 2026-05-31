package com.forensics.core.handler

import com.forensics.core.io.ByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

/** A pluggable per-format metadata reader and edit compiler. */
interface FormatHandler {
    val formatName: String

    /** True if this handler recognizes the file from its leading [magic] bytes. */
    fun canHandle(magic: ByteArray, mime: String?): Boolean

    /** Parses all readable metadata fields. Must not mutate the source. */
    fun parse(source: ByteSource): List<MetadataField>

    /** Compiles an edit of [field] to [newValue] into a validated plan. Must not write. */
    fun validateEdit(source: ByteSource, field: MetadataField, newValue: Value): EditPlan
}
