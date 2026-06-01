package com.forensics.core.app

import com.forensics.core.model.FieldType
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataGroupingTest {
    private fun field(key: String, group: String) =
        MetadataField(key, Value.Text("x"), 0, 1, FieldType.FIXED, true, group)

    @Test fun groupsByGroupPreservingFirstSeenOrder() {
        val fields = listOf(
            field("a", "EXIF"), field("b", "GPS"), field("c", "EXIF"),
        )
        val groups = MetadataGrouping.group(fields)
        assertEquals(listOf("EXIF", "GPS"), groups.map { it.name })
        assertEquals(listOf("a", "c"), groups.first { it.name == "EXIF" }.fields.map { it.key })
    }

    @Test fun emptyInputYieldsNoGroups() {
        assertEquals(emptyList(), MetadataGrouping.group(emptyList()))
    }
}
