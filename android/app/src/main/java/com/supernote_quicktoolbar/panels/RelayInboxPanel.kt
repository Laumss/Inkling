package com.supernote_quicktoolbar.panels

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.FloatingToolbarModule
import com.supernote_quicktoolbar.NativeLocale
import com.supernote_quicktoolbar.relay.AIRelayCore
import com.supernote_quicktoolbar.relay.model.RelayMessage
import com.supernote_quicktoolbar.relay.ui.DetailSelectActions
import com.supernote_quicktoolbar.relay.ui.InboxGridScreen
import com.supernote_quicktoolbar.relay.ui.MessageDetailContent
import com.supernote_quicktoolbar.relay.ui.MessageDetailMode
import com.supernote_quicktoolbar.relay.ui.RelayComposeOwner
import com.supernote_quicktoolbar.ui_common.PanelBar
import com.supernote_quicktoolbar.ui_common.PanelBase

/** AIRelay inbox and detail navigator hosted inside one full-screen PluginHost panel. */
class RelayInboxPanel private constructor(
    context: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule,
) : PanelBase(context, toolbar), AIRelayCore.Listener {
    companion object {
        @Volatile private var instance: RelayInboxPanel? = null

        fun getInstance(context: ReactApplicationContext, toolbar: FloatingToolbarModule): RelayInboxPanel =
            instance ?: synchronized(this) {
                instance ?: RelayInboxPanel(context, toolbar).also { instance = it }
            }
    }

    override val tag = "RelayInboxPanel"
    override val panelName = "airRelay"
    override val fullScreen = true

    private enum class Screen { LIST, EDIT, DETAIL_READ, DETAIL_SELECT }

    private val core by lazy { AIRelayCore.get(reactContext) }
    private var screen = Screen.LIST
    private var requestedDetailId: String? = null
    private var selectedIds = emptySet<String>()
    private var detailActions: DetailSelectActions? = null
    private var composeOwner: RelayComposeOwner? = null
    private var composeView: ComposeView? = null
    private var contentRoot: LinearLayout? = null
    private var panelBar: PanelBar.Handle? = null
    private val messagesState = mutableStateOf<List<RelayMessage>>(emptyList())
    /**
     * Whether the detail page was reached through the inbox (Co → grid → card).
     * Capsule-tap and formula-reply opens enter detail directly, so their back
     * button closes the panel to the note instead of stopping at the inbox.
     */
    private var enteredFromInbox = false

    fun showList() {
        core.start()
        screen = Screen.LIST
        requestedDetailId = null
        enteredFromInbox = true
        if (isShowing) renderChrome() else showPanel()
    }

    fun showDetail(id: String, fromInbox: Boolean = false) {
        core.start()
        requestedDetailId = id
        screen = Screen.DETAIL_READ
        enteredFromInbox = fromInbox
        if (isShowing) renderChrome() else showPanel()
    }

    override fun buildFullScreenContent(): View {
        core.addListener(this)
        messagesState.value = core.inbox.snapshot()
        val owner = RelayComposeOwner().also { composeOwner = it }
        return FrameLayout(reactContext).apply {
            // A WindowManager root has no Activity to supply these tree owners.
            // Compose resolves its window recomposer from this root on attachment.
            owner.attach(this)
            contentRoot = LinearLayout(reactContext).also { root ->
                root.orientation = LinearLayout.VERTICAL
                addView(root, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ))
            }
            renderChrome()
        }
    }

    override fun onHide() {
        core.removeListener(this)
        composeView?.disposeComposition()
        composeView = null
        composeOwner?.destroy()
        composeOwner = null
        contentRoot = null
        panelBar = null
        detailActions = null
        selectedIds = emptySet()
        instance = null
    }

    override fun onInboxChanged(messages: List<RelayMessage>) {
        messagesState.value = messages
        if (requestedDetailId != null && messages.none { it.id == requestedDetailId }) {
            screen = Screen.LIST
            requestedDetailId = null
            renderChrome()
        }
    }

    override fun onFormulaReplyReady(messageId: String) {
        if (isShowing) showDetail(messageId)
    }

    private fun closePanel() {
        hide()
        toolbar.restoreToolbar()
    }

    /** Detail back: return to the inbox only if we came through it. */
    private fun backFromDetail() {
        if (enteredFromInbox) {
            requestedDetailId = null
            screen = Screen.LIST
            renderChrome()
        } else {
            // Entered directly (capsule tap / formula reply): close to the note.
            closePanel()
        }
    }

    private fun renderChrome() {
        val root = contentRoot ?: return
        composeView?.disposeComposition()
        composeView = null
        root.removeAllViews()

        val bar = when (screen) {
            Screen.LIST -> PanelBar.build(
                reactContext,
                PanelBar.Style.PAGE_HEADER,
                left = listOf(PanelBar.IconBtn("icons/ic_arrow_left.xml", ::closePanel)),
                center = listOf(PanelBar.Title("AIRelay")),
                right = listOf(PanelBar.OutlineBtn(NativeLocale.t("relay_edit")) { screen = Screen.EDIT; selectedIds = emptySet(); renderChrome() }),
            )
            Screen.EDIT -> PanelBar.build(
                reactContext,
                PanelBar.Style.INBOX,
                left = listOf(PanelBar.TextBtn(NativeLocale.t("cancel")) { screen = Screen.LIST; selectedIds = emptySet(); renderChrome() }),
                center = listOf(
                    PanelBar.Action("icons/ic_bar_readd.xml", NativeLocale.t("relay_readd")) {
                        if (selectedIds.isNotEmpty()) core.readd(selectedIds)
                        screen = Screen.LIST; selectedIds = emptySet(); renderChrome()
                    },
                    PanelBar.Action("icons/ic_bar_delete.xml", NativeLocale.t("relay_delete")) {
                        if (selectedIds.isNotEmpty()) core.deleteMany(selectedIds)
                        screen = Screen.LIST; selectedIds = emptySet(); renderChrome()
                    },
                ),
                right = listOf(PanelBar.Title(NativeLocale.t("relay_selected_items", selectedIds.size))),
            )
            Screen.DETAIL_READ -> PanelBar.build(
                reactContext,
                PanelBar.Style.PAGE_HEADER,
                left = listOf(PanelBar.IconBtn("icons/ic_arrow_left.xml", ::backFromDetail)),
                center = listOf(PanelBar.Title("AIRelay")),
            )
            Screen.DETAIL_SELECT -> {
                val actions = detailActions
                PanelBar.build(
                    reactContext,
                    PanelBar.Style.INBOX,
                    left = listOf(PanelBar.TextBtn(NativeLocale.t("cancel")) { screen = Screen.DETAIL_READ; renderChrome() }),
                    center = listOf(
                        PanelBar.Action("icons/ic_bar_clear.xml", NativeLocale.t("relay_clear")) { detailActions?.clear?.invoke() },
                        PanelBar.Action("icons/ic_bar_insert.xml", NativeLocale.t("relay_insert")) { detailActions?.insert?.invoke() },
                    ),
                    right = listOf(PanelBar.Title(NativeLocale.t("relay_selected_rows", actions?.selectedRowCount ?: 0))),
                )
            }
        }
        panelBar = bar
        root.addView(bar.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            bar.heightPx,
        ))

        val compose = ComposeView(reactContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                when (screen) {
                    Screen.LIST, Screen.EDIT -> InboxGridScreen(
                        messages = messagesState.value,
                        editing = screen == Screen.EDIT,
                        onOpen = { id -> showDetail(id, fromInbox = true) },
                        onSelectionChanged = { ids ->
                            // Keep the Compose selection state alive; the PanelBar actions read
                            // this set directly, so a click must not rebuild the composition.
                            selectedIds = ids
                            if (screen == Screen.EDIT) {
                                panelBar?.setTitleLabel(0, NativeLocale.t("relay_selected_items", ids.size))
                            }
                        },
                    )
                    Screen.DETAIL_READ, Screen.DETAIL_SELECT -> MessageDetailContent(
                        message = messagesState.value.firstOrNull { it.id == requestedDetailId },
                        mode = if (screen == Screen.DETAIL_SELECT) MessageDetailMode.SELECT else MessageDetailMode.READ,
                        onModeChanged = { mode ->
                            screen = if (mode == MessageDetailMode.SELECT) Screen.DETAIL_SELECT else Screen.DETAIL_READ
                            renderChrome()
                        },
                        onBack = ::backFromDetail,
                        onSelectionPayload = { payload ->
                            val id = requestedDetailId ?: return@MessageDetailContent
                            if (payload.hasMath) core.insertBlocks(id, payload.plainText, payload.blocksJson, replace = false)
                            else core.insertSelection(id, payload.plainText)
                        },
                        onReplaceHandwriting = { payload ->
                            val id = requestedDetailId ?: return@MessageDetailContent
                            core.insertBlocks(id, payload.plainText, payload.blocksJson, replace = true)
                        },
                        onDelete = {
                            requestedDetailId?.let(core::delete)
                            backFromDetail()
                        },
                        onSelectionActionsChanged = { actions ->
                            // The PanelBar callbacks dereference this field at click time, which
                            // preserves the Compose selection composition while its range changes.
                            detailActions = actions
                            if (screen == Screen.DETAIL_SELECT) {
                                panelBar?.setTitleLabel(0, NativeLocale.t("relay_selected_rows", actions?.selectedRowCount ?: 0))
                            }
                        },
                    )
                }
            }
        }
        composeView = compose
        root.addView(compose, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
    }
}
