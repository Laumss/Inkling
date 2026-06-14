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

class MultiSelectState {
    var isActive = false
        private set

    private val paths = linkedSetOf<String>()

    val count: Int get() = paths.size
    val selectedPaths: List<String> get() = paths.toList()

    fun activate(seed: String? = null) {
        isActive = true
        seed?.let { paths.add(it) }
    }

    fun deactivate() {
        isActive = false
        paths.clear()
    }

    fun toggle(path: String): Boolean =
        if (path in paths) {
            paths.remove(path); false
        } else {
            paths.add(path); true
        }

    fun isSelected(path: String): Boolean = path in paths

    fun clear() {
        isActive = false
        paths.clear()
    }
}
