package dev.tune.player.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import java.io.File
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.tune.player.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A snapshot of playback, kept flat so Compose can read it without touching the player. */
data class PlayerState(
    val currentSongId: Long? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
    /** Song ids in queue order, so the queue screen can resolve them against the library. */
    val queueIds: List<Long> = emptyList(),
    val speed: Float = 1f,
)

/**
 * Wraps the [MediaController] connection to [PlaybackService] and mirrors playback into a
 * [StateFlow]. Every call is a no-op until the connection lands, so the UI can bind immediately.
 */
class PlayerController(private val context: Context, private val scope: CoroutineScope) {

    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncState()
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also {
                it.addListener(listener)
                syncState()
            }
        }, ContextCompat.getMainExecutor(context))

        // The player reports position only on demand, so poll while something is playing.
        scope.launch {
            while (true) {
                if (_state.value.isPlaying) syncState()
                delay(POSITION_POLL_MS)
            }
        }
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    /** Replaces the queue with [songs] and starts at [startIndex]. */
    fun play(songs: List<Song>, startIndex: Int = 0) {
        val player = controller ?: return
        if (songs.isEmpty()) return
        player.setMediaItems(songs.map(::toMediaItem), startIndex.coerceIn(songs.indices), 0L)
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() = controller?.seekToNextMediaItem() ?: Unit

    /** When true, Previous restarts the track past the threshold instead of always skipping. */
    var rewindOnPrevious: Boolean = true

    fun previous() {
        val player = controller ?: return
        if (rewindOnPrevious && player.currentPosition > RESTART_THRESHOLD_MS) player.seekTo(0)
        else player.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs) ?: Unit

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun cycleRepeatMode() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun addToQueue(songs: List<Song>) {
        controller?.addMediaItems(songs.map(::toMediaItem))
    }

    private fun syncState() {
        val player = controller ?: return
        _state.value = PlayerState(
            currentSongId = player.currentMediaItem?.mediaId?.toLongOrNull(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queueSize = player.mediaItemCount,
            queueIndex = player.currentMediaItemIndex,
            queueIds = (0 until player.mediaItemCount).mapNotNull {
                player.getMediaItemAt(it).mediaId.toLongOrNull()
            },
            speed = player.playbackParameters.speed,
        )
    }

    /** Moves a queue entry, e.g. dragging a track earlier. */
    fun moveQueueItem(from: Int, to: Int) {
        val player = controller ?: return
        if (from !in 0 until player.mediaItemCount) return
        if (to !in 0 until player.mediaItemCount) return
        player.moveMediaItem(from, to)
        syncState()
    }

    fun removeQueueItem(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.removeMediaItem(index)
        syncState()
    }

    /**
     * Scales output volume, used to apply ReplayGain. This is the player's own volume, separate
     * from the device volume the user sets with the hardware keys.
     */
    fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    /** Playback rate, 1.0 being normal. Pitch is left untouched. */
    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed.coerceIn(MIN_SPEED, MAX_SPEED))
        syncState()
    }

    /**
     * Restores a saved queue without starting playback — the user should come back to a paused
     * player, not have music start on its own.
     */
    fun restore(
        songs: List<Song>,
        index: Int,
        positionMs: Long,
        shuffle: Boolean = false,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
    ) {
        val player = controller ?: return
        if (songs.isEmpty() || player.mediaItemCount > 0) return
        player.shuffleModeEnabled = shuffle
        player.repeatMode = repeatMode
        player.setMediaItems(songs.map(::toMediaItem), index.coerceIn(songs.indices), positionMs)
        player.prepare()
        syncState()
    }

    /** Jumps straight to a queue entry without rebuilding the queue. */
    fun playQueueItem(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, 0L)
        player.play()
    }

    private fun toMediaItem(song: Song): MediaItem =
        MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(song.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setAlbumArtist(song.albumArtist ?: song.artist)
                    .setTrackNumber(song.track.takeIf { it > 0 })
                    .setRecordingYear(song.year.takeIf { it > 0 })
                    // Points at the audio file itself, not an image: EmbeddedArtworkBitmapLoader
                    // extracts the embedded picture from it. The MediaStore albumart URI that
                    // used to be here is a legacy path that no longer resolves, which is why the
                    // notification showed no cover at all.
                    .setArtworkUri(Uri.fromFile(File(song.path)))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    private companion object {
        const val POSITION_POLL_MS = 500L
        const val RESTART_THRESHOLD_MS = 3_000L
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 3.0f
    }
}
