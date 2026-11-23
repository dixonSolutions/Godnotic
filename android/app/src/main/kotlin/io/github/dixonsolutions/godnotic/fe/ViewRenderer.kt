package io.github.dixonsolutions.godnotic.fe

import android.content.Context
import android.graphics.Color
import android.os.Parcelable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * Draws UI bridge nodes (docs/UI_BRIDGE.md, section 4) with platform views
 * styled by Theme and Kit. One [Entry] per node id: its view, the group its
 * children go in, and its props, so a diff rebinds only that node.
 *
 * Supported: screen (sticky header with title, back and actions; `toolbar`
 * pinned under it; one scrolling body; `actionbar` or the screen's
 * `primary` pinned at the bottom), section (sticky headers), toolbar,
 * actionbar, row, grid (horizontal carousels too), tabs/pane, split, form,
 * card, hero, list (RecyclerView: table or cards; windows on demand),
 * preview, text, image, divider, status, progress, switch, slider, stepper,
 * choice (segmented chips or a sheet), field (password with a lock),
 * button. colour and keybind render as a generic row with their value; any
 * other type as a generic row (title, subtitle, actions, then its
 * children), per section 2.
 */
class ViewRenderer(
    private val ctx: Context,
    private val events: UiEvents,
    private val host: Host,
) : UiRenderer {

    /** Where screens, dialogs and toasts go, and the platform's actions (FrontendHost). */
    interface Host {
        fun showScreen(view: View, id: String, transition: String)
        fun showDialog(view: View?)
        fun toast(text: String, kind: String)
        fun canBack(): Boolean
        /** link / share / copy actions handled natively; true when handled. */
        fun platformAction(node: String, action: Map<String, Any?>): Boolean
        fun confirm(confirm: Map<String, Any?>, ok: () -> Unit)
        /** `app.reserved` rects (dp) while the engine's own controls show through (4.12); empty otherwise. */
        fun reserved(): List<Map<String, Any?>> = emptyList()
    }

    class Entry(val id: String, val type: String, val view: View, val children: ViewGroup?, var props: Map<String, Any?>) {
        var parent: String? = null
        var bind: ((Entry) -> Unit)? = null
        var top: ViewGroup? = null
        var bottom: ViewGroup? = null
        var fill: ViewGroup? = null
        var scroller: ScrollView? = null
        var body: LinearLayout? = null
        var list: VirtualList? = null
        var source: String = ""
        var empty: LinearLayout? = null
        /** tabs: the strip (pinned when the tabs are a screen's child) */
        var strip: View? = null
        /** screen: the header row and its title block, for the Settings search icon */
        var bar: ViewGroup? = null
        var titles: View? = null
    }

    private val serverListStates = HashMap<String, ServerListState>()
    private fun serverState(source: String) = serverListStates.getOrPut(source) { ServerListState() }
    private fun isServerSource(source: String) = source == "servers" || source.startsWith("servers.")
    private val serverListState get() = serverState(nodes["mp/servers"]?.source ?: "servers")
    private val fadedIn = HashSet<String>()
    private val nodes = HashMap<String, Entry>()
    private val lists = HashMap<String, MutableList<VirtualList>>() // by source
    private val saved = HashMap<String, MutableMap<String, Any?>>()
    private val imagePaths = HashMap<String, String>()
    private val imageWaiters = HashMap<String, MutableList<(String) -> Unit>>()
    private fun dp(v: Float) = Theme.dp(ctx, v)

    fun entry(id: String): Entry? = nodes[id]
    val size: Int get() = nodes.size

    // ------------------------------------------------------------------ operations

    override fun create(id: String, parent: String?, index: Int, type: String, props: Map<String, Any?>, hints: Map<String, Any?>) {
        nodes[id]?.let { remove(id) }
        // a child of Home's bar is drawn as one of its joined segments (a button)
        val inTabBar = parent?.let { nodes[it] }?.type == "tabbar"
        val e = try { if (inTabBar) tab(id, props) else build(id, type, if (id == "mp/servers") serverListState.merge(props) else props) } catch (ex: RuntimeException) { generic(id, type, props) }
        e.parent = parent
        nodes[id] = e
        bindSafe(e)
        if (e.list != null && isServerSource(e.source)) flushServers(e.source)
        e.view.visibility = if (props.bool("hidden") || id == DOCKED_DETAILS) View.GONE else View.VISIBLE
        val p = parent?.let { nodes[it] } ?: return
        if (p.type == "tabs") { tabsAdd(p, e); return }
        // Home's bar of tabs: the screen's last strip, under the action bar
        if (p.type == "screen" && type == "tabbar") { (p.view as? ViewGroup)?.addView(e.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); return }
        if (p.type == "split") { splitAdd(p, e); return }
        // Settings: the search field is an icon in the title bar that opens inline (not a heading and a field)
        if (p.type == "screen" && type == "field" && id == "search" && props.str("kind") == "search") { headerSearch(p, e); return }
        val place = UiLayout.placement(p.type, type, hints, props)
        val group = when (place) {
            Placement.TOP -> p.top
            Placement.BOTTOM -> p.bottom
            Placement.FILL -> p.children?.also { switchToFill(p) }
            Placement.BODY -> p.children
        } ?: p.children ?: return
        val lp = layoutFor(group, e, place == Placement.FILL)
        if (index < 0 || index > group.childCount) group.addView(e.view, lp) else group.addView(e.view, index, lp)
        if (group === p.top || group === p.bottom) group.visibility = View.VISIBLE
        if (type == "tabs" && p.type == "screen" && e.strip != null) { p.top?.addView(e.strip); p.top?.visibility = View.VISIBLE }
        else if (type == "tabs" && e.strip != null) group.addView(e.strip, group.indexOfChild(e.view))
        if (type == "list" && place == Placement.BODY) nestList(e)
    }

    override fun update(id: String, props: Map<String, Any?>) {
        val e = nodes[id] ?: return
        e.props = if (id == "mp/servers") serverListState.merge(props) else props
        bindSafe(e)
        e.view.visibility = if (props.bool("hidden") || id == DOCKED_DETAILS) View.GONE else View.VISIBLE
    }

    /** The engine's chosen-server card: the "i" dialog shows it instead, so it is never docked over the list. */
    private val DOCKED_DETAILS = "mp/details"

    private fun bindSafe(e: Entry) {
        try { e.bind?.invoke(e) } catch (ex: RuntimeException) { android.util.Log.w("Godnotic", "Native UI: ${e.type} ${e.id} did not bind: ${ex.message}") }
    }

    override fun remove(id: String) {
        val e = nodes.remove(id) ?: return
        (e.view.parent as? ViewGroup)?.removeView(e.view)
        (e.strip?.parent as? ViewGroup)?.removeView(e.strip)
        nodes.values.filter { it.parent == id }.map { it.id }.forEach { remove(it) }
        e.list?.let { l -> lists[e.source]?.remove(l) }
    }

    override fun navigate(screen: String, transition: String) {
        closeDetails()
        if (screen != "mp") for (st in serverListStates.values) st.select("") // a chosen row does not outlive its screen
        val e = nodes[screen] ?: return
        (e.view.parent as? ViewGroup)?.removeView(e.view)
        bindSafe(e) // the back arrow follows app.canBack
        host.showScreen(e.view, screen, transition)
        // a page opened from another starts at its top; one returned to (back) is where it was left
        if (transition == "push") { saved.remove(screen); e.scroller?.post { e.scroller?.scrollTo(0, 0) } } else restoreScroll(e)
    }

    override fun dialog(id: String?) {
        detailsOpen = false
        if (id == null) { host.showDialog(null); return }
        val e = nodes[id] ?: return
        (e.view.parent as? ViewGroup)?.removeView(e.view)
        host.showDialog(dialogFrame(e.view, e.props.bool("dismissable", true)))
    }

    override fun toast(text: String, kind: String) = host.toast(text, kind)

    override fun listRows(source: String, offset: Int, total: Int, rows: List<Map<String, Any?>>, rev: Long) {
        // The server browser is merged, never replaced: a refresh keeps the rows on
        // screen (dimmed until they answer) and updates them by address.
        if (isServerSource(source)) {
            // A zero-row window is a legitimate answer ("this search matches nothing")
            // and must not be confused with a source notice: re-asking on it loops.
            serverState(source).accept(offset, total, rows, rev)
            flushServers(source)
            if (rowsLogged.merge(source, 1, Int::plus)!! <= 60) // timing evidence (tests/srvlist/emu_card.py)
                Log.i("Godnotic", "Native UI: $source rows offset=$offset n=${rows.size} total=$total known=${serverState(source).known} drawn=${serverState(source).size}")
            return
        }
        for (l in lists[source].orEmpty()) {
            if (rows.isEmpty() && offset == 0) l.reset(total) else l.rows(offset, total, rows)
            nodes[l.id]?.let { bindEmpty(it) }
        }
    }

    /**
     * The source has a new revision. Cached rows stay on screen; publishing them alone
     * never asks the engine for the new ones, so every list re-reads its visible window.
     */
    override fun sourceChanged(source: String, total: Int, rev: Long) {
        if (!isServerSource(source)) {
            for (l in lists[source].orEmpty()) l.reset(total)
            for (l in lists[source].orEmpty()) nodes[l.id]?.let { bindEmpty(it) }
            return
        }
        val state = serverState(source)
        state.begin(total, rev)
        flushServers(source)
        if (rowsLogged.merge(source, 1, Int::plus)!! <= 60)
            Log.i("Godnotic", "Native UI: $source notice total=$total known=${state.known} lists=${lists[source].orEmpty().size}")
        for (l in lists[source].orEmpty()) l.reask()
        // Rows the list has never been sent (a cold list: only the visible window comes
        // with reask) are fetched whole, in big windows and at most once a second: the
        // engine sends four notices a second while it refreshes, and a request per
        // notice for every 32 rows flooded the UI thread on a real 500-server list (ANR).
        val list = lists[source]?.firstOrNull()
        val props = list?.let { nodes[it.id]?.props }
        if (list != null && props != null) {
            val now = android.os.SystemClock.elapsedRealtime()
            val offsets = ServerListState.fillOffsets(state.known, total, now - (fillAt[source] ?: -ServerListState.FILL_EVERY_MS), ServerListState.FILL_WINDOW)
            if (offsets.isNotEmpty()) {
                fillAt[source] = now
                for (offset in offsets)
                    events.window(list.id, source, offset, minOf(ServerListState.FILL_WINDOW, total - offset),
                        props.str("sort"), props.bool("desc"), props.str("filter"))
            }
        }
    }

    private val fillAt = HashMap<String, Long>()
    private val rowsLogged = HashMap<String, Int>()

    /** The width pictures that must be there in the first frame are decoded and cached at. */
    private val CARD_PX = 1024
    private val WARM_ROWS = 40
    private val WARM_MAPS = 24

    /**
     * Copy cached rows into every list reading this source, including newly
     * opened lists. Never settles: a source notice (the engine sends one per
     * change, four a second) is not the end of the query, and settling here
     * dimmed then removed rows that had simply not been re-sent yet.
     */
    private val firstRowsLogged = HashSet<String>()

    private fun flushServers(source: String) {
        val state = serverState(source)
        if (state.size > 0 && lists[source].orEmpty().isNotEmpty() && firstRowsLogged.add(source))
            Log.i("Godnotic", "Native UI: $source list holds ${state.size} rows (first rows handed to the list)")
        for (map in PicturePolicy.warmMaps(state.firstMaps(WARM_ROWS), WARM_MAPS)) prefetch("pk3:maps/$map")
        for (l in lists[source].orEmpty()) {
            state.publish { offset, total, rows -> l.rows(offset, total, rows) }
            nodes[l.id]?.let { bindEmpty(it) }
            showServerCount(l.id, source)
        }
    }

    override fun imageReady(ref: String, path: String) {
        imagePaths[ref] = path
        if (path.isNotEmpty() && memo.put(ref, path)) saveMemo()
        imageWaiters.remove(ref)?.forEach { it(path) }
    }

    /** Picture files the engine gave earlier (this session or the last): a card draws its picture in its first frame. */
    private val memoFile = java.io.File(ctx.cacheDir, "fe-imgpaths")
    private val memo: ImagePathMemo = try { if (memoFile.isFile) ImagePathMemo.parse(memoFile.readText()) else ImagePathMemo() } catch (e: Exception) { ImagePathMemo() }
    private val memoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var memoSaving = false
    private fun saveMemo() {
        if (memoSaving) return
        memoSaving = true
        memoHandler.postDelayed({
            memoSaving = false
            val text = memo.serialize()
            Thread { try { memoFile.writeText(text) } catch (e: Exception) { /* a convenience */ } }.start()
        }, 1500)
    }

    /** The file for a picture: what the engine answered, else what it answered last time if that file is still there. */
    private fun knownPath(ref: String): String? {
        imagePaths[ref]?.let { return it }
        val old = memo.get(ref) ?: return null
        if (java.io.File(old).isFile) { imagePaths[ref] = old; return old }
        memo.forget(ref)
        return null
    }

    // The maps pictured last time are decoded in the background at start: choosing a server before its row has been seen this session still has its picture ready.
    init {
        for ((ref, path) in memo.recent("pk3:maps/", 12)) if (java.io.File(path).isFile) Images.load(path, CARD_PX) { }
    }

    fun saveScroll(screen: String) {
        val e = nodes[screen] ?: return
        val m = saved.getOrPut(screen) { HashMap() }
        e.scroller?.let { m["body"] = it.scrollY }
        for (l in lists.values.flatten()) if (isUnder(l.id, screen)) m[l.id] = l.save()
    }

    private fun restoreScroll(e: Entry) {
        val m = saved[e.id] ?: return
        (m["body"] as? Int)?.let { y -> e.scroller?.post { e.scroller?.scrollTo(0, y) } }
        for (l in lists.values.flatten()) if (isUnder(l.id, e.id)) l.restore(m[l.id] as? Parcelable)
    }

    private fun isUnder(id: String, root: String): Boolean {
        var cur: String? = id
        while (cur != null) { if (cur == root) return true; cur = nodes[cur]?.parent }
        return false
    }

    // ------------------------------------------------------------------ build

    private fun build(id: String, type: String, props: Map<String, Any?>): Entry = when (type) {
        "screen" -> screen(id, props)
        "section" -> section(id, props)
        "toolbar" -> group(id, type, props, AdaptiveBar(ctx, dp(10f), dp(360f)).apply { setPadding(0, dp(4f), 0, dp(4f)) })
        "actionbar" -> if (id == SERVER_BAR) serverBar(id, type, props) else group(id, type, props, Kit.row(ctx, 12f).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        "row" -> if (props.bool("wrap")) group(id, type, props, FlowGrid(ctx, 0, dp(10f), wrap = true)) else labelledRow(id, props, Kit.row(ctx, 12f).apply {
            gravity = when (props.str("align")) { "center" -> Gravity.CENTER; "end" -> Gravity.END or Gravity.CENTER_VERTICAL; else -> Gravity.START or Gravity.CENTER_VERTICAL }
        })
        "split" -> split(id, props)
        "form", "pane", "column" -> group(id, type, props, Kit.column(ctx, 12f))
        "grid" -> grid(id, props)
        "tabs" -> tabs(id, props)
        "tabbar" -> tabbar(id, props)
        "card" -> card(id, props, hero = false)
        "hero" -> card(id, props, hero = true)
        "list" -> list(id, props)
        // the chosen server's info is the "i" dialog, not a card docked over the list
        "preview" -> preview(id, props)
        "text" -> text(id, props)
        "image" -> image(id, props)
        "divider" -> Entry(id, type, View(ctx).apply { setBackgroundColor(Theme.OUTLINE) }, null, props)
        "status" -> status(id, props)
        "progress" -> progress(id, props)
        "switch" -> switchRow(id, props)
        "slider" -> sliderRow(id, props)
        "stepper" -> stepper(id, props)
        "choice" -> choice(id, props)
        "field" -> field(id, props)
        "button" -> button(id, props)
        "colour" -> colourRow(id, props)
        else -> generic(id, type, props)
    }

    private fun layoutFor(g: ViewGroup, child: Entry, fill: Boolean): ViewGroup.LayoutParams {
        if (fill && g is LinearLayout) return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        if (child.type == "divider") return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1f)))
        if (g is AdaptiveBar) return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // a field in a bar takes what the buttons beside it leave (it used to be 380 dp wide, which squeezed Join to a sliver)
        if (g is LinearLayout && g.orientation == LinearLayout.HORIZONTAL && child.type == "field")
            return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (g is LinearLayout && g.orientation == LinearLayout.HORIZONTAL) {
            val grow = child.type == "card" || child.type == "hero" || child.type == "row" || child.type == "tab" || child.props.str("width") == "fill"
            return LinearLayout.LayoutParams(if (grow) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (grow) 1f else 0f)
        }
        if (g is LinearLayout) return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun title(p: Map<String, Any?>) = p.str("title").ifEmpty { p.str("label") }

    /** Invokes an action object: native link/share/copy, a confirm first if asked, then the event. */
    private fun invoke(node: String, a: Map<String, Any?>) {
        if (a.isEmpty() || (a.containsKey("enabled") && !a.bool("enabled"))) return
        val go = {
            if (a.str("kind") == "back") events.back()
            else if (!host.platformAction(node, a)) events.action(node, a)
        }
        val c = a["confirm"].obj()
        if (c.isNotEmpty()) host.confirm(c, go) else go()
    }

    private fun actionButton(node: String, a: Map<String, Any?>, primaryDefault: Boolean = false): TextView {
        val style = a.str("style")
        val b = Kit.button(ctx, a.str("label"), style == "primary" || (style.isEmpty() && primaryDefault), a.str("icon")) { invoke(node, a) }
        val teamColor = when (style) {
            "team-red" -> 0xFF8B2635.toInt()
            "team-blue" -> 0xFF245398.toInt()
            "team-yellow" -> 0xFF8B7020.toInt()
            "team-pink" -> 0xFF8B357C.toInt()
            else -> null
        }
        if (teamColor != null) b.background = Theme.rounded(teamColor, dp(24f).toFloat())
        if (style == "destructive") b.setTextColor(Theme.BAD)
        b.isEnabled = !a.containsKey("enabled") || a.bool("enabled")
        b.contentDescription = a.str("label")
        return b
    }

    private fun screen(id: String, props: Map<String, Any?>): Entry {
        val root = Kit.column(ctx)
        root.setBackgroundColor(Theme.BG)
        val pad = dp(24f)
        // the header: back, title, the screen's actions; sticky (outside the scroller)
        val bar = Kit.row(ctx, 12f)
        bar.setPadding(pad, dp(14f), pad, dp(10f))
        bar.minimumHeight = dp(76f)
        val back = Kit.button(ctx, "‹") { events.back() }
        back.textSize = Theme.SP_HEADLINE
        back.minimumWidth = dp(56f)
        back.contentDescription = "Back"
        val titleV = Kit.text(ctx, "", Theme.SP_HEADLINE, Theme.TEXT, bold = true, lines = 1)
        titleV.typeface = Theme.display
        val sub = Kit.text(ctx, "", Theme.SP_CAPTION, Theme.TEXT_MUTED, lines = 1)
        val titles = Kit.column(ctx)
        titles.gravity = Gravity.CENTER_VERTICAL
        titles.addView(titleV)
        titles.addView(sub)
        val trailing = Kit.row(ctx, 8f)
        bar.addView(back)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(trailing)
        root.addView(bar)
        val top = Kit.column(ctx, 8f)
        top.setPadding(pad, 0, pad, dp(8f))
        top.visibility = View.GONE
        root.addView(top)
        val body = FitBody(ctx, dp(18f))
        body.setPadding(pad, dp(8f), pad, dp(DialogChrome.PAGE_BOTTOM_DP)) // the last item scrolls fully clear of the bar
        val stickyHost = FrameLayout(ctx)
        val sv = PageScroll(ctx)
        sv.isFillViewport = true
        sv.isVerticalScrollBarEnabled = true
        sv.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        sv.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        sv.addView(body)
        stickyHost.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val pinned = Kit.text(ctx, "", Theme.SP_TITLE, Theme.BLUE, bold = true)
        pinned.setBackgroundColor(0xF2000000.toInt())
        pinned.setPadding(pad + dp(4f), dp(8f), pad, dp(8f))
        pinned.visibility = View.GONE
        stickyHost.addView(pinned, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        // a list scrolled to the top sits under the pinned section title
        pinned.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        sv.topInset = pinned.measuredHeight
        root.addView(stickyHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val fill = FrameLayout(ctx)
        fill.visibility = View.GONE
        root.addView(fill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = Kit.row(ctx, 12f)
        bottom.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        bottom.setPadding(pad, dp(10f), pad, dp(12f))
        bottom.setBackgroundColor(DialogChrome.PAGE_ACTION_BAR)
        bottom.visibility = View.GONE
        root.addView(bottom)
        val e = Entry(id, "screen", root, body, props)
        e.bar = bar; e.titles = titles
        e.top = top; e.bottom = bottom; e.fill = fill; e.scroller = sv; e.body = body
        // sticky section headers and the collapsing header
        sv.setOnScrollChangeListener { _, _, y, _, _ ->
            stickySection(e, pinned, y)
            if (e.props["header"].obj().bool("collapsible")) {
                val compact = y > dp(48f)
                titleV.textSize = if (compact) Theme.SP_TITLE else Theme.SP_HEADLINE
                sub.visibility = if (compact || sub.text.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        e.bind = { x ->
            val p = x.props
            titleV.text = p.str("title")
            sub.text = p.str("subtitle")
            sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
            val kindHome = p.str("kind") == "home"
            // a dialog or sheet screen sizes to its content inside the dialog box
            val modal = DialogChrome.isModal(p.str("presentation"))
            // a dialog's action row is part of the dialog (no darker band inside the card)
            bottom.setBackgroundColor(DialogChrome.actionBarFill(p.str("presentation")))
            (stickyHost.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.height = if (modal) ViewGroup.LayoutParams.WRAP_CONTENT else 0; lp.weight = if (modal) 0f else 1f; stickyHost.layoutParams = lp
            }
            (sv.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> lp.height = if (modal) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT; sv.layoutParams = lp }
            if (modal) { bar.setPadding(dp(8f), 0, dp(8f), dp(4f)); body.setPadding(dp(8f), dp(4f), dp(8f), dp(8f)); bottom.setPadding(dp(8f), dp(8f), dp(8f), dp(4f)) }
            // Dim only a paused local game; an online match remains visible.
            root.setBackgroundColor(if (p.str("kind") == "game") { MenuBackdrop.color(p.bool("menu_paused")) } else if (modal) Color.TRANSPARENT else Theme.BG)
            // Home has no header (the banner is its title): the space goes to the banner and cards
            bar.visibility = if (p["header"].obj().bool("hidden") || (kindHome && p["actions"].list().isEmpty())) View.GONE else View.VISIBLE
            body.fit = kindHome && !modal
            // Home's cards: the outer margins are the gap between the cards (16 dp)
            if (kindHome && !modal) body.setPadding(dp(16f), body.paddingTop, dp(16f), body.paddingBottom)
            body.requestLayout()
            titleV.visibility = if (kindHome) View.INVISIBLE else View.VISIBLE // Home shows the banner instead
            back.visibility = if (host.canBack() && !kindHome && !modal) View.VISIBLE else View.GONE
            if (p["header"].obj().bool("compact")) titleV.textSize = Theme.SP_TITLE
            // over the game (a scrim) the engine's console button shows through: the
            // header's actions keep clear of its rect plus 8 dp (docs/UI_BRIDGE.md 4.12)
            var endPad = pad
            if (p.str("kind") == "game") for (r in host.reserved()) {
                val wdp = ctx.resources.displayMetrics.widthPixels / ctx.resources.displayMetrics.density
                if (r.num("y") < 72.0) endPad = maxOf(endPad, dp((wdp - r.num("x")).toFloat() + 8f))
            }
            bar.setPadding(bar.paddingLeft, bar.paddingTop, endPad, bar.paddingBottom)
            trailing.removeAllViews()
            // header actions are quiet (Quit must not compete with Play)
            for (a in p["actions"].list()) { val m = a.obj(); trailing.addView(Kit.quiet(ctx, m.str("label"), m.str("icon")) { invoke(id, m) }) }
            // the screen's primary action sits in the bottom bar when there is no actionbar node
            bottom.findViewWithTag<View>("primary")?.let { bottom.removeView(it) }
            val prim = p["primary"].obj()
            if (prim.isNotEmpty()) bottom.addView(actionButton(id, prim, true).apply { tag = "primary" })
            bottom.visibility = if (bottom.childCount == 0) View.GONE else View.VISIBLE
            top.visibility = if (top.childCount == 0) View.GONE else View.VISIBLE
            // A tall dialog scrolls inside the card with its action row still in view: the card cannot be
            // taller than the room it has (the screen, less the keyboard), so the scroller leaves the row's
            // height free (otherwise the row, e.g. Unlock or Close, is pushed out of the card).
            sv.reservePx = if (modal && bottom.visibility == View.VISIBLE) dp(DialogChrome.ACTION_ROW_DP) else 0
            // a scrolled dialog body fades out at its edges instead of cutting a button in half
            sv.isVerticalFadingEdgeEnabled = true
            sv.setFadingEdgeLength(dp(if (modal) DialogChrome.FADE_DP else DialogChrome.PAGE_FADE_DP))
            // a settings search result names its row: bring it under the pinned tabs and
            // mark it for a moment, so the player can walk there next time
            val reveal = p.str("reveal")
            if (reveal.isNotEmpty()) sv.post {
                val target = nodes[reveal] ?: return@post
                var y = 0
                var v: View? = target.view
                while (v != null && v !== body) { y += v.top; v = v.parent as? View }
                sv.smoothScrollTo(0, maxOf(0, y - sv.topInset - dp(8f)))
                val flash = android.graphics.drawable.ColorDrawable(0x552E7BFF.toInt())
                target.view.foreground = flash
                target.view.postDelayed({ if (target.view.foreground === flash) target.view.foreground = null }, 1200)
            }
        }
        return e
    }


    /**
     * Settings search: an icon in the title bar. Tapping it swaps the title for the field
     * (focused, keyboard up); the X clears the query, closes the field and returns to the
     * categories. Open while a query exists, so a rebuild keeps it open.
     */
    private fun headerSearch(screen: Entry, field: Entry) {
        val bar = screen.bar ?: return
        val titles = screen.titles ?: return
        val input = field.view.tag as? android.widget.EditText
        val host = Kit.row(ctx, 4f)
        host.gravity = Gravity.CENTER_VERTICAL
        val open = Kit.quiet(ctx, "", "search") { }
        open.contentDescription = "Search settings"
        val close = Kit.quiet(ctx, "", "close") { }
        close.contentDescription = "Close search"
        (field.view.parent as? ViewGroup)?.removeView(field.view)
        field.view.visibility = View.GONE
        host.addView(field.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        host.addView(close)
        host.addView(open)
        val trailingIdx = bar.childCount - 1
        bar.addView(host, trailingIdx, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        var shown = true // forced to closed below
        fun show(on: Boolean, focus: Boolean) {
            if (on == shown && !focus) return
            shown = on
            titles.visibility = if (on) View.GONE else View.VISIBLE
            field.view.visibility = if (on) View.VISIBLE else View.GONE
            close.visibility = if (on) View.VISIBLE else View.GONE
            open.visibility = if (on) View.GONE else View.VISIBLE
            (host.layoutParams as LinearLayout.LayoutParams).apply { width = if (on) 0 else ViewGroup.LayoutParams.WRAP_CONTENT; weight = if (on) 1f else 0f }
            host.requestLayout()
            if (on && focus && input != null) input.post {
                input.requestFocus()
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.showSoftInput(input, 0)
            }
            if (!on && input != null) {
                input.clearFocus()
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
            }
        }
        open.setOnClickListener { Kit.haptic(it); show(true, true) }
        close.setOnClickListener {
            Kit.haptic(it)
            input?.setText("")
            field.props = field.props + ("value" to "")
            events.value(field.id, "")
            show(false, false)
        }
        val inner = field.bind
        field.bind = { x -> inner?.invoke(x); if (x.props.str("value").isNotEmpty()) show(true, false) }
        show(false, false)
        bindSafe(field)
    }

    /** The last sticky section whose header scrolled above the top stays pinned; the next one pushes it up. */
    private fun stickySection(screen: Entry, pinned: TextView, y: Int) {
        val body = screen.body ?: return
        var current: Entry? = null
        var nextTop = Int.MAX_VALUE
        for (i in 0 until body.childCount) {
            val v = body.getChildAt(i)
            val e = nodes.values.firstOrNull { it.view === v && it.type == "section" && it.props.bool("sticky") } ?: continue
            if (v.top <= y) current = e else { nextTop = minOf(nextTop, v.top - y); break }
        }
        if (current == null || current.view.bottom - y < pinned.height) { pinned.visibility = View.GONE; return }
        pinned.text = title(current.props)
        pinned.visibility = View.VISIBLE
        pinned.translationY = minOf(0, nextTop - pinned.height).toFloat()
    }

    /** A list in a screen: the body stops scrolling as a whole and the list takes the space left. */
    private fun switchToFill(screen: Entry) {
        val sv = screen.scroller ?: return
        val body = screen.body ?: return
        val fill = screen.fill ?: return
        if (body.parent === sv) {
            sv.removeView(body)
            (sv.parent as? View)?.visibility = View.GONE
            fill.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fill.visibility = View.VISIBLE
            body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, dp(8f))
        }
    }

    /**
     * A list inside a scrolling page (the map picker in Create, a short list
     * in a section): not the page's scroller, so cards become one horizontal
     * row (a picker strip) and a table shows up to eight rows and scrolls
     * inside its frame.
     */
    private fun nestList(e: Entry) {
        val vl = e.list ?: return
        val lp = e.view.layoutParams
        if (e.props.str("display") == "cards" && fitRows(e.props) > 0) {
            // `rows: N` in a scrolling page: N rows that scroll sideways, as
            // tall as the page's visible area under the pinned section title,
            // so scrolled to them all N rows show whole
            val frame = (vl.view.layoutParams as? LinearLayout.LayoutParams)
            frame?.let { it.weight = 0f; it.height = ctx.resources.displayMetrics.heightPixels / 2; vl.view.layoutParams = it }
            lp?.height = ViewGroup.LayoutParams.WRAP_CONTENT
            e.view.layoutParams = lp
            var top: Entry? = e
            while (top != null && top.type != "screen") top = top.parent?.let { nodes[it] }
            top?.scroller?.addOnLayoutChangeListener { sv, _, t, _, b, _, _, _, _ ->
                val h = b - t - sv.paddingTop - sv.paddingBottom - ((sv as? PageScroll)?.topInset ?: 0)
                val l = vl.view.layoutParams
                if (h > dp(120f) && l != null && l.height != h) { l.height = h; vl.view.post { vl.view.layoutParams = l } }
            }
            return
        }
        if (e.props.str("display") == "cards") {
            val w = dp(e.props.num("minItem", 220.0).toFloat()) + dp(14f)
            vl.carousel(w)
            (vl.view.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = ViewGroup.LayoutParams.WRAP_CONTENT; it.weight = 0f; vl.view.layoutParams = it }
            lp?.height = ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            val rows = minOf(8, maxOf(3, e.props.int("count", 5)))
            vl.view.isNestedScrollingEnabled = true
            lp?.height = dp(36f) + rows * dp(if (e.props.str("rowHeight") == "comfortable") 56f else 40f) + dp(2f)
        }
        e.view.layoutParams = lp
    }

    private fun fitRows(p: Map<String, Any?>) = listOf(p.int("rows"), p["style"].obj().int("rows"), p["hints"].obj().int("rows")).firstOrNull { it > 0 } ?: 0

    private fun fillChain(list: Entry) {
        var cur: Entry? = list
        while (cur != null) {
            val parent = cur.parent?.let { nodes[it] } ?: break
            (cur.view.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = 0; it.weight = 1f; cur.view.layoutParams = it }
            (cur.view.layoutParams as? FrameLayout.LayoutParams)?.let { it.height = ViewGroup.LayoutParams.MATCH_PARENT; cur.view.layoutParams = it }
            if (parent.type == "screen") { switchToFill(parent); break }
            cur = parent
        }
    }

    private fun section(id: String, props: Map<String, Any?>): Entry {
        val box = Kit.column(ctx, 8f)
        val head = Kit.row(ctx, 12f)
        val titleV = Kit.text(ctx, "", Theme.SP_TITLE, Theme.BLUE, bold = true)
        titleV.typeface = Theme.display
        val link = Kit.text(ctx, "", Theme.SP_BODY, Theme.EMBER, bold = true)
        link.minHeight = dp(Kit.MIN_TARGET_DP)
        link.gravity = Gravity.CENTER_VERTICAL
        link.isClickable = true
        head.addView(titleV, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(link)
        val sub = Kit.text(ctx, "", Theme.SP_CAPTION, Theme.TEXT_MUTED)
        val content = Kit.column(ctx, 10f)
        val foot = Kit.text(ctx, "", Theme.SP_CAPTION, Theme.TEXT_DIM)
        box.addView(head); box.addView(sub); box.addView(content); box.addView(foot)
        val e = Entry(id, "section", box, content, props)
        var collapsed = props.bool("collapsed")
        titleV.setOnClickListener { if (e.props.bool("collapsible")) { collapsed = !collapsed; content.visibility = if (collapsed) View.GONE else View.VISIBLE } }
        e.bind = { x ->
            val p = x.props
            titleV.text = title(p)
            head.visibility = if (titleV.text.isEmpty()) View.GONE else View.VISIBLE
            sub.text = p.str("subtitle"); sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
            foot.text = p.str("footer"); foot.visibility = if (foot.text.isEmpty()) View.GONE else View.VISIBLE
            val l = p["link"].obj()
            link.text = if (l.isEmpty()) "" else l.str("label") + "  ›"
            link.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
            link.setOnClickListener { invoke(id, l) }
            content.visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        return e
    }

    private fun group(id: String, type: String, props: Map<String, Any?>, g: ViewGroup) = Entry(id, type, g, g, props)

    private val SERVER_BAR = "mp/join"
    private var infoButton: View? = null

    /**
     * Multiplayer's bottom bar (address, Create, Join) with an "i" after Join: it opens the chosen
     * server's info dialog and is off while no server is chosen. The engine's children go in the
     * inner row, so their indices are untouched by the button.
     */
    private fun serverBar(id: String, type: String, props: Map<String, Any?>): Entry {
        val outer = Kit.row(ctx, 12f).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        // start gravity: with END, LinearLayout's weighted pass leaves the dividers out of the
        // content length and shifts the row by them, which cut Join off at the right edge
        val inner = Kit.row(ctx, 12f)
        outer.addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val info = Kit.button(ctx, "", false, "info") { openChosenDetails() }.apply {
            minimumWidth = dp(56f)
            val h = dp(14f)
            setPadding(h, paddingTop, h, paddingBottom)
            contentDescription = "Server info"
            tag = "server-info"
        }
        outer.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        infoButton = info
        updateInfoButton()
        return Entry(id, type, outer, inner, props)
    }

    private fun chosenServer(): String = nodes["mp/servers"]?.let { serverState(it.source).selected } ?: ""

    /** The "i" is on exactly while a server is chosen. */
    private fun updateInfoButton() {
        val b = infoButton ?: return
        val on = chosenServer().isNotEmpty()
        b.isEnabled = on
        b.alpha = if (on) 1f else 0.4f
    }

    /** The bottom bar's "i": the chosen server's info dialog again (closing it kept the choice). */
    private fun openChosenDetails() {
        val e = nodes["mp/servers"] ?: return
        val st = serverState(e.source)
        val row = st.rowOf(st.selected) ?: return
        showServerDetails(row)
    }

    /**
     * A row with a `label` (the LAN popup's "Wi-Fi", "VPN (tailscale0)", "Link"):
     * the label with its icon in a fixed column, then the children.
     */
    private fun labelledRow(id: String, props: Map<String, Any?>, inner: LinearLayout): Entry {
        val outer = Kit.row(ctx, 16f)
        outer.gravity = Gravity.CENTER_VERTICAL
        val label = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT_MUTED, bold = true, lines = 2)
        label.compoundDrawablePadding = dp(8f)
        val spread = props.str("align") == "spread"
        outer.addView(label, LinearLayout.LayoutParams(dp(if (spread) 120f else 200f), ViewGroup.LayoutParams.WRAP_CONTENT))
        // spread: the first child (the address) takes the slack, so the buttons end in one column
        if (spread) inner.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) {
                if (inner.childCount == 1) child.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            override fun onChildViewRemoved(parent: View, child: View) {}
        })
        outer.addView(inner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val e = Entry(id, "row", outer, inner, props)
        e.bind = { x ->
            val t = x.props.str("label")
            label.text = t
            label.setCompoundDrawablesRelative(Icons.drawable(x.props.str("icon"), dp(20f), Theme.TEXT_MUTED), null, null, null)
            label.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
            label.contentDescription = t
        }
        return e
    }

    /** Cards in as many columns as fit at minItem dp; scroll "horizontal" makes a carousel. */
    private fun grid(id: String, props: Map<String, Any?>): Entry {
        val gap = dp(16f)
        if (props.str("scroll") == "horizontal") {
            val hs = HorizontalScrollView(ctx)
            hs.isHorizontalScrollBarEnabled = false
            hs.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            val row = CarouselRow(ctx, dp(props.num("minItem", 260.0).toFloat()), gap)
            hs.addView(row)
            return Entry(id, "grid", hs, row, props)
        }
        val g = FlowGrid(ctx, dp(props.num("minItem", 160.0).toFloat()), gap)
        val e = Entry(id, "grid", g, g, props)
        // Home's cards: always side by side in one row, whatever the width (the
        // owner's rule); they shrink, and the description joins two when three
        // do not fit (`compact`)
        e.bind = { x -> val one = screenKind(x) == "home"; if (g.singleRow != one) { g.singleRow = one; g.requestLayout() } }
        return e
    }

    private fun tabs(id: String, props: Map<String, Any?>): Entry {
        val panes = FrameLayout(ctx)
        val holder = FrameLayout(ctx)
        val e = Entry(id, "tabs", panes, panes, props)
        e.strip = holder
        e.bind = { x -> tabsBind(x, holder) }
        return e
    }

    private fun tabsAdd(tabs: Entry, pane: Entry) {
        tabs.children?.addView(pane.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bindSafe(tabs)
    }

    private fun tabsBind(x: Entry, holder: FrameLayout) {
        val sel = x.props.str("value")
        val list = nodes.values.filter { it.parent == x.id && it.type == "pane" }.sortedBy { (x.children)?.indexOfChild(it.view) ?: 0 }
        holder.removeAllViews()
        val opts = list.map { it.props.str("key") to Icons.label(it.props.str("icon"), it.props.str("label")) }
        // sub-pages: a strip that scrolls sideways when it does not fit
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(Kit.segmented(ctx, opts, sel) { v ->
            x.props = x.props + ("value" to v)
            for (p in list) p.view.visibility = if (p.props.str("key") == v) View.VISIBLE else View.GONE
            events.value(x.id, v)
        })
        holder.addView(hs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (p in list) p.view.visibility = if (p.props.str("key") == sel || (sel.isEmpty() && p === list.first())) View.VISIBLE else View.GONE
    }

    /**
     * List-detail (Settings: vertical tabs). The section list on the start
     * side scrolls on its own and stays put; the selected section's content
     * scrolls on the end side. On a compact width the list keeps only its icons.
     */
    private fun split(id: String, props: Map<String, Any?>): Entry {
        val row = Kit.row(ctx, 20f)
        row.gravity = Gravity.TOP
        val prefs = ctx.getSharedPreferences("godnotic_ui", Context.MODE_PRIVATE)
        fun landscape() = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        fun key() = if (landscape()) "settings_rail_expanded_land" else "settings_rail_expanded_port"
        // portrait starts collapsed, landscape expanded; the toggle is remembered per orientation
        var wasLandscape = landscape()
        var expanded = try { prefs.getBoolean(key(), wasLandscape) } catch (ex: RuntimeException) { wasLandscape }
        val rail = Kit.column(ctx, 6f)
        val railScroll = ScrollView(ctx)
        railScroll.isVerticalScrollBarEnabled = true
        railScroll.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        railScroll.addView(rail)
        val panes = FrameLayout(ctx)
        val paneScroll = ScrollView(ctx)
        paneScroll.isFillViewport = true
        paneScroll.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        paneScroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        paneScroll.addView(panes)
        val railLp = LinearLayout.LayoutParams(dp(76f), ViewGroup.LayoutParams.MATCH_PARENT)
        row.addView(railScroll, railLp)
        row.addView(paneScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val e = Entry(id, "split", row, panes, props)
        e.strip = null
        fun setExpanded(on: Boolean, remember: Boolean) {
            expanded = on
            if (remember) try { prefs.edit().putBoolean(key(), on).apply() } catch (ex: RuntimeException) { }
            bindSafe(e)
        }
        e.bind = { x ->
            val wdp = ctx.resources.displayMetrics.widthPixels / ctx.resources.displayMetrics.density
            railLp.width = dp(if (expanded) minOf(300f, wdp * 0.45f) else 76f)
            railScroll.layoutParams = railLp
            rail.removeAllViews()
            val sel = x.props.str("value")
            val list = nodes.values.filter { it.parent == x.id && it.type == "pane" }.sortedBy { panes.indexOfChild(it.view) }
            // the toggle: expands the rail to icon + title, or folds it to icons
            val toggle = Kit.row(ctx, 14f)
            toggle.minimumHeight = dp(48f)
            toggle.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            toggle.background = Theme.surface(ctx, Color.TRANSPARENT, 14f, Color.TRANSPARENT)
            toggle.isClickable = true; toggle.isFocusable = true
            val tIcon = View(ctx)
            tIcon.background = Icons.drawable("menu", dp(24f), Theme.TEXT_MUTED)
            toggle.addView(tIcon, LinearLayout.LayoutParams(dp(24f), dp(24f)))
            if (expanded) toggle.addView(Kit.text(ctx, "Categories", Theme.SP_CAPTION, Theme.TEXT_MUTED, bold = true, lines = 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            toggle.contentDescription = if (expanded) "Collapse categories" else "Expand categories"
            toggle.setOnClickListener { v -> Kit.haptic(v); setExpanded(!expanded, true) }
            rail.addView(toggle)
            for (p in list) {
                val on = p.props.str("key") == sel
                val item = Kit.row(ctx, 14f)
                item.minimumHeight = dp(56f)
                item.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                item.background = if (on) Theme.rounded(0xFF15306A.toInt(), dp(14f).toFloat(), dp(2f), Theme.BLUE) else Theme.surface(ctx, Color.TRANSPARENT, 14f, Color.TRANSPARENT)
                item.isClickable = true; item.isFocusable = true; item.isSelected = on
                val icon = View(ctx)
                icon.background = Icons.drawable(p.props.str("icon"), dp(24f), if (on) Theme.EMBER else Theme.BLUE)
                item.addView(icon, LinearLayout.LayoutParams(dp(24f), dp(24f)))
                if (expanded) {
                    val texts = Kit.column(ctx)
                    texts.addView(Kit.text(ctx, p.props.str("label"), Theme.SP_BODY, if (on) Color.WHITE else Theme.TEXT, bold = true, lines = 1))
                    p.props.str("subtitle").takeIf { it.isNotEmpty() }?.let { texts.addView(Kit.text(ctx, it, Theme.SP_CAPTION, Theme.TEXT_MUTED, lines = 1)) }
                    item.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                item.contentDescription = p.props.str("label") + if (on) ", selected" else ""
                item.tooltipText = p.props.str("label")
                item.setOnClickListener { v ->
                    Kit.haptic(v)
                    x.props = x.props + ("value" to p.props.str("key"))
                    // portrait: choosing a category folds the rail so the page gets the width (not remembered)
                    if (!landscape()) expanded = false
                    bindSafe(x)
                    paneScroll.scrollTo(0, 0)
                    events.value(x.id, p.props.str("key"))
                }
                rail.addView(item)
            }
            for (p in list) p.view.visibility = if (p.props.str("key") == sel || (sel.isEmpty() && p === list.firstOrNull())) View.VISIBLE else View.GONE
        }
        // a swipe on the rail: right opens it, left folds it
        val swipe = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(a: android.view.MotionEvent?, b: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (Math.abs(vx) < Math.abs(vy) * 1.5f || Math.abs(vx) < 600f) return false
                if ((vx > 0) != expanded) setExpanded(vx > 0, true)
                return true
            }
        })
        railScroll.setOnTouchListener { _, ev -> swipe.onTouchEvent(ev); false }
        // rotating: the other orientation has its own remembered choice
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (landscape() != wasLandscape) {
                wasLandscape = landscape()
                expanded = try { prefs.getBoolean(key(), wasLandscape) } catch (ex: RuntimeException) { wasLandscape }
                row.post { bindSafe(e) }
            }
        }
        return e
    }

    private fun splitAdd(split: Entry, pane: Entry) {
        split.children?.addView(pane.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bindSafe(split)
    }

    private fun aspect(p: Map<String, Any?>, def: Float): Float {
        val parts = p.str("aspect").split(':')
        if (parts.size == 2) { val w = parts[0].toFloatOrNull(); val h = parts[1].toFloatOrNull(); if (w != null && h != null && h > 0) return w / h }
        return def
    }

    /** A map or server picture (shown as a photo) rather than artwork that stands for a section. */
    private fun isPhoto(ref: String) = ref.startsWith("pk3:maps/") || ref.startsWith("/") || ref.startsWith("mod:")

    private fun card(id: String, props: Map<String, Any?>, hero: Boolean): Entry {
        if (hero) return heroCard(id, props)
        val c = Kit.card(ctx, clickable = true, radiusDp = 16f)
        // photo cards: the picture on top; section cards: an icon slot beside the text
        val img = RoundImage(ctx, 0f)
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Theme.EMBER); visibility = View.GONE
        }
        val row = Kit.row(ctx, 14f)
        row.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        row.minimumHeight = dp(88f)
        val slot = FrameLayout(ctx)
        slot.background = Theme.rounded(0xFF0F2350.toInt(), dp(14f).toFloat(), max(1, dp(1f)), 0xFF2B4A86.toInt())
        val slotIcon = View(ctx)
        slot.addView(slotIcon, FrameLayout.LayoutParams(dp(32f), dp(32f), Gravity.CENTER))
        val texts = Kit.column(ctx, 4f)
        val titleV = Kit.text(ctx, "", Theme.SP_TITLE - 1f, Theme.TEXT, bold = true, lines = 1)
        titleV.typeface = Theme.display
        titleV.maxLines = 2
        titleV.ellipsize = null
        Kit.fitWords(titleV, Theme.SP_TITLE - 1f, 13f) // "Team Deathmatch" on a narrow card: smaller, never "Dea|thmatch"
        val subtitle = Kit.text(ctx, "", Theme.SP_CAPTION + 1f, Theme.TEXT_MUTED, lines = if (id == "home/tiles/mp") CardTags.MAX_ROWS else 2) // Home's Multiplayer card: the live server list
        val badges = FlowGrid(ctx, 0, dp(6f), wrap = true)
        val meta = FlowGrid(ctx, 0, dp(12f), wrap = true)
        val actions = FlowGrid(ctx, 0, dp(10f), wrap = true)
        // Home's cards: the title, then the details as one line of equal cells (a table card: a line per server)
        val summaryV = Kit.text(ctx, "", Theme.SP_CAPTION - 2f, Theme.TEXT_MUTED, lines = 1).apply {
            minHeight = dp(CardTags.HEADER_LINE_DP); gravity = Gravity.CENTER_VERTICAL // the same line height as a chip row
        }
        val tagsBox = Kit.column(ctx, 2f)
        texts.addView(badges); texts.addView(titleV); texts.addView(summaryV); texts.addView(tagsBox); texts.addView(subtitle); texts.addView(meta); texts.addView(actions)
        row.addView(slot, LinearLayout.LayoutParams(dp(52f), dp(52f)))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chevron = View(ctx)
        chevron.background = Icons.drawable("chevron", dp(24f), Theme.TEXT_DIM)
        row.addView(chevron, LinearLayout.LayoutParams(dp(24f), dp(24f)))
        val col = CardColumn(ctx, img, bar, row)
        col.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4f)))
        col.addView(row)
        c.addView(col)
        val e = Entry(id, "card", c, null, props)
        c.setOnClickListener { invoke(id, e.props["primary"].obj()) }
        e.bind = { x ->
            val p = x.props
            val ref = p.str("image")
            val photo = ref.isNotEmpty() && isPhoto(ref)
            titleV.text = title(p)
            // words are never broken: one line for one word (autosize shrinks it), two for more
            titleV.maxLines = if (title(p).trim().contains(' ')) 2 else 1
            subtitle.text = p.str("subtitle")
            val tagged = CardTags.tagged(p)
            col.textFirst = tagged
            // Home's cards: the title always starts at the same top padding, whatever the text below it holds
            row.gravity = if (tagged) Gravity.TOP else Gravity.CENTER_VERTICAL
            // the same header on every card: title line, detail line, then the picture or the list at one height
            titleV.minHeight = if (tagged) dp(CardTags.HEADER_TITLE_DP) else 0
            row.minimumHeight = dp(if (tagged) CardTags.HEADER_DP else 88f)
            tagsBox.removeAllViews()
            val lines = CardTags.rows(p)
            if (p.str("display") == "table" || lines.isNotEmpty()) {
                summaryV.text = p.str("summary")
                // no picture to fill the card: the lines take the room, as many as its height holds,
                // across its full inner width (a TableRows measures and hides what does not fit)
                val tbl = TableRows(ctx)
                for (r in lines) tbl.addView(TagRow(ctx, r, Theme.TEXT, table = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CardTags.tableRowDp(0f))))
                tagsBox.addView(tbl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(CardTags.HEADER_GAP_DP) })
            } else {
                summaryV.text = ""
                CardTags.of(p).takeIf { it.isNotEmpty() }?.let { tagsBox.addView(TagRow(ctx, it, Theme.TEXT_MUTED)) }
            }
            summaryV.visibility = if (summaryV.text.isEmpty()) View.GONE else View.VISIBLE
            tagsBox.visibility = if (tagsBox.childCount == 0) View.GONE else View.VISIBLE
            subtitle.visibility = if (subtitle.text.isEmpty() || tagged) View.GONE else View.VISIBLE
            badges.removeAllViews()
            for (b in p["badges"].list()) badges.addView(badge(b.obj()))
            badges.visibility = if (badges.childCount == 0) View.GONE else View.VISIBLE
            meta.removeAllViews()
            for (m in p["meta"].list()) meta.addView(Kit.text(ctx, m.obj().str("text"), Theme.SP_CAPTION, Theme.TEXT, bold = true).also { Kit.icon(it, m.obj().str("icon"), Theme.BLUE, 16f) })
            meta.visibility = if (meta.childCount == 0) View.GONE else View.VISIBLE
            actions.removeAllViews()
            for (a in p["actions"].list()) actions.addView(actionButton(id, a.obj()))
            actions.visibility = if (actions.childCount == 0) View.GONE else View.VISIBLE
            // the icon slot: the bridge's icon as a vector; artwork only when there is no icon
            val icon = Icons.drawable(p.str("icon"), dp(32f), Theme.BLUE)
            slotIcon.background = icon
            slot.visibility = if (!photo && !tagged && (icon != null)) View.VISIBLE else View.GONE
            // a chevron promises somewhere to go: only on a card that has a primary action
            // (the pause page's card for someone else's server has none, and did nothing when tapped)
            val tappable = p["primary"] is Map<*, *>
            chevron.visibility = if (!tappable || photo || p.str("display") == "table" || actions.childCount > 0 || nodes[x.parent ?: ""]?.type == "grid") View.GONE else View.VISIBLE
            c.isClickable = tappable
            img.aspect = aspect(p, 16f / 9f)
            img.maxHeight = (ctx.resources.displayMetrics.heightPixels * 0.22f).toInt()
            if (id == "home/tiles/mp") img.onShown = { Log.i("Godnotic", "Native UI: home card picture shown $ref") }
            if (photo) { img.visibility = View.VISIBLE; picture(img, ref, sync = true) } else img.visibility = View.GONE
            if (id == "home/tiles/mp") Log.i("Godnotic", "Native UI: home card rows=${if (p.str("subtitle").contains(" · ")) p.str("subtitle").count { it == '\n' } + 1 else 0} image=$ref picture=${img.hasPicture}")
            val prog = p.num("progress", -1.0)
            bar.visibility = if (prog >= 0) View.VISIBLE else View.GONE
            if (prog >= 0) bar.progress = (prog * 1000).toInt()
            c.contentDescription = listOf(title(p), p.str("subtitle")).filter { it.isNotEmpty() }.joinToString(". ")
            c.isSelected = p.bool("selected")
            c.isEnabled = !p.containsKey("enabled") || p.bool("enabled")
        }
        return e
    }

    /**
     * The hero (Play): a fixed-height band, the map's picture at a sensible
     * size on the right (centre-cropped, never stretched across the card) under
     * a scrim that fades into the navy on the left, where the title, the facts
     * and the buttons sit on one or two rows.
     */
    private fun heroCard(id: String, props: Map<String, Any?>): Entry {
        val c = Kit.card(ctx, clickable = true, radiusDp = 24f)
        val h = (ctx.resources.displayMetrics.heightPixels * 0.30f).toInt()
        val picW = h * 16 / 9
        val stack = FrameLayout(ctx)
        val img = RoundImage(ctx, 0f)
        stack.addView(img, FrameLayout.LayoutParams(picW, h, Gravity.END))
        img.fadeInto = Theme.PANEL
        val texts = Kit.column(ctx, 12f)
        texts.gravity = Gravity.CENTER_VERTICAL
        texts.setPadding(dp(28f), dp(16f), dp(16f), dp(16f))
        val titleV = Kit.text(ctx, "", Theme.SP_DISPLAY, Theme.TEXT, bold = true, lines = 1)
        titleV.typeface = Theme.display
        val subtitle = Kit.text(ctx, "", Theme.SP_TITLE, Theme.TEXT_MUTED, lines = 1)
        val meta = FlowGrid(ctx, 0, dp(18f), wrap = true)
        val actions = FlowGrid(ctx, 0, dp(12f), wrap = true)
        texts.addView(titleV); texts.addView(subtitle); texts.addView(meta); texts.addView(actions)
        stack.addView(texts, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { marginEnd = picW / 2 })
        c.addView(stack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
        val e = Entry(id, "hero", c, null, props)
        c.setOnClickListener { invoke(id, e.props["primary"].obj()) }
        e.bind = { x ->
            val p = x.props
            titleV.text = title(p)
            meta.removeAllViews()
            for (m in p["meta"].list()) meta.addView(Kit.text(ctx, m.obj().str("text"), Theme.SP_BODY, Theme.TEXT, bold = true).also { Kit.icon(it, m.obj().str("icon"), Theme.BLUE, 20f) })
            meta.visibility = if (meta.childCount == 0) View.GONE else View.VISIBLE
            // the facts or the subtitle, not both (they say the same)
            subtitle.text = if (meta.childCount > 0) "" else p.str("subtitle")
            subtitle.visibility = if (subtitle.text.isEmpty()) View.GONE else View.VISIBLE
            actions.removeAllViews()
            val prim = p["primary"].obj()
            if (prim.isNotEmpty()) actions.addView(actionButton(id, prim, true).apply { minimumWidth = dp(200f) })
            for (a in p["actions"].list()) actions.addView(actionButton(id, a.obj()))
            val ref = p.str("image")
            if (ref.isEmpty()) img.none() else picture(img, ref, sync = true)
            c.contentDescription = listOf(title(p), p.str("subtitle")).filter { it.isNotEmpty() }.joinToString(". ")
        }
        return e
    }

    /**
     * A map row's download state (`maps` cells `state`, `progress`; docs/DATA_FETCH.md):
     * the label its tile shows (null: it can be played) and the bar's fraction while it
     * downloads (0 while it waits its turn).
     */
    private fun mapState(cells: Map<String, Any?>): Pair<String?, Double?> {
        val progress = cells.num("progress", -1.0)
        return when (cells.str("state")) {
            "downloading" -> if (progress >= 0) Pair("${(progress * 100).toInt()}%", progress.coerceAtMost(1.0)) else Pair("Downloading", 0.0)
            "missing" -> Pair("Not downloaded", null)
            else -> Pair(null, null)
        }
    }

    private fun badge(b: Map<String, Any?>): TextView = Kit.text(ctx, b.str("text"), Theme.SP_CAPTION, toneColor(b.str("tone")), bold = true).apply {
        Kit.icon(this, b.str("icon"), toneColor(b.str("tone")), 16f)
        compoundDrawablePadding = dp(4f)
        val h = dp(8f)
        setPadding(h, dp(3f), h, dp(3f))
        background = Theme.rounded(0xFF13254D.toInt(), dp(10f).toFloat(), max(1, dp(1f)), 0xFF2B4A86.toInt())
    }

    private fun screenKind(e: Entry): String {
        var cur: Entry? = e
        while (cur != null) { if (cur.type == "screen") return cur.props.str("kind"); cur = cur.parent?.let { nodes[it] } }
        return ""
    }

    private fun toneColor(t: String) = when (t) { "positive" -> Theme.GOOD; "danger" -> Theme.BAD; "warning" -> Theme.WARN; "accent" -> Theme.EMBER; else -> Theme.TEXT }

    /**
     * Asks for a picture once (through the host's bridge), fills every view waiting on it.
     * [sync]: a picture the screen's first frame must have (a card, the selection card): when
     * its file is known it is decoded now and no placeholder is drawn before it.
     */
    fun picture(img: RoundImage, ref: String, sync: Boolean = false, blocking: Boolean = true) {
        val hadPicture = img.tag != null // a card whose map changes keeps the old picture until the new one is there
        img.tag = ref
        img.label = if (ref.startsWith("pk3:maps/")) ref.removePrefix("pk3:maps/") else ""
        val ready: (String) -> Unit = { path ->
            if (img.tag == ref) {
                if (path.isEmpty()) img.none()
                else {
                    val w = if (sync) CARD_PX else max(img.width, dp(320f))
                    val mem = Images.cached(path, w)
                    val now = mem ?: if (PicturePolicy.decodeNow(sync, mem != null, blocking)) Images.now(path, w) else null
                    if (now != null) img.set(now)
                    else Images.load(path, w) { b -> if (img.tag == ref) { if (b != null) img.set(b) else img.none() } }
                }
            }
        }
        val known = knownPath(ref)
        when (PicturePolicy.first(known != null, img.hasPicture || hadPicture)) {
            PicturePolicy.First.SHOW -> { ready(known!!); return }
            PicturePolicy.First.PLACEHOLDER -> img.set(null)
            PicturePolicy.First.KEEP -> {}
        }
        val waiting = imageWaiters.getOrPut(ref) { ArrayList() }
        waiting.add(ready)
        if (waiting.size == 1) events.image(ref, dp(480f), dp(270f))
    }

    /** Fetches a picture ahead of its view (the card of a chosen server, Home's top map). */
    private fun prefetch(ref: String) {
        knownPath(ref)?.let { Images.load(it, CARD_PX) { }; return } // the file is known: only decode it ahead
        if (imageWaiters.containsKey(ref)) return
        imageWaiters[ref] = arrayListOf<(String) -> Unit>({ path -> if (path.isNotEmpty()) Images.load(path, CARD_PX) { } }) // decoded and kept for the card
        events.image(ref, dp(480f), dp(270f))
    }

    private fun preview(id: String, props: Map<String, Any?>): Entry {
        val row = Kit.row(ctx, 28f)
        row.gravity = Gravity.TOP
        val img = RoundImage(ctx, 20f)
        val info = Kit.column(ctx, 10f)
        val titleV = Kit.text(ctx, "", Theme.SP_DISPLAY, Theme.TEXT, bold = true)
        titleV.typeface = Theme.display
        val sub = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT_MUTED)
        val badges = FlowGrid(ctx, 0, dp(6f), wrap = true)
        val attrs = Kit.column(ctx, 4f)
        val desc = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT)
        val actions = FlowGrid(ctx, 0, dp(12f), wrap = true)
        info.addView(titleV); info.addView(sub); info.addView(badges); info.addView(attrs); info.addView(desc); info.addView(actions)
        row.addView(img, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val e = Entry(id, "preview", row, null, props)
        e.bind = { x ->
            val p = x.props
            titleV.text = title(p)
            sub.text = p.str("subtitle"); sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
            desc.text = p.str("body"); desc.visibility = if (desc.text.isEmpty()) View.GONE else View.VISIBLE
            attrs.removeAllViews()
            for (f in p["attrs"].list()) {
                val m = f.obj()
                val line = Kit.row(ctx, 12f)
                line.addView(Kit.text(ctx, Icons.label(m.str("icon"), m.str("label")), Theme.SP_BODY, Theme.TEXT_MUTED), LinearLayout.LayoutParams(dp(200f), ViewGroup.LayoutParams.WRAP_CONTENT))
                line.addView(Kit.text(ctx, m.str("value"), Theme.SP_BODY, Theme.TEXT, bold = true))
                attrs.addView(line)
            }
            badges.removeAllViews()
            for (b in p["badges"].list()) badges.addView(badge(b.obj()))
            actions.removeAllViews()
            val prim = p["primary"].obj()
            if (prim.isNotEmpty()) actions.addView(actionButton(id, prim, true))
            for (a in p["actions"].list()) actions.addView(actionButton(id, a.obj()))
            img.aspect = aspect(p, 4f / 3f)
            val ref = p.str("image")
            // no picture (a mod without an icon): the text takes the row
            img.visibility = if (ref.isEmpty()) View.GONE else View.VISIBLE
            if (ref.isEmpty()) img.none() else picture(img, ref, sync = true)
        }
        return e
    }

    private fun text(id: String, props: Map<String, Any?>): Entry {
        val t = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT)
        val e = Entry(id, "text", t, null, props)
        e.bind = { x ->
            val style = x.props.str("style", "body")
            t.text = x.props.str("body").ifEmpty { title(x.props) }
            t.textSize = when (style) { "title" -> Theme.SP_TITLE; "caption", "note" -> Theme.SP_CAPTION; else -> Theme.SP_BODY }
            t.typeface = when (style) { "title" -> Theme.bold; "mono" -> android.graphics.Typeface.MONOSPACE; else -> Theme.regular }
            t.setTextColor(when (style) { "caption", "note" -> Theme.TEXT_MUTED; "warning" -> Theme.WARN; else -> Theme.TEXT })
            t.setTextIsSelectable(x.props.bool("selectable"))
        }
        return e
    }

    private fun image(id: String, props: Map<String, Any?>): Entry {
        val img = RoundImage(ctx, 16f)
        val e = Entry(id, "image", img, null, props)
        e.bind = { x ->
            img.aspect = aspect(x.props, 16f / 9f)
            img.contain = x.props.str("fit") == "contain"
            // a banner or picture never takes more than a fifth (contain) or two fifths of the screen's height
            img.maxHeight = (ctx.resources.displayMetrics.heightPixels * if (img.contain) 0.2f else 0.4f).toInt()
            // `role: banner` with `emphasis: high` (Home, docs/UI_BRIDGE.md): at the top, scaled to the
            // screen; on a short screen (phone landscape) it gives way first: the row of cards and the
            // bar of Settings, Credits and Quit must fit under it
            val h = ctx.resources.displayMetrics.heightPixels
            val hdp = h / ctx.resources.displayMetrics.density
            if (x.props.str("role") == "banner" && x.props.str("emphasis") == "high") img.maxHeight = (h * (if (hdp < 480f) 0.16f else if (hdp < 640f) 0.22f else 0.3f)).toInt()
            else if (img.contain && screenKind(x) == "home") img.maxHeight = (h * 0.11f).toInt()
            img.contentDescription = x.props.str("alt")
            val r = x.props.str("ref")
            if (r.isEmpty()) img.none() else picture(img, r)
        }
        return e
    }

    /** Home's bar (`tabbar`: Settings, Credits, Quit): one strip along the bottom, joined edge to edge. */
    private fun tabbar(id: String, props: Map<String, Any?>): Entry {
        val row = Kit.row(ctx)
        row.setBackgroundColor(Theme.PANEL)
        row.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        row.dividerDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(Theme.OUTLINE); setSize(max(1, dp(1f)), 1) }
        return Entry(id, "tabbar", row, row, props)
    }

    /**
     * One segment of Home's bar (Settings, Credits, Quit): a flat button joined to its neighbours.
     * The open screen's one (the bar's `current`) is a darker shade with an ember line on top and
     * does nothing when tapped; a pressed or focused one is darker too. Quit asks first (invoke).
     */
    private fun tab(id: String, props: Map<String, Any?>): Entry {
        val frame = FrameLayout(ctx)
        frame.minimumHeight = dp(56f)
        frame.isClickable = true
        frame.isFocusable = true
        val titleV = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT, bold = true, lines = 1)
        titleV.gravity = Gravity.CENTER
        titleV.ellipsize = android.text.TextUtils.TruncateAt.END
        titleV.compoundDrawablePadding = dp(8f)
        titleV.setPadding(dp(8f), 0, dp(8f), 0)
        frame.addView(titleV, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val line = View(ctx)
        frame.addView(line, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1f)), Gravity.TOP))
        val e = Entry(id, "tab", frame, null, props)
        e.bind = { x ->
            val p = x.props
            val cur = nodes[x.parent ?: ""]?.props?.str("current").orEmpty()
            val current = cur.isNotEmpty() && x.id.endsWith("/$cur")
            frame.background = android.graphics.drawable.StateListDrawable().apply {
                val dark = android.graphics.drawable.ColorDrawable(Theme.BG)
                addState(intArrayOf(android.R.attr.state_pressed), dark)
                addState(intArrayOf(android.R.attr.state_focused), dark)
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(if (current) Theme.BG else Theme.PANEL_HI))
            }
            line.setBackgroundColor(if (current) Theme.EMBER else Theme.OUTLINE)
            (line.layoutParams as? FrameLayout.LayoutParams)?.let { it.height = if (current) dp(3f) else max(1, dp(1f)); line.layoutParams = it }
            val a = p["action"].obj()
            val tint = if (current) Theme.EMBER else Theme.TEXT
            titleV.text = p.str("label").ifEmpty { a.str("label") }
            titleV.setTextColor(tint)
            titleV.setCompoundDrawablesRelative(Icons.drawable(a.str("icon"), dp(18f), tint), null, null, null)
            frame.contentDescription = titleV.text
            frame.isSelected = current
            frame.setOnClickListener { if (!current) invoke(id, e.props["action"].obj()) }
        }
        return e
    }

    private fun status(id: String, props: Map<String, Any?>): Entry {
        val col = Kit.column(ctx, 12f)
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(0, dp(32f), 0, dp(32f))
        val icon = Kit.text(ctx, "", Theme.SP_DISPLAY, Theme.BLUE)
        val titleV = Kit.text(ctx, "", Theme.SP_HEADLINE, Theme.TEXT, bold = true)
        val body = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT_MUTED)
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Theme.EMBER)
        }
        val actions = Kit.row(ctx, 12f)
        actions.gravity = Gravity.CENTER
        for (v in listOf(icon, titleV, body)) { v.gravity = Gravity.CENTER; col.addView(v) }
        col.addView(bar, LinearLayout.LayoutParams(dp(420f), dp(8f)))
        col.addView(actions)
        val e = Entry(id, "status", col, null, props)
        e.bind = { x ->
            val p = x.props
            val d = Icons.drawable(p.str("icon"), dp(56f), Theme.BLUE)
            icon.setCompoundDrawablesRelative(null, d, null, null)
            icon.visibility = if (d == null) View.GONE else View.VISIBLE
            titleV.text = title(p)
            body.text = p.str("body"); body.visibility = if (body.text.isEmpty()) View.GONE else View.VISIBLE
            val prog = p.num("progress", -1.0)
            bar.visibility = if (prog >= 0 || p.bool("indeterminate")) View.VISIBLE else View.GONE
            bar.isIndeterminate = p.bool("indeterminate")
            if (prog >= 0) bar.progress = (prog * 1000).toInt()
            actions.removeAllViews()
            p["primary"].obj().takeIf { it.isNotEmpty() }?.let { actions.addView(actionButton(id, it, true)) }
            for (a in p["actions"].list()) actions.addView(actionButton(id, a.obj()))
        }
        return e
    }

    private fun progress(id: String, props: Map<String, Any?>): Entry {
        val col = Kit.column(ctx, 6f)
        val head = Kit.row(ctx, 12f)
        val label = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT, bold = true)
        head.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val cancel = Kit.button(ctx, "Cancel") {}
        head.addView(cancel)
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Theme.EMBER)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A3342.toInt())
        }
        val detail = Kit.text(ctx, "", Theme.SP_CAPTION, Theme.TEXT_MUTED)
        col.addView(head)
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10f)))
        col.addView(detail)
        val e = Entry(id, "progress", col, null, props)
        e.bind = { x ->
            val p = x.props
            label.text = p.str("label")
            bar.isIndeterminate = p.bool("indeterminate")
            if (!bar.isIndeterminate) bar.progress = (p.num("value") * 1000).toInt()
            detail.text = p.str("detail"); detail.visibility = if (detail.text.isEmpty()) View.GONE else View.VISIBLE
            val c = p["cancel"].obj()
            cancel.visibility = if (c.isEmpty()) View.GONE else View.VISIBLE
            cancel.text = c.str("label", "Cancel")
            cancel.setOnClickListener { invoke(id, c) }
        }
        return e
    }

    private fun applyNote(p: Map<String, Any?>): String = when (p.str("apply")) {
        "video" -> "Applies when you leave this screen"
        "sound" -> "Applies after a sound restart"
        "reconnect" -> "Applies to the next match"
        "restart" -> "Applies after a restart"
        else -> ""
    }

    private fun help(p: Map<String, Any?>): String {
        val err = p.str("error")
        if (err.isNotEmpty()) return err
        return listOf(p.str("help"), applyNote(p)).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun switchRow(id: String, props: Map<String, Any?>): Entry {
        if (props.str("style") == "checkbox") return checkChip(id, props)
        var muted = false
        val holder = FrameLayout(ctx)
        val e = Entry(id, "switch", holder, null, props)
        lateinit var s: android.widget.CompoundButton
        // `confirm` (an object like an action's): asked before the switch turns on
        // (a mod's Full engine access); Cancel puts it back
        s = Kit.switch(ctx, props.bool("value"), title(props)) { v ->
            if (muted) return@switch
            val c = e.props["confirm"].obj()
            if (v && c.isNotEmpty()) {
                muted = true; s.isChecked = false; muted = false
                host.confirm(c) { muted = true; s.isChecked = true; muted = false; events.value(id, true) }
            } else events.value(id, v)
        }
        e.bind = { x ->
            holder.removeAllViews()
            (s.parent as? ViewGroup)?.removeView(s)
            muted = true
            s.isChecked = x.props.bool("value")
            muted = false
            s.isEnabled = !x.props.containsKey("enabled") || x.props.bool("enabled")
            holder.addView(Kit.formRow(ctx, title(x.props), help(x.props), s) { if (s.isEnabled) s.toggle() })
        }
        return e
    }

    private fun format(p: Map<String, Any?>): (Double) -> String {
        val unit = p.str("unit")
        return { v ->
            when (p.str("format")) {
                "percent" -> "${Math.round(v * 100)}%"
                "float1" -> String.format(java.util.Locale.US, "%.1f", v)
                "float2" -> String.format(java.util.Locale.US, "%.2f", v)
                "ms" -> "${Math.round(v)} ms"
                "x" -> String.format(java.util.Locale.US, "%.2f×", v)
                "deg" -> "${Math.round(v)}°"
                "db" -> String.format(java.util.Locale.US, "%.1f dB", v)
                else -> if (p.num("step", 1.0) >= 1.0) Math.round(v).toString() else String.format(java.util.Locale.US, "%.2f", v)
            } + if (unit.isNotEmpty()) " $unit" else ""
        }
    }

    private fun sliderRow(id: String, props: Map<String, Any?>): Entry {
        val holder = FrameLayout(ctx)
        val e = Entry(id, "slider", holder, null, props)
        var dragging = false
        var lastSent = 0L
        e.bind = { x ->
            if (!dragging) {
                holder.removeAllViews()
                val p = x.props
                val sl = Kit.slider(ctx, p.num("value"), p.num("min"), p.num("max", 1.0), p.num("step", 0.0), title(p), format(p)) { v, done ->
                    dragging = !done
                    val now = android.os.SystemClock.uptimeMillis()
                    if (done || now - lastSent >= 100) { lastSent = now; events.value(id, v) } // 10 Hz while dragging, the last on release
                }
                holder.addView(Kit.formRow(ctx, title(p), help(p), sl))
            }
        }
        return e
    }

    /** `colour`: the label, then a swatch per palette entry (ColourSwatches). */
    private fun colourRow(id: String, props: Map<String, Any?>): Entry {
        val holder = FrameLayout(ctx)
        val e = Entry(id, "colour", holder, null, props)
        e.bind = { x ->
            holder.removeAllViews()
            val p = x.props
            val col = Kit.column(ctx, 4f)
            col.addView(Kit.formRow(ctx, title(p), help(p), null))
            if (p.str("picker") == "name") { // Touch: the name colour picker (ColourPicker.kt)
                col.addView(ColourPicker.view(ctx, p.str("text"), p.num("maxLength", 64.0).toInt()) { n -> events.value(id, "name:$n") }.apply { setPadding(dp(16f), 0, dp(16f), dp(8f)) })
            } else {
                val sw = ColourSwatches.view(ctx, p["palette"].list().map { it.toString() }, p["labels"].list().map { it.toString() }, p.str("value")) { hex -> events.value(id, hex) }
                sw.setPadding(dp(16f), 0, dp(16f), dp(8f))
                col.addView(sw)
            }
            holder.addView(col)
        }
        return e
    }

    private fun stepper(id: String, props: Map<String, Any?>): Entry {
        val holder = FrameLayout(ctx)
        val e = Entry(id, "stepper", holder, null, props)
        e.bind = { x ->
            holder.removeAllViews()
            val p = x.props
            val v = p.num("value"); val step = p.num("step", 1.0)
            val box = Kit.row(ctx, 8f)
            val minus = Kit.button(ctx, "−") { events.value(id, maxOf(p.num("min", -1e9), v - step)) }
            val shown = Kit.text(ctx, format(p)(v), Theme.SP_TITLE, Theme.TEXT, bold = true).apply { minWidth = dp(64f); gravity = Gravity.CENTER }
            val plus = Kit.button(ctx, "+") { events.value(id, minOf(p.num("max", 1e9), v + step)) }
            minus.contentDescription = "Less"; plus.contentDescription = "More"
            box.addView(minus); box.addView(shown); box.addView(plus)
            holder.addView(Kit.formRow(ctx, title(p), help(p), box))
        }
        return e
    }

    private fun choice(id: String, props: Map<String, Any?>): Entry {
        val holder = FrameLayout(ctx)
        val e = Entry(id, "choice", holder, null, props)
        e.bind = { x ->
            holder.removeAllViews()
            val p = x.props
            val opts = p["options"].list().map { it.obj() }
            val sel = p.str("value")
            val style = p.str("style", "auto").let { if (it == "auto") (if (opts.size <= 5) "segmented" else "dropdown") else it }
            if (style == "segmented" || style == "radio" || style == "cards") {
                val col = Kit.column(ctx, 6f)
                col.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
                if (title(p).isNotEmpty()) col.addView(Kit.text(ctx, title(p), Theme.SP_BODY, Theme.TEXT, bold = true))
                val chips = FlowGrid(ctx, 0, dp(8f), wrap = true)
                val shown = ChoiceSelection.selected(opts.map { it.str("value") }, sel)
                for (o in opts) chips.addView(chipView(Icons.label(o.str("icon"), o.str("label", o.str("value"))), o.str("value") == shown) {
                    events.value(id, o.str("value")); x.props = x.props + ("value" to o.str("value")); bindSafe(x)
                })
                col.addView(chips)
                val h = help(p)
                if (h.isNotEmpty()) col.addView(Kit.text(ctx, h, Theme.SP_CAPTION, Theme.TEXT_MUTED))
                holder.addView(col)
            } else {
                val cur = opts.firstOrNull { it.str("value") == sel }
                val value = Kit.text(ctx, (cur?.str("label") ?: sel) + "  ▾", Theme.SP_BODY, Theme.EMBER, bold = true)
                holder.addView(Kit.formRow(ctx, title(p), help(p), value) { pick(x, opts) })
            }
        }
        return e
    }

    /** A checkbox as a compact pill (the server list's filters: LAN, Empty, Full, Laggy). */
    private fun checkChip(id: String, props: Map<String, Any?>): Entry {
        val holder = FrameLayout(ctx)
        val e = Entry(id, "switch", holder, null, props)
        val filter = filterOf(id)
        e.bind = { x ->
            holder.removeAllViews()
            val state = serverListState
            // The engine's value is the truth until the player flips the chip (its
            // props come from menu_slist_showempty and friends, which the model's
            // default can disagree with: Laggy starts off). Once flipped, the model
            // owns it: a refresh that rebinds this node, or engine props that still
            // carry the value from before the tap, cannot flip it back or twice.
            if (filter != null) state.sync(filter, x.props.bool("value"))
            val on = if (filter != null) state.include(filter) else x.props.bool("value")
            val chip = chipView(title(x.props), on) {
                if (filter != null) {
                    val now = state.toggle(filter)
                    x.props = x.props + ("value" to now)
                    bindSafe(x)
                    applyServerFilter() // the rows change in this frame, no round trip
                    events.value(id, now)
                } else {
                    x.props = x.props + ("value" to !on)
                    bindSafe(x)
                    events.value(id, !on)
                }
            }
            Kit.icon(chip, x.props.str("icon"), if (on) Color.WHITE else Theme.TEXT_MUTED, 18f)
            chip.isEnabled = !x.props.containsKey("enabled") || x.props.bool("enabled")
            holder.addView(chip)
        }
        return e
    }

    /** The server browser's filter chips, by node id (`mp/bar/filters/lan` …). */
    private val filterChips = setOf("lan", "empty", "full", "laggy")

    private fun filterOf(id: String): String? =
        if (id.startsWith("mp/bar/filters/")) id.removePrefix("mp/bar/filters/").takeIf { it in filterChips } else null

    /** Re-windows every list on the server source now: a chip's rows change in this frame. */
    private fun applyServerFilter() {
        flushServers(nodes["mp/servers"]?.source ?: "servers")
    }

    /**
     * A chosen row lowers into the join bar at once: its address in the field,
     * Join on. The engine hears the address too, so the join, and the props it
     * sends next, agree with the row the player picked.
     */
    private fun chooseServer(address: String) {
        nodes["mp/join/address"]?.let { f ->
            if (f.props.str("value") != address) {
                f.props = f.props + ("value" to address)
                bindSafe(f)
            }
        }
        nodes["mp/join/go"]?.let { g ->
            if (!g.props.bool("enabled")) {
                g.props = g.props + ("enabled" to true)
                bindSafe(g)
            }
        }
        events.value("mp/join/address", address)
    }

    private fun screenOf(id: String): Entry? {
        var cur = nodes[id]?.parent
        while (cur != null) {
            val e = nodes[cur] ?: return null
            if (e.type == "screen") return e
            cur = e.parent
        }
        return null
    }

    /**
     * The screen's "N servers · M players" over the rows the chips leave: the
     * engine's summary counts every server it holds, so with a filter on it
     * said 157 over a list of 12. Only touched while a chip is filtering.
     */
    private fun showServerCount(listId: String, source: String) {
        val state = serverState(source)
        if (!state.filtering) return
        val screen = screenOf(listId) ?: return
        val rows = (0 until state.size).mapNotNull { state.row(it) }
        val shown = ServerListState.summary(screen.props.str("subtitle"), state.size, ServerListState.players(rows))
        if (shown == screen.props.str("subtitle")) return
        screen.props = screen.props + ("subtitle" to shown)
        bindSafe(screen)
    }

    /** A compact on/off pill; tapping it never opens anything. */
    private fun chipView(label: String, on: Boolean, tap: () -> Unit): TextView = Kit.text(ctx, (if (on) "✓ " else "") + label, Theme.SP_BODY, if (on) Color.WHITE else Theme.TEXT_MUTED, bold = true, lines = 1).apply {
        gravity = Gravity.CENTER
        minHeight = dp(Kit.MIN_TARGET_DP)
        setPadding(dp(16f), 0, dp(16f), 0)
        isClickable = true; isFocusable = true; isSelected = on
        background = if (on) Theme.rounded(0xFF1A3D7A.toInt(), dp(24f).toFloat(), dp(2f), Theme.BLUE) else Theme.surface(ctx, 0xFF060E1F.toInt(), 24f)
        contentDescription = label + if (on) ", selected" else ""
        setOnClickListener { Kit.haptic(it); tap() }
    }

    private fun pick(e: Entry, opts: List<Map<String, Any?>>) {
        val list = Kit.column(ctx, 4f)
        list.addView(Kit.text(ctx, title(e.props), Theme.SP_TITLE, Theme.TEXT, bold = true).apply { setPadding(dp(16f), dp(8f), dp(16f), dp(8f)) })
        for (o in opts) {
            val on = o.str("value") == e.props.str("value")
            val r = Kit.formRow(ctx, Icons.label(o.str("icon"), o.str("label", o.str("value"))), o.str("help"), if (on) Kit.text(ctx, "✓", Theme.SP_TITLE, Theme.EMBER, bold = true) else null) {
                events.value(e.id, o.str("value"))
                e.props = e.props + ("value" to o.str("value"))
                bindSafe(e)
                host.showDialog(null)
            }
            r.isActivated = on
            list.addView(r)
        }
        val sv = ScrollView(ctx).apply { addView(list); scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY }
        host.showDialog(dialogFrame(sv, true))
    }

    /** A password the server refused: the field is emptied (it keeps no copy, so what shows is what was typed), ready for the next try. */
    fun clearSecret(id: String) {
        (nodes[id]?.view?.tag as? android.widget.EditText)?.setText("")
    }

    private fun field(id: String, props: Map<String, Any?>): Entry {
        val secret = props.str("kind") == "password" || props.bool("private")
        val col = Kit.column(ctx, 6f)
        col.setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
        val titleV = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT, bold = true)
        val lock = Kit.text(ctx, "", Theme.SP_TITLE, Theme.TEXT_MUTED).apply { minWidth = dp(40f); gravity = Gravity.CENTER }
        val e = Entry(id, "field", col, null, props)
        val input = Kit.input(ctx, if (secret) "" else props.str("value"), props.str("placeholder"), secret) { text ->
            val p = e.props
            if (secret) { if (text.isNotEmpty()) events.value(id, text) } else if (text != p.str("value")) {
                e.props = e.props + ("value" to text)
                events.value(id, text)
            }
        }
        // Publish the editable contents while the IME is composing as well as
        // when it commits a suggestion. Keep the EditText as the IME's owner.
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!input.hasFocus()) return
                val text = s?.toString() ?: ""
                if (secret) {
                    if (text.isNotEmpty()) events.value(id, text)
                } else if (text != e.props.str("value")) {
                    e.props = e.props + ("value" to text)
                    events.value(id, text)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        input.setOnEditorActionListener { v, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val text = v.text.toString()
                if (e.props["submit"].obj().isNotEmpty()) events.submit(id, text) else events.value(id, text)
                if (secret) (v as android.widget.EditText).setText("") // write-only: no copy kept
            }
            false
        }
        when (props.str("kind")) {
            "number" -> input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            "url", "address" -> input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            "search" -> input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        val line = Kit.row(ctx, 8f)
        line.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (secret) line.addView(lock)
        val helpV = Kit.text(ctx, "", Theme.SP_CAPTION, Theme.TEXT_MUTED)
        col.addView(titleV); col.addView(line); col.addView(helpV)
        col.tag = input
        e.bind = { x ->
            val p = x.props
            val inBar = nodes[x.parent ?: ""]?.type in setOf("toolbar", "actionbar", "row") || (x.id == "search" && x.props.str("kind") == "search" && nodes[x.parent ?: ""]?.type == "screen")
            titleV.text = title(p); titleV.visibility = if (titleV.text.isEmpty() || inBar) View.GONE else View.VISIBLE
            if (inBar) col.setPadding(0, 0, 0, 0)
            input.hint = p.str("placeholder").ifEmpty { title(p) }
            input.isEnabled = !p.containsKey("enabled") || p.bool("enabled")
            // Never rewrite a field the player is typing in: the value in props is the last
            // COMMITTED text, so during IME composition (the suggestion strip) it is stale and
            // setText cancels the composition - the letters stay in the strip and never arrive.
            if (!secret && !input.hasFocus() && input.text.toString() != p.str("value"))
                input.setText(p.str("value"))
            // the lock: open while no password applies, closed when one is on and set
            val locked = p.bool("hasValue") && input.isEnabled
            lock.text = if (locked) "🔒" else "🔓"
            lock.setTextColor(if (locked) Theme.WARN else Theme.TEXT_DIM)
            lock.contentDescription = if (locked) "Password set" else "No password"
            val h = help(p)
            helpV.text = h
            helpV.setTextColor(if (p.str("error").isNotEmpty()) Theme.BAD else Theme.TEXT_MUTED)
            helpV.visibility = if (h.isEmpty()) View.GONE else View.VISIBLE
            p.int("maxLength").takeIf { it > 0 }?.let { input.filters = arrayOf(android.text.InputFilter.LengthFilter(it)) }
        }
        return e
    }

    private fun button(id: String, props: Map<String, Any?>): Entry {
        val holder = FrameLayout(ctx)
        val e = Entry(id, "button", holder, null, props)
        e.bind = { x ->
            holder.removeAllViews()
            val p = x.props
            val act = p["action"].obj().ifEmpty { mapOf("id" to id) }
            val a = act + mapOf("style" to p.str("style", act.str("style")), "label" to p.str("label").ifEmpty { act.str("label") })
            val b = actionButton(id, a)
            // a chosen server keeps Join usable through the engine's next props
            b.isEnabled = (id == "mp/join/go" && serverListState.selected.isNotEmpty()) ||
                !p.containsKey("enabled") || p.bool("enabled")
            // Touch: width xs/s is a small button as wide as its label (a MATCH_PARENT child of this wrapping holder took the whole row)
            val small = p.str("width") == "xs" || p.str("width") == "s"
            if (small) b.minimumWidth = dp(if (p.str("width") == "xs") 64f else 96f)
            holder.addView(b, FrameLayout.LayoutParams(if (small) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return e
    }

    private fun dialogFrame(content: View, dismissable: Boolean, onDismiss: (() -> Unit)? = null): View {
        val frame = FrameLayout(ctx)
        frame.setBackgroundColor(0xB3000000.toInt())
        frame.isClickable = true // nothing behind it takes the tap
        if (dismissable) frame.setOnClickListener { if (onDismiss != null) onDismiss() else events.back() }
        val box = Kit.card(ctx, clickable = false, radiusDp = 24f)
        box.isClickable = true
        val p = dp(20f)
        box.setPadding(p, p, p, p)
        (content.parent as? ViewGroup)?.removeView(content)
        box.addView(content)
        val w = minOf(dp(760f), (ctx.resources.displayMetrics.widthPixels * 0.9).toInt())
        frame.addView(box, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { topMargin = dp(24f); bottomMargin = dp(24f) })
        return frame
    }

    private var detailsOpen = false

    /** Closes the server info dialog if it is up (a join, leaving a match, another screen). */
    fun closeDetails() {
        if (!detailsOpen) return
        detailsOpen = false
        host.showDialog(null)
    }

    /**
     * The menu is left (a join or a match starts): no info dialog stays up and no row stays chosen,
     * so coming back after the match shows the whole list.
     */
    fun leftMenu() {
        if (!detailsOpen && serverListStates.values.all { it.selected.isEmpty() }) return
        closeDetails()
        for (st in serverListStates.values) st.select("")
        nodes["mp/servers"]?.let { e -> e.props = e.props + ("selected" to emptyList<Any?>()); e.list?.refresh() }
        updateInfoButton()
    }

    /** The map the last Join tap named and the picture the dialog had for it (a file), for the card's first frame. */
    @Volatile var joinHint: Pair<String, String?>? = null

    /** The native server info dialog (opened by choosing a server, or the bottom bar's "i"): picture, title, facts, players, Join, Close. */
    private fun showServerDetails(row: Map<String, Any?>) {
        val cells = row["cells"].obj()
        val map = cells.str("map")
        val address = cells.str("address").ifEmpty { VirtualList.keyOf(row) }
        val content = Kit.column(ctx, 12f)
        val img = RoundImage(ctx, 16f).apply {
            aspect = 16f / 9f
            maxHeight = ServerDetails.imageMaxHeightPx(
                ctx.resources.displayMetrics.widthPixels,
                ctx.resources.displayMetrics.heightPixels,
            )
        }
        if (map.isNotEmpty()) {
            content.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // a map that is not installed has no picture: the card closes up rather than keep a bare panel
            img.onMissing = { img.visibility = View.GONE }
            val ref = "pk3:maps/$map"
            val t0 = android.os.SystemClock.uptimeMillis()
            Log.i("Godnotic", "Preview: dialog build map=$map pathKnown=${knownPath(ref) != null}")
            img.onShown = { Log.i("Godnotic", "Preview: picture set ${android.os.SystemClock.uptimeMillis() - t0} ms after build map=$map") }
            picture(img, ref, sync = true, blocking = false) // a picture that is not decoded yet never holds the dialog (or Join) back
        }
        content.addView(Kit.text(ctx, cells.str("name").ifEmpty { address }, Theme.SP_TITLE, Theme.TEXT, bold = true))
        content.addView(Kit.text(ctx, ServerDetails.facts(cells), Theme.SP_BODY, Theme.TEXT_MUTED))
        val playerDetails = cells.str("player_details").trim()
        if (playerDetails.isNotEmpty()) {
            content.addView(Kit.text(ctx, "Players · score · team", Theme.SP_CAPTION, Theme.TEXT_MUTED, bold = true))
            content.addView(Kit.text(ctx, playerDetails, Theme.SP_BODY, Theme.TEXT, bold = true))
        }
        val actions = Kit.row(ctx, 10f)
        // Join is what the dialog is for: first, twice as wide, taller, and the focused button (Enter / the d-pad's centre joins).
        val join = Kit.button(ctx, "Join", primary = true) {
            Log.i("Godnotic", "Preview: Join tapped $address")
            joinHint = map to (if (map.isNotEmpty()) knownPath("pk3:maps/$map") else null) // the join card's picture until the engine names one
            closeDetails()
            events.row("mp/servers", address, "activate")
        }.apply {
            minHeight = Theme.dp(ctx, 60f)
            textSize = Theme.SP_BODY + 2f
            isFocusableInTouchMode = true
        }
        actions.addView(join, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        actions.addView(Kit.button(ctx, "Close") { closeDetails() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.gravity = Gravity.CENTER_VERTICAL
        join.post { join.requestFocus() }
        content.addView(actions)
        val scroll = ScrollView(ctx).apply { isFillViewport = true; addView(content) }
        detailsOpen = true
        val openedAt = android.os.SystemClock.uptimeMillis()
        // a double tap's second touch lands on the backdrop: it does not close what the first opened
        host.showDialog(dialogFrame(scroll, true) {
            if (android.os.SystemClock.uptimeMillis() - openedAt > android.view.ViewConfiguration.getDoubleTapTimeout() + 100) closeDetails()
        })
    }

    private fun generic(id: String, type: String, props: Map<String, Any?>): Entry {
        val col = Kit.column(ctx, 8f)
        val head = FrameLayout(ctx)
        col.addView(head)
        val kids = Kit.column(ctx, 8f)
        col.addView(kids)
        val e = Entry(id, type, col, kids, props)
        e.bind = { x ->
            head.removeAllViews()
            val p = x.props
            val t = title(p)
            if (t.isNotEmpty()) {
                val trailing = Kit.row(ctx, 8f)
                if (p.containsKey("value")) trailing.addView(Kit.text(ctx, p["value"].let { v -> if (v is List<*>) v.joinToString(" / ") else p.str("value") }, Theme.SP_BODY, Theme.EMBER, bold = true))
                for (a in p["actions"].list()) trailing.addView(actionButton(id, a.obj()))
                head.addView(Kit.formRow(ctx, Icons.label(p.str("icon"), t), p.str("subtitle").ifEmpty { help(p) }, trailing))
            }
        }
        return e
    }

    // ------------------------------------------------------------------ lists

    private fun colWeight(c: Map<String, Any?>) = when (c.str("width")) { "xs" -> 0.35f; "s" -> 0.7f; "m" -> 1.2f; "l" -> 1.8f; "fill" -> 3f; else -> 1f }

    private fun visibleColumns(cols: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val wdp = ctx.resources.displayMetrics.widthPixels / ctx.resources.displayMetrics.density
        val maxPriority = when { wdp >= 900 -> 3; wdp >= 640 -> 2; else -> 1 }
        return cols.filter { it.int("priority", 1) <= maxPriority }
    }

    /**
     * A data list over a source (the server browser, mods, recent games):
     * `display: table` is one dense row per entry with aligned columns and a
     * slim sortable header; `cards` is a grid of preview cards (the map
     * picker). Rows come in windows as they approach (VirtualList).
     */
    private fun list(id: String, props: Map<String, Any?>): Entry {
        val cards = props.str("display") == "cards"
        val box = Kit.column(ctx)
        val header = Kit.row(ctx)
        val empty = Kit.column(ctx, 6f).apply { gravity = Gravity.CENTER; setPadding(0, dp(24f), 0, dp(24f)); visibility = View.GONE }
        var columns: List<Map<String, Any?>> = emptyList()
        val e = Entry(id, "list", box, null, props)
        e.source = props.str("source")
        e.empty = empty
        val rowH = dp(when (props.str("rowHeight", if (cards) "card" else "compact")) { "compact" -> 40f; "card" -> 96f; else -> 56f })
        val request: (IntRange) -> Unit = { r ->
            val p = e.props
            events.window(id, e.source, r.first, r.last - r.first + 1, p.str("sort"), p.bool("desc"), p.str("filter"))
        }
        val vl: VirtualList
        if (cards) {
            val gap = dp(14f)
            class CardCell(val card: FrameLayout, val img: RoundImage, val titleV: TextView, val sub: TextView, val badges: FlowGrid,
                           val state: TextView, val bar: ProgressBar) : VirtualList.Cell(card) {
                override fun recycled() { img.tag = null; img.set(null) }
            }
            vl = VirtualList(ctx, id, request, 60, grid = true, minCellPx = dp(props.num("minItem", 220.0).toFloat()) + gap,
                bindCell = { h, row, _ ->
                    val c = h as CardCell
                    val cells = row?.get("cells").obj()
                    c.titleV.text = cells.str("title").ifEmpty { cells.str("name") }
                    c.sub.text = listOf(cells.str("players"), cells.str("author")).filter { it.isNotEmpty() }.joinToString(" · ")
                    c.sub.visibility = if (c.sub.text.isEmpty()) View.GONE else View.VISIBLE
                    c.badges.removeAllViews()
                    row?.get("badges").list().take(4).forEach { b -> c.badges.addView(badge(b.obj())) }
                    if (row != null && row["badges"] == null) cells.str("modes").split(' ').filter { it.isNotEmpty() }.take(4).forEach { m -> c.badges.addView(badge(mapOf("text" to m.uppercase()))) }
                    c.img.aspect = aspect(mapOf("aspect" to e.props.str("aspectItem", "16:9")), 16f / 9f)
                    val ref = row?.str("image") ?: ""
                    if (ref.isEmpty()) { if (row == null) c.img.set(null) else c.img.none() } else picture(c.img, ref)
                    // a map still to download (cells state, progress; docs/DATA_FETCH.md): dimmed, a label, a bar
                    val ms = mapState(cells)
                    c.img.alpha = if (ms.first != null) 0.5f else 1f
                    c.state.text = ms.first ?: ""
                    c.state.visibility = if (ms.first != null) View.VISIBLE else View.GONE
                    c.bar.visibility = if (ms.second != null) View.VISIBLE else View.GONE
                    c.bar.progress = ((ms.second ?: 0.0) * 1000).toInt()
                    c.card.isSelected = row != null && e.props["selected"].list().contains(row.str("key"))
                    (c.card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                        // `rows: N`: fixed tiles in rows that scroll sideways
                        val f = e.list?.fit
                        val carousel = e.list?.carouselWidth ?: 0
                        val w = f?.width ?: if (carousel > 0) carousel - gap else ViewGroup.LayoutParams.MATCH_PARENT
                        val h = f?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
                        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; c.card.layoutParams = lp }
                    }
                    c.card.contentDescription = row?.let { listOf(c.titleV.text, ms.first ?: "", c.sub.text).filter { it.isNotEmpty() }.joinToString(". ") } ?: "Loading"
                    // long press copies the tile (a map, a server card) as one line
                    c.card.isLongClickable = row != null
                    c.card.setOnLongClickListener { v ->
                        val r0 = row ?: return@setOnLongClickListener false
                        Kit.haptic(v)
                        Kit.copyTo(v.context, CopyText.server(r0["cells"].obj(), VirtualList.keyOf(r0)))
                    }
                    bindTap(c.card, e, id, row)
                },
                makeCell = { _ ->
                    val card = Kit.card(ctx, clickable = true, radiusDp = 16f)
                    val col = Kit.column(ctx)
                    val img = RoundImage(ctx, 0f)
                    val texts = Kit.column(ctx, 4f)
                    texts.setPadding(dp(12f), dp(8f), dp(12f), dp(12f))
                    val t = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT, bold = true, lines = 1)
                    val s = Kit.text(ctx, "", Theme.SP_CAPTION, Theme.TEXT_MUTED, lines = 1)
                    val b = FlowGrid(ctx, 0, dp(4f), wrap = true)
                    texts.addView(t); texts.addView(s); texts.addView(b)
                    // the picture with a download label and bar over it (no height of their own)
                    val pic = FrameLayout(ctx)
                    pic.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val st = Kit.text(ctx, "", Theme.SP_CAPTION, Theme.TEXT, bold = true, lines = 1).apply {
                        setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                        background = Theme.rounded(0xD9091022.toInt(), dp(10f).toFloat(), 0, Color.TRANSPARENT)
                        visibility = View.GONE
                    }
                    pic.addView(st, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(6f), dp(6f), dp(6f), 0) })
                    val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Theme.EMBER); visibility = View.GONE
                    }
                    pic.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4f), Gravity.BOTTOM))
                    col.addView(pic); col.addView(texts)
                    card.addView(col)
                    val lp = androidx.recyclerview.widget.RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                    card.layoutParams = lp
                    CardCell(card, img, t, s, b, st, bar)
                })
            vl.cellGap = gap
            vl.cellChrome = {
                // one tile measured with a typical row: its height less the picture's
                val probe = Kit.column(ctx)
                val t = Kit.text(ctx, "Map", Theme.SP_BODY, Theme.TEXT, bold = true, lines = 1)
                val sub = Kit.text(ctx, "2-4", Theme.SP_CAPTION, Theme.TEXT_MUTED, lines = 1)
                val badges = FlowGrid(ctx, 0, dp(4f), wrap = true).apply { addView(badge(mapOf("text" to "DM"))) }
                probe.setPadding(dp(12f), dp(8f), dp(12f), dp(12f))
                probe.addView(t); probe.addView(sub); probe.addView(badges)
                probe.measure(View.MeasureSpec.makeMeasureSpec(dp(300f), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                probe.measuredHeight + dp(12f) // the badges row measures short outside a card
            }
            box.addView(empty)
            box.addView(vl.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            box.background = Theme.rounded(Theme.PANEL, dp(16f).toFloat(), max(1, dp(1f)), Theme.OUTLINE)
            box.clipToOutline = true
            box.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            header.setPadding(dp(12f), 0, dp(12f), 0)
            header.minimumHeight = dp(36f)
            header.setBackgroundColor(0xFF0E1E40.toInt())
            class RowCell(val row: LinearLayout) : VirtualList.Cell(row)
            vl = VirtualList(ctx, id, request, 50, grid = false, minCellPx = 0,
                bindCell = { holder, data, _ ->
                    val r = (holder as RowCell).row
                    if (r.childCount != columns.size) rebuildRow(r, columns, rowH)
                    val cells = data?.get("cells").obj()
                    for ((i, c) in columns.withIndex()) bindCell(r.getChildAt(i) as TextView, c, cells, data == null)
                    // `stale`: no answer this refresh (drawn dimmed until its grace runs
                    // out); `fresh`: new this refresh, so it fades and slides in rather
                    // than appearing between two frames
                    val dim = if (data?.bool("disabled") == true) 0.5f else 1f
                    if (data != null && data.bool("fresh")) {
                        if (fadedIn.size > 4096) fadedIn.clear()
                        if (fadedIn.add(VirtualList.keyOf(data))) {
                            r.animate().cancel()
                            r.alpha = 0f
                            r.translationY = dp(8f).toFloat()
                            r.animate().alpha(dim).translationY(0f).setDuration(180).start()
                        } else if (r.alpha != dim) {
                            r.alpha = dim
                            r.translationY = 0f
                        }
                    } else if (r.alpha != dim || r.translationY != 0f) {
                        r.animate().cancel()
                        r.alpha = dim
                        r.translationY = 0f
                    }
                    // long press copies the server as one line (name, address, map,
                    // players, ping): the cells' own long presses would copy a column
                    for (i in 0 until r.childCount) r.getChildAt(i).let { cell ->
                        // setOnLongClickListener(null) still marks a view long-clickable,
                        // and a long-clickable cell swallows the tap meant for the row.
                        cell.setOnLongClickListener(null)
                        cell.isLongClickable = false
                    }
                    r.isLongClickable = data != null
                    r.setOnLongClickListener { v ->
                        val row = data ?: return@setOnLongClickListener false
                        Kit.haptic(v)
                        Kit.copyTo(v.context, CopyText.server(row["cells"].obj(), VirtualList.keyOf(row)))
                    }
                    val on = data != null && e.props["selected"].list().any { it.toString() == VirtualList.keyOf(data) }
                    r.isSelected = on
                    r.background = if (on) Theme.rounded(0xFF1D3A73.toInt(), dp(8f).toFloat(), max(1, dp(1.5f)), Theme.EMBER)
                        else Theme.surface(ctx, Color.TRANSPARENT, 0f, Color.TRANSPARENT)
                    r.contentDescription = if (data == null) "Loading" else columns.joinToString(", ") { c -> "${c.str("label")} ${cells.str(c.str("key"))}" } + if (on) ", selected" else ""
                    bindTap(r, e, id, data)
                },
                makeCell = { _ ->
                    val r = Kit.row(ctx)
                    r.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH)
                    r.background = Theme.surface(ctx, Color.TRANSPARENT, 0f, Color.TRANSPARENT)
                    r.isClickable = true
                    r.isFocusable = true
                    rebuildRow(r, columns, rowH)
                    RowCell(r)
                })
            vl.view.addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
                val paint = android.graphics.Paint().apply { color = 0xFF16264A.toInt() }
                override fun onDraw(c: android.graphics.Canvas, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
                    for (i in 0 until parent.childCount) {
                        val v = parent.getChildAt(i)
                        c.drawRect(0f, v.bottom.toFloat(), parent.width.toFloat(), v.bottom + max(1, dp(1f)).toFloat(), paint)
                    }
                }
            })
            box.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(empty)
            box.addView(vl.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        lists.getOrPut(e.source) { ArrayList() }.add(vl)
        e.list = vl
        vl.onVisible = { r, touching ->
            if (touching != null) serverListState.hold = touching // no re-ordering under a finger
            events.visible(id, r.first, r.last - r.first + 1, touching)
        }
        var lastQuery = ""
        var lastSelected: List<Any?> = emptyList()
        e.bind = { x ->
            val p = x.props
            if (id == "mp/servers") serverState(x.source).setSearch(p.str("filter"))
            // a new source (the map picker after a mode change): drop the old rows, window the new ones
            val src = p.str("source")
            if (moveSource(lists, vl, x.source, src)) { x.source = src; vl.reset(-1); if (isServerSource(src)) flushServers(src) }
            // only `selected` changed (a tap's diff): rebind in place, the list keeps its position
            val sel = p["selected"].list()
            if (sel != lastSelected) { lastSelected = sel; vl.refresh() }
            if (cards) {
                // `rows: 2`: the map picker shows two whole rows of tiles (docs/UI_BRIDGE.md)
                val rows = fitRows(p)
                val asp = aspect(mapOf("aspect" to p.str("aspectItem", "16:9")), 16f / 9f)
                if (rows != vl.fitRows || asp != vl.cellAspect) { vl.fitRows = rows; vl.cellAspect = asp; vl.view.requestLayout() }
            }
            val newCols = visibleColumns(p["columns"].list().map { it.obj() })
            val colsChanged = newCols != columns
            columns = newCols
            if (!cards) {
                header.removeAllViews()
                val sortKey = p.str("sort")
                val desc = p.bool("desc")
                for (c in columns) {
                    val on = c.str("key") == sortKey
                    val t = Kit.text(ctx, c.str("label") + if (on) (if (desc) " ▾" else " ▴") else "", Theme.SP_CAPTION, if (on) Theme.EMBER else Theme.TEXT_MUTED, bold = true, lines = 1)
                    t.gravity = gravityOf(c) or Gravity.CENTER_VERTICAL
                    t.minHeight = dp(36f)
                    t.setPadding(dp(4f), 0, dp(4f), 0)
                    if (c.bool("sortable")) {
                        t.isClickable = true
                        t.isFocusable = true
                        t.background = Theme.surface(ctx, Color.TRANSPARENT, 6f, Color.TRANSPARENT)
                        t.contentDescription = "Sort by ${c.str("label")}"
                        t.setOnClickListener {
                            val d = if (on) !desc else (c.str("type") in setOf("players", "number"))
                            if (id == "mp/servers") serverListState.choose(c.str("key"), d)
                            x.props = x.props + mapOf("sort" to c.str("key"), "desc" to d)
                            bindSafe(x)
                        }
                    }
                    header.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, colWeight(c)))
                }
            }
            // a new sort or filter re-windows the visible range
            val query = "${p.str("sort")}|${p.bool("desc")}|${p.str("filter")}"
            if (lastQuery.isNotEmpty() && query != lastQuery) {
                // Same count, new query: clear the old query's rows in place instead of
                // invalidating the list (the list blinked and lost its place).
                vl.requery(maxOf(0, vl.window.total))
                if (isServerSource(src)) flushServers(src)
            } else if (vl.window.total < 0 && p.containsKey("count")) vl.reset(p.int("count"))
            lastQuery = query
            if (colsChanged) vl.refresh()
            // The engine's query is over (its list is no longer "loading"): rows it
            // did not send again are dimmed once, here, and kept through the grace
            // period. While it refreshes they stay as bright as they were.
            if (isServerSource(src) && p.str("state", "ready") != "loading") {
                serverState(src).settle() // dims what the query did not confirm
                flushServers(src)          // and draws it dimmed
            }
            bindEmpty(x)
        }
        return e
    }

    private val a11yActions = java.util.WeakHashMap<View, Int>()
    private var lastTapKey = ""
    private var lastTapAt = 0L

    /**
     * Rows and cards of a list. With `selection` single or multi, a tap
     * selects (event row/select, drawn at once) and a double tap, Enter or
     * the accessibility "Activate" action activates (event row/activate:
     * joins the server, starts the map); without selection a tap activates.
     */
    private fun bindTap(v: View, e: Entry, id: String, row: Map<String, Any?>?) {
        if (row == null) { v.setOnClickListener(null); v.setOnKeyListener(null); return }
        val key = VirtualList.keyOf(row)
        val selecting = e.props.str("selection") in setOf("single", "multi")
        v.setOnClickListener { x ->
            Kit.haptic(x)
            if (!selecting) { events.row(id, key, "activate"); return@setOnClickListener }
            val now = android.os.SystemClock.uptimeMillis()
            val second = key == lastTapKey && now - lastTapAt <= android.view.ViewConfiguration.getDoubleTapTimeout() + 100
            lastTapKey = if (second) "" else key
            lastTapAt = now
            if (second) {
                if (e.props.str("selection") == "multi") select(e, id, key, true) // the first tap's toggle undone
                events.row(id, key, "activate")
            } else {
                select(e, id, key, true) // choosing opens the info dialog, whose Join joins
            }
        }
        v.setOnKeyListener { _, code, ev ->
            if (ev.action == android.view.KeyEvent.ACTION_UP && (code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                events.row(id, key, "activate"); true
            } else false
        }
        // recycled cells: the previous row's action goes first
        a11yActions.remove(v)?.let { androidx.core.view.ViewCompat.removeAccessibilityAction(v, it) }
        if (selecting) {
            androidx.core.view.ViewCompat.replaceAccessibilityAction(v, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, "Select", null)
            a11yActions[v] = androidx.core.view.ViewCompat.addAccessibilityAction(v, e.props["rowPrimary"].obj().str("label", "Activate")) { _, _ -> events.row(id, key, "activate"); true }
        }
    }

    private fun select(e: Entry, id: String, key: String, send: Boolean) {
        val multi = e.props.str("selection") == "multi"
        val cur = e.props["selected"].list().map { it.toString() }
        val next = if (multi) (if (key in cur) cur - key else cur + key) else listOf(key)
        if (isServerSource(e.source) && !multi) {
            // the list's model keeps the row, the engine's next props cannot undo it
            val st = serverState(e.source)
            if (st.select(key)) {
                chooseServer(key)
                e.props = e.props + ("selected" to next)
                e.list?.refresh()
            }
            updateInfoButton()
            // a player's choice opens its info dialog; closing it keeps the choice (the bar's "i" reopens it)
            if (send && id == "mp/servers") st.rowOf(key)?.let { showServerDetails(it) }
        } else if (next != cur) {
            e.props = e.props + mapOf("selected" to next)
            e.list?.refresh()
        }
        if (send) events.row(id, key, "select")
    }

    private fun bindEmpty(e: Entry) {
        val empty = e.empty ?: return
        val p = e.props
        val total = e.list?.window?.total ?: -1
        empty.removeAllViews()
        val st = p.str("state", "ready")
        val m = if (st == "error") p["error"].obj() else p["empty"].obj()
        // rows still held through a refresh (or past a chip) are not "no servers"
        val drawn = if (isServerSource(e.source)) serverState(e.source).size else 0
        val show = ServerListState.showEmpty(st, total, drawn)
        if (show) {
            empty.addView(Kit.text(ctx, Icons.label(m.str("icon"), m.str("title", if (st == "error") "Something went wrong" else "Nothing here yet")), Theme.SP_TITLE, Theme.TEXT, bold = true).apply { gravity = Gravity.CENTER })
            m.str("text").takeIf { it.isNotEmpty() }?.let { empty.addView(Kit.text(ctx, it, Theme.SP_BODY, Theme.TEXT_MUTED).apply { gravity = Gravity.CENTER }) }
            m["action"].obj().takeIf { it.isNotEmpty() }?.let { empty.addView(actionButton(e.id, it, true)) }
        }
        empty.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun gravityOf(c: Map<String, Any?>) = when (c.str("align", if (c.str("type") in setOf("number", "ping", "players", "size")) "end" else "start")) {
        "end" -> Gravity.END; "center" -> Gravity.CENTER_HORIZONTAL; else -> Gravity.START
    }

    private fun rebuildRow(r: LinearLayout, columns: List<Map<String, Any?>>, h: Int) {
        r.removeAllViews()
        r.setPadding(dp(12f), 0, dp(12f), 0)
        for (c in columns) {
            val t = Kit.text(ctx, "", Theme.SP_BODY, Theme.TEXT, lines = 1)
            // the row copies the whole server and handles the tap: a cell must not
            // be long-clickable (it would swallow the press), must not forward the
            // tap on to the row itself (its handler would run for the same press),
            // nor copy on its own
            t.setOnClickListener(null)
            t.setOnLongClickListener(null)
            t.isClickable = false
            t.isLongClickable = false
            t.gravity = gravityOf(c) or Gravity.CENTER_VERTICAL
            t.setPadding(dp(4f), 0, dp(4f), 0)
            r.addView(t, LinearLayout.LayoutParams(0, h, colWeight(c)))
        }
    }

    private fun bindCell(t: TextView, c: Map<String, Any?>, cells: Map<String, Any?>, placeholder: Boolean) {
        if (placeholder) { t.text = ""; t.setBackgroundColor(0x0FFFFFFF); return }
        t.background = null
        val key = c.str("key")
        t.typeface = if (c.str("width") == "fill") Theme.bold else Theme.regular
        when (c.str("type")) {
            "lock" -> { t.text = if (cells.bool(key)) "🔒" else ""; t.setTextColor(Theme.WARN) }
            "bool" -> { t.text = if (cells.bool(key)) "✓" else ""; t.setTextColor(Theme.GOOD) }
            "icon" -> { t.text = Icons.glyph(cells.str(key)); t.setTextColor(Theme.TEXT) }
            "ping" -> {
                val p = cells.int(key)
                t.text = if (p <= 0) "–" else "$p"
                t.setTextColor(when { p <= 0 -> Theme.TEXT_DIM; p < 80 -> Theme.GOOD; p < 160 -> Theme.WARN; else -> Theme.BAD })
            }
            "players" -> {
                val n = cells.int(key); val mx = cells.int("max")
                t.text = if (mx > 0) "$n/$mx" else "$n"
                t.setTextColor(if (n == 0) Theme.TEXT_DIM else Theme.TEXT)
            }
            "mode" -> { t.text = cells.str("modeName").ifEmpty { cells.str(key).uppercase() }; t.setTextColor(Theme.TEXT_MUTED) }
            else -> { t.text = cells.str(key); t.setTextColor(if (c.str("width") == "fill") Theme.TEXT else Theme.TEXT_MUTED) }
        }
    }
}

/**
 * A screen's page. A list scrolling inside it (a `rows: N` map grid, as tall
 * as the page's visible area) gets the drag only once the page has brought
 * it to the top: until then the page scrolls, so the grid is never scrolled
 * half off screen. Coming back up the grid scrolls to its start first
 * (the platform's nested scrolling), then the page.
 */
class PageScroll(ctx: Context) : ScrollView(ctx) {
    init { isNestedScrollingEnabled = true }

    /** The pinned section title's height: a list stops under it. */
    var topInset = 0

    /** Height kept free below the body for a dialog's action row (0: none), so a tall body scrolls instead of pushing the row out of the card. */
    var reservePx = 0

    /**
     * Home's fitted body is given the viewport's own height, whatever the scroller's
     * fill-viewport pass did: measured here, after the scroller knows its size, so the
     * cards can never end up under the bottom bar.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var hs = heightMeasureSpec
        if (reservePx > 0 && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            hs = MeasureSpec.makeMeasureSpec(DialogChrome.bodyBudget(MeasureSpec.getSize(heightMeasureSpec), reservePx), MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, hs)
        val c = getChildAt(0) as? FitBody ?: return
        if (!c.fit || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) return
        val h = measuredHeight - paddingTop - paddingBottom
        val w = measuredWidth - paddingLeft - paddingRight
        if (c.measuredHeight != h || c.measuredWidth != w) c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        (getChildAt(0) as? FitBody)?.requestLayout()
    }

    private fun topOf(target: View): Int {
        var y = 0
        var v: View? = target
        while (v != null && v !== this) { y += v.top; v = v.parent as? View }
        return y - scrollY - topInset
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        (axes and View.SCROLL_AXIS_VERTICAL) != 0

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
        if (dy > 0) {
            val gap = topOf(target)
            if (gap > 0 && canScrollVertically(1)) {
                val d = minOf(dy, gap)
                scrollBy(0, d)
                consumed[1] = d
            }
        }
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        if (velocityY > 0 && topOf(target) > 0 && canScrollVertically(1)) { fling(velocityY.toInt()); return true }
        return false
    }
}

/** Children side by side at a fixed card width, for horizontal carousels. */
class CarouselRow(ctx: Context, private val item: Int, private val gap: Int) : ViewGroup(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        var x = 0; var hh = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(item, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            x += item + gap; hh = max(hh, c.measuredHeight)
        }
        setMeasuredDimension(max(0, x - gap), hh)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.layout(x, 0, x + item, c.measuredHeight)
            x += item + gap
        }
    }
}

/**
 * Lays children out in equal columns, as many as fit at [minChild] px
 * (or, with [wrap], at their own widths like words in a line).
 */
class FlowGrid(ctx: Context, private val minChild: Int, private val gap: Int, private val wrap: Boolean = false) : ViewGroup(ctx) {
    /** Every child in one row (Home's cards), however narrow. */
    var singleRow = false
    /** Home, fitted: the cells share the exact height given (see FitBody); [stack]: one column. */
    var fill = false
    var stack = false
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val width = MeasureSpec.getSize(wSpec)
        if (wrap) {
            var x = 0; var y = 0; var line = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                c.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + c.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
                x += c.measuredWidth + gap
                line = max(line, c.measuredHeight)
            }
            setMeasuredDimension(width, y + line)
            return
        }
        val visible = (0 until childCount).count { getChildAt(it).visibility != GONE }
        val exactH = fill && MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY
        val cols = if (exactH && stack) 1 else if (singleRow) max(1, visible) else max(1, (width + gap) / (max(1, minChild) + gap))
        val cw = (width - gap * (cols - 1)) / cols
        if (exactH) {
            val rows = max(1, (visible + cols - 1) / cols)
            val ch = max(0, (MeasureSpec.getSize(hSpec) - gap * (rows - 1)) / rows)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(width, MeasureSpec.getSize(hSpec))
            return
        }
        var y = 0; var rowH = 0; var col = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            rowH = max(rowH, c.measuredHeight)
            if (++col == cols) { y += rowH + gap; rowH = 0; col = 0 }
        }
        setMeasuredDimension(width, if (col == 0) max(0, y - gap) else y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        if (wrap) {
            var x = 0; var y = 0; var line = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == GONE) continue
                if (x > 0 && x + c.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
                val left = if (rtl) width - x - c.measuredWidth else x
                c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth + gap
                line = max(line, c.measuredHeight)
            }
            return
        }
        val visible = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        val cols = if (fill && stack) 1 else if (singleRow) max(1, visible.size) else max(1, (width + gap) / (max(1, minChild) + gap))
        val cw = (width - gap * (cols - 1)) / cols
        var y = 0
        var i = 0
        while (i < visible.size) {
            val row = visible.subList(i, minOf(i + cols, visible.size))
            val rowH = row.maxOf { it.measuredHeight }
            for ((k, c) in row.withIndex()) {
                val x = k * (cw + gap)
                val left = if (rtl) width - x - cw else x
                c.layout(left, y, left + cw, y + rowH)
            }
            y += rowH + gap
            i += cols
        }
    }
}

