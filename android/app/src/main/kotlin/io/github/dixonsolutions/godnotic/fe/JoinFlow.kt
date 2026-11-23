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

    /**
     * The one bar of a join: downloads (when there are any) fill the first half, precache and the map
     * load the rest; the load part is [JoinFlow.progress]'s value (-1 when it has nothing yet, then the half stays).
     */
    fun overall(stage: String, sawDownload: Boolean, download: Double, load: Double): Double {
        val base = if (sawDownload) 0.5 else 0.0
        if (stage == "downloading") return (download.coerceIn(0.0, 1.0) * 0.5).coerceAtLeast(0.01)
        if (stage == "connecting") return -1.0
        val l = if (load < 0) 0.0 else load.coerceIn(0.0, 1.0)
        return (base + (1 - base) * l).coerceAtLeast(0.01)
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
        if (s.waitS >= 0) {
            val parts = ArrayList<String>()
            if (s.sigBytes >= 1024) parts.add(size(s.sigBytes) + " received")
            if (s.waitS >= 2) parts.add("${s.waitS} s")
            return Lines("Receiving game data from the server", parts.joinToString("  ·  "))
        }
        return when (s.stage) {
            "connecting" -> Lines(if (s.status.isNotEmpty() && s.status.startsWith("Connect")) s.status else "Connecting to the server", "")
            "downloading" -> {
                val parts = ArrayList<String>()
                val files = maxOf(s.filesTotal, s.running + s.queued)
                if (files > 1) parts.add("File ${minOf(s.filesDone + 1, files)} of $files")
                if (s.dlTotal > 0) parts.add(sizes(s.dlBytes, s.dlTotal).replace("/", " of ")) else if (s.dlBytes > 0) parts.add(size(s.dlBytes) + " received")
                rate(s.speed).let { if (it.isNotEmpty()) parts.add(it) }
                eta(s.dlBytes, s.dlTotal, s.speed).let { if (it.isNotEmpty()) parts.add(it) }
                val name = clip(s.dlFile.substringAfterLast('/'))
                Lines(if (name.isNotEmpty()) "Downloading $name" else "Downloading game data", parts.joinToString("  ·  "))
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
