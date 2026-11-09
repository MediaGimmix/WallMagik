package com.example.wallpaperlauncher.gl

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.util.Log
import com.example.wallpaperlauncher.AppState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the current wallpaper with a crossfade from the previous one, plus blur, duotone and chromatic
 * aberration. Each image's blurred copy is made once when it arrives (downsample + separable Gaussian into a
 * small texture), so toggling blur is just a mix between the sharp and blurred textures. GL thread only.
 */
class WallRenderer {
    private class Slot {
        var tex = 0
        var blurTex = 0
        var iw = 0
        var ih = 0
        var bw = 0
        var bh = 0
        var blurRadius = -1f
        var id = -1L
        var has = false
    }

    private var w = 1
    private var h = 1
    private var cur = Slot()
    private var prev = Slot()
    private var tmpTex = 0
    private var tmpW = 0
    private var tmpH = 0
    private var fbo = 0
    private var vbo = 0
    private var progComposite = 0
    private var progDown = 0
    private var progBlur = 0

    private var fade = 1f
    private var fadeMs = 0f
    private var blurAmt = 0f
    private var duoAmt = 0f
    private var caAmt = 0f
    private var lastFrame = 0L

    // Set from outside through GlRenderThread.post
    var hold = false
    var holdX = 0.5f
    var holdY = 0.5f
    var drawerBlur = false

    fun init() {
        progComposite = program(VERT, FRAG_COMPOSITE)
        progDown = program(VERT, FRAG_DOWN)
        progBlur = program(VERT, FRAG_BLUR)
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(quad).position(0)
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        vbo = ids[0]
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, quad.size * 4, buf, GL_STATIC_DRAW)
        val t = IntArray(5)
        glGenTextures(5, t, 0)
        cur.tex = t[0]; cur.blurTex = t[1]; prev.tex = t[2]; prev.blurTex = t[3]; tmpTex = t[4]
        t.forEach { setupTex(it) }
        glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
    }

    /** Free the big textures while hidden; the next setImage uploads again. */
    fun purge() {
        for (sl in listOf(cur, prev)) {
            glBindTexture(GL_TEXTURE_2D, sl.tex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            glBindTexture(GL_TEXTURE_2D, sl.blurTex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            sl.has = false; sl.id = -1; sl.bw = 0; sl.bh = 0; sl.blurRadius = -1f
        }
        glBindTexture(GL_TEXTURE_2D, tmpTex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        tmpW = 0; tmpH = 0
        fade = 1f
    }

    fun release() {
        glDeleteTextures(5, intArrayOf(cur.tex, cur.blurTex, prev.tex, prev.blurTex, tmpTex), 0)
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        glDeleteBuffers(1, intArrayOf(vbo), 0)
        glDeleteProgram(progComposite); glDeleteProgram(progDown); glDeleteProgram(progBlur)
    }

    fun resize(width: Int, height: Int) {
        w = max(1, width)
        h = max(1, height)
    }

    /** [bmp] null with id < 0 clears the screen (no images selected). */
    fun setImage(id: Long, bmp: Bitmap?) {
        if (bmp == null) {
            if (id < 0) { cur.has = false; prev.has = false; cur.id = -1 }
            return
        }
        if (cur.has && cur.id == id) return
        val t = prev; prev = cur; cur = t
        upload(cur, bmp)
        cur.id = id
        cur.has = true
        val cfg = AppState.config.value
        fadeMs = min(cfg.transitionMs.toFloat(), cfg.intervalMs * 0.8f)
        fade = if (prev.has && fadeMs > 16f) 0f else 1f
        if (fade >= 1f) prev.has = false
    }

    private fun upload(s: Slot, bmp: Bitmap) {
        glBindTexture(GL_TEXTURE_2D, s.tex)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        s.iw = bmp.width
        s.ih = bmp.height
        s.blurRadius = -1f
    }

    private fun computeBlur(s: Slot, radius: Float) {
        val d = 2f + radius * 14f
        val bw = max(1, (s.iw / d).toInt())
        val bh = max(1, (s.ih / d).toInt())
        if (bw != s.bw || bh != s.bh) {
            glBindTexture(GL_TEXTURE_2D, s.blurTex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bw, bh, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            s.bw = bw; s.bh = bh
        }
        if (bw != tmpW || bh != tmpH) {
            glBindTexture(GL_TEXTURE_2D, tmpTex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bw, bh, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            tmpW = bw; tmpH = bh
        }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, bw, bh)

        // Downsample: a 4x4 grid of bilinear taps over each destination pixel's footprint.
        target(s.blurTex)
        glUseProgram(progDown)
        bindTex(progDown, "uTex", 0, s.tex)
        glUniform2f(loc(progDown, "uStep"), (s.iw.toFloat() / bw) / 4f / s.iw, (s.ih.toFloat() / bh) / 4f / s.ih)
        drawQuad(progDown, flip = false)

        // Separable Gaussian, ping-ponging through tmpTex.
        glUseProgram(progBlur)
        repeat(3) {
            target(tmpTex)
            bindTex(progBlur, "uTex", 0, s.blurTex)
            glUniform2f(loc(progBlur, "uDir"), 1f / bw, 0f)
            drawQuad(progBlur, flip = false)
            target(s.blurTex)
            bindTex(progBlur, "uTex", 0, tmpTex)
            glUniform2f(loc(progBlur, "uDir"), 0f, 1f / bh)
            drawQuad(progBlur, flip = false)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        s.blurRadius = radius
    }

    /** Returns true while anything is still animating (another frame is wanted). */
    fun draw(frameTimeNanos: Long): Boolean {
        val dt = if (lastFrame == 0L) 0f else ((frameTimeNanos - lastFrame) / 1_000_000f).coerceIn(0f, 100f)
        lastFrame = frameTimeNanos
        val cfg = AppState.config.value
        val fx = cfg.effectsEnabled

        val blurTarget = if ((fx && cfg.blurEnabled) || drawerBlur) 1f else 0f
        val duoTarget = if (fx && cfg.duotoneEnabled) cfg.duotoneStrength else 0f
        // Hold is a deliberate gesture, so it works even with the effects master switch off.
        val caTarget = if (cfg.caEnabled && hold) 1f else 0f
        blurAmt = approach(blurAmt, blurTarget, dt / 280f)
        duoAmt = approach(duoAmt, duoTarget, dt / 280f)
        caAmt = approach(caAmt, caTarget, dt / (if (hold) 220f else 160f))
        if (fade < 1f) fade = if (fadeMs <= 0f) 1f else min(1f, fade + dt / fadeMs)
        if (fade >= 1f) prev.has = false

        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        if (!cur.has) {
            glViewport(0, 0, w, h)
            glClearColor(0.05f, 0.05f, 0.06f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
            lastFrame = 0L
            return false
        }

        val needBlur = blurAmt > 0.001f || blurTarget > 0f
        if (needBlur) {
            if (cur.blurRadius != cfg.blurRadius) computeBlur(cur, cfg.blurRadius)
            if (prev.has && prev.blurRadius != cfg.blurRadius) computeBlur(prev, cfg.blurRadius)
        }

        glViewport(0, 0, w, h)
        val p = progComposite
        glUseProgram(p)
        val back = if (prev.has) prev else cur
        bindTex(p, "uCur", 0, cur.tex)
        bindTex(p, "uCurBlur", 1, if (needBlur) cur.blurTex else cur.tex)
        bindTex(p, "uPrev", 2, back.tex)
        bindTex(p, "uPrevBlur", 3, if (needBlur) back.blurTex else back.tex)
        cropUniform(p, "uCurXf", cur)
        cropUniform(p, "uPrevXf", back)
        val f = fade * fade * (3f - 2f * fade)
        glUniform1f(loc(p, "uFade"), if (prev.has) f else 1f)
        glUniform1f(loc(p, "uBlur"), if (needBlur) blurAmt else 0f)
        glUniform1f(loc(p, "uDuo"), duoAmt)
        colorUniform(p, "uDark", cfg.duotoneDark)
        colorUniform(p, "uLight", cfg.duotoneLight)
        glUniform1f(loc(p, "uCa"), caAmt * (0.15f + cfg.caStrength * 0.85f) * 0.12f)
        glUniform2f(loc(p, "uCaCenter"), holdX, holdY)
        drawQuad(p, flip = true)

        val animating = fade < 1f || abs(blurAmt - blurTarget) > 0f || abs(duoAmt - duoTarget) > 0f ||
            abs(caAmt - caTarget) > 0f
        if (!animating) lastFrame = 0L
        return animating
    }

    // ---- helpers ----

    private fun approach(v: Float, target: Float, step: Float): Float =
        if (v < target) min(target, v + step) else max(target, v - step)

    private fun cropUniform(p: Int, name: String, s: Slot) {
        val sa = w.toFloat() / h
        val ia = s.iw.toFloat() / max(1, s.ih)
        var sx = 1f
        var sy = 1f
        if (ia > sa) sx = sa / ia else sy = ia / sa
        glUniform4f(loc(p, name), sx, sy, (1f - sx) / 2f, (1f - sy) / 2f)
    }

    private fun colorUniform(p: Int, name: String, c: Int) =
        glUniform3f(loc(p, name), Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)

    private fun target(tex: Int) =
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)

    private fun bindTex(p: Int, name: String, unit: Int, tex: Int) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(loc(p, name), unit)
    }

    private fun drawQuad(p: Int, flip: Boolean) {
        glUniform1f(loc(p, "uFlip"), if (flip) 1f else 0f)
        val a = glGetAttribLocation(p, "aPos")
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(a)
        glVertexAttribPointer(a, 2, GL_FLOAT, false, 0, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    private val locs = HashMap<Long, Int>()
    private fun loc(p: Int, name: String): Int =
        locs.getOrPut((p.toLong() shl 32) xor name.hashCode().toLong()) { glGetUniformLocation(p, name) }

    private fun setupTex(t: Int) {
        glBindTexture(GL_TEXTURE_2D, t)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    private fun program(vs: String, fs: String): Int {
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "link: " + glGetProgramInfoLog(p))
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "compile: " + glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val TAG = "WallRenderer"

        // vUv (0,0) = top-left of the image when uFlip = 1 (screen); identity when rendering into FBOs.
        private const val VERT = """
attribute vec2 aPos;
uniform float uFlip;
varying vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    vUv.y = mix(vUv.y, 1.0 - vUv.y, uFlip);
    gl_Position = vec4(aPos, 0.0, 1.0);
}"""

        private const val FRAG_DOWN = """
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uStep;
varying vec2 vUv;
void main() {
    vec3 c = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            c += texture2D(uTex, vUv + (vec2(float(x), float(y)) - 1.5) * uStep).rgb;
        }
    }
    gl_FragColor = vec4(c / 16.0, 1.0);
}"""

        private const val FRAG_BLUR = """
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uDir;
varying vec2 vUv;
void main() {
    vec3 c = texture2D(uTex, vUv).rgb * 0.2270270270;
    c += texture2D(uTex, vUv + uDir * 1.3846153846).rgb * 0.3162162162;
    c += texture2D(uTex, vUv - uDir * 1.3846153846).rgb * 0.3162162162;
    c += texture2D(uTex, vUv + uDir * 3.2307692308).rgb * 0.0702702703;
    c += texture2D(uTex, vUv - uDir * 3.2307692308).rgb * 0.0702702703;
    gl_FragColor = vec4(c, 1.0);
}"""

        private const val FRAG_COMPOSITE = """
precision mediump float;
uniform sampler2D uCur;
uniform sampler2D uCurBlur;
uniform sampler2D uPrev;
uniform sampler2D uPrevBlur;
uniform vec4 uCurXf;
uniform vec4 uPrevXf;
uniform float uFade;
uniform float uBlur;
uniform float uDuo;
uniform vec3 uDark;
uniform vec3 uLight;
uniform float uCa;
uniform vec2 uCaCenter;
varying vec2 vUv;

vec3 img(sampler2D s, sampler2D sb, vec4 xf, vec2 uv) {
    vec2 t = clamp(uv, 0.0, 1.0) * xf.xy + xf.zw;
    vec3 c = texture2D(s, t).rgb;
    if (uBlur > 0.001) c = mix(c, texture2D(sb, t).rgb, uBlur);
    return c;
}

vec3 scene(vec2 uv) {
    vec3 c = img(uCur, uCurBlur, uCurXf, uv);
    if (uFade < 0.999) c = mix(img(uPrev, uPrevBlur, uPrevXf, uv), c, uFade);
    return c;
}

void main() {
    vec3 c;
    if (uCa > 0.0001) {
        // Grows with distance from the finger, with a minimum split so it shows right under it too.
        vec2 d = vUv - uCaCenter;
        float len = length(d);
        vec2 dir = len > 0.0001 ? d / len : vec2(1.0, 0.0);
        vec2 off = d * uCa + dir * uCa * 0.08;
        c.r = scene(vUv + off).r;
        c.g = scene(vUv).g;
        c.b = scene(vUv - off).b;
    } else {
        c = scene(vUv);
    }
    if (uDuo > 0.001) {
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        c = mix(c, mix(uDark, uLight, l), uDuo);
    }
    gl_FragColor = vec4(c, 1.0);
}"""
    }
}
