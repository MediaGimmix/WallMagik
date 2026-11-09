package com.example.wallpaperlauncher.launcher

import android.graphics.Rect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.wallpaperlauncher.AppState
import com.example.wallpaperlauncher.Config

@Composable
fun AppDrawer(
    cfg: Config,
    apps: List<AppEntry>,
    resetKey: Int,
    onClose: () -> Unit,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpenSettings: () -> Unit,
    onAddToDock: (AppEntry) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val list = rememberLazyListState()

    LaunchedEffect(Unit) { if (cfg.drawerAutoKeyboard) runCatching { focus.requestFocus() } }
    LaunchedEffect(resetKey) { query = ""; list.scrollToItem(0) }

    val q = query.trim()
    val shown = remember(apps, q, showHidden, cfg.hiddenApps, cfg.hiddenInSearch) {
        apps.filter { a ->
            val hidden = a.key in cfg.hiddenApps
            if (q.isEmpty()) !hidden || showHidden
            else a.label.contains(q, ignoreCase = true) && (!hidden || showHidden || cfg.hiddenInSearch)
        }.let { list ->
            // Names that start with the query first.
            if (q.isEmpty()) list else list.sortedBy { !it.label.startsWith(q, ignoreCase = true) }
        }
    }

    // Pulling down past the top of the list closes the drawer.
    val pullToClose = remember {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    acc += available.y
                    if (acc > 220f) { acc = 0f; onClose() }
                } else if (consumed.y < 0f) acc = 0f
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                acc = 0f
                return Velocity.Zero
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.40f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onVerticalDrag = { _, d -> total += d; if (total > 120f) { total = 0f; onClose() } },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.16f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.12f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    shown.firstOrNull()?.let { onLaunch(it, null) }
                    focusManager.clearFocus()
                }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Launcher settings", tint = Color.White) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (showHidden) "Don't show hidden apps" else "Show hidden apps") },
                        onClick = { showHidden = !showHidden; menu = false },
                    )
                }
            }
        }

        LazyColumn(
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize().nestedScroll(pullToClose),
        ) {
            items(shown, key = { it.key }) { a ->
                DrawerItem(app = a, hidden = a.key in cfg.hiddenApps, onLaunch = onLaunch, onAddToDock = onAddToDock)
            }
        }
    }
}

private val ItemShape = RoundedCornerShape(10.dp)
private val ItemBackground = Color.White.copy(alpha = 0.09f)

/** Just the app's name on a faint background: cheap to draw and scroll. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItem(
    app: AppEntry,
    hidden: Boolean,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onAddToDock: (AppEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Text(
            app.label,
            color = if (hidden) Color.White.copy(alpha = 0.5f) else Color.White,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ItemShape)
                .background(ItemBackground)
                .combinedClickable(onClick = { onLaunch(app, null) }, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (menu) {
            val ctx = LocalContext.current
            DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Add to dock") }, onClick = { menu = false; onAddToDock(app) })
                DropdownMenuItem(text = { Text(if (hidden) "Unhide" else "Hide") }, onClick = {
                    menu = false
                    AppState.update { c -> c.copy(hiddenApps = if (hidden) c.hiddenApps - app.key else c.hiddenApps + app.key) }
                })
                DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; AppsRepo.appInfo(app) })
                DropdownMenuItem(text = { Text("Uninstall") }, onClick = { menu = false; AppsRepo.uninstall(ctx, app) })
            }
        }
    }
}

/** Searchable list of every app; used to fill dock slots. */
@Composable
fun AppPickerDialog(apps: List<AppEntry>, title: String, onDismiss: () -> Unit, onPick: (AppEntry) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) { apps.filter { it.label.contains(query.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.key }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(a, Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
