package com.forensics.core.handler.exif

/** EXIF/TIFF field types we handle (subset). Values are the TIFF type codes. */
enum class ExifType(val code: Int, val byteSize: Int) {
    BYTE(1, 1), ASCII(2, 1), SHORT(3, 2), LONG(4, 4);

    companion object {
        fun fromCode(code: Int): ExifType? = entries.firstOrNull { it.code == code }
    }
}

/** Tag id -> human name for the subset we surface. */
object ExifTags {
    const val ORIENTATION = 0x0112
    const val DATETIME = 0x0132
    const val MAKE = 0x010F
    const val MODEL = 0x0110

    private val names = mapOf(
        ORIENTATION to "Orientation",
        DATETIME to "DateTime",
        MAKE to "Make",
        MODEL to "Model",
        0x011A to "XResolution",
        0x0131 to "Software",
    )

    fun name(tag: Int): String = names[tag] ?: "Tag-0x%04X".format(tag)
}
