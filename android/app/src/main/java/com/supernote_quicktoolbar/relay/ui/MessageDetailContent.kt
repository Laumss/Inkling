package com.supernote_quicktoolbar.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supernote_quicktoolbar.NativeLocale
import com.supernote_quicktoolbar.relay.core.FormulaBlocks
import com.supernote_quicktoolbar.relay.core.MarkdownDocumentParser
import com.supernote_quicktoolbar.relay.core.MarkdownRangeSelection
import com.supernote_quicktoolbar.relay.core.MarkdownSelectionState
import com.supernote_quicktoolbar.relay.core.SelectionRowInfo
import com.supernote_quicktoolbar.relay.core.SelectionRows
import com.supernote_quicktoolbar.relay.model.RelayMessage

/** State exposed to the owning relay panel, which also supplies the PanelBar. */
enum class MessageDetailMode { READ, SELECT }

data class DetailSelectActions(
    val groupCount: Int,
    val selectedRowCount: Int,
    val canClear: Boolean,
    val canInsert: Boolean,
    val clear: () -> Unit,
    val insert: () -> Unit,
)

/**
 * Activity-free message reader and selector.
 *
 * The caller owns navigation, storage and note insertion. [onSelectionPayload] receives the
 * core's typed text/math payload, while [onReplaceHandwriting] receives only the recognized
 * formula section. No service, activity, or connection lifecycle is retained here.
 */
@Composable
fun MessageDetailContent(
    message: RelayMessage?,
    modifier: Modifier = Modifier,
    mode: MessageDetailMode = MessageDetailMode.READ,
    onModeChanged: (MessageDetailMode) -> Unit,
    onBack: () -> Unit,
    onSelectionPayload: (FormulaBlocks.Payload) -> Unit,
    onReplaceHandwriting: (FormulaBlocks.Payload) -> Unit,
    onDelete: () -> Unit,
    onSelectionActionsChanged: (DetailSelectActions?) -> Unit = {},
) {
    val document = remember(message?.text) { MarkdownDocumentParser.parse(message?.text.orEmpty()) }
    var selection by remember(document) { mutableStateOf(MarkdownSelectionState()) }
    var rows by remember(document) { mutableStateOf<List<SelectionRowInfo>>(emptyList()) }
    val selectedRows = remember(selection, rows) {
        SelectionRows.selectedIndices(rows, selection)
    }
    val selectionPayload = remember(document, rows, selectedRows) {
        SelectionRows.payload(document, rows, selectedRows)
    }
    val recognized = remember(document, message?.kind) {
        if (message?.kind == "formula") FormulaBlocks.recognizedIndices(document) else emptyList()
    }
    val replacePayload = remember(document, recognized) { FormulaBlocks.payload(document, recognized) }
    fun leaveSelection() { selection = MarkdownRangeSelection.clear(); onModeChanged(MessageDetailMode.READ) }
    val selectActions = DetailSelectActions(
        groupCount = selection.groupCount,
        selectedRowCount = selectedRows.size,
        canClear = selection.anchorBlockIndex != null || selection.hasSelection,
        canInsert = selectionPayload.plainText.isNotBlank() || selectionPayload.hasMath,
        clear = { selection = MarkdownRangeSelection.clear() },
        insert = { onSelectionPayload(selectionPayload); leaveSelection() },
    )
    LaunchedEffect(mode, document, selection, rows) {
        onSelectionActionsChanged(if (mode == MessageDetailMode.SELECT) selectActions else null)
    }
    DisposableEffect(Unit) { onDispose { onSelectionActionsChanged(null) } }

    Column(modifier.fillMaxSize().background(OverlayPaper, RectangleShape)) {
        when (mode) {
            MessageDetailMode.READ -> ReadContent(message, document, recognized.isNotEmpty(), onSelect = {
                selection = MarkdownRangeSelection.clear(); onModeChanged(MessageDetailMode.SELECT)
            }, onReplace = { onReplaceHandwriting(replacePayload) }, onDelete = onDelete, onBack = onBack)
            MessageDetailMode.SELECT -> {
                ContentSelectionPane(document, selection, onToggleRow = { index ->
                    selection = MarkdownRangeSelection.toggle(selection, index, rows.size)
                }, onRowsChanged = { updated ->
                    if (updated != rows) { rows = updated; selection = MarkdownRangeSelection.clear() }
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ColumnScope.ReadContent(message: RelayMessage?, document: com.supernote_quicktoolbar.relay.core.MarkdownDocument, canReplace: Boolean, onSelect: () -> Unit, onReplace: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    val scroll = rememberScrollState()
    if (message != null) DuWhileScrolling(scroll)
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 32.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (message == null) NativeLocale.t("relay_msg_unavailable") else "${sourceLabel(message.source)} · ${formatInboxTime(message.updatedAt)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.weight(1f))
        if (scroll.maxValue > 0) Text("${(scroll.value * 100 / scroll.maxValue).coerceIn(0, 100)}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted)
    }
    if (message == null) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { EinkEmptyState(NativeLocale.t("relay_no_message")) }
    else {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(start = 24.dp, end = 32.dp, top = 18.dp, bottom = 18.dp)) {
            MarkdownDocumentView(document, MdPalette(Ink, Muted, Faint, Ink), baseFontSize = 18, boldBody = true)
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.fillMaxWidth().topLine().padding(start = 18.dp, end = 32.dp, top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EinkButton(NativeLocale.t("relay_select"), onSelect, primary = true, modifier = Modifier.weight(1f))
            if (canReplace) EinkButton(NativeLocale.t("relay_replace_handwriting"), onReplace)
            EinkButton(NativeLocale.t("relay_delete"), onDelete)
        }
    }
}
