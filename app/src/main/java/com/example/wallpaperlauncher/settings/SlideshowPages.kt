package com.example.wallpaperlauncher.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wallpaperlauncher.AppState
import com.example.wallpaperlauncher.media.Folder
import com.example.wallpaperlauncher.media.ImageCache
import com.example.wallpaperlauncher.media.ImageItem
import com.example.wallpaperlauncher.media.ImageRepo
import com.example.wallpaperlauncher.media.Slideshow
import com.example.wallpaperlauncher.media.Thumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Slider stops from a tenth of a second to a day. */
val INTERVAL_STEPS = longArrayOf(
    100, 200, 250, 500, 750,
    1_000, 2_000, 3_000, 5_000, 10_000, 15_000, 30_000,
    60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000,
    3_600_000, 7_200_000, 10_800_000, 21_600_000, 43_200_000, 86_400_000,
)

fun formatInterval(ms: Long): String = when {
    ms < 60_000 -> trim(ms / 1000.0) + " s"
    ms < 3_600_000 -> trim(ms / 60_000.0) + " min"
    ms < 86_400_000 -> trim(ms / 3_600_000.0) + " h"
    else -> trim(ms / 86_400_000.0) + " day"
}

private fun trim(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(d).trimEnd('0').trimEnd('.')

@Composable
fun SlideshowPage(onBack: () -> Unit, onOpenFolder: (String) -> Unit) {
    val cfg by AppState.config.collectAsStateWithLifecycle()
    val folders by ImageRepo.folders.collectAsStateWithLifecycle()
    val scanning by ImageRepo.scanning.collectAsStateWithLifecycle()
    val current by Slideshow.current.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ImageRepo.hasPermission(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) ImageRepo.scan()
    }

    Page("Slideshow", onBack) {
        if (!granted) {
            Note("Wallpaper Launcher needs access to your photos to find images.")
            Button(onClick = { permLauncher.launch(ImageRepo.permission) }, modifier = Modifier.padding(16.dp)) {
                Text("Allow photo access")
            }
        }

        // Current image
        Section("Now showing")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
            val bmp by produceState<ImageBitmap?>(null, current) {
                value = current?.let { ImageCache.await(it)?.asImageBitmap() }
            }
            Box(
                Modifier.width(150.dp).aspectRatio(9f / 19.5f).clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF222226)),
                contentAlignment = Alignment.Center,
            ) {
                val b = bmp
                if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(if (current == null) "Nothing selected" else "…", color = Color.Gray)
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${Slideshow.poolSize} images in rotation", style = MaterialTheme.typography.bodyMedium)
                Text("${cfg.selectedFolders.size} folders selected", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { Slideshow.next() }, enabled = current != null) { Text("Next image") }
            }
        }

        // Folder carousel
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Section("Folders") }
            if (granted) {
                OutlinedButton(onClick = { ImageRepo.scan() }, enabled = !scanning, modifier = Modifier.padding(end = 16.dp, top = 12.dp)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) "Scanning…" else "Rescan")
                }
            }
        }
        Note("Tap the check to use a folder; tap the folder to pick images inside it.")
        if (granted && folders.isEmpty() && !scanning) Note("No images found on this device.")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            // Selected folders first.
            items(folders.sortedBy { it.id !in cfg.selectedFolders }, key = { it.id }) { f ->
                FolderCard(f, f.id in cfg.selectedFolders, onOpen = { onOpenFolder(f.id) }, onToggle = {
                    AppState.update { c ->
                        c.copy(selectedFolders = if (f.id in c.selectedFolders) c.selectedFolders - f.id else c.selectedFolders + f.id)
                    }
                })
            }
        }

        Section("Timing")
        IntervalSetting(cfg.intervalMs) { ms -> AppState.update { it.copy(intervalMs = ms) } }
        SliderRow(
            "Crossfade", cfg.transitionMs.toFloat(),
            if (cfg.transitionMs == 0) "Off" else formatInterval(cfg.transitionMs.toLong()),
            range = 0f..3000f, steps = 29,
        ) { v -> AppState.update { it.copy(transitionMs = v.roundToInt()) } }
        Note("The crossfade is shortened automatically for very short intervals.")
        SwitchRow("Next image on wake", cfg.nextOnWake, "Show a new image every time the screen turns on") { v ->
            AppState.update { it.copy(nextOnWake = v) }
        }
        Gap()
    }
}

@Composable
private fun IntervalSetting(ms: Long, onChange: (Long) -> Unit) {
    val idx = INTERVAL_STEPS.indices.minBy { abs(INTERVAL_STEPS[it] - ms) }
    SliderRow(
        "Change every", idx.toFloat(), formatInterval(ms),
        range = 0f..(INTERVAL_STEPS.size - 1).toFloat(), steps = INTERVAL_STEPS.size - 2,
    ) { onChange(INTERVAL_STEPS[it.roundToInt()]) }
    var text by remember(ms) { mutableStateOf(trim(ms / 1000.0)) }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = text,
            onValueChange = { t ->
                text = t
                t.toDoubleOrNull()?.let { s -> if (s >= 0.05 && s <= 86_400.0) onChange((s * 1000).roundToInt().toLong()) }
            },
            label = { Text("Exact interval (seconds, 0.05 – 86400)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Note(
        if (ms >= 1000) "Changes line up with the clock, e.g. every minute on :00, every hour on the hour, every day at midnight."
        else "Sub-second intervals count from the previous change."
    )
}

@Composable
private fun FolderCard(f: Folder, selected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Column(Modifier.width(120.dp)) {
        Box {
            Thumb(
                f.cover, 120.dp,
                Modifier.clip(RoundedCornerShape(14.dp))
                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier)
                    .clickable(onClick = onOpen)
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f), CircleShape)
                    .border(1.5.dp, Color.White, CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, "Selected", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp))
        Text("${f.count} images", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Thumb(item: ImageItem, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtMost(384)
    val bmp by produceState(Thumbs.cached(item.id)?.asImageBitmap(), item.id) {
        if (value == null) value = withContext(Dispatchers.IO) { Thumbs.load(ctx.contentResolver, item, px)?.asImageBitmap() }
    }
    Box(modifier.size(size).background(Color(0xFF2A2A2E))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Every image in one folder; tap to leave it out of (or put it back in) the slideshow. */
@Composable
fun FolderPage(bucketId: String, onBack: () -> Unit) {
    val cfg by AppState.config.collectAsStateWithLifecycle()
    val folders by ImageRepo.folders.collectAsStateWithLifecycle()
    val all by ImageRepo.images.collectAsStateWithLifecycle()
    val folder = folders.firstOrNull { it.id == bucketId }
    val images = remember(all, bucketId) { all.filter { it.bucketId == bucketId } }
    val selected = bucketId in cfg.selectedFolders
    val included = images.count { it.id !in cfg.excludedImages }

    Page(folder?.name ?: "Folder", onBack, scroll = false) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (folder != null && folder.path.isNotEmpty()) Note(folder.path)
                    SwitchRow("Use this folder", selected, "$included of ${images.size} images included") { on ->
                        AppState.update { c -> c.copy(selectedFolders = if (on) c.selectedFolders + bucketId else c.selectedFolders - bucketId) }
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            AppState.update { c -> c.copy(excludedImages = c.excludedImages - images.map { it.id }.toSet()) }
                        }) { Text("Include all") }
                        OutlinedButton(onClick = {
                            AppState.update { c -> c.copy(excludedImages = c.excludedImages + images.map { it.id }) }
                        }) { Text("Exclude all") }
                    }
                    Note("Tap an image to include or exclude it.")
                    Spacer(Modifier.height(4.dp))
                }
            }
            items(images, key = { it.id }) { img ->
                val excluded = img.id in cfg.excludedImages
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        AppState.update { c ->
                            c.copy(excludedImages = if (excluded) c.excludedImages - img.id else c.excludedImages + img.id)
                        }
                    }
                ) {
                    Thumb(img, 110.dp, Modifier.fillMaxWidth().aspectRatio(1f))
                    if (excluded) {
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Close, "Excluded", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
