package com.example.wallpaperlauncher.settings

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wallpaperlauncher.AppState
import com.example.wallpaperlauncher.BuildConfig
import com.example.wallpaperlauncher.Config
import com.example.wallpaperlauncher.Gesture
import com.example.wallpaperlauncher.GestureAction
import com.example.wallpaperlauncher.launcher.AppPickerDialog
import com.example.wallpaperlauncher.launcher.AppsRepo
import com.example.wallpaperlauncher.launcher.IconPack
import com.example.wallpaperlauncher.launcher.toIconBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.wallpaperlauncher.wallpaper.SlideWallpaperService
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppsRepo.init(this)
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(ctx) else darkColorScheme()) {
                SettingsRoot(onExit = { finish() })
            }
        }
    }
}

@Composable
private fun SettingsRoot(onExit: () -> Unit) {
    val stack = remember { mutableStateListOf("main") }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit() }
    BackHandler(enabled = stack.size > 1) { back() }
    val page = stack.last()
    when {
        page == "main" -> MainPage(onExit) { stack.add(it) }
        page == "slideshow" -> SlideshowPage(back) { stack.add("folder:$it") }
        page.startsWith("folder:") -> FolderPage(page.removePrefix("folder:"), back)
        page == "effects" -> EffectsPage(back)
        page == "gestures" -> GesturesPage(back)
        page == "home" -> HomePage(back)
        page == "icons" -> IconsPage(back)
        page == "drawer" -> DrawerPage(back) { stack.add("hidden") }
        page == "hidden" -> HiddenAppsPage(back)
        page == "system" -> SystemPage(back)
    }
}

@Composable
private fun MainPage(onExit: () -> Unit, open: (String) -> Unit) {
    Page("Launcher settings", onBack = onExit) {
        NavRow(Icons.Default.PlayArrow, "Slideshow", "Folders, images, interval, crossfade") { open("slideshow") }
        NavRow(Icons.Default.Star, "Effects", "Blur, duotone, chromatic aberration") { open("effects") }
        NavRow(Icons.Default.ThumbUp, "Gestures", "Taps, holds and swipes on the home screen") { open("gestures") }
        NavRow(Icons.Default.Home, "Home screen", "Dock apps, slots, icon size") { open("home") }
        NavRow(Icons.Default.Face, "Icons", "Icon packs, transparent icons") { open("icons") }
        NavRow(Icons.AutoMirrored.Filled.List, "App drawer", "Search, hidden apps") { open("drawer") }
        NavRow(Icons.Default.Lock, "Live wallpaper & system", "Lock screen, default launcher, about") { open("system") }
    }
}

@Composable
private fun EffectsPage(onBack: () -> Unit) {
    val cfg by AppState.config.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<String?>(null) }
    fun upd(f: (Config) -> Config) = AppState.update(f)

    Page("Effects", onBack) {
        SwitchRow("Effects", cfg.effectsEnabled, "Master switch for blur, duotone and chromatic aberration") { v ->
            upd { it.copy(effectsEnabled = v) }
        }

        Section("Blur")
        SwitchRow("Blur", cfg.blurEnabled) { v -> upd { it.copy(blurEnabled = v) } }
        SliderRow("Strength", cfg.blurRadius, "${(cfg.blurRadius * 100).roundToInt()}%") { v ->
            upd { it.copy(blurRadius = v) }
        }
        SwitchRow("Blur behind the app drawer", cfg.drawerBlur) { v -> upd { it.copy(drawerBlur = v) } }

        Section("Duotone")
        SwitchRow("Duotone", cfg.duotoneEnabled, "Maps the image's brightness between a dark and a light color") { v ->
            upd { it.copy(duotoneEnabled = v) }
        }
        Box(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(28.dp)
                .background(Brush.horizontalGradient(listOf(Color(cfg.duotoneDark), Color(cfg.duotoneLight))), RoundedCornerShape(8.dp))
        )
        ColorRow("Dark color", cfg.duotoneDark) { picking = "dark" }
        ColorRow("Light color", cfg.duotoneLight) { picking = "light" }
        SliderRow("Intensity", cfg.duotoneStrength, "${(cfg.duotoneStrength * 100).roundToInt()}%") { v ->
            upd { it.copy(duotoneStrength = v) }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { upd { it.copy(duotoneDark = it.duotoneLight, duotoneLight = it.duotoneDark) } }) {
                Text("Swap colors")
            }
        }

        Section("Chromatic aberration")
        SwitchRow("Press and hold", cfg.caEnabled, "Splits the colors around your finger while you hold the home screen") { v ->
            upd { it.copy(caEnabled = v) }
        }
        SliderRow("Strength", cfg.caStrength, "${(cfg.caStrength * 100).roundToInt()}%") { v ->
            upd { it.copy(caStrength = v) }
        }
        Note("Effects also apply to the live wallpaper on the lock screen (press and hold is launcher only).")
        Gap()
    }

    picking?.let { which ->
        ColorPickerDialog(
            title = if (which == "dark") "Dark color" else "Light color",
            initial = if (which == "dark") cfg.duotoneDark else cfg.duotoneLight,
            onDismiss = { picking = null },
            onPick = { c ->
                upd { if (which == "dark") it.copy(duotoneDark = c) else it.copy(duotoneLight = c) }
                picking = null
            },
        )
    }
}

@Composable
private fun GesturesPage(onBack: () -> Unit) {
    val cfg by AppState.config.collectAsStateWithLifecycle()
    Page("Gestures", onBack) {
        Section("Taps on the home screen")
        Gesture.entries.forEach { g ->
            ChoiceRow(g.label, cfg.action(g), GestureAction.entries.toList(), { it.label }) { a ->
                AppState.update { it.copy(gestures = it.gestures + (g to a)) }
            }
        }
        Note("Leaving double/triple taps unused makes the others react faster.")
        Section("Other")
        SwitchRow("Show a hint when toggling", cfg.showToggleHints) { v -> AppState.update { it.copy(showToggleHints = v) } }
        SwitchRow("Swipe down for notifications", cfg.swipeDownNotifications) { v ->
            AppState.update { it.copy(swipeDownNotifications = v) }
        }
        SwitchRow("Home button toggles the app drawer", cfg.homeOpensDrawer, "Pressing Home while on the home screen opens the drawer; pressing it again closes it") { v ->
            AppState.update { it.copy(homeOpensDrawer = v) }
        }
        Note("Swipe up opens the app drawer. Press and hold shows chromatic aberration (Effects).")
    }
}

@Composable
private fun HomePage(onBack: () -> Unit) {
    val cfg by AppState.config.collectAsStateWithLifecycle()
    val apps by AppsRepo.apps.collectAsStateWithLifecycle()
    var pickSlot by remember { mutableIntStateOf(-1) }
    Page("Home screen", onBack) {
        Section("Dock")
        SliderRow("Slots", cfg.dockSlots.toFloat(), "${cfg.dockSlots}", 0f..Config.MAX_DOCK_SLOTS.toFloat(),
            steps = Config.MAX_DOCK_SLOTS - 1) { v -> AppState.update { it.copy(dockSlots = v.roundToInt()) } }
        SliderRow("Icon size", cfg.dockIconDp.toFloat(), "${cfg.dockIconDp} dp", 36f..72f, steps = 8) { v ->
            AppState.update { it.copy(dockIconDp = v.roundToInt()) }
        }
        SwitchRow("Background", cfg.dockBackground) { v -> AppState.update { it.copy(dockBackground = v) } }
        SwitchRow("Labels", cfg.dockLabels) { v -> AppState.update { it.copy(dockLabels = v) } }

        Section("Dock apps")
        Note("Empty slots are invisible on the home screen. Long-press a dock app there to remove it.")
        cfg.dock().forEachIndexed { i, key ->
            val app = apps.firstOrNull { it.key == key }
            Row(
                Modifier.fillMaxWidth().clickable { pickSlot = i }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (app != null) {
                    Image(app.icon, null, Modifier.size(36.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = {
                        AppState.update { c -> c.copy(dockApps = c.dock().toMutableList().also { it[i] = "" }) }
                    }) { Text("Remove") }
                } else {
                    Box(Modifier.size(36.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Empty — tap to choose an app", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OutlinedButton(onClick = { AppState.update { it.copy(dockApps = emptyList()) } }, modifier = Modifier.padding(16.dp)) {
            Text("Clear dock")
        }
    }

    if (pickSlot >= 0) {
        AppPickerDialog(
            apps = apps,
            title = "Choose an app for slot ${pickSlot + 1}",
            onDismiss = { pickSlot = -1 },
            onPick = { a ->
                val slot = pickSlot
                AppState.update { c -> c.copy(dockApps = c.dock().toMutableList().also { it[slot] = a.key }) }
                pickSlot = -1
            },
        )
    }
}

@Composable
private fun DrawerPage(onBack: () -> Unit, onHidden: () -> Unit) {
    val cfg by AppState.config.collectAsStateWithLifecycle()
    Page("App drawer", onBack) {
        SwitchRow("Open the keyboard", cfg.drawerAutoKeyboard, "Focus search when the drawer opens") { v ->
            AppState.update { it.copy(drawerAutoKeyboard = v) }
        }
        Section("Hidden apps")
        NavRow(null, "Hidden apps", "${cfg.hiddenApps.size} hidden", onHidden)
        SwitchRow("Find hidden apps in search", cfg.hiddenInSearch) { v -> AppState.update { it.copy(hiddenInSearch = v) } }
    }
}

@Composable
private fun HiddenAppsPage(onBack: () -> Unit) {
    val cfg by AppState.config.collectAsStateWithLifecycle()
    val apps by AppsRepo.apps.collectAsStateWithLifecycle()
    Page("Hidden apps", onBack) {
        apps.forEach { a ->
            val hidden = a.key in cfg.hiddenApps
            Row(
                Modifier.fillMaxWidth().clickable {
                    AppState.update { c -> c.copy(hiddenApps = if (hidden) c.hiddenApps - a.key else c.hiddenApps + a.key) }
                }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(a.icon, null, Modifier.size(36.dp))
                Spacer(Modifier.width(14.dp))
                Text(a.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Checkbox(checked = hidden, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun SystemPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ours = ComponentName(ctx, SlideWallpaperService::class.java)
    val active = remember { WallpaperManager.getInstance(ctx).wallpaperInfo?.component == ours }
    Page("Live wallpaper & system", onBack) {
        Section("Live wallpaper")
        Note(
            "The same slideshow and effects can run as the system live wallpaper, so the lock screen matches the " +
                "launcher. Tap gestures only work in the launcher." +
                if (active) "\n\nIt's currently set as your wallpaper." else ""
        )
        Button(onClick = {
            val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ours)
            try {
                ctx.startActivity(i)
            } catch (e: Exception) {
                try {
                    ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                } catch (e2: Exception) {
                    Toast.makeText(ctx, "No live wallpaper picker on this device", Toast.LENGTH_SHORT).show()
                }
            }
        }, modifier = Modifier.padding(16.dp)) { Text("Set as live wallpaper") }

        Section("Launcher")
        OutlinedButton(onClick = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Choose default home app") }

        Section("About")
        Note("Wallpaper Launcher ${BuildConfig.VERSION_NAME}")
        Gap()
    }
}

@Composable
private fun IconsPage(onBack: () -> Unit) {
    val cfg by AppState.config.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val density = ctx.resources.displayMetrics.density
    val packs by produceState(emptyList<Pair<String, Pair<String, androidx.compose.ui.graphics.ImageBitmap?>>>()) {
        value = withContext(Dispatchers.IO) {
            IconPack.installed(ctx).map { it.pkg to (it.label to it.icon?.toIconBitmap((40 * density).toInt(), false)?.asImageBitmap()) }
        }
    }
    Page("Icons", onBack) {
        Section("Icon pack")
        @Composable
        fun option(pkg: String, label: String, icon: androidx.compose.ui.graphics.ImageBitmap?) {
            Row(
                Modifier.fillMaxWidth().clickable { AppState.update { it.copy(iconPack = pkg) } }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = cfg.iconPack == pkg, onClick = null)
                Spacer(Modifier.width(14.dp))
                if (icon != null) Image(icon, null, Modifier.size(36.dp)) else Box(Modifier.size(36.dp))
                Spacer(Modifier.width(14.dp))
                Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        option("", "System icons", null)
        packs.forEach { (pkg, v) -> option(pkg, v.first, v.second) }
        if (packs.isEmpty()) Note("No icon packs found. Packs that work with Nova, ADW or Apex show up here.")
        if (cfg.iconPack.isNotEmpty() && packs.none { it.first == cfg.iconPack }) {
            Note("The selected pack isn't installed any more; system icons are used.")
        }

        Section("Style")
        SwitchRow(
            "Transparent icons", cfg.transparentIcons,
            "Drop the background shape of adaptive icons and show just the symbol",
        ) { v -> AppState.update { it.copy(transparentIcons = v) } }
        SwitchRow(
            "Theme apps the pack doesn't cover", cfg.iconPackFallback,
            "Uses the pack's own background and mask, if it has them",
        ) { v -> AppState.update { it.copy(iconPackFallback = v) } }
        Gap()
    }
}
