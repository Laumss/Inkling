package com.supernote_quicktoolbar.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supernote_quicktoolbar.NativeLocale
import com.supernote_quicktoolbar.relay.model.RelayMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val COLUMNS = 2
private const val ROWS = 3
private const val PAGE_SIZE = COLUMNS * ROWS

fun formatInboxTime(timestamp: Long): String = SimpleDateFormat(
    if (System.currentTimeMillis() - timestamp < 20 * 3_600_000L) "HH:mm" else "MM-dd HH:mm",
    Locale.getDefault(),
).format(Date(timestamp))

internal fun sourceLabel(source: String) = when (source) {
    "llm" -> NativeLocale.t("relay_source_ai")
    "manual" -> NativeLocale.t("relay_source_manual")
    else -> NativeLocale.t("relay_source_dictation")
}

/** Content-only 2×3 inbox. The PanelBar owns navigation and edit actions. */
@Composable
fun InboxGridScreen(
    messages: List<RelayMessage>,
    editing: Boolean,
    onOpen: (String) -> Unit,
    onSelectionChanged: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember(messages.size) { mutableIntStateOf(0) }
    val pageCount = maxOf(1, (messages.size + PAGE_SIZE - 1) / PAGE_SIZE)
    page = page.coerceIn(0, pageCount - 1)
    val selected = remember(editing) { mutableStateListOf<String>() }

    LaunchedEffect(editing, selected.size) {
        onSelectionChanged(if (editing) selected.toSet() else emptySet())
    }
    DisposableEffect(Unit) { onDispose { onSelectionChanged(emptySet()) } }

    Column(modifier.fillMaxSize().background(OverlayPaper)) {
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                EinkEmptyState(NativeLocale.t("relay_empty"))
            }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp).pointerInput(pageCount) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onHorizontalDrag = { _, drag -> distance += drag },
                    onDragEnd = {
                        if (distance < -80 && page < pageCount - 1) page++
                        else if (distance > 80 && page > 0) page--
                    },
                )
            },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(ROWS) { row ->
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(COLUMNS) { col ->
                        messages.getOrNull(page * PAGE_SIZE + row * COLUMNS + col)?.let { message ->
                            InboxCard(
                                message = message,
                                editing = editing,
                                selected = message.id in selected,
                                onClick = {
                                    if (editing) {
                                        if (!selected.remove(message.id)) selected.add(message.id)
                                        onSelectionChanged(selected.toSet())
                                    } else {
                                        onOpen(message.id)
                                    }
                                },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        } ?: Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxCard(
    message: RelayMessage,
    editing: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .einkBorder()
            .then(if (selected) Modifier.border(3.dp, Ink, RectangleShape) else Modifier)
            .background(if (message.inserted) Faint else Paper, RectangleShape)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EinkChip(sourceLabel(message.source), message.source == "llm")
            Spacer(Modifier.width(7.dp))
            Text(formatInboxTime(message.updatedAt), fontSize = 11.sp, color = Muted)
            Spacer(Modifier.weight(1f))
            if (editing) EinkCheckbox(selected)
            else if (message.inserted) EinkChip(NativeLocale.t("relay_sent_to_note"))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            message.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            message.preview.ifEmpty { message.title },
            fontSize = 13.sp,
            color = Ink,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 19.sp,
        )
    }
}
