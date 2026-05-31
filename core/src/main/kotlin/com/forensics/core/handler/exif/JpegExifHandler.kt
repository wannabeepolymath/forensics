package com.forensics.core.handler.exif

import com.forensics.core.handler.FormatHandler
import com.forensics.core.io.ByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.FieldType
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

/** Reads/edits EXIF (IFD0) inside a JPEG APP1 segment. Big- and little-endian supported. */
class JpegExifHandler : FormatHandler {
    override val formatName = "JPEG/EXIF"

    override fun canHandle(magic: ByteArray, mime: String?): Boolean =
        magic.size >= 3 &&
            (magic[0].toInt() and 0xFF) == 0xFF &&
            (magic[1].toInt() and 0xFF) == 0xD8 &&
            (magic[2].toInt() and 0xFF) == 0xFF

    /** Locates the TIFF block: returns absolute file offset of the TIFF header, or -1. */
    private fun findTiffBase(bytes: ByteArray): Int {
        var i = 2 // skip SOI
        while (i + 4 <= bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) return -1
            val marker = bytes[i + 1].toInt() and 0xFF
            val segLen = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker == 0xE1) {
                val idStart = i + 4
                if (idStart + 6 <= bytes.size &&
                    String(bytes, idStart, 4, Charsets.US_ASCII) == "Exif"
                ) return idStart + 6
            }
            i += 2 + segLen
        }
        return -1
    }

    override fun parse(source: ByteSource): List<MetadataField> {
        val bytes = source.readAt(0, source.size().toInt())
        val tiffBase = findTiffBase(bytes)
        if (tiffBase < 0 || tiffBase + 8 > bytes.size) return emptyList()

        val littleEndian = String(bytes, tiffBase, 2, Charsets.US_ASCII) == "II"
        val tiff = TiffReader(bytes.copyOfRange(tiffBase, bytes.size), littleEndian)
        val ifd0 = tiff.u32(4).toInt()
        if (ifd0 + 2 > tiff.data.size) return emptyList()

        val count = tiff.u16(ifd0)
        val fields = ArrayList<MetadataField>()
        for (e in 0 until count) {
            val entry = ifd0 + 2 + e * 12
            if (entry + 12 > tiff.data.size) break
            val tag = tiff.u16(entry)
            val type = ExifType.fromCode(tiff.u16(entry + 2)) ?: continue
            val valueCount = tiff.u32(entry + 4).toInt()
            val totalBytes = type.byteSize * valueCount
            val valueLocalOffset = if (totalBytes <= 4) entry + 8 else tiff.u32(entry + 8).toInt()
            if (valueLocalOffset + totalBytes > tiff.data.size) continue

            val absoluteOffset = (tiffBase + valueLocalOffset).toLong()
            val field = when (type) {
                ExifType.SHORT -> MetadataField(
                    key = ExifTags.name(tag),
                    value = Value.Integer(tiff.u16(valueLocalOffset).toLong()),
                    byteOffset = absoluteOffset, byteLength = 2,
                    type = FieldType.FIXED, editable = true, group = "EXIF",
                )
                ExifType.LONG -> MetadataField(
                    key = ExifTags.name(tag),
                    value = Value.Integer(tiff.u32(valueLocalOffset)),
                    byteOffset = absoluteOffset, byteLength = 4,
                    type = FieldType.FIXED, editable = true, group = "EXIF",
                )
                ExifType.ASCII -> {
                    val raw = tiff.data.copyOfRange(valueLocalOffset, valueLocalOffset + totalBytes)
                    val text = String(raw, Charsets.US_ASCII).trimEnd(' ')
                    MetadataField(
                        key = ExifTags.name(tag),
                        value = Value.Text(text),
                        byteOffset = absoluteOffset, byteLength = totalBytes,
                        type = FieldType.FIXED, editable = true, group = "EXIF",
                    )
                }
                ExifType.BYTE -> MetadataField(
                    key = ExifTags.name(tag),
                    value = Value.Raw(tiff.data.copyOfRange(valueLocalOffset, valueLocalOffset + totalBytes)),
                    byteOffset = absoluteOffset, byteLength = totalBytes,
                    type = FieldType.FIXED, editable = false, group = "EXIF",
                )
            }
            fields.add(field)
        }
        return fields
    }

    override fun validateEdit(source: ByteSource, field: MetadataField, newValue: Value): EditPlan {
        if (!field.editable) return EditPlan.Rejected("field is read-only")
        val bytes = source.readAt(0, source.size().toInt())
        val tiffBase = findTiffBase(bytes)
        if (tiffBase < 0) return EditPlan.Rejected("no EXIF block")
        val littleEndian = String(bytes, tiffBase, 2, Charsets.US_ASCII) == "II"
        val tiff = TiffReader(bytes, littleEndian) // for encodeU16 in this byte order

        return when (val v = newValue) {
            is Value.Integer -> {
                if (field.byteLength != 2) return EditPlan.Rejected("unsupported integer field width")
                if (field.key == "Orientation" && v.n !in 1..8)
                    return EditPlan.Rejected("Orientation must be 1..8")
                if (v.n !in 0..0xFFFF) return EditPlan.Rejected("value out of SHORT range")
                val original = source.readAt(field.byteOffset, field.byteLength)
                val newBytes = tiff.encodeU16(v.n.toInt())
                EditPlan.InPlace(field.byteOffset, original, newBytes)
            }
            is Value.Text -> {
                val encoded = v.s.toByteArray(Charsets.US_ASCII)
                val capacity = field.byteLength // includes the NUL/pad terminator slot
                when {
                    encoded.size + 1 > capacity ->
                        EditPlan.RequiresRewrite(
                            "new text is longer than the original field",
                            rebuildWithText(bytes, field, v.s),
                        )
                    else -> {
                        val original = source.readAt(field.byteOffset, field.byteLength)
                        val padded = ByteArray(capacity) // zero-filled => NUL padding
                        System.arraycopy(encoded, 0, padded, 0, encoded.size)
                        EditPlan.InPlace(field.byteOffset, original, padded)
                    }
                }
            }
            is Value.Raw -> EditPlan.Rejected("raw fields are not editable in v1")
        }
    }

    /**
     * Rebuilds the whole file with [field]'s ASCII value replaced by [text].
     * v1: a real TIFF rebuild is a scoped follow-on. This safe stub returns the ORIGINAL bytes
     * unchanged, so the EditEngine's structural re-parse + value re-check (Task 18) detects that
     * the value did not actually change and reports Failure — i.e. it fails CLOSED, never corrupts.
     */
    private fun rebuildWithText(original: ByteArray, field: MetadataField, text: String): ByteArray {
        return original.copyOf()
    }
}
