package com.supernote_quicktoolbar.relay.ui

import android.util.Log
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

// ---------------------------------------------------------------------------
// E-ink primitives
// ---------------------------------------------------------------------------

/** Minimal monochrome primitives shared by relay panel content. Parent panels own bars. */
internal val Ink = Color.Black
internal val Paper = Color.White
/** AIRelay overlays keep the note page visible underneath, matching the relay detail activity. */
internal val OverlayPaper = Color(0xD0FFFFFF)
internal val Muted = Color(0xFF888888)
internal val Faint = Color(0xFFE0E0E0)
internal fun Modifier.einkBorder(enabled: Boolean = true) = border(1.5.dp, if (enabled) Ink else Muted, RectangleShape)
internal fun Modifier.topLine() = drawBehind {
    drawLine(Ink, Offset.Zero, Offset(size.width, 0f), 1.5.dp.toPx())
}

@Composable
internal fun EinkButton(label: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Box(modifier.alpha(if (enabled) 1f else .4f).defaultMinSize(minWidth = 106.dp, minHeight = 44.dp)
        .then(if (primary) Modifier else Modifier.einkBorder()).background(if (primary) Ink else Paper, RectangleShape)
        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(horizontal = 16.dp), Alignment.Center) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (primary) Paper else Ink, maxLines = 1)
    }
}

@Composable
internal fun EinkChip(label: String, filled: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.einkBorder().background(if (filled) Ink else Paper, RectangleShape).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (filled) Paper else Ink)
    }
}

@Composable
internal fun EinkEmptyState(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Muted, textAlign = TextAlign.Center)
}

@Composable
internal fun EinkCheckbox(checked: Boolean) {
    Box(Modifier.size(22.dp).einkBorder().background(if (checked) Ink else Paper), Alignment.Center) {
        if (checked) Text("✓", color = Paper, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------

private const val DU_MODE = 1
private const val RELEASE_DELAY_MS = 140L

private class DuScrollController(private val view: View) {
    private var enabled = false
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        try {
            if (value) View::class.java.getMethod("setEinkUpdateMode", Int::class.javaPrimitiveType).invoke(view, DU_MODE)
            else { View::class.java.getMethod("resetEinkUpdateMode").invoke(view); view.postInvalidateOnAnimation() }
            enabled = value
        } catch (e: Exception) { Log.w("RelayDuScroll", "E-ink DU unavailable: ${e.message}") }
    }
}

/** Uses the host's optional Supernote DU mode while a detail document scrolls. */
@Composable
internal fun DuWhileScrolling(scrollState: ScrollState) {
    val view = LocalView.current
    val controller = remember(view) { DuScrollController(view) }
    LaunchedEffect(scrollState, controller) {
        snapshotFlow { scrollState.isScrollInProgress }.distinctUntilChanged().collectLatest {
            if (it) controller.setEnabled(true) else { delay(RELEASE_DELAY_MS); controller.setEnabled(false) }
        }
    }
    DisposableEffect(controller) { onDispose { controller.setEnabled(false) } }
}

// ---------------------------------------------------------------------------
// Compose view-tree owner
// ---------------------------------------------------------------------------

/** Supplies the view-tree contracts normally installed by ComponentActivity. */
class RelayComposeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun attach(view: View) {
        controller.performAttach()
        controller.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
