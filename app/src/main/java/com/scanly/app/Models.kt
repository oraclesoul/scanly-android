package com.scanly.app

enum class PageFilter(val label: String) {
    ORIGINAL("Original"),
    CROPPED("Cropped"),
    ENHANCED("Enhanced"),
    XEROX("Xerox")
}

/** [corners] holds 4 normalized (0..1) points as x,y pairs in order top-left, top-right, bottom-right, bottom-left. */
data class ScanPage(
    val id: String,
    val corners: List<Float>,
    val filter: PageFilter,
    val version: Int
)

data class ScanDoc(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<ScanPage>
)
