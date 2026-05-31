package com.forensics.core.model

/** A single absolute-offset byte write (used for a value or a recomputed checksum). */
class BytePatch(val offset: Long, val bytes: ByteArray) {
    override fun equals(other: Any?) =
        other is BytePatch && offset == other.offset && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * offset.hashCode() + bytes.contentHashCode()
}

/** The compiled, validated outcome of asking a handler to edit a field. */
sealed interface EditPlan {
    /**
     * A pure same-length in-place patch. [newBytes].size MUST equal [originalBytes].size.
     * [checksumPatches] are additional same-length writes (e.g. a recomputed CRC).
     */
    class InPlace(
        val offset: Long,
        val originalBytes: ByteArray,
        val newBytes: ByteArray,
        val checksumPatches: List<BytePatch> = emptyList(),
    ) : EditPlan

    /** A length-changing or structural edit requiring a full rebuild of the file. */
    class RequiresRewrite(val reason: String, val rebuiltBytes: ByteArray) : EditPlan

    /** The edit cannot be performed (out of range, signature-locked, etc.). */
    data class Rejected(val reason: String) : EditPlan
}

/** Result of executing an [EditPlan]. */
sealed interface EditResult {
    /** Applied. [inPlace] true if no rewrite occurred. [bytesPatched] for user feedback. */
    data class Success(val inPlace: Boolean, val bytesPatched: Int) : EditResult
    /** Not applied. File is unchanged (restored if a write was attempted). */
    data class Failure(val reason: String) : EditResult
}
