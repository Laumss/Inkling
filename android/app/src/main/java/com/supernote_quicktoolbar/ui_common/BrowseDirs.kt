package com.supernote_quicktoolbar.ui_common

object BrowseDirs {
    val KEYS = listOf("Document", "Export", "MyStyle", "Note", "SCREENSHOT", "INBOX")

    private val PATH_MAP = mapOf(
        "Document" to "/sdcard/Document",
        "Export" to "/sdcard/EXPORT",
        "MyStyle" to "/sdcard/MyStyle",
        "Note" to "/sdcard/Note",
        "SCREENSHOT" to "/sdcard/SCREENSHOT",
        "INBOX" to "/sdcard/INBOX"
    )

    fun pathForKey(key: String?): String =
        if (key != null) PATH_MAP[key] ?: "/sdcard" else "/sdcard"
}
