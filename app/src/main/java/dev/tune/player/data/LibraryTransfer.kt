package dev.tune.player.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TuneBackup(
    val formatVersion: Int = CURRENT_BACKUP_VERSION,
    val playlists: List<Playlist> = emptyList(),
    val favouriteSongKeys: Set<String> = emptySet(),
    val genresBySongKey: Map<String, String> = emptyMap(),
    val playStatsBySongKey: Map<String, PlayStat> = emptyMap(),
) {
    companion object {
        const val CURRENT_BACKUP_VERSION = 1
    }
}

/** Portable JSON backup and conventional extended-M3U playlist transfer. */
object LibraryTransfer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun writeBackup(context: Context, destination: Uri, backup: TuneBackup) {
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: error("Could not open the backup destination")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(json.encodeToString(backup)) }
    }

    fun readBackup(context: Context, source: Uri): TuneBackup {
        val input = context.contentResolver.openInputStream(source)
            ?: error("Could not open the selected backup")
        val backup = input.bufferedReader(Charsets.UTF_8).use { json.decodeFromString<TuneBackup>(it.readText()) }
        require(backup.formatVersion in 1..TuneBackup.CURRENT_BACKUP_VERSION) {
            "Unsupported Tune backup version ${backup.formatVersion}"
        }
        return backup
    }

    fun writeM3u(context: Context, destination: Uri, songs: List<Song>) {
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: error("Could not open the playlist destination")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("#EXTM3U")
            for (song in songs) {
                writer.appendLine("#EXTINF:${song.durationMs / 1_000L},${song.artist} - ${song.title}")
                writer.appendLine(song.path)
            }
        }
    }

    fun readM3u(context: Context, source: Uri, library: List<Song>): Pair<String, List<Song>> {
        val input = context.contentResolver.openInputStream(source)
            ?: error("Could not open the selected playlist")
        val paths = input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .map { Uri.decode(it.removePrefix("file://")) }
                .toList()
        }
        val exact = library.associateBy { normalisePath(it.path) }
        val byName = library.groupBy { File(it.path).name.lowercase() }
        val songs = paths.mapNotNull { path ->
            exact[normalisePath(path)] ?: byName[File(path).name.lowercase()]?.singleOrNull()
        }
        require(songs.isNotEmpty()) { "No playlist entries matched this music library" }
        return displayName(context, source).substringBeforeLast('.').ifBlank { "Imported playlist" } to songs
    }

    private fun normalisePath(path: String): String = path.replace('\\', '/').trim().lowercase()

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0).orEmpty()
            }
        return uri.lastPathSegment.orEmpty()
    }
}
