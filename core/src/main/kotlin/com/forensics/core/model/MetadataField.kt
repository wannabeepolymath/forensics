package com.forensics.core.model

/** Whether the field occupies a fixed byte length (in-place-patchable) or a variable one. */
enum class FieldType { FIXED, VARIABLE }

/**
 * One metadata field located in the file.
 *
 * @param byteOffset absolute offset of the field's raw value bytes
 * @param byteLength length of the field's raw value bytes
 * @param editable whether the handler can attempt to edit this field at all
 * @param group display/grouping bucket, e.g. "EXIF", "GPS"
 */
data class MetadataField(
    val key: String,
    val value: Value,
    val byteOffset: Long,
    val byteLength: Int,
    val type: FieldType,
    val editable: Boolean,
    val group: String,
)
