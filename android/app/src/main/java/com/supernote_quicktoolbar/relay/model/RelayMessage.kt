package com.supernote_quicktoolbar.relay.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One inbox entry on the relay device: an AI reply or a voice-transcription
 * turn. Streamed chunks belonging to the same turn are appended into a single
 * message. These records live ONLY on the relay device (filesDir JSON) and
 * are never synced back to the phone.
 */
data class RelayMessage(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    /** "llm" | "stt" | "manual" */
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    /** A closed turn no longer accepts appended chunks. */
    var closed: Boolean = false,
    /** User pinned this message to the plugin's capsule bars. */
    var pinned: Boolean = false,
    /** Already inserted into the note (grid shows it dimmed, capsules drop it). */
    var inserted: Boolean = false,
    /** Query kind tag from the plugin ("formula" = handwriting-formula recognition). */
    var kind: String = "",
) {
    /** First non-empty line, used as the card / capsule title. */
    val title: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""

    /** Body preview: everything after the title line. */
    val preview: String
        get() {
            val lines = text.lines()
            val idx = lines.indexOfFirst { it.isNotBlank() }
            if (idx < 0) return ""
            return lines.drop(idx + 1).joinToString("\n").trim()
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("source", source)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("closed", closed)
        put("pinned", pinned)
        put("inserted", inserted)
        put("kind", kind)
    }

    companion object {
        fun fromJson(obj: JSONObject): RelayMessage = RelayMessage(
            id = obj.optString("id", UUID.randomUUID().toString()),
            text = obj.optString("text", ""),
            source = obj.optString("source", "llm"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            closed = obj.optBoolean("closed", true),
            pinned = obj.optBoolean("pinned", false),
            inserted = obj.optBoolean("inserted", false),
            kind = obj.optString("kind", ""),
        )

        fun listToJson(list: List<RelayMessage>): JSONArray =
            JSONArray().apply { list.forEach { put(it.toJson()) } }

        fun listFromJson(arr: JSONArray): List<RelayMessage> =
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
    }
}
