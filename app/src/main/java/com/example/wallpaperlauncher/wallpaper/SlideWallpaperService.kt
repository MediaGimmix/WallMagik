package com.example.wallpaperlauncher.wallpaper

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.example.wallpaperlauncher.gl.WallSurface

/**
 * The same slideshow + effects as the launcher, as a system live wallpaper (home and lock screen). No tap
 * gestures here: those only exist in the launcher. Runs in the launcher's process, so they share one clock.
 */
class SlideWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = SlideEngine()

    private inner class SlideEngine : Engine() {
        private var wall: WallSurface? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            wall = WallSurface()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            wall?.surfaceCreated(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            wall?.surfaceChanged(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            wall?.setVisible(false)
            wall?.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            wall?.setVisible(visible)
        }

        override fun onDestroy() {
            wall?.release()
            wall = null
            super.onDestroy()
        }
    }
}
