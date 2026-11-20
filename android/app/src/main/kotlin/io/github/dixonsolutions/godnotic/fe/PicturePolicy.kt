package io.github.dixonsolutions.godnotic.fe

/**
 * What a picture view does the moment its picture is asked for (ViewRenderer.picture), as plain
 * logic so a JVM test can pin it: a placeholder (the shimmer) is for a picture the engine still has
 * to produce, never for one whose file is already known, and a card whose map changes keeps its old
 * picture until the new one is there.
 */
object PicturePolicy {
    enum class First {
        /** The file is known: draw it with the view (the bitmap is cached, or decoded now). No placeholder. */
        SHOW,
        /** The file is not known yet and the view has no picture: the placeholder, until the engine answers. */
        PLACEHOLDER,
        /** The file is not known yet; what the view shows now stays until the new picture is there. */
        KEEP,
    }

    fun first(pathKnown: Boolean, viewHasPicture: Boolean): First = when {
        pathKnown -> First.SHOW
        viewHasPicture -> First.KEEP
        else -> First.PLACEHOLDER
    }

    /** Whether a picture the screen's first frame needs is decoded on the calling thread (a few ms) rather than queued. */
    fun decodeNow(sync: Boolean, cachedInMemory: Boolean) = sync && !cachedInMemory
}
