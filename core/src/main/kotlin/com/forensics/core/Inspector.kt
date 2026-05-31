package com.forensics.core

import com.forensics.core.generic.Hashing
import com.forensics.core.handler.FormatHandler
import com.forensics.core.io.ByteSource
import com.forensics.core.model.MetadataField

data class InspectionResult(
    val handlerName: String?,
    val fields: List<MetadataField>,
    val md5: String,
    val sha256: String,
)

/** One-call inspection: hashes (always) + handler fields (if a handler matches). */
class Inspector(private val handlers: List<FormatHandler>) {
    fun inspect(source: ByteSource): InspectionResult {
        val magicLen = minOf(16L, source.size()).toInt()
        val magic = if (magicLen > 0) source.readAt(0, magicLen) else ByteArray(0)
        val handler = handlers.firstOrNull { it.canHandle(magic, null) }
        val fields = handler?.let { runCatching { it.parse(source) }.getOrDefault(emptyList()) } ?: emptyList()
        return InspectionResult(
            handlerName = if (fields.isNotEmpty() || handler != null) handler?.formatName else null,
            fields = fields,
            md5 = Hashing.md5(source),
            sha256 = Hashing.sha256(source),
        )
    }
}
