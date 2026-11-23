package io.github.dixonsolutions.godnotic.fe

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The fullscreen model viewer's camera (ModelViewer): it circles the model, z up. A drag (a finger
 * or the mouse) turns the model; in free view it also tilts and a two-finger drag moves the view;
 * a pinch or the mouse wheel zooms. Plain Kotlin, no Android, so the JVM tests cover it.
 */
class OrbitCamera {
    var yaw = START_YAW; private set          // degrees around the model; 0 looks at its front (+x)
    var pitch = START_PITCH; private set      // degrees above its horizon
    var zoom = 1f; private set                // 1 fits the whole model; larger is closer
    var panX = 0f; private set                // free view: the view's move sideways and up, in screen heights
    var panY = 0f; private set
    var free = false

    /** One finger or the mouse moved by dx, dy pixels: always turns; free view also tilts. */
    fun drag(dx: Float, dy: Float) {
        yaw -= dx * TURN_PER_PX
        if (free) pitch = (pitch + dy * TURN_PER_PX).coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    /** Two fingers moved the view by dx, dy pixels on a view [heightPx] high; only in free view. */
    fun pan(dx: Float, dy: Float, heightPx: Float) {
        if (!free || heightPx <= 0f) return
        // the model follows the fingers: the looked-at point moves the other way
        panX = (panX - dx / heightPx).coerceIn(-1f, 1f)
        panY = (panY + dy / heightPx).coerceIn(-1f, 1f)
    }

    /** A pinch: [scale] above 1 spreads the fingers (closer). */
    fun pinch(scale: Float) { if (scale > 0f) zoom = (zoom * scale).coerceIn(MIN_ZOOM, MAX_ZOOM) }

    /** The mouse wheel: [steps] above 0 scrolls up (closer). */
    fun wheel(steps: Float) { zoom = (zoom * WHEEL_STEP.pow(steps)).coerceIn(MIN_ZOOM, MAX_ZOOM) }

    /** Turning (and tilting) off the free view puts the tilt back and the view back in the middle. */
    fun enableFree(on: Boolean) {
        free = on
        if (!on) { pitch = START_PITCH; panX = 0f; panY = 0f }
    }

    fun reset() { yaw = START_YAW; pitch = START_PITCH; zoom = 1f; panX = 0f; panY = 0f }

    /**
     * eye x y z, then the point looked at (the model's centre moved by the pan), for a model of
     * [radius] around [cx], [cy], [cz]. The up vector is always z.
     */
    fun lookAt(cx: Float, cy: Float, cz: Float, radius: Float): FloatArray {
        val dist = max(radius, 1e-3f) * FIT / zoom
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        // the view's right and up in the world, for the pan
        val rx = -sin(y).toFloat(); val ry = cos(y).toFloat()
        val ux = (-sin(p) * cos(y)).toFloat(); val uy = (-sin(p) * sin(y)).toFloat(); val uz = cos(p).toFloat()
        val s = max(radius, 1e-3f) * 2f
        val tx = cx + (rx * panX + ux * panY) * s
        val ty = cy + (ry * panX + uy * panY) * s
        val tz = cz + uz * panY * s
        return floatArrayOf(
            tx + (dist * cos(p) * cos(y)).toFloat(), ty + (dist * cos(p) * sin(y)).toFloat(), tz + (dist * sin(p)).toFloat(),
            tx, ty, tz,
        )
    }

    companion object {
        const val START_YAW = 28f
        const val START_PITCH = 8f
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 8f
        const val MAX_PITCH = 85f
        private const val TURN_PER_PX = 0.45f
        private const val WHEEL_STEP = 1.12f
        private const val FIT = 2.7f   // the distance, in radii, that lets the whole model in at zoom 1
        fun clampFrame(f: Float, count: Int): Float = if (count <= 0) 0f else min(max(f, 0f), count.toFloat())
    }
}
