package com.example.wallpaperlauncher.gl

import android.view.Surface
import com.example.wallpaperlauncher.AppState
import com.example.wallpaperlauncher.media.ImageCache
import com.example.wallpaperlauncher.media.ImageItem
import com.example.wallpaperlauncher.media.Slideshow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Glue between a Surface (launcher SurfaceView or wallpaper engine) and the slideshow: owns one GL thread +
 * renderer, follows [Slideshow.current] while visible. Main thread only.
 */
class WallSurface {
    private val renderer = WallRenderer()
    private val thread = GlRenderThread(renderer).also { it.begin() }
    private var scope: CoroutineScope? = null
    private var visible = false
    private val onConfig: () -> Unit = { thread.requestRender() }

    fun surfaceCreated(surface: Surface) = thread.setSurface(surface)
    fun surfaceChanged(w: Int, h: Int) = thread.resize(w, h)
    fun surfaceDestroyed() = thread.clearSurface()

    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        if (v) {
            Slideshow.acquire()
            AppState.listeners += onConfig
            val s = MainScope()
            scope = s
            s.launch { Slideshow.current.collect { show(it) } }
            thread.requestRender()
        } else {
            Slideshow.release()
            AppState.listeners -= onConfig
            scope?.cancel()
            scope = null
        }
    }

    private fun show(item: ImageItem?) {
        if (item == null) {
            thread.post { renderer.setImage(-1, null) }
            thread.requestRender()
            return
        }
        val bmp = ImageCache.get(item.id)
        if (bmp != null) {
            thread.post { renderer.setImage(item.id, bmp) }
            thread.requestRender()
        } else {
            ImageCache.load(item) { b ->
                thread.post { renderer.setImage(item.id, b) }
                thread.requestRender()
            }
        }
    }

    /** Chromatic aberration hold; x/y are 0..1 of the surface. */
    fun setHold(active: Boolean, x: Float, y: Float) {
        thread.post {
            renderer.hold = active
            renderer.holdX = x
            renderer.holdY = y
        }
        thread.requestRender()
    }

    fun setDrawerBlur(on: Boolean) {
        thread.post { renderer.drawerBlur = on }
        thread.requestRender()
    }

    fun release() {
        setVisible(false)
        thread.shutdown()
    }
}
