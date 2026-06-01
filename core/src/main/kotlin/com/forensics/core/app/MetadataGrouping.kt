package com.forensics.core.app

import com.forensics.core.model.MetadataField

/** A named bucket of fields for display. */
data class FieldGroup(val name: String, val fields: List<MetadataField>)

/** Groups fields by their [MetadataField.group], preserving first-seen group order. */
object MetadataGrouping {
    fun group(fields: List<MetadataField>): List<FieldGroup> =
        fields.groupBy { it.group } // LinkedHashMap: preserves encounter order
            .map { (name, groupFields) -> FieldGroup(name, groupFields) }
}
