package com.example.wallpaperlauncher.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.example.wallpaperlauncher.gl.WallSurface
import com.example.wallpaperlauncher.media.ImageRepo
import com.example.wallpaperlauncher.settings.SettingsActivity

/**
 * The home screen. It draws its own wallpaper (a SurfaceView behind the Compose UI) instead of relying on the
 * system live wallpaper, so a vendor killing wallpaper services can't blank it.
 */
class HomeActivity : ComponentActivity() {
    lateinit var wall: WallSurface
        private set
    val drawerOpen = mutableStateOf(false)
    /** Bumped when Home is pressed while already home, so the UI can reset itself. */
    val homePresses = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        AppsRepo.init(this)
        wall = WallSurface()

        val root = FrameLayout(this)
        val surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = wall.surfaceCreated(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) =
                wall.surfaceChanged(w, h)
            override fun surfaceDestroyed(holder: SurfaceHolder) = wall.surfaceDestroyed()
        })
        root.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(ComposeView(this).apply {
            setContent { HomeScreen(this@HomeActivity) }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        wall.setVisible(true)
        if (ImageRepo.hasPermission(this) && ImageRepo.images.value.isEmpty()) ImageRepo.scan()
    }

    override fun onStop() {
        wall.setVisible(false)
        wall.setHold(false, 0.5f, 0.5f)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            drawerOpen.value = false
            homePresses.intValue++
        }
    }

    override fun onDestroy() {
        wall.release()
        super.onDestroy()
    }

    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @SuppressLint("WrongConstant")
    fun expandNotifications() {
        runCatching {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        }
    }
}
