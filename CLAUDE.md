# Wallpaper Launcher

Kotlin + Jetpack Compose Android launcher that draws its own slideshow wallpaper (so the FiiO JM21 can't kill it),
plus the same renderer as a system live wallpaper for the lock screen.
Build with `source env.sh && ./gradlew --offline assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` (release = R8, debug-signed; debug builds are far too laggy to judge UI). Lint jars are not in the offline cache. Same toolchain as ../AndroidMediaPlayer-Claude.

## Constraints
- No Google dependencies at runtime. Target: FiiO JM21 (Android 13, adb transport id, serial shows as "?"). minSdk 30.

## Architecture
- One process for launcher, settings and wallpaper service; they share singletons.
- `Config.kt`: `Config` data class (all settings) + `AppState` (StateFlow, SharedPreferences "settings").
  `AppState.update {}` on the main thread; `AppState.listeners` makes GL views redraw.
- `media/ImageRepo`: MediaStore scan → images + folders (buckets). `ImageCache`: screen-size software bitmaps,
  LRU of 4, EXIF rotation safety net. `Thumbs`: picker thumbnails.
- `media/Slideshow`: the single clock. Shuffle bag, prefetches the next image, runs only while something is visible
  (`acquire/release`). Intervals >= 1 s are anchored to local wall-clock boundaries; shorter ones are relative.
- `gl/GlRenderThread`: EGL14 on a HandlerThread, pbuffer keeps the context (and textures) across surfaces,
  Choreographer frames only while animating. `gl/WallRenderer`: crossfade, blur (per-image downsample + Gaussian
  computed once), duotone, chromatic aberration (hold). `gl/WallSurface`: glue used by both hosts.
- `launcher/HomeActivity`: SurfaceView (wallpaper) + ComposeView (UI). `HomeScreen`: gestures, dock, hints.
  `Gestures.kt`: `homeGestures` modifier + `TapClassifier`. `AppDrawer`: text-only list (name on a faint pill), search, hide, long-press menu.
- `launcher/IconPack`: ADW/Nova appfilter.xml packs (iconback/mask/upon fallback); `toIconBitmap` drops adaptive
  backgrounds for "transparent icons". `AppsRepo` rebuilds icons when icon settings change.
- Dock: empty slots are invisible on the home screen; dock apps are assigned in Settings → Home screen.
- Chromatic aberration (hold) ignores the effects master switch; only its own switch gates it.
- Home pressed while already home toggles the drawer (`HomeActivity.onNewIntent`, focus + BROUGHT_TO_FRONT test).
- Picked folders (`Config.safFolders`, tree URIs): `ImageRepo.scanTree` walks them with DocumentsContract (works with .nomedia and SD cards); bucket id = "saf:" + tree URI, image ids negative.
- Next on wake: `App` registers an ACTION_SCREEN_ON receiver → `Slideshow.next()` when enabled.
- `wallpaper/SlideWallpaperService`: live wallpaper engine, no touch.
- `settings/`: `SettingsActivity` (page stack), categories: Slideshow (+ FolderPage), Effects, Gestures,
  Home screen (dock apps), Icons, App drawer (+ Hidden apps), Live wallpaper & system.
