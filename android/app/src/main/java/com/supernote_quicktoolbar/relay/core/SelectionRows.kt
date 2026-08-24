package com.supernote_quicktoolbar.relay.core

import org.json.JSONArray
import org.json.JSONObject

data class SelectionRowInfo(
    val blockIndex: Int,
    val charStart: Int = 0,
    val charEnd: Int = 0,
    val atomic: Boolean = true,
)

object SelectionRows {

    fun selectableText(block: MarkdownBlock): String? = when (block) {
        is MarkdownBlock.Paragraph -> block.text
        is MarkdownBlock.Quote -> block.text
        is MarkdownBlock.ListItem -> block.text
        is MarkdownBlock.Heading -> block.text
        is MarkdownBlock.CodeFence -> block.code
        else -> null
    }

    fun selectedIndices(rows: List<SelectionRowInfo>, state: MarkdownSelectionState): List<Int> =
        state.ranges
            .sortedBy { it.firstBlockIndex }
            .flatMap { r -> (r.firstBlockIndex..r.lastBlockIndex) }
            .filter { it in rows.indices }
            .distinct()

    fun payload(
        document: MarkdownDocument,
        rows: List<SelectionRowInfo>,
        selected: List<Int>,
    ): FormulaBlocks.Payload {
        val arr = JSONArray()
        val plain = StringBuilder()
        var hasMath = false
        val textRun = StringBuilder()

        fun flushText() {
            val text = textRun.toString().trim('\r', '\n')
            textRun.setLength(0)
            if (text.isBlank()) return
            arr.put(JSONObject().put("type", "text").put("content", text))
            if (plain.isNotEmpty()) plain.append("\n\n")
            plain.append(text)
        }

        var prevRow = -2
        var prevBlock = -1
        for (ri in selected.sorted().distinct()) {
            val row = rows.getOrNull(ri) ?: continue
            val block = document.blocks.getOrNull(row.blockIndex) ?: continue
            when {
                block is MarkdownBlock.Rule -> { }

                block is MarkdownBlock.Math -> {
                    flushText()
                    if (block.tex.isNotBlank()) {
                        hasMath = true
                        arr.put(JSONObject().put("type", "math").put("content", block.tex))
                        if (plain.isNotEmpty()) plain.append("\n\n")
                        plain.append("$$${block.tex}$$")
                    }
                }

                row.atomic -> {
                    val t = when (block) {
                        is MarkdownBlock.Table -> document.source.substring(
                            block.sourceRange.startOffset, block.sourceRange.endOffsetExclusive)
                        is MarkdownBlock.ListItem -> "${block.marker} ${block.text}"
                        else -> selectableText(block) ?: ""
                    }
                    if (t.isNotBlank()) {
                        if (textRun.isNotEmpty()) textRun.append('\n')
                        textRun.append(t)
                    }
                }

                else -> {
                    val full = selectableText(block) ?: ""
                    var piece = full.substring(
                        row.charStart.coerceIn(0, full.length),
                        row.charEnd.coerceIn(row.charStart, full.length))
                    if (block is MarkdownBlock.ListItem && row.charStart == 0) {
                        piece = "${block.marker} $piece"
                    }
                    if (textRun.isNotEmpty()) {
                        val seamless = row.blockIndex == prevBlock && ri == prevRow + 1
                        if (!seamless) textRun.append('\n')
                    }
                    textRun.append(piece)
                }
            }
            prevRow = ri
            prevBlock = row.blockIndex
        }
        flushText()
        return FormulaBlocks.Payload(plain.toString(), arr.toString(), hasMath)
    }
}
