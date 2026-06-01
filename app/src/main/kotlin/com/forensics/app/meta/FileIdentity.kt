package com.forensics.app.meta

/** Filesystem-level metadata about a picked document. */
data class FileIdentity(
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val lastModified: Long?,
)
