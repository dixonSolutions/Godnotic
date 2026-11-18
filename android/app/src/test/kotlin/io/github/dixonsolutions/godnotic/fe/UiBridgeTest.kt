package io.github.dixonsolutions.godnotic.fe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin side of the native interface that runs without Android: the
 * JSON reader, the UI bridge store and client (snapshots, diffs, list
 * windows) driving a recording renderer, row diffs, sticky placement, the
 * `fe` argument encoding and join links. Fixtures come from
 * tests/uibridge/fixtures (docs/UI_BRIDGE.md section 13) when the tree has
 * them (-Puib.fixtures=<dir>), and from the json files of the app assets directory fe.
 */
class UiBridgeTest {
    @Test fun serverDetailMapPictureFitsPortraitAndLandscape() {
        assertEquals(640, ServerDetails.imageMaxHeightPx(1200, 1920))
        assertEquals(400, ServerDetails.imageMaxHeightPx(1920, 1200))
        assertEquals(0, ServerDetails.imageMaxHeightPx(100, 0))
    }

    /** Records renderer operations as lines. */
    class Recorder : UiRenderer {
        val ops = ArrayList<String>()
        val created = LinkedHashMap<String, String>() // id -> type
        val parents = HashMap<String, String?>()
        val rows = HashMap<String, Int>()
        override fun create(id: String, parent: String?, index: Int, type: String, props: Map<String, Any?>, hints: Map<String, Any?>) {
            ops.add("create $id $type ${parent ?: "-"}"); created[id] = type; parents[id] = parent
        }
        override fun update(id: String, props: Map<String, Any?>) { ops.add("update $id") }
        override fun remove(id: String) { ops.add("remove $id"); created.remove(id) }
        override fun navigate(screen: String, transition: String) { ops.add("navigate $screen $transition") }
        override fun dialog(id: String?) { ops.add("dialog ${id ?: "-"}") }
        override fun toast(text: String, kind: String) { ops.add("toast $text") }
        override fun listRows(source: String, offset: Int, total: Int, rows: List<Map<String, Any?>>, rev: Long) { ops.add("rows $source $offset ${rows.size}/$total/$rev"); this.rows[source] = total }
        override fun sourceChanged(source: String, total: Int, rev: Long) { ops.add("source $source $total/$rev") }
        override fun imageReady(ref: String, path: String) { ops.add("image $ref") }
    }

    private fun fixtureFiles(): List<File> {
        val dirs = listOfNotNull(System.getProperty("uib.fixtures"), "src/main/assets/fe").map { File(it) }
        return dirs.filter { it.isDirectory }.flatMap { d -> d.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name } }
    }

    private fun load(f: File) = Json.parse(f.readText()).obj()

    /** A filtered empty answer must not be read as a notice: only a notice re-windows. */
    @Test fun emptyWindowAndSourceNoticeRemainDistinct() {
        val rec = Recorder()
        val client = UibClient(rec) {}
        client.receive(mapOf("t" to "rows", "source" to "servers", "rev" to 11.0, "re" to 7.0, "offset" to 0.0, "total" to 0.0, "rows" to emptyList<Any>()))
        val reply = rec.ops.last()
        assertTrue("a window reply is not a source notice", reply.startsWith("rows "))
        client.receive(mapOf("t" to "source", "source" to "servers", "rev" to 12.0, "total" to 3.0))
        assertTrue("the notice is its own operation", rec.ops.last() != reply && rec.ops.last() == "source servers 3/12")
    }

    @Test fun serverListStateSurvivesRefreshAndRebuild() {
        val state = ServerListState()
        state.choose("players", true)
        val refreshed = mapOf<String, Any?>("sort" to "ping", "desc" to false,
            "selected" to listOf("example:26000"), "empty" to true, "full" to true, "laggy" to true)
        val merged = state.merge(refreshed)
        assertEquals("players", merged["sort"])
        assertEquals(true, merged["desc"])
        assertEquals(listOf("example:26000"), merged["selected"])
        for (chip in listOf("empty", "full", "laggy")) assertEquals(true, merged[chip])
        assertEquals(merged, state.merge(refreshed)) // a newly built list uses the same state
    }

    @Test fun jsonRoundTrip() {
        val text = """{"a":[1,2.5,"x\n\"y\"",true,null],"b":{"c":"é"}}"""
        val v = Json.parse(text)
        assertEquals(v, Json.parse(Json.write(v)))
        assertEquals("é", v.obj()["b"].obj().str("c"))
        assertNull(Json.parseOrNull("{\"a\":"))
    }

    @Test fun everyFixtureRendersWithoutSync() {
        val files = fixtureFiles()
        assertTrue("no fixtures found", files.isNotEmpty())
        for (f in files) {
            val doc = load(f)
            val snap = doc["snapshot"].obj()
            if (snap.isEmpty()) continue
            val rec = Recorder()
            val sent = ArrayList<String>()
            val client = UibClient(rec) { sent.add(it) }
            client.receive(snap)
            assertTrue("${f.name}: asked for a sync", sent.none { it.contains("\"t\":\"sync\"") })
            val app = client.store.app!!
            val top = app.props["stack"].list().last().toString()
            assertTrue("${f.name}: no navigate to $top", rec.ops.contains("navigate $top replace"))
            // every node of the shown screen has a view, parents before children
            for (id in client.store.subtree(top)) {
                assertTrue("${f.name}: $id not created", rec.created.containsKey(id))
                val p = rec.parents[id]
                if (p != null) assertTrue("${f.name}: $id before its parent", rec.ops.indexOf("create $p ${rec.created[p]} ${rec.parents[p] ?: "-"}") < rec.ops.indexOf("create $id ${rec.created[id]} $p"))
            }
            // a modal on the snapshot is shown as a dialog
            val modal = app.props["modal"].list().map { it.toString() }
            if (modal.isNotEmpty()) assertTrue("${f.name}: dialog", rec.ops.contains("dialog ${modal.last()}"))
            // the steps of a sequence apply in order without a sync
            for (step in doc["steps"].list()) {
                client.receive(step.obj()["msg"].obj())
                assertTrue("${f.name}: a step asked for a sync", sent.none { it.contains("\"t\":\"sync\"") })
            }
            // rows of each source come through as one operation
            for ((source, rows) in doc["rows"].obj()) {
                val list = rows.list()
                client.receive(mapOf("t" to "rows", "source" to source, "offset" to 0.0, "total" to list.size.toDouble(), "rows" to list.take(50)))
                assertEquals(list.size, rec.rows[source])
            }
        }
    }

    @Test fun diffUpdatesInPlaceAndRemovesWhatLeft() {
        val rec = Recorder()
        val sent = ArrayList<String>()
        val c = UibClient(rec) { sent.add(it) }
        c.receive(mapOf("t" to "snapshot", "rev" to 1.0, "nodes" to listOf(
            mapOf("id" to "app", "type" to "app", "props" to mapOf("stack" to listOf("s")), "children" to listOf("s")),
            mapOf("id" to "s", "type" to "screen", "props" to mapOf("title" to "A"), "children" to listOf("s/a", "s/b")),
            mapOf("id" to "s/a", "type" to "switch", "props" to mapOf("value" to false)),
            mapOf("id" to "s/b", "type" to "text", "props" to mapOf("body" to "x")))))
        rec.ops.clear()
        c.receive(mapOf("t" to "diff", "base" to 1.0, "rev" to 2.0, "del" to listOf("s/b"),
            "set" to listOf(mapOf("id" to "s/a", "type" to "switch", "props" to mapOf("value" to true)),
                mapOf("id" to "s", "type" to "screen", "props" to mapOf("title" to "A"), "children" to listOf("s/a", "s/c")),
                mapOf("id" to "s/c", "type" to "button", "props" to mapOf("label" to "Go")))))
        assertTrue(rec.ops.toString(), rec.ops.contains("remove s/b"))
        assertTrue(rec.ops.toString(), rec.ops.contains("update s/a"))
        assertTrue(rec.ops.toString(), rec.ops.contains("create s/c button s"))
        assertFalse("the screen is not rebuilt", rec.ops.contains("create s screen -"))
        assertEquals(2L, c.store.rev)
        // a diff on the wrong base asks for a snapshot
        c.receive(mapOf("t" to "diff", "base" to 7.0, "rev" to 8.0, "set" to emptyList<Any>()))
        assertTrue(sent.last().contains("\"t\":\"sync\""))
    }

    @Test fun desktopAccessibilitySwitchRoundTripsThroughBridge() {
        val rec = Recorder()
        val sent = ArrayList<String>()
        val client = UibClient(rec) { sent.add(it) }
        val id = "settings/accessibility/desktop/assist"
        fun switch(value: Boolean) = mapOf("id" to id, "type" to "switch",
            "props" to mapOf("label" to "Enable auto shoot and aim assist",
                "bind" to "cvar:cl_accessibility_desktop", "value" to value))
        client.receive(mapOf("t" to "snapshot", "rev" to 1, "nodes" to listOf(
            mapOf("id" to "app", "type" to "app", "props" to mapOf("stack" to listOf("settings")), "children" to listOf("settings")),
            mapOf("id" to "settings", "type" to "screen", "props" to emptyMap<String, Any>(), "children" to listOf(id)),
            switch(false))))
        assertEquals("switch", rec.created[id])
        assertFalse(client.store.node(id)!!.bool("value"))
        client.event("value", mapOf("node" to id, "value" to true))
        val event = Json.parse(sent.single()).obj()
        assertEquals("value", event["ev"])
        assertEquals(id, event["node"])
        assertEquals(true, event["value"])
        client.receive(mapOf("t" to "diff", "base" to 1, "rev" to 2, "set" to listOf(switch(true))))
        assertTrue(client.store.node(id)!!.bool("value"))
        assertTrue(rec.ops.contains("update $id"))
    }

    @Test fun navigationPushPopAndToast() {
        val rec = Recorder()
        val c = UibClient(rec) {}
        fun app(stack: List<String>, kids: List<String>) = mapOf("id" to "app", "type" to "app", "props" to mapOf("stack" to stack, "canBack" to (stack.size > 1)), "children" to kids)
        c.receive(mapOf("t" to "snapshot", "rev" to 1.0, "nodes" to listOf(app(listOf("home"), listOf("home")), mapOf("id" to "home", "type" to "screen", "props" to emptyMap<String, Any>()))))
        c.receive(mapOf("t" to "diff", "base" to 1.0, "rev" to 2.0, "set" to listOf(app(listOf("home", "mp"), listOf("home", "mp", "t1")),
            mapOf("id" to "mp", "type" to "screen", "props" to emptyMap<String, Any>()),
            mapOf("id" to "t1", "type" to "toast", "props" to mapOf("text" to "Hi")))))
        assertTrue(rec.ops.toString(), rec.ops.contains("navigate mp push"))
        assertTrue(rec.ops.contains("toast Hi"))
        c.receive(mapOf("t" to "diff", "base" to 2.0, "rev" to 3.0, "del" to listOf("mp", "t1"), "set" to listOf(app(listOf("home"), listOf("home")))))
        assertTrue(rec.ops.toString(), rec.ops.contains("navigate home pop"))
        assertTrue(rec.ops.contains("remove mp"))
    }

    @Test fun listWindowAsksOncePerPage() {
        val w = ListWindow("l", 50)
        w.accept(mapOf("offset" to 0.0, "total" to 2000.0, "rows" to emptyList<Any>()))
        assertEquals(0..49, w.missing(0, 20))
        assertNull("asked already", w.missing(0, 20))
        w.accept(mapOf("offset" to 0.0, "total" to 2000.0, "rows" to List(50) { mapOf("key" to "k$it") }))
        assertNull(w.missing(0, 49))
        assertEquals(50..99, w.missing(40, 60))
        assertEquals(1950..1999, w.missing(1990, 2100))
        assertNotNull(w.row(10))
    }

    @Test fun rowDiffKeepsKeyedRows() {
        val d = UiDiff.rows(listOf("a", "b", "c"), listOf("a", "x", "c"), listOf(1, 2, 3), listOf(1, 9, 4))!!
        assertEquals(listOf(UiDiff.Op.Remove(1, 1), UiDiff.Op.Insert(1, 1), UiDiff.Op.Change(2, 1)), d.ops)
        assertNull("duplicate keys", UiDiff.rows(listOf("a", "a"), listOf("a"), listOf(1, 1), listOf(1)))
        assertEquals(emptyList<UiDiff.Op>(), UiDiff.rows(listOf("a"), listOf("a"), listOf(1), listOf(1))!!.ops)
    }

    @Test fun stickyPlacement() {
        assertEquals(Placement.TOP, UiLayout.placement("screen", "toolbar", emptyMap(), emptyMap()))
        assertEquals(Placement.BOTTOM, UiLayout.placement("screen", "actionbar", emptyMap(), emptyMap()))
        assertEquals(Placement.FILL, UiLayout.placement("screen", "list", emptyMap(), emptyMap()))
        assertEquals(Placement.BODY, UiLayout.placement("screen", "section", emptyMap(), emptyMap()))
        assertEquals(Placement.BODY, UiLayout.placement("section", "toolbar", emptyMap(), emptyMap()))
    }

    @Test fun feArgumentsAreOneSafeTokenEach() {
        assertEquals("~", FeArgs.encode(""))
        assertEquals("Tester%20%22One%22%3Bquit", FeArgs.encode("Tester \"One\";quit"))
        assertEquals("a%2F%2Fb%24c", FeArgs.encode("a//b\$c"))
        assertEquals("fe connect 10.0.0.2:26000", FeArgs.command("connect", "10.0.0.2:26000"))
        assertEquals("%C3%A9", FeArgs.encode("é"))
    }

    /** Xonotic's join dialog in a match is shown over the game, not on the pause page (Driver::over_game). */
    @Test fun joinDialogOverTheMatch() {
        val rec = Recorder()
        val client = UibClient(rec) { }
        client.receive(Json.parse("""{"uib":1,"t":"snapshot","rev":1,"nodes":[
            {"id":"app","type":"app","props":{"stack":["pause"],"modal":["join"],"canBack":true,"context":"game"},"children":["pause","join"]},
            {"id":"pause","type":"screen","props":{"title":"Paused","kind":"game"},"children":[]},
            {"id":"join","type":"screen","props":{"title":"Join game","presentation":"dialog","dismissable":true},"children":["join/spectate"]},
            {"id":"join/spectate","type":"button","props":{"label":"Spectate","action":{"id":"join.spectate"}},"children":[]}]}""").obj())
        assertTrue(rec.ops.contains("dialog join"))
        assertTrue(client.overGame)
        client.receive(Json.parse("""{"uib":1,"t":"diff","base":1,"rev":2,"del":["join","join/spectate"],
            "set":[{"id":"app","type":"app","props":{"stack":["pause"],"modal":[],"canBack":true,"context":"game"},"children":["pause"]}]}""").obj())
        assertFalse(client.overGame)
        val quit = UibClient(Recorder()) { }
        quit.receive(Json.parse("""{"uib":1,"t":"snapshot","rev":1,"nodes":[
            {"id":"app","type":"app","props":{"stack":["pause"],"modal":["dialog.quit"],"context":"game"},"children":["pause","dialog.quit"]},
            {"id":"pause","type":"screen","props":{"title":"Paused","kind":"game"},"children":[]},
            {"id":"dialog.quit","type":"screen","props":{"title":"Quit?","presentation":"dialog"},"children":[]}]}""").obj())
        assertFalse(quit.overGame)
    }

    /** The join card is the one join screen, also when a native screen launched the join. */
    @Test fun joinCardIsNotDroppedForAVisibleNativeScreen() {
        assertFalse(JoinFlow.settled(0, 299))
        assertTrue(JoinFlow.settled(0, 300))
        assertFalse(JoinFlow.card("connecting", joinDialog = false, settled = false))
        assertTrue(JoinFlow.card("connecting", joinDialog = false, settled = true))
        assertTrue(JoinFlow.card("preview", joinDialog = false, settled = true))
        assertFalse(JoinFlow.card("preview", joinDialog = true, settled = true))
        assertFalse(JoinFlow.card("idle", joinDialog = false, settled = true))
        assertTrue(JoinFlow.transparent("preview"))
        assertFalse(JoinFlow.transparent("loading"))
    }

    /**
     * The owner's "too many flashing logos": the join card is built once and
     * kept for the whole join (every rebuild painted the black page and the
     * banner again), and the banner is up only until the preview.
     */
    @Test fun joinLogoAppearsOnceAndOnlyUntilThePreview() {
        // built once, once it has settled...
        assertFalse(JoinFlow.card("connecting", joinDialog = false, settled = false, hasCard = false))
        assertTrue(JoinFlow.card("connecting", joinDialog = false, settled = true, hasCard = false))
        // ...then kept through the stages that follow, settled or not
        for (stage in listOf("downloading", "loading", "signon", "preview")) {
            assertTrue(JoinFlow.card(stage, joinDialog = false, settled = false, hasCard = true))
        }
        // the join is over, or the dialog is the join screen: the card goes
        assertFalse(JoinFlow.card("idle", joinDialog = false, settled = true, hasCard = true))
        assertFalse(JoinFlow.card("signon", joinDialog = true, settled = true, hasCard = true))

        // the logo is for the wait before the preview, once
        assertFalse(JoinFlow.banner("connecting", previewSeen = false))
        assertFalse(JoinFlow.banner("signon", previewSeen = false))
        assertFalse(JoinFlow.banner("preview", previewSeen = false))
        assertFalse(JoinFlow.banner("signon", previewSeen = true))
        assertFalse(JoinFlow.banner("loading", previewSeen = true))

        // and the preview, once seen, stays the background
        assertTrue(JoinFlow.preview("preview", previewSeen = false))
        assertTrue(JoinFlow.preview("signon", previewSeen = true))
        assertFalse(JoinFlow.preview("signon", previewSeen = false))
        assertTrue(JoinFlow.transparent("preview", previewSeen = false))
        assertTrue(JoinFlow.transparent("signon", previewSeen = true))
        assertFalse(JoinFlow.transparent("signon", previewSeen = false))
    }

    /** The map's picture shows at once: before the settle wait, before any server, until the world draws. */
    @Test fun joinShowsTheMapPictureImmediately() {
        assertTrue(JoinFlow.showShot("/data/fe_cache/maps_stormkeep.jpg", world = false))
        assertFalse(JoinFlow.showShot("/data/fe_cache/maps_stormkeep.jpg", world = true))
        assertFalse(JoinFlow.showShot("", world = false))
        // a local start: first snapshot, no settle wait, no card yet
        assertFalse(JoinFlow.card("loading", joinDialog = false, settled = false))
        assertTrue(JoinFlow.card("loading", joinDialog = false, settled = false, shot = true))
        assertTrue(JoinFlow.card("connecting", joinDialog = false, settled = false, shot = true))
        assertFalse(JoinFlow.card("idle", joinDialog = false, settled = false, shot = true))
        // the picture counts as the preview: the logo never shows
        assertTrue(JoinFlow.preview("loading", previewSeen = false, shot = true))
        assertFalse(JoinFlow.banner("loading", previewSeen = true))
    }

    @Test fun joinLinks() {
        assertEquals("192.168.1.5:26001", FrontendHost.parseJoin("godnotic://join/192.168.1.5:26001"))
        assertEquals("host.example:26000", FrontendHost.parseJoin("host.example"))
        assertEquals("[fd00::1]:26000", FrontendHost.parseJoin("[fd00::1]:26000"))
        assertNull(FrontendHost.parseJoin("evil;quit"))
        assertNull(FrontendHost.parseJoin("1.2.3.4:99999"))
    }

    /** Create's map picker after a mode change: the list follows its node's new source. */
    @Test fun listFollowsANewSource() {
        val lists = HashMap<String, MutableList<String>>()
        lists.getOrPut("maps.ctf") { ArrayList() }.add("sp/maps/list")
        assertFalse(moveSource(lists, "sp/maps/list", "maps.ctf", "maps.ctf"))
        assertTrue(moveSource(lists, "sp/maps/list", "maps.ctf", "maps.duel"))
        assertEquals(listOf("sp/maps/list"), lists["maps.duel"])
        assertNull(lists["maps.ctf"]) // rows for the old source reach nobody
        // the window starts over: the first page of the new source is asked for again
        val w = ListWindow("sp/maps/list", 60)
        w.accept(mapOf("offset" to 0.0, "total" to 9.0, "rows" to List(9) { mapOf("key" to "m$it") }))
        assertNull(w.missing(0, 8))
        w.invalidate()
        assertEquals(0..59, w.missing(0, 59))
    }
}
