package com.forensics.core.generic

import com.forensics.core.io.ByteSource
import java.security.MessageDigest

/** Streams the source through a digest so arbitrarily large files never load fully. */
object Hashing {
    fun md5(source: ByteSource): String = digest(source, "MD5")
    fun sha256(source: ByteSource): String = digest(source, "SHA-256")

    private fun digest(source: ByteSource, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        source.openStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
