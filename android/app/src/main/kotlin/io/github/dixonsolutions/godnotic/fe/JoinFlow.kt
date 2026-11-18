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
 * map picture marked `levelshot_kind` "generic", which the card dims and blurs), so the
 * card has a map behind it from the first frame, the map's own the moment its pack
 * (or its picture) arrives, then the live world.
 */
internal object JoinFlow {
    private val busy = setOf("connecting", "downloading", "loading", "signon", "preview")

    const val SETTLE_MS = 300L

    fun isBusy(stage: String): Boolean = stage in busy

    fun settled(stageSince: Long, now: Long): Boolean = now - stageSince >= SETTLE_MS

    /**
     * [hasCard] is the card already on screen for this join: it stays through
     * the stages that follow instead of being rebuilt (each build is another
     * black page with the logo).
     */
    fun card(stage: String, joinDialog: Boolean, settled: Boolean, hasCard: Boolean = false, shot: Boolean = false): Boolean =
        isBusy(stage) && !joinDialog && (settled || hasCard || shot)

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

    /** The picture is the generic stand-in (not this map's): dimmed and blurred. */
    fun generic(kind: String): Boolean = kind == "generic"

    /** How dark the generic picture is drawn (1 = as is). */
    const val GENERIC_DIM = 0.5f

    /** MB with one decimal, KB under 1 MB: "12.3 MB", "640 KB". */
    fun size(bytes: Double): String =
        if (bytes >= 1048576.0) String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        else String.format(java.util.Locale.US, "%d KB", (bytes / 1024.0).toInt())

    /** Speed: "1.2 MB/s" or "640 KB/s"; empty when not moving. */
    fun rate(bytesPerSecond: Double): String =
        if (bytesPerSecond <= 1024.0) "" else size(bytesPerSecond) + "/s"

    /**
     * What the card says under the title: the stage and what is loading.
     * Downloading: the file, `x of y MB`, the speed (and which file of how many when
     * several). Precaching: "Precaching models 12/40". Else loading the map.
     */
    fun detail(
        stage: String, file: String, bytes: Double, total: Double, speed: Double, downloads: Int,
        load: String, precache: String, precacheN: Int, precacheTotal: Int,
    ): String = when (stage) {
        "connecting" -> "Waiting for the server"
        "downloading" -> {
            val name = file.substringAfterLast('/')
            val of = if (bytes > 0 && total > 0) "  ·  ${size(bytes)} of ${size(total)}" else if (bytes > 0) "  ·  ${size(bytes)}" else ""
            val r = rate(speed)
            val files = if (downloads > 1) "  ·  $downloads files" else ""
            "Downloading $name$of" + (if (r.isNotEmpty()) "  ·  $r" else "") + files
        }
        "signon" -> "Entering the game"
        else -> when {
            precache.isNotEmpty() && precacheTotal > 0 -> "Precaching $precache $precacheN/$precacheTotal"
            load.isEmpty() || load.startsWith("*") -> "Loading the map"
            load.contains('/') -> "Loading " + load.substringAfterLast('/').substringBeforeLast('.')
            else -> load.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * The bar's fraction 0..1, or -1 for an indeterminate bar (nothing to report yet).
     * Downloading: the download's. Loading: the engine's loading stack value (the band
     * the engine no longer draws), or the precache count when the stack says nothing.
     */
    fun progress(stage: String, download: Double, load: Double, precache: String, precacheN: Int, precacheTotal: Int): Double {
        val p = when (stage) {
            "downloading" -> download
            "loading", "preview" -> if (load >= 0.02) load else if (precache.isNotEmpty() && precacheTotal > 0) precacheN.toDouble() / precacheTotal else load
            else -> 0.0
        }
        return if (p >= 0.01) p.coerceAtMost(1.0) else -1.0
    }
}
