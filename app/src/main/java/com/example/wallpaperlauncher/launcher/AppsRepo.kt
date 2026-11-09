package com.example.wallpaperlauncher.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.wallpaperlauncher.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
)

/**
 * Launchable activities for every profile, kept fresh through LauncherApps callbacks. Icons are built only when
 * something shows them (the drawer is text-only), and kept in a size-capped cache.
 */
object AppsRepo {
    private lateinit var app: Context
    private lateinit var launcherApps: LauncherApps
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val infos = HashMap<String, LauncherActivityInfo>()
    private var started = false
    private var pack: IconPack? = null

    private val icons = object : LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    /** Bumped whenever cached icons are dropped, so on-screen icons reload. */
    private val _iconVersion = MutableStateFlow(0)
    val iconVersion: StateFlow<Int> = _iconVersion.asStateFlow()

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
        reload(null)
        // Icon settings changed: drop every icon.
        scope.launch {
            AppState.config.map { Triple(it.iconPack, it.iconPackFallback, it.transparentIcons) }
                .distinctUntilChanged().drop(1)
                .collect { dropIcons() }
        }
    }

    private fun dropIcons() {
        icons.evictAll()
        pack = null
        _iconVersion.value++
    }

    /** Low memory: icons are rebuilt on demand. */
    fun trim() {
        if (started) scope.launch { dropIcons() }
    }

    fun reload(changedPackage: String?) {
        scope.launch {
            if (changedPackage != null) {
                if (changedPackage == AppState.config.value.iconPack) dropIcons()
                else {
                    icons.snapshot().keys.filter { it.startsWith("$changedPackage/") }.forEach { icons.remove(it) }
                    _iconVersion.value++
                }
            }
            val um = app.getSystemService(UserManager::class.java)
            val me = Process.myUserHandle()
            val list = ArrayList<AppEntry>()
            infos.clear()
            for (user in launcherApps.profiles) {
                for (info in launcherApps.getActivityList(null, user)) {
                    if (info.componentName.packageName == app.packageName &&
                        info.componentName.className.endsWith("HomeActivity")) continue
                    val key = info.componentName.flattenToString() +
                        if (user != me) "#${um.getSerialNumberForUser(user)}" else ""
                    infos[key] = info
                    list += AppEntry(key, info.label.toString(), info.componentName, user)
                }
            }
            list.sortBy { it.label.lowercase() }
            _apps.value = list
        }
    }

    fun cachedIcon(key: String): ImageBitmap? = icons.get(key)

    suspend fun icon(a: AppEntry): ImageBitmap? = icons.get(a.key) ?: withContext(dispatcher) {
        icons.get(a.key) ?: infos[a.key]?.let { buildIcon(it, a.user) }?.also { icons.put(a.key, it) }
    }

    private fun buildIcon(info: LauncherActivityInfo, user: UserHandle): ImageBitmap {
        val cfg = AppState.config.value
        if (pack?.pkg != cfg.iconPack) {
            pack = if (cfg.iconPack.isEmpty()) null else runCatching { IconPack(app, cfg.iconPack) }.getOrNull()
        }
        val p = pack
        val size = (app.resources.displayMetrics.density * 56).toInt()
        val cn = info.componentName
        val packed = p?.iconFor(cn)
        var bmp = if (packed != null) packed.toIconBitmap(size, cfg.transparentIcons) else {
            val own = info.getIcon(app.resources.displayMetrics.densityDpi).toIconBitmap(size, cfg.transparentIcons)
            if (p != null && cfg.iconPackFallback && p.canThemeOthers) p.themeOther(own, cn, size) else own
        }
        if (user != Process.myUserHandle()) {
            val badged = app.packageManager.getUserBadgedIcon(BitmapDrawable(app.resources, bmp), user)
            bmp = badged.toIconBitmap(size, false)
        }
        return bmp.asImageBitmap()
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

/** An app's icon, built on demand; empty space until it's ready. */
@Composable
fun AppIcon(app: AppEntry, modifier: Modifier, alpha: Float = 1f) {
    val version by AppsRepo.iconVersion.collectAsState()
    val icon by produceState(AppsRepo.cachedIcon(app.key), app.key, version) {
        value = AppsRepo.icon(app)
    }
    val i = icon
    if (i != null) Image(i, app.label, modifier, alpha = alpha) else Box(modifier)
}
