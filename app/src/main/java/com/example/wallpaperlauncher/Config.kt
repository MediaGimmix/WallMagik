package com.example.wallpaperlauncher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArraySet

enum class GestureAction(val label: String) {
    NONE("Do nothing"),
    NEXT_WALLPAPER("Next wallpaper"),
    TOGGLE_BLUR("Toggle blur"),
    TOGGLE_DUOTONE("Toggle duotone"),
    TOGGLE_EFFECTS("Toggle effects"),
    OPEN_DRAWER("Open app drawer"),
    OPEN_SETTINGS("Open launcher settings"),
}

enum class Gesture(val label: String) {
    DOUBLE_TAP("Single finger double tap"),
    TRIPLE_TAP("Single finger triple tap"),
    TWO_FINGER_TAP("Two finger tap"),
    TWO_FINGER_DOUBLE_TAP("Two finger double tap"),
}

/** Every user setting. Immutable: change it through [AppState.update]. */
data class Config(
    // Slideshow
    val intervalMs: Long = 30_000,
    val transitionMs: Int = 800,
    val selectedFolders: Set<String> = emptySet(),
    val excludedImages: Set<Long> = emptySet(),
    val nextOnWake: Boolean = false,
    val safFolders: Set<String> = emptySet(),   // folder tree URIs picked through the system picker

    // Effects
    val effectsEnabled: Boolean = true,
    val blurEnabled: Boolean = false,
    val blurRadius: Float = 0.5f,          // 0..1
    val duotoneEnabled: Boolean = false,
    val duotoneDark: Int = 0xFF1B1036.toInt(),
    val duotoneLight: Int = 0xFFFFC796.toInt(),
    val duotoneStrength: Float = 1f,       // 0..1
    val caEnabled: Boolean = true,
    val caStrength: Float = 0.5f,          // 0..1
    val drawerBlur: Boolean = true,

    // Gestures
    val gestures: Map<Gesture, GestureAction> = DEFAULT_GESTURES,
    val swipeDownNotifications: Boolean = true,
    val homeOpensDrawer: Boolean = true,
    val showToggleHints: Boolean = true,

    // Home screen / dock
    val dockSlots: Int = 5,
    val dockApps: List<String> = emptyList(),
    val dockIconDp: Int = 52,
    val dockBackground: Boolean = true,
    val dockLabels: Boolean = false,

    // App drawer
    val drawerColumns: Int = 4,
    val drawerLabels: Boolean = true,
    val drawerAutoKeyboard: Boolean = false,
    val hiddenApps: Set<String> = emptySet(),
    val hiddenInSearch: Boolean = true,

    // Icons
    val iconPack: String = "",              // icon pack package; "" = system icons
    val iconPackFallback: Boolean = true,   // put apps the pack lacks on the pack's backgrounds/mask
    val transparentIcons: Boolean = false,  // drop adaptive icon backgrounds, show just the glyph
) {
    fun action(g: Gesture): GestureAction = gestures[g] ?: GestureAction.NONE

    /** Dock contents padded/truncated to [dockSlots]; "" = empty slot. */
    fun dock(): List<String> = List(dockSlots) { dockApps.getOrNull(it) ?: "" }

    companion object {
        val DEFAULT_GESTURES = mapOf(
            Gesture.DOUBLE_TAP to GestureAction.NEXT_WALLPAPER,
            Gesture.TRIPLE_TAP to GestureAction.TOGGLE_EFFECTS,
            Gesture.TWO_FINGER_TAP to GestureAction.TOGGLE_BLUR,
            Gesture.TWO_FINGER_DOUBLE_TAP to GestureAction.TOGGLE_DUOTONE,
        )
        const val MAX_DOCK_SLOTS = 8
    }
}

/** Process-wide settings; the launcher, settings screen and live wallpaper all run in one process. */
object AppState {
    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    /** Called on every settings change so the GL views redraw (effects read [config] each frame). */
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _config.value = load(prefs)
    }

    /** Main thread only. */
    fun update(change: (Config) -> Config) {
        val old = _config.value
        val new = change(old)
        if (new == old) return
        _config.value = new
        save(prefs, new)
        listeners.forEach { it() }
    }

    private fun load(p: SharedPreferences): Config {
        val d = Config()
        return Config(
            intervalMs = p.getLong("interval_ms", d.intervalMs),
            transitionMs = p.getInt("transition_ms", d.transitionMs),
            nextOnWake = p.getBoolean("next_on_wake", d.nextOnWake),
            safFolders = p.getStringSet("saf_folders", null)?.toSet() ?: d.safFolders,
            selectedFolders = p.getStringSet("folders", null)?.toSet() ?: d.selectedFolders,
            excludedImages = p.getStringSet("excluded", null)?.mapNotNull { it.toLongOrNull() }?.toSet()
                ?: d.excludedImages,
            effectsEnabled = p.getBoolean("effects", d.effectsEnabled),
            blurEnabled = p.getBoolean("blur", d.blurEnabled),
            blurRadius = p.getFloat("blur_radius", d.blurRadius),
            duotoneEnabled = p.getBoolean("duotone", d.duotoneEnabled),
            duotoneDark = p.getInt("duotone_dark", d.duotoneDark),
            duotoneLight = p.getInt("duotone_light", d.duotoneLight),
            duotoneStrength = p.getFloat("duotone_strength", d.duotoneStrength),
            caEnabled = p.getBoolean("ca", d.caEnabled),
            caStrength = p.getFloat("ca_strength", d.caStrength),
            drawerBlur = p.getBoolean("drawer_blur", d.drawerBlur),
            gestures = Gesture.entries.associateWith { g ->
                p.getString("gesture_${g.name}", null)
                    ?.let { runCatching { GestureAction.valueOf(it) }.getOrNull() }
                    ?: d.action(g)
            },
            swipeDownNotifications = p.getBoolean("swipe_down_notifications", d.swipeDownNotifications),
            homeOpensDrawer = p.getBoolean("home_opens_drawer", d.homeOpensDrawer),
            showToggleHints = p.getBoolean("toggle_hints", d.showToggleHints),
            dockSlots = p.getInt("dock_slots", d.dockSlots),
            dockApps = p.getString("dock_apps", null)?.split("\n") ?: d.dockApps,
            dockIconDp = p.getInt("dock_icon_dp", d.dockIconDp),
            dockBackground = p.getBoolean("dock_background", d.dockBackground),
            dockLabels = p.getBoolean("dock_labels", d.dockLabels),
            drawerColumns = p.getInt("drawer_columns", d.drawerColumns),
            drawerLabels = p.getBoolean("drawer_labels", d.drawerLabels),
            drawerAutoKeyboard = p.getBoolean("drawer_keyboard", d.drawerAutoKeyboard),
            hiddenApps = p.getStringSet("hidden_apps", null)?.toSet() ?: d.hiddenApps,
            hiddenInSearch = p.getBoolean("hidden_in_search", d.hiddenInSearch),
            iconPack = p.getString("icon_pack", null) ?: d.iconPack,
            iconPackFallback = p.getBoolean("icon_pack_fallback", d.iconPackFallback),
            transparentIcons = p.getBoolean("transparent_icons", d.transparentIcons),
        )
    }

    private fun save(p: SharedPreferences, c: Config) {
        p.edit().apply {
            putLong("interval_ms", c.intervalMs)
            putInt("transition_ms", c.transitionMs)
            putBoolean("next_on_wake", c.nextOnWake)
            putStringSet("saf_folders", c.safFolders)
            putStringSet("folders", c.selectedFolders)
            putStringSet("excluded", c.excludedImages.map { it.toString() }.toSet())
            putBoolean("effects", c.effectsEnabled)
            putBoolean("blur", c.blurEnabled)
            putFloat("blur_radius", c.blurRadius)
            putBoolean("duotone", c.duotoneEnabled)
            putInt("duotone_dark", c.duotoneDark)
            putInt("duotone_light", c.duotoneLight)
            putFloat("duotone_strength", c.duotoneStrength)
            putBoolean("ca", c.caEnabled)
            putFloat("ca_strength", c.caStrength)
            putBoolean("drawer_blur", c.drawerBlur)
            c.gestures.forEach { (g, a) -> putString("gesture_${g.name}", a.name) }
            putBoolean("swipe_down_notifications", c.swipeDownNotifications)
            putBoolean("home_opens_drawer", c.homeOpensDrawer)
            putBoolean("toggle_hints", c.showToggleHints)
            putInt("dock_slots", c.dockSlots)
            putString("dock_apps", c.dockApps.joinToString("\n"))
            putInt("dock_icon_dp", c.dockIconDp)
            putBoolean("dock_background", c.dockBackground)
            putBoolean("dock_labels", c.dockLabels)
            putInt("drawer_columns", c.drawerColumns)
            putBoolean("drawer_labels", c.drawerLabels)
            putBoolean("drawer_keyboard", c.drawerAutoKeyboard)
            putStringSet("hidden_apps", c.hiddenApps)
            putBoolean("hidden_in_search", c.hiddenInSearch)
            putString("icon_pack", c.iconPack)
            putBoolean("icon_pack_fallback", c.iconPackFallback)
            putBoolean("transparent_icons", c.transparentIcons)
        }.apply()
    }
}
