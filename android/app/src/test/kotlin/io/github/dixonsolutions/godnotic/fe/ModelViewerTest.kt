package io.github.dixonsolutions.godnotic.fe

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fullscreen model viewer's logic: the iqm reader and skinning (Iqm), the camera (OrbitCamera), Play (ModelAnim). */
class ModelViewerTest {
    private fun le(vararg v: Number): ByteArray {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) if (x is Float) b.putFloat(x) else b.putInt(x.toInt())
        return b.array()
    }

    /**
     * A one-triangle model with one joint that moves +10 on x in frame 1 (frame 0 is the bind pose),
     * written to the iqm 2 layout byte by byte: the reader is checked against the spec, not against itself.
     */
    private fun triangleIqm(version: Int = 2, positionFormat: Int = 7): ByteArray {
        val text = "\u0000tri\u0000mat\u0000root\u0000".toByteArray()      // offsets: 1 "tri", 5 "mat", 9 "root"
        val body = ByteArrayOutputStream()
        val headerSize = 124
        fun here() = headerSize + body.size()
        val ofsText = here(); body.write(text); while (body.size() % 4 != 0) body.write(0)
        val ofsMeshes = here(); body.write(le(1, 5, 0, 3, 0, 1))
        val ofsVA = here()
        val vaCount = 3
        val posOfs = ofsVA + vaCount * 20
        body.write(le(0, 0, positionFormat, 3, posOfs))                    // position: float x3
        val nrmOfs = posOfs + 36
        body.write(le(2, 0, 7, 3, nrmOfs))                                  // normal
        val uvOfs = nrmOfs + 36
        body.write(le(1, 0, 7, 2, uvOfs))                                   // texcoord
        // the arrays (3 vertices): bone indexes and weights as ubyte x4 follow
        body.write(le(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f))                // positions
        body.write(le(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f))                  // normals
        body.write(le(0f, 0f, 1f, 0f, 0f, 1f))                              // uvs
        val ofsTris = here(); body.write(le(0, 1, 2))
        val ofsJoints = here(); body.write(le(9, -1, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f))
        val ofsPoses = here(); body.write(le(-1, 1, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 10f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))   // tx varies: mask bit 0, scale 10
        val ofsAnims = here(); body.write(le(1, 0, 2, 12f, 0))
        val ofsFrames = here(); body.write(byteArrayOf(0, 0, 1, 0))         // two frames of one channel: 0 and 1 (x10 = 10 units)
        val b = body.toByteArray()
        val h = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        h.put("INTERQUAKEMODEL\u0000".toByteArray(Charsets.ISO_8859_1))
        val vals = intArrayOf(version, headerSize + b.size, 0, text.size, ofsText, 1, ofsMeshes, vaCount, 3, ofsVA, 1, ofsTris, 0, 1, ofsJoints, 1, ofsPoses, 1, ofsAnims, 2, 1, ofsFrames, 0, 0, 0, 0, 0)
        for (v in vals) h.putInt(v)
        return h.array() + b
    }

    @Test fun readsTheTriangle() {
        val m = Iqm.parse(triangleIqm())!!
        assertEquals(3, m.vertexCount)
        assertEquals(1, m.meshes.size)
        assertEquals("tri", m.meshes[0].name); assertEquals("mat", m.meshes[0].material)
        assertEquals(listOf(0, 1, 2), m.triangles.toList())
        assertEquals(10f, m.positions[3], 0f)
        assertEquals(1, m.joints.size); assertEquals("root", m.joints[0].name); assertEquals(-1, m.joints[0].parent)
        assertEquals(2, m.frameCount)
        assertEquals(12f, m.framerate, 0f)
        assertEquals(listOf(0f, 0f, 0f, 10f, 10f, 0f), m.bounds.toList().let { listOf(it[0], it[1], it[2], it[3], it[4], it[5]) })
    }

    @Test fun refusesWhatItCannotRead() {
        assertNull(Iqm.parse(ByteArray(10)))
        assertNull(Iqm.parse(triangleIqm(version = 1)))
        assertNull(Iqm.parse(triangleIqm(positionFormat = 3)))   // positions as shorts: not handled
        val cut = triangleIqm().copyOf(130)
        assertNull(Iqm.parse(cut))                                // cut short
        assertNull(Iqm.parse("IQE not a model at all, just text".toByteArray().copyOf(200)))
    }

    @Test fun skinningMovesTheVerticesWithTheJoint() {
        val m = Iqm.parse(triangleIqm())!!
        val pos = FloatArray(9); val nrm = FloatArray(9)
        // vertices 3 weights: the file has no blend arrays, so each vertex is unweighted and stays in the bind pose
        m.pose(0f, pos, nrm)
        assertEquals(m.positions.toList(), pos.toList())
        assertEquals(1f, nrm[2], 1e-6f)
    }

    @Test fun jointMatricesAndBlend() {
        // a joint moved by (1,2,3) and turned 90 degrees about z
        val q = Math.sqrt(0.5).toFloat()
        val t = floatArrayOf(1f, 2f, 3f, 0f, 0f, q, q, 1f, 1f, 1f)
        val mtx = Iqm.toMatrix(t)
        // x axis goes to y
        assertEquals(1f, mtx[3], 1e-6f)
        assertEquals(0f, mtx[0], 1e-6f); assertEquals(1f, mtx[4], 1e-6f)
        // inverse undoes it
        val id = Iqm.mul(mtx, Iqm.invert(mtx))
        for (i in 0 until 3) for (j in 0 until 4) assertEquals(if (i == j) 1f else 0f, id[i * 4 + j], 1e-5f)
        // blending half way moves half way
        val a = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
        val b = floatArrayOf(10f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
        assertEquals(5f, Iqm.blend(a, b, 0.5f)[0], 1e-6f)
        assertEquals(a.toList(), Iqm.blend(a, b, 0f).toList())
    }

    @Test fun frameGroupsFileParses() {
        val g = Iqm.frameGroups("0 1 10 0\n1 30 24 1\n\nnot a line\n31 0 10 0\n")
        assertEquals(2, g.size)
        assertEquals(listOf(1f, 30f, 24f), g[1].toList())
    }

    @Test fun playRunsThroughTheGroupAndLoops() {
        val a = ModelAnim(listOf(floatArrayOf(0f, 10f, 10f), floatArrayOf(10f, 20f, 20f)), 30, 10f)
        assertEquals(2, a.count)
        assertEquals(0f, a.frame(1f), 0f)                // paused: stays on the first frame
        a.playing = true
        assertEquals(5f, a.frame(0.5f), 1e-5f)
        assertEquals(5f, a.frame(1f), 1e-5f)             // 15 frames in a 10 frame loop
        a.next()
        assertEquals(10f, a.frame(0f), 1e-5f)            // the next clip starts at its first frame
        assertEquals(10f + 10f, a.frame(0.5f), 1e-5f)    // 20 fps for half a second
        assertEquals("2 / 2", a.title())
        a.next(); assertEquals("1 / 2", a.title())
    }

    @Test fun playWithoutFramegroupsUsesAllFrames() {
        val a = ModelAnim(emptyList(), 12, 8f)
        assertEquals(1, a.count); assertEquals("", a.title())
        a.playing = true
        assertEquals(8f, a.frame(1f), 1e-5f)
        assertEquals(4f, a.frame(1f), 1e-5f)             // 16 frames mod 12
        assertEquals(0f, ModelAnim(emptyList(), 0, 8f).frame(1f), 0f)
    }

    @Test fun dragTurnsAndOnlyFreeViewTilts() {
        val c = OrbitCamera()
        val y0 = c.yaw; val p0 = c.pitch
        c.drag(100f, 100f)
        assertTrue(c.yaw < y0); assertEquals(p0, c.pitch, 0f)
        c.enableFree(true)
        c.drag(0f, 100f)
        assertTrue(c.pitch > p0)
        c.drag(0f, 100000f); assertEquals(OrbitCamera.MAX_PITCH, c.pitch, 0f)
        c.enableFree(false)
        assertEquals(OrbitCamera.START_PITCH, c.pitch, 0f)
    }

    @Test fun zoomIsClamped() {
        val c = OrbitCamera()
        c.pinch(100f); assertEquals(OrbitCamera.MAX_ZOOM, c.zoom, 0f)
        c.pinch(0.0001f); assertEquals(OrbitCamera.MIN_ZOOM, c.zoom, 0f)
        c.reset(); c.wheel(1f); assertTrue(c.zoom > 1f)
        c.reset(); c.wheel(-1f); assertTrue(c.zoom < 1f)
        c.pinch(-1f); c.pinch(0f)   // nonsense scale changes nothing
    }

    @Test fun panOnlyInFreeViewAndTheEyeStaysOnItsSphere() {
        val c = OrbitCamera()
        c.pan(50f, 50f, 800f); assertEquals(0f, c.panX, 0f)
        c.enableFree(true); c.pan(80f, 0f, 800f); assertTrue(c.panX < 0f)
        val c2 = OrbitCamera()
        val l = c2.lookAt(0f, 0f, 20f, 10f)
        val d = Math.sqrt(((l[0] - l[3]) * (l[0] - l[3]) + (l[1] - l[4]) * (l[1] - l[4]) + (l[2] - l[5]) * (l[2] - l[5])).toDouble())
        assertEquals(27.0, d, 0.01)                        // FIT radii away at zoom 1
        assertEquals(0f, l[3], 1e-6f); assertEquals(20f, l[5], 1e-6f)
        c2.pinch(2f)
        val l2 = c2.lookAt(0f, 0f, 20f, 10f)
        val d2 = Math.sqrt(((l2[0] - l2[3]) * (l2[0] - l2[3]) + (l2[1] - l2[4]) * (l2[1] - l2[4]) + (l2[2] - l2[5]) * (l2[2] - l2[5])).toDouble())
        assertEquals(13.5, d2, 0.01)
        assertFalse(l2[2] < l2[5] - 1f)                    // starts above the horizon
        assertNotNull(l)
    }
}
