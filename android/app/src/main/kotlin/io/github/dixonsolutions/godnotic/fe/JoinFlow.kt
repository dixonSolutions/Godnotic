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
 * The banner (the Godnotic logo on black) belongs to the waiting, not to the
 * join: it is up until the world or the map's levelshot is behind the card, and
 * once the preview has shown it is never drawn again, whatever the engine's
 * stage says afterwards.
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

    /** The logo belongs to the wait, once. */
    fun banner(stage: String, previewSeen: Boolean): Boolean = !preview(previewSeen = previewSeen, stage = stage)
}
