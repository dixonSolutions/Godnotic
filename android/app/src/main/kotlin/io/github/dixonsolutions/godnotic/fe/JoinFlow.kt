package io.github.dixonsolutions.godnotic.fe

/**
 * When the join/load card may be on screen.
 *
 * A join starts with the native screens visible (the browser that launched it).
 * The card belongs over the engine's surface even then: the old rule dropped it
 * whenever the overlay was visible, which left the opaque native screen over the
 * preview. Once the join dialog is up that dialog is the join screen, so the
 * card steps aside instead of stacking under it.
 *
 * A stage must last [SETTLE_MS] before the card appears, so a join that answers
 * at once does not flash a loading screen for a few frames. That wait applies to
 * creating the card only: the card lives for the whole join, because tearing it
 * down and building the next stage's one put the black page and the banner on
 * screen again for every step (the "flashing logos").
 *
 * There is no logo page in a join any more: the engine always names a picture
 * (`levelshot`: the map's own, the one the server list's map name finds, or a generic
 * map picture marked `levelshot_kind` "generic", which the card dims a little), so the
 * card has a map behind it from the first frame, the map's own the moment its pack
 * (or its picture) arrives, then the live world.
 */
internal object JoinFlow {
    private val busy = setOf("connecting", "downloading", "loading", "signon", "preview")

    private val loadStages = setOf("loading", "preview")

    const val SETTLE_MS = 300L

    /**
     * Where the card sits in its screen: centred, at the BOTTOM, in every phase (the owner's tablet
     * showed it docked at the top over the map picture, 1.2.313-316). The map's picture fills the
     * rest of the screen. The screen is created with this gravity, so there is no frame in which the
     * card is anywhere else, and [FrontendHost.applyLook] sets the same value on every look change.
     */
    const val CARD_GRAVITY: Int = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM

    fun isBusy(stage: String): Boolean = stage in busy

    fun settled(stageSince: Long, now: Long): Boolean = now - stageSince >= SETTLE_MS

    /**
     * [hasCard] is the card already on screen for this join: it stays through
     * the stages that follow instead of being rebuilt (each build is another
     * black page with the logo).
     */
    @Suppress("UNUSED_PARAMETER")
    fun card(stage: String, joinDialog: Boolean, settled: Boolean, hasCard: Boolean = false, shot: Boolean = false): Boolean =
        isBusy(stage)

    /** How long the card waits, after the engine says idle, for the failure notice or the reconnect that explains it. */
    const val FAIL_GRACE_MS = 2500L

    /**
     * The card stays through a gap in a join: the engine reads `idle` for a moment between a
     * dropped connection and the reconnect, and between a failed attempt and its notice (a
     * second later, from the app topic). Tearing the card down there showed the menu for those
     * moments. It stays while the join has not ended: a failure to show ([failed]), a reconnect
     * under way, or the app has not entered the game and the grace ([FAIL_GRACE_MS]) is not over.
     * A cancel, and the game itself ([appState] `ingame`/`paused`), end it at once.
     */
    fun holdCard(stage: String, appState: String, hasCard: Boolean, failed: Boolean, reconnecting: Boolean, idleMs: Long, cancelled: Boolean): Boolean {
        if (isBusy(stage)) return true
        if (!hasCard || cancelled) return false
        if (failed) return true
        if (appState == "ingame" || appState == "paused") return false
        return reconnecting || idleMs < FAIL_GRACE_MS
    }

    /**
     * The connection was lost in the game and the engine joins again by itself (touch_net.c): the
     * app topic then reads `menu` (nothing is connected for a moment) with the reconnect text.
     * That is a join: the card takes the screen with the first such snapshot, before the first
     * `connect` snapshot of the new attempt, instead of the main menu showing in between.
     */
    fun reconnectCard(appState: String, reconnect: String, hasCard: Boolean): Boolean =
        reconnect.isNotEmpty() && !hasCard && appState in setOf("menu", "loading", "connecting")

    /**
     * The map's picture (the engine's connect `levelshot`: a file once the map is known, a
     * local game's pick before any server exists) is what the card shows until the world is
     * drawable (`world`). The engine cannot draw it itself during a blocking local load, so
     * the card does, from the first snapshot: no settle wait, no logo.
     */
    fun showShot(shot: String, world: Boolean): Boolean = shot.isNotEmpty() && !world

    /** The preview is the live world: the card lets it show through. */
    fun transparent(stage: String, previewSeen: Boolean = false): Boolean =
        stage == "preview" || previewSeen

    /** The world (or the levelshot before it) is what to show, from now on for this join. */
    fun preview(stage: String, previewSeen: Boolean, shot: Boolean = false): Boolean = previewSeen || stage == "preview" || shot

    /** The logo never shows in a join: a picture, a dim backdrop or the world is behind the card. */
    @Suppress("UNUSED_PARAMETER")
    fun banner(stage: String, previewSeen: Boolean): Boolean = false

    /**
     * The picture the card shows, with where it came from. A join's picture must appear once and stay: a
     * snapshot without one is a gap (the engine clears it between stages and when its preview goes off, a
     * NewMap), not a change; a different file replaces the shown one only when it is a better kind, and the
     * shown one is kept until the new one is decoded (no frame without a picture in between).
     */
    data class Shot(val path: String = "", val kind: String = "", val map: String = "")

    /** generic stand-in < the server list's cached picture < the map's own (pack, signon or BSP picture). */
    fun kindRank(kind: String): Int = when (kind) { "map" -> 3; "list" -> 2; "generic" -> 1; else -> 0 }

    /** What the card shows after a snapshot offering [incoming] while it shows [current]. */
    fun nextShot(current: Shot, incoming: Shot): Shot {
        if (incoming.path.isEmpty()) return current
        if (current.path.isEmpty()) return incoming
        if (incoming.path == current.path) return if (incoming.kind == current.kind) current else incoming
        if (incoming.map.isNotEmpty() && current.map.isNotEmpty() && incoming.map != current.map) return incoming
        return if (kindRank(incoming.kind) > kindRank(current.kind)) incoming else current
    }

    /** Another map's picture: the old one must not stay (it would be the wrong map); a better one of the same map fades in. */
    fun sameJoin(current: Shot, next: Shot): Boolean = current.path.isEmpty() || current.map.isEmpty() || next.map.isEmpty() || current.map == next.map

    /** The engine's world takes the screen from the picture only once it has been drawable this long (a flip back at once keeps the picture). */
    const val WORLD_SETTLE_MS = 600L
    fun worldShows(world: Boolean, worldSince: Long, now: Long): Boolean = world && worldSince > 0 && now - worldSince >= WORLD_SETTLE_MS

    /** The picture the card starts with: the engine's, else the dialog's, else a local one (a fetched map picture, or the art made from the name): never none. */
    fun firstShot(engine: String, hint: String?, local: String?): String = engine.ifEmpty { hint.orEmpty() }.ifEmpty { local.orEmpty() }

    /** The bar never goes back inside a join, and never returns to the sweep once it has a number. */
    fun monotonic(prev: Double, next: Double): Double = if (next < 0) prev else maxOf(prev, next)

    /** Top and bottom of the backdrop that fills the whole screen when there is no picture (the card floats over it). */
    const val BACKDROP_TOP = 0xFF0B1730.toInt()
    const val BACKDROP_BOTTOM = 0xFF1D4A8C.toInt()

    /** One layer of the join screen, rows [top, bottom) of a screen [h] rows high. */
    data class Layer(val name: String, val top: Int, val bottom: Int, val paints: Boolean)

    /**
     * What FrontendHost composes: the backdrop (the picture, or the gradient) is the screen's own background over ALL
     * rows; the map-name banner is a transparent text box above the card; the card floats at the bottom. No layer
     * other than the backdrop paints a background behind or below the card (the "wedge").
     */
    fun layers(h: Int, cardTop: Int, cardBottom: Int): List<Layer> = listOf(
        Layer("backdrop", 0, h, true), Layer("banner", 0, cardTop, false), Layer("card", cardTop, cardBottom, true),
    )

    /** The gradient's colour at row [y] of [h]: a straight blend, so no row differs from its neighbours by more than one step. */
    fun backdropColorAt(y: Int, h: Int): Int {
        val f = if (h <= 1) 0f else y.coerceIn(0, h - 1) / (h - 1f)
        fun ch(s: Int) = (((BACKDROP_TOP shr s) and 255) * (1 - f) + ((BACKDROP_BOTTOM shr s) and 255) * f).toInt()
        return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Cross-fade length when a better picture replaces the shown one. */
    const val FADE_MS = 200

    /** The picture is the generic stand-in (not this map's): dimmed a little, never blurred. */
    fun generic(kind: String): Boolean = kind == "generic"

    /** How dark the generic picture is drawn (1 = as is). */
    const val GENERIC_DIM = 0.75f

    /** Decode the original picture without resampling or a JPEG thumbnail. */
    const val SHOT_WIDTH = Int.MAX_VALUE

    /** MB with one decimal, KB under 1 MB: "12.3 MB", "640 KB". */
    fun size(bytes: Double): String =
        if (bytes >= 1048576.0) String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        else String.format(java.util.Locale.US, "%d KB", (bytes / 1024.0).toInt())

    /** Speed: "1.2 MB/s" or "640 KB/s"; empty when not moving. */
    fun rate(bytesPerSecond: Double): String =
        if (bytesPerSecond <= 1024.0) "" else size(bytesPerSecond) + "/s"

    /**
     * What the card says under the title: the stage, never a single sound or model file.
     * Downloading: "Downloading wind2.pk3 12.3/40.0 MB . 1.2 MB/s" (and the file count when
     * several). Precaching: "Precaching sounds 12/80". Else "Loading map <name>"; the
     * engine's own load line names whatever file it is on (loop_rain_04), so it is only
     * used when it is not a file: a phase such as "Starting the server".
     */
    fun detail(
        stage: String, file: String, bytes: Double, total: Double, speed: Double, downloads: Int,
        load: String, precache: String, precacheN: Int, precacheTotal: Int, map: String = "",
    ): String = when (stage) {
        "connecting" -> "Waiting for the server"
        "downloading" -> {
            val name = file.substringAfterLast('/')
            val of = if (bytes > 0 && total > 0) " " + sizes(bytes, total) else if (bytes > 0) "  ·  " + size(bytes) else ""
            val r = rate(speed)
            val files = if (downloads > 1) "  ·  $downloads files" else ""
            "Downloading $name$of" + (if (r.isNotEmpty()) "  ·  $r" else "") + files
        }
        "signon" -> "Entering the game"
        else -> when {
            precache.isNotEmpty() && precacheTotal > 0 -> "Precaching $precache $precacheN/$precacheTotal"
            map.isNotEmpty() -> "Loading map $map"
            phase(load) -> load.replaceFirstChar { it.uppercase() }
            else -> "Loading the map"
        }
    }

    /** All downloads as one line: "Downloading wind2.pk3 · 12.3 of 40.0 MB · 1.2 MB/s · 0:20 left · 3 waiting". */
    fun downloading(file: String, bytes: Double, total: Double, speed: Double, running: Int, queued: Int): String {
        val name = file.substringAfterLast('/').let { if (it.length > 28) it.take(27) + "…" else it }
        val parts = ArrayList<String>()
        parts.add("Downloading $name")
        if (total > 0) parts.add(sizes(bytes, total).replace("/", " of ")) else if (bytes > 0) parts.add(size(bytes))
        val r = rate(speed)
        if (r.isNotEmpty()) parts.add(r)
        if (speed > 1024 && total > bytes) {
            val left = ((total - bytes) / speed).toInt()
            parts.add(String.format(java.util.Locale.US, "%d:%02d left", left / 60, left % 60))
        }
        if (running + queued > 1) parts.add("${running + queued} files")
        return parts.joinToString("  ·  ")
    }

    /** What a stock server sends first (serverinfo and the precache lists): about 22 KB, a round trip per KB. */
    const val SIGNON_EXPECTED = 22.0 * 1024

    /** The share of the one bar the server's own data takes, and the downloads (when this join has any). */
    const val BAR_SIGNON = 0.2
    const val BAR_DOWNLOAD = 0.4

    /**
     * How much of the server's first data has arrived, 0..0.97. [expected] is what the last join
     * received (else [SIGNON_EXPECTED]); a server that sends more than that stretches it, so the
     * bar never sits full while data still comes.
     */
    fun signonFraction(sigBytes: Double, expected: Double = SIGNON_EXPECTED): Double =
        (sigBytes / maxOf(expected, sigBytes * 1.02, 1.0)).coerceIn(0.0, 0.97)

    /**
     * The one bar of a join, moving through every phase: the server's data (waiting: the engine reports
     * how many bytes arrived) is the first fifth, downloads (when this join has any) the next two fifths,
     * precache and the map load the rest. [load] is [JoinFlow.progress]'s value (-1 when it has nothing yet,
     * then the part stays). Connecting has nothing to measure: -1, a sweep.
     */
    fun overall(
        stage: String, sawDownload: Boolean, download: Double, load: Double,
        waiting: Boolean = false, signon: Double = 0.0,
    ): Double {
        if (waiting) return (0.01 + (BAR_SIGNON - 0.01) * signon.coerceIn(0.0, 1.0))
        val base = BAR_SIGNON + if (sawDownload) BAR_DOWNLOAD else 0.0
        if (stage == "downloading") return (BAR_SIGNON + BAR_DOWNLOAD * download.coerceIn(0.0, 1.0)).coerceAtLeast(0.01)
        if (stage == "connecting") return -1.0
        val l = if (load < 0) 0.0 else load.coerceIn(0.0, 1.0)
        return (base + (1 - base) * l).coerceAtLeast(0.01)
    }

    /** Bytes this join has used from the network so far: the server's first data and every download (the card shows it). */
    fun used(sigBytes: Double, downloadBytes: Double): Double = sigBytes + downloadBytes

    /** Mobile data and a Wi-Fi marked metered are metered; offline is nothing to measure. */
    fun metered(kind: io.github.dixonsolutions.godnotic.Net.Kind): Boolean = when (kind) {
        io.github.dixonsolutions.godnotic.Net.Kind.MOBILE,
        io.github.dixonsolutions.godnotic.Net.Kind.MOBILE_WIFI_NO_INTERNET,
        io.github.dixonsolutions.godnotic.Net.Kind.METERED_WIFI -> true
        else -> false
    }

    /** On a metered link the card says so and how much the join has used: "mobile data  ·  12.3 MB used" (nothing on an unmetered one). */
    fun usage(usedBytes: Double, metered: Boolean): String {
        if (!metered) return ""
        return if (usedBytes >= 100 * 1024.0) "mobile data  ·  " + size(usedBytes) + " used" else "mobile data"
    }

    /** The engine names the server's own download (the game code of its CSQC) by file; the card says what it is. */
    fun downloadName(file: String): String {
        val n = file.substringAfterLast('/')
        return if (n.startsWith("csprogs.dat")) "the server's game code" else clip(n)
    }

    /**
     * The padding that keeps the docked card clear of the system: the larger of the system bars
     * and the cutout on each side, and at the bottom also the gesture area. [l,t,r,b] in pixels.
     */
    fun cardInsets(l: Int, t: Int, r: Int, b: Int, cutout: IntArray, gestureBottom: Int): IntArray =
        intArrayOf(maxOf(l, cutout[0]), maxOf(t, cutout[1]), maxOf(r, cutout[2]), maxOf(b, cutout[3], gestureBottom))

    /** The stage the card describes: a running or queued download is a download, whatever stage the engine reports. */
    fun shownStage(stage: String, downloadsBusy: Boolean): String =
        if (downloadsBusy && stage in setOf("preview", "loading", "signon", "downloading")) "downloading" else stage

    /** What the engine's connect topic says about a join, in the numbers the card needs (docs/FRONTEND_BRIDGE.md). */
    data class Snap(
        val stage: String = "", val waitS: Int = -1, val sigBytes: Double = 0.0,
        val dlFile: String = "", val filesDone: Int = 0, val filesTotal: Int = 0,
        val dlBytes: Double = 0.0, val dlTotal: Double = 0.0, val speed: Double = 0.0,
        val running: Int = 0, val queued: Int = 0,
        val loadStage: String = "", val loadN: Int = 0, val loadM: Int = 0,
        val precache: String = "", val precacheN: Int = 0, val precacheTotal: Int = 0,
        val load: String = "", val map: String = "", val status: String = "",
        val usedBytes: Double = 0.0, val metered: Boolean = false,
    )

    /** The card's two lines: what it is doing (head) and the numbers (detail). */
    data class Lines(val head: String, val detail: String)

    /** "0:19 left"; empty when the speed or the total is unknown. */
    fun eta(bytes: Double, total: Double, speed: Double): String {
        if (speed <= 1024.0 || total <= bytes) return ""
        val left = ((total - bytes) / speed).toInt()
        return if (left >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d left", left / 3600, left / 60 % 60, left % 60)
        else String.format(java.util.Locale.US, "%d:%02d left", left / 60, left % 60)
    }

    /** "Map textures 120 / 218", "Models 40 / 332", "Sounds": the precache step the engine reports (load_stage, load_n, load_m). */
    fun precacheStep(stage: String, n: Int, m: Int): String {
        val label = when (stage) {
            "models" -> "Models"
            "world" -> "Map textures"
            "worldsetup" -> "Setting up the world"
            "sounds" -> "Sounds"
            else -> return ""
        }
        return if (m > 0 && n >= 0 && stage != "worldsetup") "$label $n / $m" else label
    }

    private fun clip(name: String, max: Int = 36): String = if (name.length > max) name.take(max - 1) + "…" else name

    /**
     * The whole card text for a join: a headline and one line of numbers, in every phase.
     * Downloading names the file, "File 2 of 5", MB received of the total (all files of the join,
     * finished ones included), speed and time left. After the downloads the precache step.
     */
    fun describe(s: Snap): Lines {
        val l = describeStage(s)
        val u = usage(if (s.waitS >= 0) 0.0 else s.usedBytes, s.metered)
        return if (u.isEmpty()) l else Lines(l.head, listOf(l.detail, u).filter { it.isNotEmpty() }.joinToString("  ·  "))
    }

    private fun describeStage(s: Snap): Lines {
        if (s.waitS >= 0) {
            val parts = ArrayList<String>()
            if (s.sigBytes >= 1024) parts.add(size(s.sigBytes) + " received")
            if (s.waitS >= 2) parts.add("${s.waitS} s")
            return Lines("Receiving game data from the server", parts.joinToString("  ·  "))
        }
        return when (s.stage) {
            "connecting" -> Lines("Connecting…", "")
            "downloading" -> {
                val parts = ArrayList<String>()
                val files = maxOf(s.filesTotal, s.running + s.queued)
                if (files > 1) parts.add("File ${minOf(s.filesDone + 1, files)} of $files")
                if (s.dlTotal > 0) parts.add(sizes(s.dlBytes, s.dlTotal).replace("/", " of ")) else if (s.dlBytes > 0) parts.add(size(s.dlBytes) + " received")
                rate(s.speed).let { if (it.isNotEmpty()) parts.add(it) }
                eta(s.dlBytes, s.dlTotal, s.speed).let { if (it.isNotEmpty()) parts.add(it) }
                val name = downloadName(s.dlFile)
                Lines(if (s.dlFile.isNotEmpty()) "Downloading $name" else "Downloading game data", parts.joinToString("  ·  "))
            }
            "signon" -> Lines("Entering the game", "")
            else -> {
                val step = precacheStep(s.loadStage, s.loadN, s.loadM)
                    .ifEmpty { if (s.precache.isNotEmpty() && s.precacheTotal > 0) precacheStep(s.precache, s.precacheN, s.precacheTotal) else "" }
                val head = if (s.map.isNotEmpty()) "Loading map ${s.map}" else if (phase(s.load)) s.load.replaceFirstChar { it.uppercase() } else "Loading the map"
                Lines(head, step)
            }
        }
    }

    /** The server's own signon data (serverinfo, precache lists) is arriving: nothing loads or downloads yet. */
    fun waiting(seconds: Int): String =
        "Receiving game data from the server" + if (seconds >= 2) "  ·  $seconds s" else ""

    /** "12.3/40.0 MB" (one unit, the total's), "512/900 KB". */
    fun sizes(bytes: Double, total: Double): String =
        if (total >= 1048576.0) String.format(java.util.Locale.US, "%.1f/%.1f MB", bytes / 1048576.0, total / 1048576.0)
        else String.format(java.util.Locale.US, "%d/%d KB", (bytes / 1024.0).toInt(), (total / 1024.0).toInt())

    /** The engine's load line is a phase ("starting server"), not a file or a "Loading x" for one. */
    private fun phase(load: String): Boolean =
        load.isNotEmpty() && !load.startsWith("*") && !load.contains('/') && !load.contains('.') &&
            !load.startsWith("Loading ", true) && !load.startsWith("loop_") && !load.contains('_')

    /**
     * The bar's fraction 0..1, or -1 for an indeterminate bar (nothing to report yet).
     * Downloading: the download's. Loading: the engine's loading stack value (the band
     * the engine no longer draws), or the precache count when the stack says nothing.
     */
    fun progress(stage: String, download: Double, load: Double, precache: String, precacheN: Int, precacheTotal: Int): Double {
        val counted = precache.isNotEmpty() && precacheTotal > 0
        val p = when {
            stage == "downloading" -> download
            // the precache count is known in any stage after the download (1/283 is a bar at its start, not a slide)
            stage != "connecting" && load < 0.02 && counted -> maxOf(precacheN.toDouble() / precacheTotal, 0.01)
            stage in loadStages || (stage == "signon" && counted) -> load
            else -> 0.0
        }
        // a load with nothing to report yet is a determinate bar at its start, not a sliding one
        if (p < 0.01 && stage in loadStages) return 0.01
        return if (p >= 0.01) p.coerceAtMost(1.0) else -1.0
    }
}
