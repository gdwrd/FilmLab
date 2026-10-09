package com.example.raw

import java.util.Locale

/**
 * Identifies the RAW file format or standard image format.
 */
enum class RawFormat(
    val formatName: String,
    val badgeLabel: String,
    val extensions: List<String>,
    val mimeTypes: List<String>
) {
    SONY_ARW(
        formatName = "Sony Alpha RAW",
        badgeLabel = "RAW • SONY ARW",
        extensions = listOf("arw", "srf", "sr2"),
        mimeTypes = listOf(
            "image/x-sony-arw",
            "image/arw",
            "image/x-raw",
            "application/x-sony-arw"
        )
    ),
    DNG(
        formatName = "Adobe Digital Negative",
        badgeLabel = "RAW • ADOBE DNG",
        extensions = listOf("dng"),
        mimeTypes = listOf(
            "image/x-adobe-dng",
            "image/dng",
            "image/x-raw",
            "application/x-adobe-dng"
        )
    ),
    STANDARD(
        formatName = "Standard Image",
        badgeLabel = "35mm SCAN",
        extensions = listOf("jpg", "jpeg", "png", "webp", "heic", "heif"),
        mimeTypes = listOf("image/jpeg", "image/png", "image/webp", "image/heic")
    );

    val isRaw: Boolean
        get() = this != STANDARD

    companion object {
        fun detect(fileName: String?, mimeType: String?): RawFormat {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
            val mime = mimeType?.lowercase(Locale.ROOT)

            if (ext in SONY_ARW.extensions || mime in SONY_ARW.mimeTypes) {
                return SONY_ARW
            }
            if (ext in DNG.extensions || mime in DNG.mimeTypes) {
                return DNG
            }
            return STANDARD
        }
    }
}
