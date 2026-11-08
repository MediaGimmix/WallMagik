package com.example.wallpaperlauncher.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An EGL context on its own thread that outlives window surfaces (a 1x1 pbuffer keeps it current when there is
 * no window), so textures survive the launcher going to the background. Frames are driven by Choreographer and
 * only requested while [WallRenderer.draw] says something is animating.
 */
class GlRenderThread(private val renderer: WallRenderer) : HandlerThread("wall-gl") {
    private lateinit var handler: Handler
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var frameScheduled = false
    private var ready = false

    private val frameCallback = Choreographer.FrameCallback { t ->
        frameScheduled = false
        drawFrame(t)
    }

    fun begin() {
        start()
        handler = Handler(looper)
        handler.post {
            ready = initEgl()
            if (ready) renderer.init()
        }
    }

    fun post(r: () -> Unit) {
        handler.post(r)
    }

    fun setSurface(surface: Surface) = runSync {
        if (!ready) return@runSync
        destroyWindow()
        window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (window == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            return@runSync
        }
        EGL14.eglMakeCurrent(display, window, window, context)
    }

    fun resize(w: Int, h: Int) { handler.post {
        renderer.resize(w, h)
        schedule()
    } }

    /** Must return before the Surface is gone, hence synchronous. */
    fun clearSurface() = runSync {
        if (!ready) return@runSync
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
        destroyWindow()
    }

    fun requestRender() { handler.post { schedule() } }

    fun shutdown() { handler.post {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (ready) {
            renderer.release()
            destroyWindow()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        quitSafely()
    } }

    private fun schedule() {
        if (!frameScheduled && window != EGL14.EGL_NO_SURFACE) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun drawFrame(frameTimeNanos: Long) {
        if (window == EGL14.EGL_NO_SURFACE) return
        val more = renderer.draw(frameTimeNanos)
        if (!EGL14.eglSwapBuffers(display, window)) {
            Log.w(TAG, "eglSwapBuffers: ${EGL14.eglGetError()}")
        }
        if (more) schedule()
    }

    private fun destroyWindow() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
    }

    private fun initEgl(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0, EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            Log.w(TAG, "no EGL config")
            return false
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (context == EGL14.EGL_NO_CONTEXT) return false
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        return EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    private fun runSync(block: () -> Unit) {
        if (Looper.myLooper() == looper) {
            block(); return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    companion object {
        private const val TAG = "GlRenderThread"
    }
}
