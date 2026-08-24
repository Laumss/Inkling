package com.supernote_quicktoolbar.relay.llm

/**
 * Bidirectional bridge to an LLM backend.
 *
 * Upstream:   [sendUserMessage] pushes a user turn and triggers a completion.
 * Downstream: replies arrive via [onStreamToken] (incremental) and
 *             [onReplyComplete] (final full text, exactly once per turn).
 *
 * This is the entire contract the service layer consumes; provider-specific
 * concepts (conversations, session following, transport) stay inside the
 * implementation.
 */
interface LlmBridge {
    val isConnected: Boolean

    /** Connect. [conversationId] resumes an existing session when the provider supports it. */
    fun start(conversationId: String? = null)

    fun stop()

    fun sendUserMessage(text: String)

    /** Incremental reply delta while the model is generating. */
    var onStreamToken: ((delta: String) -> Unit)?

    /** Final reply text; fired exactly once per completed turn. */
    var onReplyComplete: ((text: String) -> Unit)?

    var onConnectionChanged: ((connected: Boolean) -> Unit)?
}
