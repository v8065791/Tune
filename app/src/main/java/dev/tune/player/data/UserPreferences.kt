package dev.tune.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tune_settings")

/** Which tabs the home screen shows, in order. */
enum class HomeTab(val label: String) {
    SONGS("Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    GENRES("Genres"),
    FAVOURITES("Favourites"),
    MOST_PLAYED("Most played"),
    PLAYLISTS("Playlists"),
    FOLDERS("Folders"),
}

/**
 * Playback state saved between app launches. [repeatMode] holds a media3 `Player.REPEAT_MODE_*`
 * constant as a plain Int, so this stays free of any media3 dependency and JVM-testable.
 */
data class ResumeState(
    val queue: List<Long>,
    val queueKeys: List<String> = emptyList(),
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

/** How the chosen folder list is applied to the library. */
enum class FolderMode(val label: String) {
    /** Everything is scanned except the chosen folders and their subfolders. */
    EXCLUDE("Exclude selected"),

    /** Only the chosen folders and their subfolders are scanned. */
    INCLUDE("Include only selected"),
}

/** Accent colours, used when Material You is off or unavailable. */
enum class AccentColour(val label: String, val rgb: Long) {
    PURPLE("Purple", 0xFF7C5CFF),
    BLUE("Blue", 0xFF2E7BE0),
    TEAL("Teal", 0xFF00897B),
    GREEN("Green", 0xFF2E7D32),
    YELLOW("Amber", 0xFFF9A825),
    ORANGE("Orange", 0xFFEF6C00),
    RED("Red", 0xFFD32F2F),
    PINK("Pink", 0xFFC2185B),
}

/** App theme, independent of the "black" (AMOLED) toggle. */
enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** Where cover art is sourced from. */
enum class CoverMode(val label: String) {
    EMBEDDED("Embedded in file"),
    MEDIA_STORE("System media store"),
    OFF("Disabled"),
}

/** What tapping a song in a list queues up. */
enum class PlayInMode(val label: String) {
    /** Only the list that was tapped — the album, the playlist, the folder. */
    FROM_LIST("The list it's in"),

    /** The entire library, regardless of where the tap happened. */
    FROM_LIBRARY("The whole library"),

    /** Just that one song. */
    SINGLE("Only that song"),
}

/**
 * Every user-facing setting, backed by DataStore.
 *
 * Folder selection is stored as an *exclusion* list: a newly discovered folder is included by
 * default, so plugging in new music never silently hides it.
 */
class UserPreferences(private val context: Context) {

    private object Keys {
        val excludedFolders = stringSetPreferencesKey("excluded_folders")
        val folderMode = stringPreferencesKey("folder_mode")
        val songSort = stringPreferencesKey("song_sort")
        val groupSort = stringPreferencesKey("group_sort")
        val detailSort = stringPreferencesKey("detail_sort")
        val detailSortDescending = booleanPreferencesKey("detail_sort_descending")
        val sortDescending = booleanPreferencesKey("sort_descending")
        val groupSortDescending = booleanPreferencesKey("group_sort_descending")
        val replayGain = stringPreferencesKey("replay_gain")

        // Library
        val observing = booleanPreferencesKey("observe_media_store")
        val autoSortNames = booleanPreferencesKey("auto_sort_names")
        val hideCollaborators = booleanPreferencesKey("hide_collaborators")
        val separators = stringPreferencesKey("tag_separators")

        // Playback
        val playInListWith = stringPreferencesKey("play_in_list_with")
        val rewindOnPrevious = booleanPreferencesKey("rewind_on_previous")
        val rememberPlayback = booleanPreferencesKey("remember_playback")
        val keepShuffle = booleanPreferencesKey("keep_shuffle")

        // Appearance
        val themeMode = stringPreferencesKey("theme_mode")
        val blackTheme = booleanPreferencesKey("black_theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val coverMode = stringPreferencesKey("cover_mode")
        val squareCovers = booleanPreferencesKey("square_covers")
        val roundedCorners = booleanPreferencesKey("rounded_corners")
        val homeTabs = stringPreferencesKey("home_tabs")
        val accent = stringPreferencesKey("accent_colour")
        val gridView = booleanPreferencesKey("grid_view")

        // Favourites and resume state
        val favourites = stringSetPreferencesKey("favourite_songs")
        val favouriteKeys = stringSetPreferencesKey("favourite_song_keys")
        val resumeQueue = stringPreferencesKey("resume_queue")
        val resumeQueueKeys = stringPreferencesKey("resume_queue_keys")
        val resumeIndex = stringPreferencesKey("resume_index")
        val resumePosition = stringPreferencesKey("resume_position")
        val resumeShuffle = booleanPreferencesKey("resume_shuffle")
        val resumeRepeat = intPreferencesKey("resume_repeat")
    }

    // ---- Favourites --------------------------------------------------------

    val favourites: Flow<Set<Long>> = flow { prefs ->
        prefs[Keys.favourites].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun setFavourite(song: Song, favourite: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.favourites].orEmpty()
            val keys = prefs[Keys.favouriteKeys].orEmpty()
            val id = song.id.toString()
            prefs[Keys.favourites] = if (favourite) current + id else current - id
            prefs[Keys.favouriteKeys] =
                if (favourite) keys + song.stableKey else keys - song.stableKey
        }
    }

    // ---- Resume state ------------------------------------------------------

    /** The queue as it was when playback last stopped, for [rememberPlayback]. */
    val resumeState: Flow<ResumeState?> = flow { prefs ->
        val ids = prefs[Keys.resumeQueue]
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return@flow null
        ResumeState(
            queue = ids,
            queueKeys = prefs[Keys.resumeQueueKeys]
                ?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            index = prefs[Keys.resumeIndex]?.toIntOrNull() ?: 0,
            positionMs = prefs[Keys.resumePosition]?.toLongOrNull() ?: 0L,
            shuffle = prefs[Keys.resumeShuffle] ?: false,
            repeatMode = prefs[Keys.resumeRepeat] ?: 0,
        )
    }

    suspend fun saveResumeState(
        queue: List<Long>,
        queueKeys: List<String>,
        index: Int,
        positionMs: Long,
        shuffle: Boolean,
        repeatMode: Int,
    ) = edit {
        it[Keys.resumeQueue] = queue.joinToString(",")
        it[Keys.resumeQueueKeys] = queueKeys.joinToString(",")
        it[Keys.resumeIndex] = index.toString()
        it[Keys.resumePosition] = positionMs.toString()
        it[Keys.resumeShuffle] = shuffle
        it[Keys.resumeRepeat] = repeatMode
    }

    /** Upgrades MediaStore-id state and resolves portable keys after every completed scan. */
    suspend fun reconcileSongReferences(songs: List<Song>) {
        val byId = songs.associateBy { it.id }
        val byKey = songs.associateBy { it.stableKey }
        context.dataStore.edit { prefs ->
            val storedKeys = prefs[Keys.favouriteKeys].orEmpty()
            val favourites = if (storedKeys.isNotEmpty()) {
                storedKeys.mapNotNull(byKey::get)
            } else {
                prefs[Keys.favourites].orEmpty()
                    .mapNotNull { it.toLongOrNull()?.let(byId::get) }
            }
            prefs[Keys.favourites] = favourites.mapTo(mutableSetOf()) { it.id.toString() }
            prefs[Keys.favouriteKeys] = favourites.mapTo(mutableSetOf()) { it.stableKey }

            val storedQueueKeys = prefs[Keys.resumeQueueKeys]
                ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            val queue = if (storedQueueKeys.isNotEmpty()) {
                storedQueueKeys.mapNotNull(byKey::get)
            } else {
                prefs[Keys.resumeQueue]?.split(',')
                    ?.mapNotNull { it.toLongOrNull()?.let(byId::get) }.orEmpty()
            }
            prefs[Keys.resumeQueue] = queue.joinToString(",") { it.id.toString() }
            prefs[Keys.resumeQueueKeys] = queue.joinToString(",") { it.stableKey }
        }
    }

    suspend fun replaceFavourites(keys: Set<String>, songs: List<Song>) {
        val byKey = songs.associateBy { it.stableKey }
        val resolved = keys.mapNotNull(byKey::get)
        context.dataStore.edit { prefs ->
            prefs[Keys.favourites] = resolved.mapTo(mutableSetOf()) { it.id.toString() }
            prefs[Keys.favouriteKeys] = keys
        }
    }

    // ---- Folders & sorting -------------------------------------------------

    val excludedFolders: Flow<Set<String>> = flow { it[Keys.excludedFolders].orEmpty() }

    val songSort: Flow<SortOrder> = flow { prefs ->
        prefs[Keys.songSort]?.let { name -> SortOrder.entries.firstOrNull { it.name == name } }
            ?: SortOrder.TITLE
    }

    suspend fun setFolderExcluded(path: String, excluded: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.excludedFolders].orEmpty()
            prefs[Keys.excludedFolders] = if (excluded) current + path else current - path
        }
    }

    suspend fun setExcludedFolders(paths: Set<String>) =
        edit { it[Keys.excludedFolders] = paths }

    suspend fun setSongSort(order: SortOrder) = edit { it[Keys.songSort] = order.name }

    /** Order for the album, artist, genre, playlist and folder tabs. */
    val groupSort: Flow<GroupSortOrder> = flow { prefs ->
        prefs[Keys.groupSort]?.let { n -> GroupSortOrder.entries.firstOrNull { it.name == n } }
            ?: GroupSortOrder.NAME
    }

    suspend fun setGroupSort(order: GroupSortOrder) = edit { it[Keys.groupSort] = order.name }

    /**
     * Order for the track list inside an album, artist, playlist, genre or folder.
     *
     * `null` means "leave the list as it came", which is the only sensible default: an album is
     * already in disc and track order, and a playlist is in whatever order the user arranged.
     * Neither has an entry in [SortOrder] that could reproduce it.
     */
    val detailSort: Flow<SortOrder?> = flow { prefs ->
        prefs[Keys.detailSort]
            ?.takeIf { it.isNotEmpty() }
            ?.let { name -> SortOrder.entries.firstOrNull { it.name == name } }
    }

    val detailSortDescending: Flow<Boolean> = flow { it[Keys.detailSortDescending] ?: false }

    suspend fun setDetailSort(order: SortOrder?) =
        edit { it[Keys.detailSort] = order?.name ?: "" }

    suspend fun setDetailSortDescending(value: Boolean) =
        edit { it[Keys.detailSortDescending] = value }

    /** Sort direction for song lists and for group tabs, tracked separately. */
    val sortDescending: Flow<Boolean> = flow { it[Keys.sortDescending] ?: false }

    val groupSortDescending: Flow<Boolean> = flow { it[Keys.groupSortDescending] ?: false }

    suspend fun setSortDescending(value: Boolean) = edit { it[Keys.sortDescending] = value }

    suspend fun setGroupSortDescending(value: Boolean) =
        edit { it[Keys.groupSortDescending] = value }

    /**
     * Whether the stored folder list is a deny-list or an allow-list. Defaults to excluding, so a
     * fresh install scans everything.
     */
    val folderMode: Flow<FolderMode> = flow { prefs ->
        prefs[Keys.folderMode]?.let { n -> FolderMode.entries.firstOrNull { it.name == n } }
            ?: FolderMode.EXCLUDE
    }

    suspend fun setFolderMode(mode: FolderMode) = edit { it[Keys.folderMode] = mode.name }

    // ---- Library -----------------------------------------------------------

    /** Watch MediaStore and rescan automatically when it changes. */
    val observing: Flow<Boolean> = flow { it[Keys.observing] ?: true }

    /** Sort "The Beatles" under B by ignoring a leading article. */
    val autoSortNames: Flow<Boolean> = flow { it[Keys.autoSortNames] ?: true }

    /** Split an artist's own albums from ones they merely appear on. */
    val hideCollaborators: Flow<Boolean> = flow { it[Keys.hideCollaborators] ?: false }

    /**
     * Characters that split a multi-value tag, e.g. ";" turns "A; B" into two artists. Empty
     * disables splitting, which is the safe default — plenty of artists have a "/" in their name.
     */
    val separators: Flow<String> = flow { it[Keys.separators] ?: "" }

    suspend fun setObserving(value: Boolean) = edit { it[Keys.observing] = value }

    suspend fun setAutoSortNames(value: Boolean) = edit { it[Keys.autoSortNames] = value }

    suspend fun setHideCollaborators(value: Boolean) = edit { it[Keys.hideCollaborators] = value }

    suspend fun setSeparators(value: String) = edit { it[Keys.separators] = value }

    // ---- Playback ----------------------------------------------------------

    val playInListWith: Flow<PlayInMode> = flow { prefs ->
        prefs[Keys.playInListWith]?.let { n -> PlayInMode.entries.firstOrNull { it.name == n } }
            ?: PlayInMode.FROM_LIST
    }

    /** Previous restarts the track instead of skipping back, when past the threshold. */
    val rewindOnPrevious: Flow<Boolean> = flow { it[Keys.rewindOnPrevious] ?: true }

    val rememberPlayback: Flow<Boolean> = flow { it[Keys.rememberPlayback] ?: true }

    val keepShuffle: Flow<Boolean> = flow { it[Keys.keepShuffle] ?: true }

    /** Which ReplayGain tag, if any, adjusts playback volume. */
    val replayGain: Flow<ReplayGainMode> = flow { prefs ->
        prefs[Keys.replayGain]?.let { n -> ReplayGainMode.entries.firstOrNull { it.name == n } }
            ?: ReplayGainMode.OFF
    }

    suspend fun setReplayGain(mode: ReplayGainMode) = edit { it[Keys.replayGain] = mode.name }

    suspend fun setPlayInListWith(mode: PlayInMode) = edit { it[Keys.playInListWith] = mode.name }

    suspend fun setRewindOnPrevious(value: Boolean) = edit { it[Keys.rewindOnPrevious] = value }

    suspend fun setRememberPlayback(value: Boolean) = edit { it[Keys.rememberPlayback] = value }

    suspend fun setKeepShuffle(value: Boolean) = edit { it[Keys.keepShuffle] = value }

    // ---- Appearance --------------------------------------------------------

    val themeMode: Flow<ThemeMode> = flow { prefs ->
        prefs[Keys.themeMode]?.let { n -> ThemeMode.entries.firstOrNull { it.name == n } }
            ?: ThemeMode.SYSTEM
    }

    /** True black backgrounds in dark mode, for OLED panels. */
    val blackTheme: Flow<Boolean> = flow { it[Keys.blackTheme] ?: false }

    val dynamicColor: Flow<Boolean> = flow { it[Keys.dynamicColor] ?: true }

    val coverMode: Flow<CoverMode> = flow { prefs ->
        prefs[Keys.coverMode]?.let { n -> CoverMode.entries.firstOrNull { it.name == n } }
            ?: CoverMode.EMBEDDED
    }

    val squareCovers: Flow<Boolean> = flow { it[Keys.squareCovers] ?: true }

    val roundedCorners: Flow<Boolean> = flow { it[Keys.roundedCorners] ?: true }

    /** Falls back to the full tab set if the stored value references tabs that no longer exist. */
    val homeTabs: Flow<List<HomeTab>> = flow { prefs ->
        val stored = prefs[Keys.homeTabs]
            ?.split(',')
            ?.mapNotNull { name -> HomeTab.entries.firstOrNull { it.name == name } }
        stored?.takeIf { it.isNotEmpty() } ?: HomeTab.entries.toList()
    }

    val accent: Flow<AccentColour> = flow { prefs ->
        prefs[Keys.accent]?.let { n -> AccentColour.entries.firstOrNull { it.name == n } }
            ?: AccentColour.PURPLE
    }

    /** Albums and artists as a grid, or as a denser list. */
    val gridView: Flow<Boolean> = flow { it[Keys.gridView] ?: true }

    suspend fun setAccent(colour: AccentColour) = edit { it[Keys.accent] = colour.name }

    suspend fun setGridView(value: Boolean) = edit { it[Keys.gridView] = value }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.themeMode] = mode.name }

    suspend fun setBlackTheme(value: Boolean) = edit { it[Keys.blackTheme] = value }

    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.dynamicColor] = value }

    suspend fun setCoverMode(mode: CoverMode) = edit { it[Keys.coverMode] = mode.name }

    suspend fun setSquareCovers(value: Boolean) = edit { it[Keys.squareCovers] = value }

    suspend fun setRoundedCorners(value: Boolean) = edit { it[Keys.roundedCorners] = value }

    suspend fun setHomeTabs(tabs: List<HomeTab>) =
        edit { it[Keys.homeTabs] = tabs.joinToString(",") { tab -> tab.name } }

    // ---- Plumbing ----------------------------------------------------------

    private fun <T> flow(transform: (Preferences) -> T): Flow<T> =
        context.dataStore.data.map(transform)

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
