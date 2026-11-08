# WallMagik

An Android launcher built around a live wallpaper slideshow. It draws its own wallpaper instead of relying on a
separate live wallpaper service, so devices that kill wallpaper apps (looking at you, FiiO JM21) can't blank it.
The same renderer is also registered as a system live wallpaper for the lock screen.

## Features
- **Slideshow:** random order from chosen folders (shuffle bag), intervals from 0.05 s to a day, aligned to the
  clock for intervals of 1 s or more, crossfades, optional new image on every wake.
- **Folders:** pick from your gallery's folders, or add any folder with the system picker (works with `.nomedia`
  folders and SD cards); include or exclude single images.
- **Effects (OpenGL ES):** blur, duotone with your own dark/light colors, and chromatic aberration while you press
  and hold the home screen.
- **Gestures:** single finger double/triple tap and two finger single/double tap, each mapped to next wallpaper,
  toggle blur, toggle duotone, toggle effects and more. Home on the home screen toggles the app drawer.
- **Launcher:** dock with a configurable number of slots, minimal text-only app drawer with search and hidden apps,
  icon packs (Nova/ADW format) and transparent icons.

## Building
Kotlin + Jetpack Compose, no Google libraries at runtime, minSdk 30.

```bash
./gradlew assembleRelease
```

Use the release build: debug builds run Compose in the interpreter and feel very laggy.
The release build is signed with the debug key so it installs over a debug build.
