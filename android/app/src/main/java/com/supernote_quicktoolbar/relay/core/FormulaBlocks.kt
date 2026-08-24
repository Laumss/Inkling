package com.supernote_quicktoolbar.relay.core

import org.json.JSONArray
import org.json.JSONObject

object FormulaBlocks {

    data class Payload(val plainText: String, val blocksJson: String, val hasMath: Boolean)

    /** Builds the wire payload from [indices] (block indexes into [document]). */
    fun payload(document: MarkdownDocument, indices: List<Int>): Payload {
        val arr = JSONArray()
        val plain = StringBuilder()
        var hasMath = false

        var textRunStart = -1
        var textRunEnd = -1

        fun flushTextRun() {
            if (textRunStart < 0) return
            val runBlocks = (textRunStart..textRunEnd).mapNotNull { document.blocks.getOrNull(it) }
            val hasSynthetic = runBlocks.any {
                (it is MarkdownBlock.Paragraph && it.synthetic) ||
                    (it is MarkdownBlock.ListItem && it.synthetic)
            }
            val text = if (hasSynthetic) {
                runBlocks.joinToString("\n") { b ->
                    when (b) {
                        is MarkdownBlock.Paragraph -> b.text
                        is MarkdownBlock.ListItem -> "${b.marker} ${b.text}"
                        is MarkdownBlock.Heading -> b.text
                        is MarkdownBlock.Quote -> b.text
                        is MarkdownBlock.CodeFence -> b.code
                        else -> ""
                    }
                }.trim('\r', '\n')
            } else {
                val first = document.blocks[textRunStart]
                val last = document.blocks[textRunEnd]
                document.source
                    .substring(first.sourceRange.startOffset, last.sourceRange.endOffsetExclusive)
                    .trim('\r', '\n')
            }
            if (text.isNotBlank()) {
                arr.put(JSONObject().put("type", "text").put("content", text))
                if (plain.isNotEmpty()) plain.append("\n\n")
                plain.append(text)
            }
            textRunStart = -1
            textRunEnd = -1
        }

        for (i in indices.sorted()) {
            val block = document.blocks.getOrNull(i) ?: continue
            if (block is MarkdownBlock.Rule) {
                flushTextRun()
                continue
            }
            if (block is MarkdownBlock.Math) {
                flushTextRun()
                if (block.tex.isNotBlank()) {
                    hasMath = true
                    arr.put(JSONObject().put("type", "math").put("content", block.tex))
                    if (plain.isNotEmpty()) plain.append("\n\n")
                    plain.append("$$${block.tex}$$")
                }
            } else {
                if (textRunStart < 0) textRunStart = i
                else if (i > textRunEnd + 1) {
                    // Non-adjacent selection: close the previous run.
                    flushTextRun()
                    textRunStart = i
                }
                textRunEnd = i
            }
        }
        flushTextRun()

        return Payload(plain.toString(), arr.toString(), hasMath)
    }

    /** Block indexes covered by the selection ranges. */
    fun selectedIndices(document: MarkdownDocument, state: MarkdownSelectionState): List<Int> =
        state.ranges
            .sortedBy { it.firstBlockIndex }
            .flatMap { r -> (r.firstBlockIndex..r.lastBlockIndex) }
            .filter { it in document.blocks.indices }
            .distinct()

    // ── formula reply sections ───────────────────────────────────────────

    private val RECOGNIZED_TITLES = listOf("识别", "recognized", "recognition")
    private val ANSWER_TITLES = listOf("解答", "answer", "solution")

    private fun headingMatches(block: MarkdownBlock, titles: List<String>): Boolean {
        if (block !is MarkdownBlock.Heading) return false
        val t = block.text.trim().lowercase()
        return titles.any { t == it || t.startsWith(it) }
    }

    fun recognizedIndices(document: MarkdownDocument): List<Int> {
        val blocks = document.blocks
        val start = blocks.indexOfFirst { headingMatches(it, RECOGNIZED_TITLES) }
        if (start < 0) return emptyList()
        val end = blocks.drop(start + 1).indexOfFirst { headingMatches(it, ANSWER_TITLES) }
            .let { if (it < 0) blocks.size else start + 1 + it }
        return ((start + 1) until end).toList()
    }
}
