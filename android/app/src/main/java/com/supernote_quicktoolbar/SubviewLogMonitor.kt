package com.supernote_quicktoolbar

import android.graphics.Rect
import android.util.Log

object SubviewLogMonitor {

    private const val TAG = "SubviewLogMonitor"

    private const val MAX_RESTARTS = 5
    private const val RESTART_BASE_DELAY_MS = 1000L
    private const val RESTART_MAX_DELAY_MS = 30_000L

    private val LOGCAT_CMD = arrayOf(
        "logcat", "-T", "1", "-v", "brief", "-s",
        "FragmentNavigator:I", "SideBarView:I",
        "NoteInsidePagesActivity:I", "NoteLinkTypeSelectView:I",
        "AreaSelectionMenuView:D", "AreaSelectionMoreMenuView:D",
        "DialogLib:E",
        "KnowledgeInsertView:I",
        "Toolbar:I",             // PageBarView showPageBar/hidePageBar
        "HandWriteClient:D",
        "DigestHandWriteClient:D",
        "HandWritePresenter:V",
        "RattaSnNoteLib:I",
        "PagingView:I",
    )

    interface Listener {
        fun onSubviewOpened(generation: Int)

        fun onSubviewClosed(generation: Int)

        fun onToolbarMenuChanged(open: Boolean, pluginListMenu: Boolean, generation: Int)

        fun onLassoMenuChanged(open: Boolean, generation: Int)

        fun onNativePenAreasChanged(rects: List<Rect>, generation: Int)

        fun onOwnedRotationDialogChanged(open: Boolean, token: Long, generation: Int)

        fun onHostFullScreenDisableArea(generation: Int)
    }

    private sealed class Op {
        class ShowExclusive(val name: String) : Op()
        class Add(val name: String) : Op()
        class Clear(val name: String) : Op()
        object ClearAll : Op()
        class ToolbarMenu(val open: Boolean, val pluginListMenu: Boolean = false) : Op()
        class LassoMenu(val open: Boolean) : Op()
    }

    private class Rule(val regex: Regex, val toOp: (MatchResult) -> Op?)

    private val LASSO_MENU_CLASSES = setOf("AreaSelectionMenuView", "AreaSelectionMoreMenuView")

    private var pendingNativeAreaCount = -1
    private val pendingNativeAreas = mutableMapOf<Int, Rect>()

    private var toolbarMenuOpen = false

    private var toolbarMenuPluginList = false

    private var lassoMenuOpen = false

    private var ownedRotationDialogToken: Long? = null
    private var ownedRotationDialogOpen = false

    private val rules: List<Rule> = listOf(
        // showExclusive ThumbnailFragment / StyleFragment / ExportFragment / CoverFragment ...
        Rule(Regex("""FragmentNavigator.*showExclusive (\w+)""")) { m ->
            val name = m.groupValues[1]
            if (name in LASSO_MENU_CLASSES) Op.LassoMenu(true) else Op.ShowExclusive(name)
        },
        // clear ThumbnailFragment / clear all
        Rule(Regex("""FragmentNavigator.*clear (\w+|all)""")) { m ->
            val what = m.groupValues[1]
            when {
                what == "all" -> Op.ClearAll
                what in LASSO_MENU_CLASSES -> Op.LassoMenu(false)
                else -> Op.Clear(what)
            }
        },
        Rule(Regex("""NoteLinkTypeSelectView.*setInitData""")) { Op.Add("NoteLinkTypeSelectView") },
        Rule(Regex("""RattaSnNoteLib.*NotePresenter: beginLassoRecognition""")) { Op.Add("LassoRecognition") },
        Rule(Regex("""NoteInsidePagesActivity.*onLassoRecognitionResult""")) { Op.Clear("LassoRecognition") },
        Rule(Regex("""AreaSelectionMenuView.*setButtonList""")) { Op.LassoMenu(true) },
        Rule(Regex("""AreaSelectionMoreMenuView.*setButtonList""")) { Op.LassoMenu(true) },
        Rule(Regex("""HandWriteClient.*setFullAuto: false lasso view gone""")) { Op.LassoMenu(false) },
        Rule(Regex("""SideBarView.*getDisableRect:\s*(buttonEntryView|pluginListView|layerSelectView|eraserSelectView|penSelectView|quickAccessView|pageTurningModeView|toolbarPositionSelectView)""")) { m ->
            Op.ToolbarMenu(true, pluginListMenu = m.groupValues[1] == "pluginListView")
        },
        Rule(Regex("""SideBarView.*showMoreView""")) { Op.Add("MoreFunctionView") },
        Rule(Regex("""NoteInsidePagesActivity.*showKeywordRecognitionSucceed""")) { Op.Add("KeywordCreateView") },
        Rule(Regex("""NoteInsidePagesActivity.*turns off keyword locked frequency""")) { Op.Clear("KeywordCreateView") },
        Rule(Regex("""SideBarView.*sidebarPreferredClick""")) { Op.Add("PreferredSettingView") },
        Rule(Regex("""SideBarView.*sidebarGestureSettingsClick""")) { Op.Add("GestureSettingView") },
        Rule(Regex("""DialogLib.*dialog show""")) { Op.Add("RattaDialog") },
        Rule(Regex("""DialogLib.*dialog dismiss""")) { Op.Clear("RattaDialog") },
        Rule(Regex("""KnowledgeInsertView.*showDigestList""")) { Op.Add("KnowledgeInsertView") },
        Rule(Regex("""SideBarView.*sidebarRenameClick""")) { Op.Add("SideBarRenameView") },
        Rule(Regex("""SideBarView.*sidebarPageClick""")) { Op.Add("UnifiedView") },
        Rule(Regex("""PageBarView\.java.*hidePageBar""")) { Op.Clear("UnifiedView") },
        Rule(Regex("""NotePresenter: notePageJumps""")) { Op.Clear("UnifiedView") },
        Rule(Regex("""PagingView.*setPageInfo""")) { Op.Add("PagingView") },
        Rule(Regex("""PageBarView\.java.*hidePageBar""")) { Op.Clear("PagingView") },
        Rule(Regex("""NotePresenter: notePageJumps""")) { Op.Clear("PagingView") },
    )

    private val POPUP_CLASSES = setOf(
        "MoreFunctionView", "KeywordCreateView", "PreferredSettingView",
        "GestureSettingView", "KnowledgeInsertView", "SideBarRenameView", "UnifiedView",
        "PagingView", "CustomToolbarView", "NoteTextSearchView",
    )

    private val WATCH_CLASSES = setOf("CustomToolbarView", "NoteTextSearchView", "PagingView")

    private const val POPUP_POLL_MS = 1200L

    private const val POPUP_MISS_THRESHOLD = 2

    private const val POPUP_POLL_MAX_FAILURES = 3

    private val lock = Any()

    @Volatile private var gen = 0

    private var listener: Listener? = null
    private var process: Process? = null
    private val openSubviews = mutableSetOf<String>()
    private var anyOpen = false

    private var popupPollerRunning = false

    fun isSubviewOpen(): Boolean = synchronized(lock) { anyOpen }

    fun isToolbarMenuOpen(): Boolean = synchronized(lock) { toolbarMenuOpen }

    fun isLassoMenuOpen(): Boolean = synchronized(lock) { lassoMenuOpen }

    fun isOwnedRotationDialogOpen(): Boolean = synchronized(lock) { ownedRotationDialogOpen }

    fun armOwnedRotationDialog(token: Long): Boolean = synchronized(lock) {
        if (anyOpen || ownedRotationDialogToken != null) return@synchronized false
        ownedRotationDialogToken = token
        ownedRotationDialogOpen = false
        true
    }

    fun retargetOwnedRotationDialog(token: Long): Boolean = synchronized(lock) {
        if (ownedRotationDialogToken == null) return@synchronized false
        ownedRotationDialogToken = token
        true
    }

    fun cancelOwnedRotationDialog(token: Long) {
        synchronized(lock) {
            if (ownedRotationDialogToken != token) return
            ownedRotationDialogToken = null
            ownedRotationDialogOpen = false
        }
    }

    fun closeLassoMenuFromFallback(reason: String) {
        val state = synchronized(lock) {
            if (!lassoMenuOpen) return
            lassoMenuOpen = false
            listener to gen
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "lasso menu changed: open=false fallback=$reason")
        state.first?.onLassoMenuChanged(false, state.second)
    }

    fun isCurrentGeneration(candidate: Int): Boolean = candidate == gen

    fun currentGeneration(): Int = gen

    fun start(l: Listener) {
        val myGen: Int
        synchronized(lock) {
            gen++
            myGen = gen
            listener = l
            openSubviews.clear()
            anyOpen = false
            toolbarMenuOpen = false
            toolbarMenuPluginList = false
            lassoMenuOpen = false
            ownedRotationDialogToken = null
            ownedRotationDialogOpen = false
            try { process?.destroy() } catch (_: Exception) {}
            process = null
            popupPollerRunning = false
        }
        Thread({ runLoop(myGen) }, "$TAG-g$myGen").apply {
            isDaemon = true
            start()
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "started (gen=$myGen)")
    }

    fun stop() {
        synchronized(lock) {
            gen++
            listener = null
            openSubviews.clear()
            anyOpen = false
            toolbarMenuOpen = false
            toolbarMenuPluginList = false
            lassoMenuOpen = false
            ownedRotationDialogToken = null
            ownedRotationDialogOpen = false
            try { process?.destroy() } catch (_: Exception) {}
            process = null
            popupPollerRunning = false
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "stopped (gen=$gen)")
    }

    private fun isCurrent(myGen: Int) = myGen == gen

    private fun bootstrapNativePenAreas(myGen: Int) {
        pendingNativeAreaCount = -1
        pendingNativeAreas.clear()
        var bootstrap: Process? = null
        try {
            bootstrap = ProcessBuilder(
                "logcat", "-d", "-t", "300", "-v", "brief", "-s",
                "HandWriteClient:D", "DigestHandWriteClient:D"
            ).redirectErrorStream(true).start()
            bootstrap.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!isCurrent(myGen)) return@useLines
                    if (line.contains("rectList.size()") || line.contains("rectList.get(")) {
                        handleNativePenAreaLine(
                            myGen = myGen,
                            line = line,
                            detectRebuildSignals = false
                        )
                    }
                }
            }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "native pen bootstrap complete gen=$myGen")
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.w(TAG, "native pen bootstrap failed gen=$myGen: ${e.message}")
            }
        } finally {
            try { bootstrap?.destroy() } catch (_: Exception) {}
        }
    }

    private fun runLoop(myGen: Int) {
        bootstrapNativePenAreas(myGen)

        var restarts = 0
        while (isCurrent(myGen)) {
            var proc: Process? = null
            try {
                proc = ProcessBuilder(*LOGCAT_CMD).redirectErrorStream(true).start()
                synchronized(lock) {
                    if (!isCurrent(myGen)) {
                        try { proc.destroy() } catch (_: Exception) {}
                        return
                    }
                    process = proc
                }
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "logcat attached (gen=$myGen restarts=$restarts)")
                proc.inputStream.bufferedReader().use { reader ->
                    var firstLine = true
                    while (isCurrent(myGen)) {
                        val line = reader.readLine() ?: break
                        if (firstLine) {
                            firstLine = false
                            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "first line: $line")
                        }
                        handleLine(myGen, line)
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "logcat read error (gen=$myGen): ${e.message}")
            } finally {
                try { proc?.destroy() } catch (_: Exception) {}
                synchronized(lock) { if (process === proc) process = null }
            }
            if (!isCurrent(myGen)) break

            restarts++
            if (restarts > MAX_RESTARTS) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "logcat died $restarts times, give up (gen=$myGen)")
                break
            }
            val delay = (RESTART_BASE_DELAY_MS shl (restarts - 1))
                .coerceAtMost(RESTART_MAX_DELAY_MS)
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "logcat died, restart #$restarts in ${delay}ms (gen=$myGen)")
            try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "reader thread exit (gen=$myGen)")
    }

    private fun handleLine(myGen: Int, line: String) {
        if (handleNativePenAreaLine(myGen, line)) return
        if (handleOwnedRotationDialogLine(myGen, line)) return
        for (rule in rules) {
            val match = rule.regex.find(line) ?: continue
            val op = rule.toOp(match)
            if (BuildConfig.ENABLE_DEBUG) {
                val opName = op?.let { it::class.simpleName } ?: "null(side-effect-only)"
                Log.d(TAG, "[menu-state] line=${line.trim().take(120)} op=$opName toolbarMenuOpen=${isToolbarMenuOpen()} openSubviews=$openSubviews")
            }
            op ?: continue
            applyOp(myGen, op, line)
            if (op is Op.Add && op.name in POPUP_CLASSES) ensurePopupPoller(myGen)
        }
    }

    private fun handleOwnedRotationDialogLine(myGen: Int, line: String): Boolean {
        val shown = line.contains("DialogLib") && line.contains("dialog show")
        val dismissed = line.contains("DialogLib") && line.contains("dialog dismiss")
        if (!shown && !dismissed) return false
        var token: Long? = null
        var currentListener: Listener? = null
        var open = false
        synchronized(lock) {
            if (!isCurrent(myGen)) return true
            val ownedToken = ownedRotationDialogToken ?: return false
            when {
                shown && !ownedRotationDialogOpen -> {
                    ownedRotationDialogOpen = true
                    token = ownedToken
                    currentListener = listener
                    open = true
                }
                dismissed && ownedRotationDialogOpen -> {
                    ownedRotationDialogOpen = false
                    ownedRotationDialogToken = null
                    token = ownedToken
                    currentListener = listener
                    open = false
                }
                else -> return true
            }
        }
        Log.i(TAG, "owned rotation dialog open=$open token=$token gen=$myGen")
        currentListener?.onOwnedRotationDialogChanged(open, token!!, myGen)
        return true
    }

    private fun handleNativePenAreaLine(
        myGen: Int,
        line: String,
        detectRebuildSignals: Boolean = true
    ): Boolean {
        val rebuildSignal = if (detectRebuildSignals) when {
            line.contains("sendFullScreenDisableArea") -> "full-screen"
            line.contains("sendToolBarDisableArea") -> "toolbar"
            line.contains("DisableWriteArea") -> "doc-write-area"
            line.contains("sendWritable: true") -> "doc-writable-true"
            else -> null
        } else null
        if (rebuildSignal != null) {
            val hasSnapshotHeader = line.contains("rectList.size()")
            val currentListener = synchronized(lock) { if (isCurrent(myGen)) listener else null }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "host pen table rebuild signal=$rebuildSignal " +
                    "snapshotHeader=$hasSnapshotHeader line=$line")
            }
            if (!hasSnapshotHeader) {
                pendingNativeAreaCount = -1
                pendingNativeAreas.clear()
                currentListener?.onHostFullScreenDisableArea(myGen)
                return true
            }
            currentListener?.onHostFullScreenDisableArea(myGen)
        }
        val size = Regex("""rectList\.size\(\)\s+(\d+)""").find(line)
        if (size != null) {
            pendingNativeAreaCount = size.groupValues[1].toInt()
            pendingNativeAreas.clear()
            if (pendingNativeAreaCount == 0) notifyNativePenAreas(myGen)
            return true
        }
        val item = Regex("""rectList\.get\((\d+)\)\s*:\s*\((-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\)""").find(line)
            ?: Regex("""rectList\.get\((\d+)\)\s*:\s*(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)""").find(line)
            ?: return false
        if (pendingNativeAreaCount < 0) return true
        val index = item.groupValues[1].toInt()
        val left = item.groupValues[2].toInt()
        val top = item.groupValues[3].toInt()
        val width = item.groupValues[4].toInt()
        val height = item.groupValues[5].toInt()
        val right = left.toLong() + width
        val bottom = top.toLong() + height
        if (width <= 0 || height <= 0 ||
            right !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
            bottom !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.w(TAG, "invalid native pen area: index=$index x=$left y=$top width=$width height=$height")
            }
            pendingNativeAreaCount = -1
            pendingNativeAreas.clear()
            return true
        }
        pendingNativeAreas[index] = Rect(left, top, right.toInt(), bottom.toInt())
        if (pendingNativeAreas.size >= pendingNativeAreaCount) notifyNativePenAreas(myGen)
        return true
    }

    private fun notifyNativePenAreas(myGen: Int) {
        val count = pendingNativeAreaCount
        val snapshot = (0 until count).mapNotNull { pendingNativeAreas[it]?.let(::Rect) }
        if (BuildConfig.ENABLE_DEBUG && snapshot.size < count) {
            Log.w(TAG, "native pen areas incomplete: declared=$count received=${snapshot.size}")
        }
        pendingNativeAreaCount = -1
        pendingNativeAreas.clear()
        val currentListener = synchronized(lock) { if (isCurrent(myGen)) listener else null }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "native pen areas changed: $snapshot")
        currentListener?.onNativePenAreasChanged(snapshot, myGen)

        val menuFullScreen = snapshot.any {
            it.left == 0 && it.top == 0 && it.right != 18888 && it.bottom != 18888 &&
                it.width() > 1000 && it.height() > 1000
        }
        if (!menuFullScreen) applyOp(myGen, Op.ToolbarMenu(false), "native pen area returned to normal")
    }

    private fun ensurePopupPoller(myGen: Int) {
        synchronized(lock) {
            if (!isCurrent(myGen) || popupPollerRunning) return
            if (openSubviews.none { it in POPUP_CLASSES }) return
            popupPollerRunning = true
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "popup poller started (gen=$myGen)")
        Thread({ popupPollLoop(myGen) }, "$TAG-popup-g$myGen").apply {
            isDaemon = true
            start()
        }
    }

    private fun popupPollLoop(myGen: Int) {
        var failures = 0
        var emptyRounds = 0
        val missCounts = mutableMapOf<String, Int>()
        while (true) {
            try { Thread.sleep(POPUP_POLL_MS) } catch (_: InterruptedException) {}
            val pending: List<String>
            synchronized(lock) {
                if (!isCurrent(myGen)) {
                    popupPollerRunning = false
                    return
                }
                pending = openSubviews.filter { it in POPUP_CLASSES }
            }
            if (pending.isEmpty()) {
                emptyRounds++
                if (emptyRounds > 1) {
                    synchronized(lock) { popupPollerRunning = false }
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "popup poller exit: no pending popups (gen=$myGen)")
                    return
                }
            } else {
                emptyRounds = 0
            }
            val targets = (pending + WATCH_CLASSES).distinct()
            val visible = queryVisibleViews(targets)
            if (visible == null) {
                failures++
                if (failures >= POPUP_POLL_MAX_FAILURES) {
                    Log.e(TAG, "popup poll: dumpsys failed x$failures, fail-open clearing $pending")
                    for (name in pending) applyOp(myGen, Op.Clear(name), "popup-poll fail-open: $name")
                }
                continue
            }
            failures = 0
            for (name in pending) {
                if (name in visible) {
                    missCounts.remove(name)
                } else {
                    val misses = (missCounts[name] ?: 0) + 1
                    missCounts[name] = misses
                    if (misses >= POPUP_MISS_THRESHOLD) {
                        applyOp(myGen, Op.Clear(name), "popup-poll: $name not visible for $misses rounds")
                        missCounts.remove(name)
                    }
                }
            }
            for (watch in WATCH_CLASSES) {
                if (watch in visible && watch !in pending) {
                    applyOp(myGen, Op.Add(watch), "popup-poll: discovered $watch visible")
                }
            }
        }
    }

    private fun queryVisibleViews(targets: List<String>): Set<String>? {
        return try {
            val proc = ProcessBuilder("dumpsys", "activity", "top")
                .redirectErrorStream(true).start()
            val found = mutableSetOf<String>()
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    for (t in targets) {
                        val idx = line.indexOf("$t{")
                        if (idx < 0) continue
                        val sp = line.indexOf(' ', idx)
                        if (sp > 0 && sp + 1 < line.length && line[sp + 1] == 'V') found.add(t)
                        if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "[popup-poll] $t: ${line.trim().take(120)}")
                    }
                }
            }
            proc.waitFor()
            found
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "popup poll dumpsys failed: ${e.message}")
            null
        }
    }

    private fun applyOp(myGen: Int, op: Op, line: String) {
        if (op is Op.ToolbarMenu) {
            var menuIsPluginList = false
            val l = synchronized(lock) {
                if (!isCurrent(myGen) || toolbarMenuOpen == op.open) return
                toolbarMenuOpen = op.open
                menuIsPluginList = if (op.open) {
                    toolbarMenuPluginList = op.pluginListMenu
                    op.pluginListMenu
                } else {
                    toolbarMenuPluginList.also { toolbarMenuPluginList = false }
                }
                listener
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu changed: open=${op.open} pluginList=$menuIsPluginList")
            l?.onToolbarMenuChanged(op.open, menuIsPluginList, myGen)
            return
        }
        if (op is Op.LassoMenu) {
            val l = synchronized(lock) {
                if (!isCurrent(myGen) || lassoMenuOpen == op.open) return
                lassoMenuOpen = op.open
                listener
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "lasso menu changed: open=${op.open}")
            l?.onLassoMenuChanged(op.open, myGen)
            return
        }
        if (op is Op.ClearAll) {
            val lassoListener = synchronized(lock) {
                if (!isCurrent(myGen) || !lassoMenuOpen) null else {
                    lassoMenuOpen = false
                    listener
                }
            }
            if (lassoListener != null) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "lasso menu changed: open=false (clear all)")
                lassoListener.onLassoMenuChanged(false, myGen)
            }
        }
        val nowOpen: Boolean
        val snapshot: String
        val l: Listener?
        synchronized(lock) {
            if (!isCurrent(myGen)) return
            val wasOpen = openSubviews.isNotEmpty()
            when (op) {
                is Op.ShowExclusive -> {
                    openSubviews.clear()
                    openSubviews.add(op.name)
                }
                is Op.Add -> openSubviews.add(op.name)
                is Op.Clear -> openSubviews.remove(op.name)
                Op.ClearAll -> openSubviews.clear()
                is Op.ToolbarMenu -> return
                is Op.LassoMenu -> return
            }
            nowOpen = openSubviews.isNotEmpty()
            anyOpen = nowOpen
            snapshot = openSubviews.toString()
            if (wasOpen == nowOpen) {
                if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "op on [$line] -> open=$snapshot (no edge)")
                return
            }
            l = listener
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "subview state changed: open=$nowOpen ($snapshot)")
        if (l == null) return
        if (nowOpen) l.onSubviewOpened(myGen) else l.onSubviewClosed(myGen)
    }
}
