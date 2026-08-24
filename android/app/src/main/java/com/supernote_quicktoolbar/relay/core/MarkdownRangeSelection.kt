package com.supernote_quicktoolbar.relay.core

data class MarkdownBlockRange(
    val firstBlockIndex: Int,
    val lastBlockIndex: Int,
) {
    init {
        require(firstBlockIndex >= 0)
        require(lastBlockIndex >= firstBlockIndex)
    }

    operator fun contains(index: Int): Boolean = index in firstBlockIndex..lastBlockIndex
}

data class MarkdownSelectionState(
    val anchorBlockIndex: Int? = null,
    val ranges: List<MarkdownBlockRange> = emptyList(),
) {
    val groupCount: Int get() = ranges.size
    val hasSelection: Boolean get() = ranges.isNotEmpty()
}

object MarkdownRangeSelection {
    fun toggle(
        state: MarkdownSelectionState,
        blockIndex: Int,
        blockCount: Int,
    ): MarkdownSelectionState {
        if (blockIndex !in 0 until blockCount) return state

        val anchor = state.anchorBlockIndex
        if (anchor == null) {
            val selectedRange = state.ranges.firstOrNull { blockIndex in it }
            return if (selectedRange != null) {
                state.copy(ranges = state.ranges - selectedRange)
            } else {
                state.copy(anchorBlockIndex = blockIndex)
            }
        }

        val completed = MarkdownBlockRange(
            firstBlockIndex = minOf(anchor, blockIndex),
            lastBlockIndex = maxOf(anchor, blockIndex),
        )
        return MarkdownSelectionState(
            anchorBlockIndex = null,
            ranges = mergeRanges(state.ranges + completed),
        )
    }

    fun clear(): MarkdownSelectionState = MarkdownSelectionState()

    fun isSelected(state: MarkdownSelectionState, blockIndex: Int): Boolean =
        state.ranges.any { blockIndex in it }

    private fun mergeRanges(ranges: List<MarkdownBlockRange>): List<MarkdownBlockRange> {
        val sorted = ranges.sortedBy { it.firstBlockIndex }
        if (sorted.isEmpty()) return emptyList()

        val merged = mutableListOf(sorted.first())
        sorted.drop(1).forEach { next ->
            val current = merged.last()
            if (next.firstBlockIndex <= current.lastBlockIndex + 1) {
                merged[merged.lastIndex] = MarkdownBlockRange(
                    firstBlockIndex = current.firstBlockIndex,
                    lastBlockIndex = maxOf(current.lastBlockIndex, next.lastBlockIndex),
                )
            } else {
                merged += next
            }
        }
        return merged
    }
}
