package io.github.dixonsolutions.godnotic.fe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A player model for the fullscreen model viewer: the Inter-Quake Model file (iqm, the format the
 * game's stock player models are in) read into plain arrays, with its skeleton and animations,
 * so the viewer can turn it, pose it and play it. Plain Kotlin, no Android: the JVM tests build
 * a small file and check the parse and the skinning. Only iqm version 2; any other format
 * (md3, dpm, zym) is [parse]d to null and the viewer says so.
 */
class Iqm private constructor(
    val positions: FloatArray,      // x y z per vertex, the bind pose (the game's own axes: z up, facing +x)
    val normals: FloatArray,        // x y z
    val uvs: FloatArray,            // u v
    val blendIdx: IntArray,         // 4 joint indexes per vertex
    val blendWgt: FloatArray,       // 4 weights per vertex
    val meshes: List<Mesh>,
    val triangles: IntArray,        // 3 vertex indexes per triangle
    val joints: List<Joint>,
    val frames: List<Array<FloatArray>>, // [frame][joint] -> local transform: tx ty tz qx qy qz qw sx sy sz
    val framerate: Float,
) {
    class Mesh(val name: String, val material: String, val firstVertex: Int, val numVertices: Int, val firstTriangle: Int, val numTriangles: Int)
    class Joint(val name: String, val parent: Int, val base: FloatArray) // base: tx ty tz qx qy qz qw sx sy sz

    val vertexCount: Int get() = positions.size / 3
    val frameCount: Int get() = frames.size

    /** Per-joint 3x4 inverse of the bind pose, row-major: what turns a bind-pose vertex into joint space. */
    private val inverseBind: Array<FloatArray> by lazy {
        val abs = absolute(joints.map { it.base }.toTypedArray())
        Array(joints.size) { invert(abs[it]) }
    }

    /** The model's box in the bind pose: min x y z then max x y z. */
    val bounds: FloatArray by lazy {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (v in 0 until vertexCount) for (a in 0 until 3) {
            val p = positions[v * 3 + a]
            b[a] = min(b[a], p); b[a + 3] = max(b[a + 3], p)
        }
        if (vertexCount == 0) b.fill(0f)
        b
    }

    /**
     * The vertices skinned to [frame] (a fraction blends this frame into the next), into [outPos] and
     * [outNrm] (x y z per vertex). A model without joints or frames comes out as it is.
     */
    fun pose(frame: Float, outPos: FloatArray, outNrm: FloatArray) {
        if (joints.isEmpty() || frames.isEmpty()) {
            positions.copyInto(outPos); normals.copyInto(outNrm); return
        }
        val f0 = Math.floorMod(frame.toInt(), frames.size)
        val f1 = (f0 + 1) % frames.size
        val t = frame - Math.floor(frame.toDouble()).toFloat()
        val local = Array(joints.size) { j -> blend(frames[f0][j], frames[f1][j], t) }
        val abs = absolute(local)
        val skin = Array(joints.size) { mul(abs[it], inverseBind[it]) }
        for (v in 0 until vertexCount) {
            var px = 0f; var py = 0f; var pz = 0f; var nx = 0f; var ny = 0f; var nz = 0f
            val x = positions[v * 3]; val y = positions[v * 3 + 1]; val z = positions[v * 3 + 2]
            val ax = normals[v * 3]; val ay = normals[v * 3 + 1]; val az = normals[v * 3 + 2]
            var total = 0f
            for (k in 0 until 4) {
                val w = blendWgt[v * 4 + k]
                if (w <= 0f) continue
                val m = skin[blendIdx[v * 4 + k].coerceIn(0, joints.size - 1)]
                px += w * (m[0] * x + m[1] * y + m[2] * z + m[3])
                py += w * (m[4] * x + m[5] * y + m[6] * z + m[7])
                pz += w * (m[8] * x + m[9] * y + m[10] * z + m[11])
                nx += w * (m[0] * ax + m[1] * ay + m[2] * az)
                ny += w * (m[4] * ax + m[5] * ay + m[6] * az)
                nz += w * (m[8] * ax + m[9] * ay + m[10] * az)
                total += w
            }
            if (total <= 0f) { px = x; py = y; pz = z; nx = ax; ny = ay; nz = az }
            val len = sqrt(nx * nx + ny * ny + nz * nz).let { if (it > 1e-6f) it else 1f }
            outPos[v * 3] = px; outPos[v * 3 + 1] = py; outPos[v * 3 + 2] = pz
            outNrm[v * 3] = nx / len; outNrm[v * 3 + 1] = ny / len; outNrm[v * 3 + 2] = nz / len
        }
    }

    private fun absolute(local: Array<FloatArray>): Array<FloatArray> {
        val out = arrayOfNulls<FloatArray>(joints.size)
        for (j in joints.indices) {
            val m = toMatrix(local[j])
            val p = joints[j].parent
            out[j] = if (p in 0 until j) mul(out[p]!!, m) else m
        }
        return Array(joints.size) { out[it]!! }
    }

    companion object {
        /** A "frame groups" line list ("first count fps loop", the engine's `.framegroups`) as [first, count, fps] triples. */
        fun frameGroups(text: String): List<FloatArray> = text.lines().mapNotNull { l ->
            val p = l.trim().split(Regex("\\s+"))
            if (p.size < 3) null else try { floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()) } catch (e: NumberFormatException) { null }
        }.filter { it[1] >= 1f }

        /** The model in [data], or null when it is not an iqm version 2 or is cut short. */
        fun parse(data: ByteArray): Iqm? = try { read(data) } catch (e: RuntimeException) { null }

        private fun read(data: ByteArray): Iqm? {
            if (data.size < 124) return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val magic = String(data, 0, 16, Charsets.ISO_8859_1)
            if (!magic.startsWith("INTERQUAKEMODEL")) return null
            fun u(i: Int) = b.getInt(16 + i * 4)
            if (u(0) != 2) return null
            // header after the magic: version, filesize, flags, then the counts and offsets in the spec's order
            val numText = u(3); val ofsText = u(4)
            val numMeshes = u(5); val ofsMeshes = u(6)
            val numVA = u(7); val numVerts = u(8); val ofsVA = u(9)
            val numTris = u(10); val ofsTris = u(11)
            val numJoints = u(13); val ofsJoints = u(14)
            val numPoses = u(15); val ofsPoses = u(16)
            val numAnims = u(17); val ofsAnims = u(18)
            val numFrames = u(19); val numChannels = u(20); val ofsFrames = u(21)
            if (numVerts <= 0 || numVerts > 200_000 || numTris < 0 || numTris > 400_000) return null
            fun text(o: Int): String {
                if (o < 0 || o >= numText) return ""
                var e = ofsText + o
                val s = e
                while (e < data.size && data[e] != 0.toByte()) e++
                return String(data, s, e - s, Charsets.UTF_8)
            }
            val pos = FloatArray(numVerts * 3); val nrm = FloatArray(numVerts * 3); val uv = FloatArray(numVerts * 2)
            val bi = IntArray(numVerts * 4); val bw = FloatArray(numVerts * 4)
            var havePos = false
            for (a in 0 until numVA) {
                val o = ofsVA + a * 20
                val type = b.getInt(o); val format = b.getInt(o + 8); val size = b.getInt(o + 12); val off = b.getInt(o + 16)
                if (size < 1 || size > 4) continue
                if ((type == 0 || type == 1 || type == 2) && format != 7) { if (type == 0) return null else continue }   // positions, texcoords and normals as floats
                val stride = size * (if (format == 7) 4 else 1)
                val scale = if (format == 1 && type == 5) 1f / 255f else 1f   // blend weights are bytes 0..255
                for (v in 0 until numVerts) {
                    fun at(i: Int): Float = when (format) {
                        7 -> b.getFloat(off + v * stride + i * 4)
                        1 -> (b.get(off + v * stride + i).toInt() and 0xFF).toFloat()
                        0 -> b.get(off + v * stride + i).toFloat()
                        else -> 0f
                    }
                    when (type) {
                        0 -> for (i in 0 until min(3, size)) pos[v * 3 + i] = at(i)
                        1 -> for (i in 0 until min(2, size)) uv[v * 2 + i] = at(i)
                        2 -> for (i in 0 until min(3, size)) nrm[v * 3 + i] = at(i)
                        4 -> for (i in 0 until min(4, size)) bi[v * 4 + i] = at(i).toInt()
                        5 -> for (i in 0 until min(4, size)) bw[v * 4 + i] = at(i) * scale
                    }
                }
                if (type == 0) havePos = true
            }
            if (!havePos) return null
            val meshes = (0 until numMeshes).map {
                val o = ofsMeshes + it * 24
                Mesh(text(b.getInt(o)), text(b.getInt(o + 4)), b.getInt(o + 8), b.getInt(o + 12), b.getInt(o + 16), b.getInt(o + 20))
            }
            val tris = IntArray(numTris * 3) { b.getInt(ofsTris + it * 4) }
            for (t in tris) if (t < 0 || t >= numVerts) return null
            val joints = (0 until numJoints).map {
                val o = ofsJoints + it * 48
                Joint(text(b.getInt(o)), b.getInt(o + 4), FloatArray(10) { k -> b.getFloat(o + 8 + k * 4) })
            }
            // poses: how each joint's ten channels are packed into a frame
            val frames = ArrayList<Array<FloatArray>>()
            if (numPoses == numJoints && numFrames > 0) {
                var cursor = ofsFrames
                for (fr in 0 until numFrames) {
                    val perJoint = Array(numPoses) { FloatArray(10) }
                    for (j in 0 until numPoses) {
                        val o = ofsPoses + j * 88
                        val mask = b.getInt(o + 4)
                        for (c in 0 until 10) {
                            var v = b.getFloat(o + 8 + c * 4)
                            if (mask and (1 shl c) != 0) {
                                v += (b.getShort(cursor).toInt() and 0xFFFF) * b.getFloat(o + 48 + c * 4)
                                cursor += 2
                            }
                            perJoint[j][c] = v
                        }
                    }
                    frames.add(perJoint)
                }
                if (numChannels < 0) return null
            }
            val rate = if (numAnims > 0) b.getFloat(ofsAnims + 12) else 10f
            return Iqm(pos, nrm, uv, bi, bw, meshes, tris, joints, frames, if (rate > 0f) rate else 10f)
        }

        // ---- 3x4 matrices, row-major (the last row of the 4x4 is 0 0 0 1)

        fun toMatrix(t: FloatArray): FloatArray {
            var qx = t[3]; var qy = t[4]; var qz = t[5]; var qw = t[6]
            val n = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).let { if (it > 1e-9f) it else 1f }
            qx /= n; qy /= n; qz /= n; qw /= n
            val sx = t[7]; val sy = t[8]; val sz = t[9]
            return floatArrayOf(
                (1 - 2 * (qy * qy + qz * qz)) * sx, 2 * (qx * qy - qz * qw) * sy, 2 * (qx * qz + qy * qw) * sz, t[0],
                2 * (qx * qy + qz * qw) * sx, (1 - 2 * (qx * qx + qz * qz)) * sy, 2 * (qy * qz - qx * qw) * sz, t[1],
                2 * (qx * qz - qy * qw) * sx, 2 * (qy * qz + qx * qw) * sy, (1 - 2 * (qx * qx + qy * qy)) * sz, t[2],
            )
        }

        fun mul(a: FloatArray, b: FloatArray): FloatArray {
            val r = FloatArray(12)
            for (i in 0 until 3) {
                for (j in 0 until 3) r[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j]
                r[i * 4 + 3] = a[i * 4] * b[3] + a[i * 4 + 1] * b[7] + a[i * 4 + 2] * b[11] + a[i * 4 + 3]
            }
            return r
        }

        fun invert(m: FloatArray): FloatArray {
            val a = m[0]; val b = m[1]; val c = m[2]; val d = m[4]; val e = m[5]; val f = m[6]; val g = m[8]; val h = m[9]; val i = m[10]
            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            val s = if (kotlin.math.abs(det) > 1e-12f) 1f / det else 0f
            val r = floatArrayOf(
                (e * i - f * h) * s, (c * h - b * i) * s, (b * f - c * e) * s, 0f,
                (f * g - d * i) * s, (a * i - c * g) * s, (c * d - a * f) * s, 0f,
                (d * h - e * g) * s, (b * g - a * h) * s, (a * e - b * d) * s, 0f,
            )
            r[3] = -(r[0] * m[3] + r[1] * m[7] + r[2] * m[11])
            r[7] = -(r[4] * m[3] + r[5] * m[7] + r[6] * m[11])
            r[11] = -(r[8] * m[3] + r[9] * m[7] + r[10] * m[11])
            return r
        }

        /** Two joint transforms blended: translation and scale linearly, rotation by normalised lerp on the short way round. */
        fun blend(a: FloatArray, b: FloatArray, t: Float): FloatArray {
            if (t <= 0f) return a
            val r = FloatArray(10)
            for (k in intArrayOf(0, 1, 2, 7, 8, 9)) r[k] = a[k] + (b[k] - a[k]) * t
            val dot = a[3] * b[3] + a[4] * b[4] + a[5] * b[5] + a[6] * b[6]
            val sgn = if (dot < 0f) -1f else 1f
            for (k in 3..6) r[k] = a[k] + (sgn * b[k] - a[k]) * t
            return r
        }
    }
}
