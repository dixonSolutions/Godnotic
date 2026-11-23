package io.github.dixonsolutions.godnotic.fe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The fullscreen model viewer (Settings > Player, the fullscreen button on the character's picture):
 * the model itself, read from the game's files (an `image` ref `pk3raw:<path>`, touch_uib_img.c) and
 * drawn with OpenGL ES 2 on its own surface. A drag, with a finger or the mouse, turns it; a pinch or the
 * wheel zooms; Free view adds tilting and a two-finger move; Play runs its animations, Next picks
 * the next. X, Back and Escape close it. Only iqm models can be shown (the game's own); a model
 * the viewer cannot read says so and the picture stays as it was.
 *
 * [fetch] asks the engine for a file by ref and answers with the path of a copy, or "" when it has none.
 */
class ModelViewer(
    ctx: Context,
    private val title: String,
    private val modelPath: String,
    private val skin: Int,
    private val fetch: (String, (String) -> Unit) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(ctx) {
    private val cam = OrbitCamera()
    private var anim: ModelAnim? = null
    @Volatile private var playing = false
    private val gl: GLView
    private val status = Kit.text(ctx, "Loading the model…", Theme.SP_BODY, Theme.TEXT_MUTED).apply { gravity = Gravity.CENTER }
    private val playBtn: TextView
    private val nextBtn: TextView
    private val freeBtn: TextView
    private var closed = false

    init {
        setBackgroundColor(0xFF050B18.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        gl = GLView(ctx)
        addView(gl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // top: the model's name and X
        val top = Kit.row(ctx, 12f).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20f), dp(12f), dp(12f), dp(12f)) }
        top.addView(Kit.text(ctx, title, Theme.SP_TITLE, Theme.TEXT, bold = true, lines = 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = Kit.button(ctx, "", false, "close") { close() }.apply { contentDescription = "Close"; minimumWidth = dp(56f); minimumHeight = dp(56f); gravity = Gravity.CENTER }
        top.addView(x, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // bottom: Free view, Play, Next, Reset
        val bar = Kit.row(ctx, 10f).apply { gravity = Gravity.CENTER; setPadding(dp(16f), dp(12f), dp(16f), dp(20f)) }
        freeBtn = Kit.button(ctx, "Free view", false, "") { toggleFree() }
        playBtn = Kit.button(ctx, "Play", true, "play") { togglePlay() }
        nextBtn = Kit.button(ctx, "Next move", false, "") { anim?.next(); updateButtons(); gl.requestRender() }
        val reset = Kit.button(ctx, "Reset", false, "refresh") { cam.reset(); freeBtn.alpha = 1f; updateButtons(); gl.requestRender() }
        for (b in listOf(freeBtn, playBtn, nextBtn, reset)) bar.addView(b)
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        playBtn.visibility = View.GONE; nextBtn.visibility = View.GONE
        updateButtons()
        load()
        postDelayed({ if (!closed && status.visibility == View.VISIBLE && status.text.startsWith("Loading")) status.text = "The model did not load." }, 8000)
    }

    private fun dp(v: Float) = Theme.dp(context, v)

    private fun updateButtons() {
        freeBtn.background = Theme.surface(context, if (cam.free) 0xFF15306A.toInt() else 0xFF0B1730.toInt(), 24f, if (cam.free) Theme.BLUE else Theme.OUTLINE)
        freeBtn.contentDescription = if (cam.free) "Free view on" else "Free view off"
        playBtn.text = if (playing) "Pause" else "Play"
        Kit.icon(playBtn, if (playing) "pause" else "play", Color_WHITE, 20f)
        nextBtn.visibility = if ((anim?.count ?: 0) > 1 && playBtn.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        nextBtn.text = "Next move " + (anim?.title() ?: "")
    }

    private fun toggleFree() { cam.enableFree(!cam.free); updateButtons(); gl.requestRender() }

    private fun togglePlay() {
        playing = !playing
        anim?.playing = playing
        gl.setPlaying(playing)
        updateButtons()
        gl.requestRender()
    }

    fun close() { if (closed) return; closed = true; onClose() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_ESCAPE && e.action == KeyEvent.ACTION_UP) { close(); return true }
        return super.dispatchKeyEvent(e)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestFocus() }

    // ---- loading: the model, its skin file, the animations, then a picture for each mesh

    private fun load() {
        fetch("pk3raw:$modelPath") { path ->
            post {
                val bytes = if (path.isEmpty()) null else try { File(path).readBytes() } catch (e: Exception) { null }
                val model = bytes?.let { Iqm.parse(it) }
                if (model == null) {
                    status.text = if (bytes == null) "This model is not on the device yet." else "This model's format can't be shown here."
                    return@post
                }
                status.visibility = View.GONE
                gl.setModel(model)
                // the animations, if the model has any
                if (model.frameCount > 1) {
                    fetch("pk3raw:$modelPath.framegroups") { fg ->
                        post {
                            val groups = if (fg.isEmpty()) emptyList() else try { Iqm.frameGroups(File(fg).readText()) } catch (e: Exception) { emptyList() }
                            anim = ModelAnim(groups, model.frameCount, model.framerate)
                            gl.setAnim(anim)
                            playBtn.visibility = View.VISIBLE
                            updateButtons()
                        }
                    }
                }
                // the skin: "<mesh>,<texture>" lines say which picture each mesh wears
                fetch("pk3raw:${modelPath}_$skin.skin") { sp ->
                    post {
                        val map = HashMap<String, String>()
                        if (sp.isNotEmpty()) try {
                            for (l in File(sp).readLines()) { val p = l.split(',').map { it.trim() }; if (p.size >= 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) map[p[0]] = p[1] }
                        } catch (e: Exception) { /* untextured */ }
                        model.meshes.forEachIndexed { i, m ->
                            val tex = map[m.name] ?: m.material.takeIf { it.contains('/') } ?: return@forEachIndexed
                            if (tex.startsWith("common/")) return@forEachIndexed
                            fetch("pk3:$tex") { ip ->
                                if (ip.isNotEmpty()) Thread {
                                    val bmp = try { BitmapFactory.decodeFile(ip) } catch (e: Exception) { null }
                                    if (bmp != null) gl.setTexture(i, bmp)
                                }.start()
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- touch and mouse

    private inner class GLView(ctx: Context) : GLSurfaceView(ctx) {
        private val r = Renderer()
        private val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { cam.pinch(d.scaleFactor); requestRender(); return true }
        })
        private var lastX = 0f; private var lastY = 0f; private var lastCx = 0f; private var lastCy = 0f; private var pointers = 0

        init {
            setEGLContextClientVersion(2)
            setZOrderMediaOverlay(true)   // above the game's own surface, under the buttons
            setRenderer(r)
            renderMode = RENDERMODE_WHEN_DIRTY
            isFocusable = false
        }

        fun setModel(m: Iqm) { queueEvent { r.setModel(m) }; requestRender() }
        fun setAnim(a: ModelAnim?) { r.anim = a }
        /** Playing draws every frame; otherwise only when something changed. */
        fun setPlaying(on: Boolean) { renderMode = if (on) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY }
        fun setTexture(mesh: Int, b: Bitmap) { queueEvent { r.setTexture(mesh, b) }; requestRender() }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            pinch.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); lastX = e.x; lastY = e.y; pointers = 1 }
                MotionEvent.ACTION_POINTER_DOWN -> { pointers = e.pointerCount; lastCx = centreX(e); lastCy = centreY(e) }
                MotionEvent.ACTION_POINTER_UP -> { pointers = e.pointerCount - 1; if (pointers == 1) { val i = if (e.actionIndex == 0) 1 else 0; lastX = e.getX(i); lastY = e.getY(i) } }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val cx = centreX(e); val cy = centreY(e)
                        if (!pinch.isInProgress || cam.free) cam.pan(cx - lastCx, cy - lastCy, height.toFloat())
                        lastCx = cx; lastCy = cy
                    } else {
                        cam.drag(e.x - lastX, e.y - lastY)
                        lastX = e.x; lastY = e.y
                    }
                    requestRender()
                }
            }
            return true
        }

        private fun centreX(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getX(it).toDouble() }.toFloat() / e.pointerCount
        private fun centreY(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getY(it).toDouble() }.toFloat() / e.pointerCount

        /** The mouse wheel zooms. */
        override fun onGenericMotionEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
                cam.wheel(e.getAxisValue(MotionEvent.AXIS_VSCROLL)); requestRender(); return true
            }
            return super.onGenericMotionEvent(e)
        }
    }

    // ---- drawing

    private inner class Renderer : GLSurfaceView.Renderer {
        @Volatile var anim: ModelAnim? = null
        private var model: Iqm? = null
        private var pos = FloatArray(0); private var nrm = FloatArray(0)
        private var posBuf: FloatBuffer? = null; private var nrmBuf: FloatBuffer? = null; private var uvBuf: FloatBuffer? = null; private var idxBuf: ShortBuffer? = null
        private var textures = IntArray(0)
        private var program = 0
        private var aPos = 0; private var aNrm = 0; private var aUv = 0
        private var uMvp = 0; private var uTex = 0; private var uUseTex = 0; private var uColor = 0; private var uLight = 0
        private var w = 1; private var h = 1
        private var last = 0L
        private var cx = 0f; private var cy = 0f; private var cz = 0f; private var radius = 1f
        private val proj = FloatArray(16); private val view = FloatArray(16); private val mvp = FloatArray(16)

        fun setModel(m: Iqm) {
            model = m
            val n = m.vertexCount
            pos = FloatArray(n * 3); nrm = FloatArray(n * 3)
            posBuf = direct(n * 3); nrmBuf = direct(n * 3)
            uvBuf = direct(n * 2).also { it.put(m.uvs).position(0) }
            if (n > 65535) { model = null; return }   // ES 2 indexes with 16 bits
            idxBuf = ByteBuffer.allocateDirect(m.triangles.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().also { b -> for (t in m.triangles) b.put(t.toShort()); b.position(0) }
            textures = IntArray(m.meshes.size)
            val b = m.bounds
            cx = (b[0] + b[3]) / 2; cy = (b[1] + b[4]) / 2; cz = (b[2] + b[5]) / 2
            radius = Math.sqrt(((b[3] - b[0]) * (b[3] - b[0]) + (b[4] - b[1]) * (b[4] - b[1]) + (b[5] - b[2]) * (b[5] - b[2])).toDouble()).toFloat() / 2f
        }

        fun setTexture(mesh: Int, bmp: Bitmap) {
            if (mesh !in textures.indices) return
            val id = IntArray(1)
            GLES20.glGenTextures(1, id, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            textures[mesh] = id[0]
        }

        private fun direct(floats: Int): FloatBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.02f, 0.045f, 0.094f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            program = link(VERT, FRAG)
            aPos = GLES20.glGetAttribLocation(program, "aPos"); aNrm = GLES20.glGetAttribLocation(program, "aNrm"); aUv = GLES20.glGetAttribLocation(program, "aUv")
            uMvp = GLES20.glGetUniformLocation(program, "uMvp"); uTex = GLES20.glGetUniformLocation(program, "uTex")
            uUseTex = GLES20.glGetUniformLocation(program, "uUseTex"); uColor = GLES20.glGetUniformLocation(program, "uColor"); uLight = GLES20.glGetUniformLocation(program, "uLight")
            // the surface was lost (the app came back): textures are gone with it
            textures.fill(0)
            last = 0
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            w = max1(width); h = max1(height)
            GLES20.glViewport(0, 0, w, h)
        }

        private fun max1(v: Int) = if (v < 1) 1 else v

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val m = model ?: return
            val now = System.nanoTime()
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            val a = anim
            val frame = a?.frame(dt) ?: 0f
            m.pose(frame, pos, nrm)
            posBuf!!.position(0); posBuf!!.put(pos); posBuf!!.position(0)
            nrmBuf!!.position(0); nrmBuf!!.put(nrm); nrmBuf!!.position(0)
            Matrix.perspectiveM(proj, 0, 40f, w.toFloat() / h, radius * 0.05f, radius * 40f)
            val l = cam.lookAt(cx, cy, cz, radius)
            Matrix.setLookAtM(view, 0, l[0], l[1], l[2], l[3], l[4], l[5], 0f, 0f, 1f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            // a light from over the viewer's shoulder, so the front of the model is always lit
            val ex = l[0] - l[3]; val ey = l[1] - l[4]; val ez = l[2] - l[5]
            val el = Math.sqrt((ex * ex + ey * ey + ez * ez).toDouble()).toFloat().let { if (it > 1e-6f) it else 1f }
            GLES20.glUniform3f(uLight, ex / el * 0.6f + 0.2f, ey / el * 0.6f - 0.2f, ez / el * 0.6f + 0.5f)
            GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 12, posBuf)
            GLES20.glEnableVertexAttribArray(aNrm); GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 12, nrmBuf)
            GLES20.glEnableVertexAttribArray(aUv); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 8, uvBuf)
            val idx = idxBuf!!
            for ((i, mesh) in m.meshes.withIndex()) {
                val tex = textures.getOrElse(i) { 0 }
                GLES20.glUniform1f(uUseTex, if (tex != 0) 1f else 0f)
                GLES20.glUniform3f(uColor, 0.62f, 0.7f, 0.82f)
                if (tex != 0) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex); GLES20.glUniform1i(uTex, 0) }
                idx.position(mesh.firstTriangle * 3)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.numTriangles * 3, GLES20.GL_UNSIGNED_SHORT, idx)
            }
            GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aNrm); GLES20.glDisableVertexAttribArray(aUv)
        }

        private fun link(vs: String, fs: String): Int {
            fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
                return s
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }
    }

    companion object {
        private const val Color_WHITE = 0xFFFFFFFF.toInt()
        private const val VERT = "uniform mat4 uMvp; attribute vec3 aPos; attribute vec3 aNrm; attribute vec2 aUv;\n" +
            "varying vec3 vN; varying vec2 vUv;\n" +
            "void main() { vN = aNrm; vUv = aUv; gl_Position = uMvp * vec4(aPos, 1.0); }\n"
        private const val FRAG = "precision mediump float; uniform sampler2D uTex; uniform float uUseTex; uniform vec3 uColor; uniform vec3 uLight;\n" +
            "varying vec3 vN; varying vec2 vUv;\n" +
            "void main() { vec3 base = mix(uColor, texture2D(uTex, vUv).rgb, uUseTex);\n" +
            "  float d = max(dot(normalize(vN), normalize(uLight)), 0.0);\n" +
            "  gl_FragColor = vec4(base * (0.42 + 0.78 * d), 1.0); }\n"
    }
}
