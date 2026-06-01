package com.forensics.app.meta

import com.forensics.core.model.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileIdentityFieldsTest {

    @Test fun buildsFileGroupWithAllPresentFieldsInOrder() {
        val identity = FileIdentity("photo.jpg", 1234L, "image/jpeg", 0L)
        val group = FileIdentityFields.toGroup(identity, md5 = "md5hash", sha256 = "sha256hash")

        assertEquals("File", group.name)
        assertEquals(
            listOf("Name", "Size", "MIME", "Modified", "MD5", "SHA-256"),
            group.fields.map { it.key },
        )
        assertEquals(Value.Text("photo.jpg"), group.fields.first { it.key == "Name" }.value)
        assertEquals(Value.Text("1234 bytes"), group.fields.first { it.key == "Size" }.value)
        assertEquals(Value.Text("image/jpeg"), group.fields.first { it.key == "MIME" }.value)
        // epoch millis 0 -> ISO-8601 UTC
        assertEquals(Value.Text("1970-01-01T00:00:00Z"), group.fields.first { it.key == "Modified" }.value)
        assertEquals(Value.Text("md5hash"), group.fields.first { it.key == "MD5" }.value)
        // generic file metadata is informational, never editable
        assertTrue(group.fields.all { !it.editable })
    }

    @Test fun omitsNullFilesystemFieldsButAlwaysKeepsHashes() {
        val identity = FileIdentity(null, null, null, null)
        val group = FileIdentityFields.toGroup(identity, md5 = "m", sha256 = "s")
        assertEquals(listOf("MD5", "SHA-256"), group.fields.map { it.key })
    }
}
