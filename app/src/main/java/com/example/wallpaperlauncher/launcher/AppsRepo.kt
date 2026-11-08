package com.example.wallpaperlauncher.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.example.wallpaperlauncher.AppState
import java.util.concurrent.Executors

data class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
)

/** Launchable activities for every profile, kept fresh through LauncherApps callbacks. */
object AppsRepo {
    private lateinit var app: Context
    private lateinit var launcherApps: LauncherApps
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val icons = HashMap<String, ImageBitmap>()
    private var started = false

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    fun init(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        launcherApps = app.getSystemService(LauncherApps::class.java)
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: UserHandle) = reload(p)
            override fun onPackageAdded(p: String, u: UserHandle) = reload(p)
            override fun onPackageChanged(p: String, u: UserHandle) = reload(p)
            override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload(null)
            override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload(null)
        }, Handler(Looper.getMainLooper()))
        // Icon settings changed: rebuild every icon.
        scope.launch {
            AppState.config.map { Triple(it.iconPack, it.iconPackFallback, it.transparentIcons) }
                .distinctUntilChanged()
                .collect {
                    icons.clear()
                    pack = null
                    reloadNow(null)
                }
        }
    }

    private var pack: IconPack? = null

    fun reload(changedPackage: String?) {
        scope.launch { reloadNow(changedPackage) }
    }

    private fun reloadNow(changedPackage: String?) {
        run {
            val cfg = AppState.config.value
            if (changedPackage != null) {
                if (changedPackage == cfg.iconPack) { icons.clear(); pack = null }
                else icons.keys.removeAll { it.startsWith("$changedPackage/") }
            }
            if (pack?.pkg != cfg.iconPack) {
                pack = if (cfg.iconPack.isEmpty()) null
                else runCatching { IconPack(app, cfg.iconPack) }.getOrNull()
            }
            val p = pack
            val pm = app.packageManager
            val dpi = app.resources.displayMetrics.densityDpi
            val um = app.getSystemService(UserManager::class.java)
            val me = Process.myUserHandle()
            val size = (app.resources.displayMetrics.density * 64).toInt()
            val list = ArrayList<AppEntry>()
            for (user in launcherApps.profiles) {
                for (info in launcherApps.getActivityList(null, user)) {
                    if (info.componentName.packageName == app.packageName &&
                        info.componentName.className.endsWith("HomeActivity")) continue
                    val key = info.componentName.flattenToString() +
                        if (user != me) "#${um.getSerialNumberForUser(user)}" else ""
                    val icon = icons.getOrPut(key) {
                        val cn = info.componentName
                        val packed = p?.iconFor(cn)
                        var bmp = if (packed != null) packed.toIconBitmap(size, cfg.transparentIcons) else {
                            val own = info.getIcon(dpi).toIconBitmap(size, cfg.transparentIcons)
                            if (p != null && cfg.iconPackFallback && p.canThemeOthers) p.themeOther(own, cn, size) else own
                        }
                        if (user != me) {
                            val badged = pm.getUserBadgedIcon(BitmapDrawable(app.resources, bmp), user)
                            bmp = badged.toIconBitmap(size, false)
                        }
                        bmp.asImageBitmap()
                    }
                    list += AppEntry(key, info.label.toString(), info.componentName, user, icon)
                }
            }
            list.sortBy { it.label.lowercase() }
            _apps.value = list
        }
    }

    fun find(key: String): AppEntry? = _apps.value.firstOrNull { it.key == key }

    fun launch(ctx: Context, a: AppEntry, bounds: Rect? = null) {
        try {
            launcherApps.startMainActivity(a.component, a.user, bounds, null)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't open ${a.label}", Toast.LENGTH_SHORT).show()
            reload(null)
        }
    }

    fun appInfo(a: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(a.component, a.user, null, null) }
    }

    fun uninstall(ctx: Context, a: AppEntry) {
        val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.component.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }
}
