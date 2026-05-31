package com.forensics.core.generic

enum class DetectedFormat { JPEG, PNG }

/** Detects a file format from its leading bytes. Magic bytes are trusted over MIME hints. */
object MagicDetector {
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun detect(magic: ByteArray): DetectedFormat? = when {
        magic.startsWith(JPEG) -> DetectedFormat.JPEG
        magic.startsWith(PNG) -> DetectedFormat.PNG
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
