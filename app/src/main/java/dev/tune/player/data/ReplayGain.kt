package dev.tune.player.data

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.pow

/** Which ReplayGain tag, if any, is applied to playback volume. */
enum class ReplayGainMode(val label: String) {
    OFF("Off"),
    TRACK("Track gain"),
    ALBUM("Album gain, then track"),
}

/** Gain values in dB as tagged in a file. Either may be absent. */
data class ReplayGainInfo(val trackGainDb: Float? = null, val albumGainDb: Float? = null) {
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null
}

/**
 * Reads ReplayGain tags straight out of a file's header.
 *
 * This is deliberately dependency-free: the two containers that carry these tags in practice are
 * MP3 (ID3v2 TXXX frames) and FLAC (Vorbis comments), and both are cheap to walk. Only the tag
 * block at the start of the file is read, never the audio.
 *
 * Not handled: ID3v2.2 (three-character frame ids, effectively extinct), Ogg/Opus page framing,
 * and APEv2 trailers. Those return no gain rather than a wrong one.
 */
object ReplayGainReader {

    fun read(path: String): ReplayGainInfo = runCatching {
        val file = File(path)
        if (!file.isFile) return@runCatching ReplayGainInfo()
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < MIN_HEADER_BYTES) return@use ReplayGainInfo()
            val magic = ByteArray(4)
            raf.readFully(magic)
            when {
                magic[0] == 'I'.code.toByte() &&
                    magic[1] == 'D'.code.toByte() &&
                    magic[2] == '3'.code.toByte() -> readId3(raf)

                String(magic, Charsets.US_ASCII) == "fLaC" -> readFlac(raf)
                else -> ReplayGainInfo()
            }
        }
    }.getOrElse { ReplayGainInfo() }

    // ---- ID3v2 -------------------------------------------------------------

    private fun readId3(raf: RandomAccessFile): ReplayGainInfo {
        raf.seek(3)
        val major = raf.readUnsignedByte()
        raf.readUnsignedByte() // revision
        val headerFlags = raf.readUnsignedByte()
        val tagSize = raf.readSynchsafe()
        // v2.2 packs frames differently and is vanishingly rare; treat it as untagged.
        if (major < 3 || tagSize <= 0) return ReplayGainInfo()

        var pos = ID3_HEADER_BYTES.toLong()
        if (headerFlags and EXTENDED_HEADER_FLAG != 0) {
            raf.seek(pos)
            // v2.4 sizes the extended header synchsafely and counts itself; v2.3 does neither.
            pos += if (major >= 4) raf.readSynchsafe().toLong()
            else raf.readInt().toLong() + 4
        }

        val end = (ID3_HEADER_BYTES + tagSize).toLong().coerceAtMost(raf.length())
        var track: Float? = null
        var album: Float? = null

        while (pos + FRAME_HEADER_BYTES <= end) {
            raf.seek(pos)
            val id = ByteArray(4)
            raf.readFully(id)
            // Padding after the last frame is zero-filled.
            if (id[0] == 0.toByte()) break
            val size = if (major >= 4) raf.readSynchsafe() else raf.readInt()
            raf.readUnsignedShort() // frame flags
            if (size <= 0 || pos + FRAME_HEADER_BYTES + size > end) break

            if (String(id, Charsets.US_ASCII) == "TXXX" && size <= MAX_FRAME_BYTES) {
                val body = ByteArray(size)
                raf.readFully(body)
                val (key, value) = decodeTxxx(body) ?: (null to null)
                if (key != null && value != null) {
                    when (key.uppercase()) {
                        TRACK_GAIN_KEY -> track = parseGainDb(value)
                        ALBUM_GAIN_KEY -> album = parseGainDb(value)
                    }
                }
            }
            pos += FRAME_HEADER_BYTES + size
        }
        return ReplayGainInfo(track, album)
    }

    /**
     * A TXXX frame is an encoding byte, then a NUL-separated description and value. Decoding the
     * whole body at once and splitting on the NUL character handles all four encodings uniformly,
     * including the two-byte terminator UTF-16 uses.
     */
    private fun decodeTxxx(body: ByteArray): Pair<String, String>? {
        if (body.size < 2) return null
        val charset = when (body[0].toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text = String(body, 1, body.size - 1, charset)
        val parts = text.split(NUL)
        if (parts.size < 2) return null
        // A second UTF-16 string carries its own BOM, which survives decoding as a stray char.
        return parts[0].trim(BOM, ' ') to parts[1].trim(BOM, ' ')
    }

    /** ID3 sizes are 28 bits spread over 4 bytes, with the top bit of each left clear. */
    private fun RandomAccessFile.readSynchsafe(): Int {
        var value = 0
        repeat(4) { value = (value shl 7) or (readUnsignedByte() and 0x7F) }
        return value
    }

    // ---- FLAC --------------------------------------------------------------

    private fun readFlac(raf: RandomAccessFile): ReplayGainInfo {
        raf.seek(4)
        while (raf.filePointer + FLAC_BLOCK_HEADER_BYTES <= raf.length()) {
            val header = raf.readUnsignedByte()
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val length = (raf.readUnsignedByte() shl 16) or
                (raf.readUnsignedByte() shl 8) or
                raf.readUnsignedByte()
            if (length < 0 || raf.filePointer + length > raf.length()) return ReplayGainInfo()

            if (type == VORBIS_COMMENT_BLOCK) {
                if (length > MAX_FRAME_BYTES) return ReplayGainInfo()
                val body = ByteArray(length)
                raf.readFully(body)
                return parseVorbisComments(body)
            }
            if (isLast) return ReplayGainInfo()
            raf.seek(raf.filePointer + length)
        }
        return ReplayGainInfo()
    }

    /** Vendor string, then a count, then that many "KEY=value" entries — all lengths 32-bit LE. */
    private fun parseVorbisComments(body: ByteArray): ReplayGainInfo {
        var offset = 0
        fun readLength(): Int? {
            if (offset + 4 > body.size) return null
            val value = (body[offset].toInt() and 0xFF) or
                ((body[offset + 1].toInt() and 0xFF) shl 8) or
                ((body[offset + 2].toInt() and 0xFF) shl 16) or
                ((body[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            return value.takeIf { it >= 0 && offset + it <= body.size }
        }

        val vendorLength = readLength() ?: return ReplayGainInfo()
        offset += vendorLength
        val count = readLength() ?: return ReplayGainInfo()

        var track: Float? = null
        var album: Float? = null
        repeat(count.coerceAtMost(MAX_COMMENTS)) {
            val length = readLength() ?: return ReplayGainInfo(track, album)
            val entry = String(body, offset, length, Charsets.UTF_8)
            offset += length
            val separator = entry.indexOf('=')
            if (separator > 0) {
                val key = entry.substring(0, separator).uppercase()
                val value = entry.substring(separator + 1)
                when (key) {
                    TRACK_GAIN_KEY -> track = parseGainDb(value)
                    ALBUM_GAIN_KEY -> album = parseGainDb(value)
                }
            }
        }
        return ReplayGainInfo(track, album)
    }

    /** Values look like "-7.50 dB"; take the leading signed number and ignore the rest. */
    private fun parseGainDb(value: String): Float? =
        GAIN_PATTERN.find(value.trim())?.value?.toFloatOrNull()?.takeIf { it in MIN_DB..MAX_DB }

    private val GAIN_PATTERN = Regex("^[+-]?\\d+(?:\\.\\d+)?")

    private const val NUL = '\u0000'
    private const val BOM = '\uFEFF'
    private const val TRACK_GAIN_KEY = "REPLAYGAIN_TRACK_GAIN"
    private const val ALBUM_GAIN_KEY = "REPLAYGAIN_ALBUM_GAIN"
    private const val ID3_HEADER_BYTES = 10
    private const val FRAME_HEADER_BYTES = 10
    private const val FLAC_BLOCK_HEADER_BYTES = 4
    private const val VORBIS_COMMENT_BLOCK = 4
    private const val EXTENDED_HEADER_FLAG = 0x40
    private const val MIN_HEADER_BYTES = 16
    private const val MAX_FRAME_BYTES = 1 shl 20
    private const val MAX_COMMENTS = 1024
    private const val MIN_DB = -60f
    private const val MAX_DB = 60f
}

/**
 * Converts a gain in dB to a player volume.
 *
 * Positive gain is clamped away: raising volume above unity would clip, and there is no limiter
 * here to catch it. Quiet tracks are therefore left alone and loud ones brought down, which is the
 * conservative half of what ReplayGain normally does.
 */
fun gainToVolume(db: Float?): Float =
    if (db == null) 1f else 10f.pow(db / 20f).coerceIn(0.05f, 1f)
