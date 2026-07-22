# Tune

A local music player for Android — Kotlin, Jetpack Compose, Media3. Reads what's already on the
device, plays it, and stays out of the way.

## Features

- **Tabs**: Songs · Albums · Artists · Genres · Favourites · Most played · Playlists · Folders —
  reorderable, and any of them can be hidden
- **Grid or list** layout, toggled from the toolbar, on every tab
- **Sorting** on every tab and inside every album, artist, playlist, genre and folder, with an
  explicit ascending/descending toggle. Orders include title, artist, album, release date,
  artist-then-release-date, duration, date added, play count and last played
- **Folder selection** — allow-list or deny-list, always covering subfolders
- **Metadata** — full tag readout per song (title, artist, album, album artist, composer, genre,
  year, track, disc, duration, format, size, dates, path)
- **Artwork** — embedded cover art everywhere, with custom images assignable per album and artist
- **Custom genres** — assign to one song or a whole selection
- **ReplayGain** — MP3, FLAC, Ogg Vorbis, Opus, M4A and ALAC
- **Duplicate detection**
- **Playback** — background service with notification, lockscreen and Bluetooth controls; shuffle,
  repeat, queue reordering, seek, speed control, resume across restarts (queue, position, shuffle
  and repeat), pause on headphone disconnect, system equalizer handoff
- **Playlists** — create, rename, delete, add and remove songs
- **Portable library data** — JSON backup/restore and M3U8 playlist import/export
- **Safe file deletion** — delete one song or a selection through Android's confirmation flow
- **Sleep timer** — pause automatically after a chosen interval
- **Appearance** — Material You, eight accent colours, AMOLED black, square or original-ratio
  covers

## The one rule

**Tune never modifies the contents or metadata of your audio files.**

Custom artwork and custom genres are stored in the app's own data directory and layered over each
file's real tags when the library is built. Files are removed only when the user explicitly chooses
Delete and confirms the permanent action. This is a deliberate trade:

- No bug in this app can corrupt a music file.
- Deletion is isolated behind both Tune's warning and Android's MediaStore approval where required.
- Those customisations are **not portable**. They live in Tune's data, so uninstalling or clearing
  its storage loses them, and they won't follow the files to another player or device.

If you want changes baked into the files, use a real tag editor — this app is not one.

## Building

Requires **JDK 17** and an Android SDK with **platform 35**.

```sh
export JAVA_HOME=/opt/android-studio/jbr   # the bundled JBR works fine

./gradlew :app:assembleDebug         # debug APK; applicationId gets a .debug suffix
./gradlew :app:assembleRelease       # release APK, minified and resource-shrunk
./gradlew :app:testReleaseUnitTest   # JVM unit tests
```

Output lands in `app/build/outputs/apk/`.

`local.properties` points `sdk.dir` at the SDK in `../.toolchain/android-sdk`. Change it if yours
lives elsewhere, or delete it and let Android Studio fill it in.

### Signing

Release builds are signed only if `keystore.properties` exists at the repo root:

```properties
storeFile=../tune-release.jks
storePassword=...
keyAlias=tune
keyPassword=...
```

Without it the release build still succeeds and produces an **unsigned** APK, so cloning and
building never breaks for anyone else. `keystore.properties` and `*.jks` are gitignored — keep them
out of the repo.

### Version pinning

These versions are pinned deliberately, not just "whatever was current":

| Component  | Version | Why |
|------------|---------|-----|
| media3     | 1.5.1   | 1.10.x requires `compileSdk 36`, which needs AGP 8.9+ |
| AGP        | 8.7.3   | Known-good with the media3 and Kotlin pins |
| Kotlin     | 2.1.0   | Paired with the Compose compiler plugin |
| compileSdk | 35      | Bounded by the AGP version |

Moving to newer media3 means bumping AGP and `compileSdk` together. They are not independent.

## Releasing

```sh
# 1. Bump versionCode and versionName in app/build.gradle.kts
# 2. Verify
./gradlew :app:testReleaseUnitTest :app:assembleRelease
# 3. Tag and publish, uploading the APK in the same call
gh release create vX.Y app/build/outputs/apk/release/app-release.apk \
  --title "Tune vX.Y" --notes "..."
```

**Obtainium needs a non-draft release with an APK asset attached.** Creating the release and
uploading the asset as separate steps has failed before — a release with `"assets": []` is
invisible to Obtainium. Verify after publishing:

```sh
gh release view vX.Y --json isDraft,assets
```

## Project layout

```
app/src/main/java/dev/tune/player/
  data/      Models, MediaStore scanning, preferences, and every persistent store
  player/    Media3 MediaSessionService and the MediaController wrapper
  art/       Coil fetcher resolving overrides → embedded art
  ui/        Compose screens: home tabs, detail screens, now playing, sheets, theme
```

`MusicRepository` is the single source of truth. Raw scan results are held separately from the
filtered `library`, so toggling a folder re-groups in memory instead of re-reading MediaStore.
Everything downstream is a `StateFlow` derived from it.

Persistent state is split by concern: `UserPreferences` (DataStore), plus `PlaylistStore`,
`ArtworkStore`, `GenreStore` and `PlayStatsStore` (each its own JSON file in `filesDir`).

## Design notes

- **Metadata comes from MediaStore** in a single cursor pass, fast even on large libraries.
  `MediaMetadataRetriever` is used only for artwork bytes, which MediaStore doesn't expose —
  decoded with subsampling so a grid of large covers doesn't churn memory.
- **Folder selection defaults to an exclusion list**, so newly added music is visible by default
  rather than silently hidden.
- **Play counts are recorded after 30 seconds** (or half the track, whichever is shorter), not on
  track change — otherwise skipping through a queue inflates every count on the way past.

## Things that have bitten us

Worth reading before changing anything:

- **`StateFlow.value` does not subscribe.** Calling a ViewModel function that reads `.value` from a
  composable compiles fine and then updates late or never — the screen only refreshes when
  something unrelated recomposes. This has caused two shipped bugs (the mini player not appearing;
  artwork grids not refreshing). Collect the flow in the composable instead; `sortedForDetail` in
  `ui/detail/DetailScreens.kt` is the pattern to copy.
- **Sort comparators are all ascending**, with direction applied on top via `descending`. That is
  what lets the menu's ▲/▼ mean something. Don't bake a direction into a comparator.
- **Artists group by album artist**, not track artist — grouping by track artist turned a 2-artist
  library into 43 entries. Identity is a hash of the normalised name, since MediaStore has no
  `ALBUM_ARTIST_ID`, and it must stay stable because artwork overrides key off it.
- **MediaStore's `YEAR` is only a year.** A library recorded inside one year therefore ties on
  every date comparison and the sort falls through to its tiebreaker, looking broken. Full dates
  are read from the tags instead (`ReleaseDateStore`), packed as `yyyymmdd` and cached by mtime.
  Sort on `Song.releaseDateKey`, never on `year`.
- **`Song.uri` is computed from `id`, not stored**, which keeps `Song` constructible without
  Android present. That is what makes the sorting and duplicate logic unit-testable.
- **R8 needs explicit keep rules for serializers** (`proguard-rules.pro`). A missing rule shows up
  only in a release build, as playlists or stats silently failing to load.
- **Pipelines mask Gradle failures.** `./gradlew ... | grep ...` returns grep's exit code, not
  Gradle's. Check `${PIPESTATUS[0]}` or the `BUILD SUCCESSFUL` line — a failed build has been
  reported as passing here before.

## Testing

```sh
./gradlew :app:testReleaseUnitTest
```

Covers ReplayGain and release-date parsing (including M4A atoms), stable song identities,
duplicate detection, and every sort comparator.

The fixtures in `app/src/test/resources/gain` are **real files generated with ffmpeg**, not
hand-assembled byte arrays — the failure mode being guarded against is a byte offset that is wrong
against what encoders actually emit. Regenerate with:

```sh
ffmpeg -f lavfi -i "sine=frequency=440:duration=1" -c:a libopus \
  -metadata R128_TRACK_GAIN=-1280 -metadata R128_ALBUM_GAIN=-512 track.opus
```

**The UI has no automated tests.** Everything under `ui/` is verified by compilation only. If you
change a screen, run it on a device — several bugs shipped here would have been caught in the first
minute of actually using the app.

## Known limitations

- **No ID3v2.2** — three-character frame ids, effectively extinct.
- **No APEv2 tag reading** — files using only APEv2 metadata get no ReplayGain or full date.
- **ReplayGain only attenuates** unless a peak tag is present, since boosting past `1/peak` would
  clip and there is no limiter in the chain.
- **Skip-silence is not implemented.** ExoPlayer handles true gapless natively, but
  `setSkipSilenceEnabled` lives on `ExoPlayer` and can't be reached through `MediaController`
  without a custom session command.
- **No file management.** Tune won't rename, move or delete anything; duplicate detection reports
  only.
- **Custom genres and artwork don't survive a reinstall** — see [The one rule](#the-one-rule).
