package com.forensics.core.model

/** A typed metadata value. Kept deliberately small for v1. */
sealed interface Value {
    /** Textual value (e.g. EXIF ASCII fields, dates). */
    data class Text(val s: String) : Value

    /** Whole-number value (e.g. EXIF SHORT/LONG fields like Orientation, ISO). */
    data class Integer(val n: Long) : Value

    /** Raw bytes for values we surface but do not interpret. */
    class Raw(val bytes: ByteArray) : Value {
        override fun equals(other: Any?) =
            other is Raw && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}
