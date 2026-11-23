package io.github.dixonsolutions.godnotic.fe

/**
 * What "Play" does in the model viewer: the model's animations in turn. The engine's
 * `.framegroups` file lists each as `first count fps loop`; a model without one plays all
 * its frames as one. Plain Kotlin, JVM-tested.
 */
class ModelAnim(groups: List<FloatArray>, private val frameCount: Int, defaultFps: Float) {
    private val list: List<FloatArray> = groups
        .filter { it[0] >= 0f && it[0] < frameCount && it[1] >= 1f }
        .map { floatArrayOf(it[0], minOf(it[1], frameCount - it[0]), if (it[2] > 0f) it[2] else defaultFps) }
        .ifEmpty { if (frameCount > 0) listOf(floatArrayOf(0f, frameCount.toFloat(), defaultFps)) else emptyList() }
    val count: Int get() = list.size
    var index = 0; private set
    var playing = false

    /** The next animation (wrapping); the clip starts again. */
    fun next() { if (list.isNotEmpty()) { index = (index + 1) % list.size; time = 0f } }
    private var time = 0f

    /** Advances [dt] seconds while playing; the frame to show (fractional), looping inside the group. */
    fun frame(dt: Float): Float {
        if (list.isEmpty()) return 0f
        val g = list[index]
        if (playing) time += dt * g[2]
        val n = g[1]
        val t = if (n <= 1f) 0f else time % n
        return g[0] + t
    }

    fun title(): String = if (list.size > 1) "${index + 1} / ${list.size}" else ""
}
