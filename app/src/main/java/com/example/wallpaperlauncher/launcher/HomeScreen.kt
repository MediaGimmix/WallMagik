package com.example.wallpaperlauncher.launcher

import android.graphics.Rect
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wallpaperlauncher.AppState
import com.example.wallpaperlauncher.Config
import com.example.wallpaperlauncher.GestureAction
import com.example.wallpaperlauncher.media.Slideshow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LabelStyle = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 4f),
)

@Composable
fun HomeScreen(host: HomeActivity) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val cfg by AppState.config.collectAsStateWithLifecycle()
        val apps by AppsRepo.apps.collectAsStateWithLifecycle()
        val current by Slideshow.current.collectAsStateWithLifecycle()
        var drawerOpen by host.drawerOpen
        val scope = rememberCoroutineScope()
        val ctx = LocalContext.current

        var hint by remember { mutableStateOf<String?>(null) }
        var hintJob by remember { mutableStateOf<Job?>(null) }
        fun showHint(text: String) {
            if (!AppState.config.value.showToggleHints) return
            hint = text
            hintJob?.cancel()
            hintJob = scope.launch { delay(1200); hint = null }
        }

        fun perform(a: GestureAction) {
            fun onOff(b: Boolean) = if (b) "on" else "off"
            when (a) {
                GestureAction.NONE -> {}
                GestureAction.NEXT_WALLPAPER -> Slideshow.next()
                GestureAction.TOGGLE_BLUR -> {
                    AppState.update { it.copy(blurEnabled = !it.blurEnabled) }
                    showHint("Blur " + onOff(AppState.config.value.blurEnabled))
                }
                GestureAction.TOGGLE_DUOTONE -> {
                    AppState.update { it.copy(duotoneEnabled = !it.duotoneEnabled) }
                    showHint("Duotone " + onOff(AppState.config.value.duotoneEnabled))
                }
                GestureAction.TOGGLE_EFFECTS -> {
                    AppState.update { it.copy(effectsEnabled = !it.effectsEnabled) }
                    showHint("Effects " + onOff(AppState.config.value.effectsEnabled))
                }
                GestureAction.OPEN_DRAWER -> drawerOpen = true
                GestureAction.OPEN_SETTINGS -> host.openSettings()
            }
        }

        val classifier = remember {
            TapClassifier(
                scope, ViewConfiguration.getDoubleTapTimeout().toLong(),
                { AppState.config.value.gestures },
                { g -> perform(AppState.config.value.action(g)) },
            )
        }

        LaunchedEffect(drawerOpen, cfg.drawerBlur) { host.wall.setDrawerBlur(drawerOpen && cfg.drawerBlur) }
        BackHandler(enabled = drawerOpen) { drawerOpen = false }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .homeGestures(
                        holdMs = 280,
                        onTap = classifier::tap,
                        onHold = { active, p -> host.wall.setHold(active, p.x, p.y) },
                        onHoldMove = { p -> host.wall.setHold(true, p.x, p.y) },
                        onSwipe = { up ->
                            if (up) drawerOpen = true
                            else if (AppState.config.value.swipeDownNotifications) host.expandNotifications()
                        },
                    )
            )

            if (current == null) {
                Text(
                    "No wallpapers yet.\nOpen settings (swipe up, then the gear) and pick some folders.",
                    style = LabelStyle.copy(fontSize = 15.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }

            Dock(
                cfg = cfg,
                apps = apps,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )

            AnimatedVisibility(
                visible = hint != null,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 24.dp),
            ) {
                Text(
                    hint ?: "",
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }

            AnimatedVisibility(
                visible = drawerOpen,
                enter = slideInVertically { it / 3 } + fadeIn(),
                exit = slideOutVertically { it / 3 } + fadeOut(),
            ) {
                AppDrawer(
                    cfg = cfg,
                    apps = apps,
                    resetKey = host.homePresses.intValue,
                    onClose = { drawerOpen = false },
                    onLaunch = { a, r -> AppsRepo.launch(ctx, a, r) },
                    onOpenSettings = { host.openSettings() },
                    onAddToDock = { a ->
                        val dock = cfg.dock().toMutableList()
                        val free = dock.indexOf("")
                        if (free < 0) showHint("The dock is full")
                        else {
                            dock[free] = a.key
                            AppState.update { it.copy(dockApps = dock) }
                            showHint("Added to dock")
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dock(cfg: Config, apps: List<AppEntry>, modifier: Modifier) {
    val byKey = remember(apps) { apps.associateBy { it.key } }
    if (cfg.dockSlots <= 0 || cfg.dock().none { it in byKey }) return
    val ctx = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .then(
                if (cfg.dockBackground) Modifier.background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(28.dp))
                else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cfg.dock().forEachIndexed { i, key ->
            val app = byKey[key]
            var menu by remember { mutableStateOf(false) }
            val coords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (app == null) {
                    // Empty slots keep their spot but show nothing; fill them in Settings → Home screen.
                    Spacer(Modifier.size(cfg.dockIconDp.dp))
                } else Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .onGloballyPositioned { coords[0] = it }
                        .combinedClickable(
                            onClick = {
                                val b = coords[0]?.takeIf { it.isAttached }?.boundsInWindow()
                                AppsRepo.launch(ctx, app, b?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) })
                            },
                            onLongClick = { menu = true },
                        ),
                ) {
                    AppIcon(app, Modifier.size(cfg.dockIconDp.dp))
                    if (cfg.dockLabels) {
                        Text(app.label, style = LabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (app != null) {
                        DropdownMenuItem(text = { Text("Remove from dock") }, onClick = {
                            menu = false
                            AppState.update { c -> c.copy(dockApps = c.dock().toMutableList().also { it[i] = "" }) }
                        })
                        DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; AppsRepo.appInfo(app) })
                    }
                }
            }
        }
    }
}
