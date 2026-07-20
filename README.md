# Tune

A simple local music player for Android — Kotlin, Jetpack Compose, Media3.

## Features

- **Tabs**: Songs · Albums · Artists · Playlists · Folders
- **Folder selection** — pick which folders the library is built from (Settings ▸ folder icon in the toolbar). Unchecking a folder hides it and its subfolders.
- **Metadata** — full tag readout per song (title, artist, album, album artist, composer, genre, year, track, disc, duration, format, size, date added/modified, path).
- **Artwork** — embedded cover art shown in lists, grids, the mini player and the now-playing screen.
- **Custom album/artist images** — pick any image to replace the embedded art.
- **Playback** — background service with notification, lockscreen and Bluetooth controls; shuffle, repeat, queue, seek.
- **Playlists** — create, rename, delete, add/remove songs.

## Design notes

- **Audio files are never modified.** Custom images are copied into the app's own storage
  (`files/artwork/`) and referenced from `artwork-overrides.json`. Clearing an override restores
  the embedded artwork untouched. Playlists live in `files/playlists.json`.
- **Metadata comes from MediaStore** in a single cursor pass, which is fast even on large
  libraries. `MediaMetadataRetriever` is used only for artwork bytes, which MediaStore doesn't
  expose — decoded with subsampling so a grid of large covers doesn't churn memory.
- **Folder selection is stored as an exclusion list**, so newly added music folders are visible
  by default rather than silently hidden.

## Project layout

```
app/src/main/java/dev/tune/player/
  data/      Models, MediaStore scanning, preferences, artwork + playlist stores, repository
  player/    Media3 MediaSessionService and the MediaController wrapper
  art/       Coil fetcher resolving overrides → embedded art
  ui/        Compose screens: home tabs, detail screens, now playing, sheets, theme
```

## Building

Requires JDK 17+ and an Android SDK with platform 35.

```sh
./gradlew assembleDebug
```

`local.properties` points `sdk.dir` at the SDK in `../.toolchain/android-sdk`. Change it if yours
lives elsewhere, or delete it and let Android Studio fill it in.

## Version pinning

`media3` is pinned to **1.5.1**, not the latest 1.10.x: 1.10 requires `compileSdk 36`, which needs
AGP 8.9+. The current AGP/Kotlin/Compose set (8.7.3 / 2.1.0 / 1.7.5) is a known-good combination.
To move to newer media3, bump AGP and `compileSdk` together.
