package com.supernote_quicktoolbar.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.supernote_quicktoolbar.relay.model.RelayMessage
import org.json.JSONArray
import java.io.File

/**
 * Relay-local message inbox. Holds AI replies and voice transcriptions until
 * the user decides to insert them into the note. Streamed chunks of the same
 * source arriving within [MERGE_GAP_MS] are merged into one message (a
 * "turn"); an explicit turn_end marker from the phone closes the head early.
 *
 * Persisted to filesDir/relay_inbox.json (debounced). Exists only on the
 * relay device — never synced anywhere.
 */
class RelayInboxStore(private val context: Context) {

    companion object {
        private const val TAG = "RelayInbox"
        private const val FILE_NAME = "relay_inbox.json"
        private const val MAX_MESSAGES = 200
        private const val MERGE_GAP_MS = 20_000L
        private const val SAVE_DEBOUNCE_MS = 800L
        private val UUID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{1,12}", RegexOption.IGNORE_CASE)
    }

    private val messages = mutableListOf<RelayMessage>()
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    /** Fired on the main thread after any change. */
    var onChanged: (() -> Unit)? = null

    fun load() {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists()) return
        try {
            val list = RelayMessage.listFromJson(JSONArray(f.readText()))
            synchronized(messages) {
                messages.clear()
                // Anything loaded from disk is a finished turn.
                messages.addAll(list.onEach { it.closed = true })
            }
            Log.i(TAG, "loaded ${list.size} messages")
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${e.message}")
        }
    }

    /** Newest first. */
    fun snapshot(): List<RelayMessage> =
        synchronized(messages) { messages.map { it.copy() } }

    fun get(id: String): RelayMessage? =
        synchronized(messages) { messages.firstOrNull { it.id == id }?.copy() }

    /**
     * Append an incoming chunk. Merges into the newest message when it has
     * the same source, is still open, and arrived recently enough.
     */
    fun append(chunk: String, source: String, kind: String = "") {
        if (chunk.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(messages) {
            val head = messages.firstOrNull()
            if (head != null && !head.closed && head.source == source &&
                now - head.updatedAt <= MERGE_GAP_MS
            ) {
                head.text = head.text + chunk
                head.updatedAt = now
                if (kind.isNotEmpty() && head.kind.isEmpty()) head.kind = kind
            } else {
                head?.closed = true
                messages.add(0, RelayMessage(text = chunk.trimStart(), source = source, createdAt = now, updatedAt = now, kind = kind))
                while (messages.size > MAX_MESSAGES) messages.removeAt(messages.size - 1)
            }
        }
        notifyChanged()
    }

    /** Close the current turn (e.g. LLM reply completed). */
    fun closeHead(source: String? = null) {
        var changed = false
        synchronized(messages) {
            val head = messages.firstOrNull() ?: return
            if (!head.closed && (source == null || head.source == source)) {
                head.closed = true
                head.text = head.text.replace(UUID_REGEX, "")
                changed = true
            }
        }
        if (changed) notifyChanged()
    }

    /** Re-add to the plugin capsules: pin and clear the inserted mark. */
    fun readd(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        var changed = false
        synchronized(messages) {
            messages.filter { it.id in set }.forEach {
                if (!it.pinned || it.inserted) {
                    it.pinned = true; it.inserted = false; changed = true
                }
            }
        }
        if (changed) notifyChanged()
    }

    /** Mark a message as inserted into the note; it leaves the capsules. */
    fun markInserted(id: String) {
        var changed = false
        synchronized(messages) {
            messages.firstOrNull { it.id == id }?.let {
                if (!it.inserted) { it.inserted = true; it.pinned = false; changed = true }
            }
        }
        if (changed) notifyChanged()
    }

    fun delete(id: String) {
        val changed = synchronized(messages) { messages.removeAll { it.id == id } }
        if (changed) notifyChanged()
    }

    fun deleteMany(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        val changed = synchronized(messages) { messages.removeAll { it.id in set } }
        if (changed) notifyChanged()
    }

    private fun notifyChanged() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS)
        handler.post { onChanged?.invoke() }
    }

    private fun saveNow() {
        try {
            val json = synchronized(messages) { RelayMessage.listToJson(messages).toString() }
            File(context.filesDir, FILE_NAME).writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
        }
    }

    fun flush() {
        handler.removeCallbacks(saveRunnable)
        saveNow()
    }
}
