package com.example.wallpaperlauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.example.wallpaperlauncher.media.ImageCache
import com.example.wallpaperlauncher.media.ImageRepo
import com.example.wallpaperlauncher.media.Slideshow

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        ImageCache.init(this)
        ImageRepo.init(this)
        Slideshow.init(this)
        // Screen-on can only be received by a runtime-registered receiver; the process is kept alive by being
        // the home app (and the live wallpaper, if set).
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (AppState.config.value.nextOnWake) Slideshow.next()
            }
        }, IntentFilter(Intent.ACTION_SCREEN_ON))
    }
}
