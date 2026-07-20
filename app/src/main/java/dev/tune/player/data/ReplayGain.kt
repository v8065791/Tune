package dev.tune.player.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.pow

/** Which ReplayGain tag, if any, is applied to playback volume. */
enum class ReplayGainMode(val label: String) {
    OFF("Off"),
    TRACK("Track gain"),
    ALBUM("Album gain, then track"),
}

/**
 * Loudness tags read from a file.
 *
 * Gains are in dB at the ReplayGain reference level. Peaks are sample amplitudes where 1.0 is
 * full scale; they are what makes a positive gain safe to apply.
 */
data class ReplayGainInfo(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
) {
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null

    /**
     * The player volume this file should play at under [mode].
     *
     * A positive gain is only allowed as far as the peak says is safe: boosting past `1/peak`
     * would clip, and there is no limiter in the chain to catch it. With no peak tag the gain is
     * capped at unity, so an untagged-peak file is quiet rather than distorted.
     */
    fun volumeFor(mode: ReplayGainMode): Float {
        if (mode == ReplayGainMode.OFF) return 1f
        val album = mode == ReplayGainMode.ALBUM
        val gain = if (album) albumGainDb ?: trackGainDb else trackGainDb ?: albumGainDb
        val peak = if (album) albumPeak ?: trackPeak else trackPeak ?: albumPeak
        if (gain == null) return 1f

        val ceiling = peak?.takeIf { it > 0f }?.let { 1f / it } ?: 1f
        return 10f.pow(gain / 20f).coerceAtMost(ceiling).coerceIn(MIN_VOLUME, MAX_VOLUME)
    }

    private companion object {
        const val MIN_VOLUME = 0.05f
        const val MAX_VOLUME = 1f
    }
}

/**
 * Reads loudness tags straight out of a file's header.
 *
 * Deliberately dependency-free — every container that carries these tags is cheap to walk, and
 * only the header is read, never the audio. Handled:
 *
 *  - **MP3** — ID3v2.3 and v2.4 `TXXX` frames
 *  - **FLAC** — native Vorbis comments
 *  - **Ogg Vorbis, Opus and Ogg FLAC** — Vorbis comments inside Ogg pages
 *
 * Opus additionally uses `R128_*` tags at a different reference level, and an output gain baked
 * into its header; both are converted so one number comes out the other side.
 *
 * Not handled: ID3v2.2 (three-character frame ids, effectively extinct), MP4/M4A `----` atoms,
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
                magic.matches("ID3", length = 3) -> readId3(raf)
                magic.matches("fLaC") -> readFlac(raf)
                magic.matches("OggS") -> readOgg(raf)
                else -> ReplayGainInfo()
            }
        }
    }.getOrElse { ReplayGainInfo() }

    // ---- Tag collection ----------------------------------------------------

    /**
     * Accumulates whichever loudness keys a file happens to carry.
     *
     * Opus files tag with `R128_*`, measured against EBU R128's -23 LUFS. ReplayGain targets
     * -18 LUFS, so those values are shifted by [R128_TO_REPLAYGAIN_DB] to put every format on one
     * scale. A file carrying both is taken at its ReplayGain value, since that needs no conversion.
     */
    private class GainBuilder {
        private var trackGain: Float? = null
        private var albumGain: Float? = null
        private var r128Track: Float? = null
        private var r128Album: Float? = null
        private var trackPeak: Float? = null
        private var albumPeak: Float? = null

        fun accept(rawKey: String, value: String) {
            when (rawKey.uppercase()) {
                "REPLAYGAIN_TRACK_GAIN" -> trackGain = parseGainDb(value)
                "REPLAYGAIN_ALBUM_GAIN" -> albumGain = parseGainDb(value)
                "REPLAYGAIN_TRACK_PEAK" -> trackPeak = parsePeak(value)
                "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = parsePeak(value)
                "R128_TRACK_GAIN" -> r128Track = parseR128(value)
                "R128_ALBUM_GAIN" -> r128Album = parseR128(value)
            }
        }

        /**
         * [headerGainDb] is Opus's own output gain, which the specification says always applies.
         * It is folded in here rather than applied separately, which means switching ReplayGain
         * off also skips it — a deliberate simplification, and near-universally zero in practice.
         */
        fun build(headerGainDb: Float = 0f): ReplayGainInfo {
            fun combine(gain: Float?): Float? =
                gain?.plus(headerGainDb) ?: headerGainDb.takeIf { it != 0f }

            return ReplayGainInfo(
                trackGainDb = combine(trackGain ?: r128Track),
                albumGainDb = combine(albumGain ?: r128Album),
                trackPeak = trackPeak,
                albumPeak = albumPeak,
            )
        }
    }

    /** Values look like "-7.50 dB"; take the leading signed number and ignore the rest. */
    private fun parseGainDb(value: String): Float? =
        GAIN_PATTERN.find(value.trim())?.value?.toFloatOrNull()?.takeIf { it in MIN_DB..MAX_DB }

    private fun parsePeak(value: String): Float? =
        value.trim().toFloatOrNull()?.takeIf { it > 0f && it <= MAX_PEAK }

    /** R128 gains are Q7.8 fixed point: a whole number of 1/256 dB steps. */
    private fun parseR128(value: String): Float? =
        value.trim().toIntOrNull()
            ?.let { it / 256f + R128_TO_REPLAYGAIN_DB }
            ?.takeIf { it in MIN_DB..MAX_DB }

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
        val gains = GainBuilder()

        while (pos + FRAME_HEADER_BYTES <= end) {
            raf.seek(pos)
            val id = ByteArray(4)
            raf.readFully(id)
            // Padding after the last frame is zero-filled.
            if (id[0] == 0.toByte()) break
            val size = if (major >= 4) raf.readSynchsafe() else raf.readInt()
            raf.readUnsignedShort() // frame flags
            if (size <= 0 || pos + FRAME_HEADER_BYTES + size > end) break

            if (id.matches("TXXX") && size <= MAX_BLOCK_BYTES) {
                val body = ByteArray(size)
                raf.readFully(body)
                decodeTxxx(body)?.let { (key, value) -> gains.accept(key, value) }
            }
            pos += FRAME_HEADER_BYTES + size
        }
        return gains.build()
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
        val parts = String(body, 1, body.size - 1, charset).split(NUL)
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

    // ---- Native FLAC -------------------------------------------------------

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
                if (length > MAX_BLOCK_BYTES) return ReplayGainInfo()
                val body = ByteArray(length)
                raf.readFully(body)
                val gains = GainBuilder()
                parseVorbisComments(body, 0, gains)
                return gains.build()
            }
            if (isLast) return ReplayGainInfo()
            raf.seek(raf.filePointer + length)
        }
        return ReplayGainInfo()
    }

    // ---- Ogg (Vorbis, Opus, Ogg FLAC) --------------------------------------

    /**
     * Ogg wraps everything in pages, and the tag packet routinely spans several of them once
     * cover art is embedded, so the packets are reassembled before anything is parsed. Which
     * codec is inside is decided by the first packet's magic.
     */
    private fun readOgg(raf: RandomAccessFile): ReplayGainInfo {
        val packets = readOggPackets(raf)
        val first = packets.firstOrNull() ?: return ReplayGainInfo()
        val gains = GainBuilder()

        return when {
            first.matches("OpusHead") -> {
                // Output gain is a signed Q7.8 value in the identification header.
                val headerGain =
                    if (first.size >= OPUS_GAIN_OFFSET + 2) {
                        first.readShortLe(OPUS_GAIN_OFFSET) / 256f
                    } else 0f
                packets.firstOrNull { it.matches("OpusTags") }
                    // OpusTags is the magic followed by a bare comment block — no framing bit.
                    ?.let { parseVorbisComments(it, "OpusTags".length, gains) }
                gains.build(headerGain)
            }

            first.isVorbisPacket(VORBIS_IDENTIFICATION) -> {
                packets.firstOrNull { it.isVorbisPacket(VORBIS_COMMENT) }
                    ?.let { parseVorbisComments(it, VORBIS_HEADER_BYTES, gains) }
                gains.build()
            }

            first.matches("FLAC", offset = 1) && first.firstOrNull() == 0x7F.toByte() -> {
                // In Ogg FLAC each packet after the first holds one native metadata block.
                packets.asSequence()
                    .drop(1)
                    .firstOrNull {
                        it.size > FLAC_BLOCK_HEADER_BYTES &&
                            (it[0].toInt() and 0x7F) == VORBIS_COMMENT_BLOCK
                    }
                    ?.let { parseVorbisComments(it, FLAC_BLOCK_HEADER_BYTES, gains) }
                gains.build()
            }

            else -> ReplayGainInfo()
        }
    }

    /**
     * Walks Ogg pages, joining segments back into packets. Only the first logical stream is
     * followed — a multiplexed video track would otherwise interleave its own packets in.
     */
    private fun readOggPackets(raf: RandomAccessFile): List<ByteArray> {
        raf.seek(0)
        val packets = mutableListOf<ByteArray>()
        var packet = ByteArrayOutputStream()
        var streamSerial: Int? = null

        while (packets.size < MAX_OGG_PACKETS &&
            raf.filePointer + OGG_PAGE_HEADER_BYTES <= raf.length()
        ) {
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (!magic.matches("OggS")) break

            raf.skipBytes(2) // version, header type
            raf.skipBytes(8) // granule position
            val serial = raf.readInt() // byte order is irrelevant; only equality matters
            raf.skipBytes(8) // sequence number, checksum

            val segmentCount = raf.readUnsignedByte()
            val segments = ByteArray(segmentCount)
            raf.readFully(segments)

            val payloadSize = segments.sumOf { it.toInt() and 0xFF }
            if (raf.filePointer + payloadSize > raf.length()) break
            val payload = ByteArray(payloadSize)
            raf.readFully(payload)

            if (streamSerial == null) streamSerial = serial
            if (serial != streamSerial) continue

            var offset = 0
            for (segment in segments) {
                val length = segment.toInt() and 0xFF
                if (packet.size() + length <= MAX_BLOCK_BYTES) packet.write(payload, offset, length)
                offset += length
                // Any segment shorter than 255 bytes terminates the packet it belongs to.
                if (length < OGG_MAX_SEGMENT) {
                    packets.add(packet.toByteArray())
                    packet = ByteArrayOutputStream()
                    if (packets.size >= MAX_OGG_PACKETS) break
                }
            }
        }
        return packets
    }

    /** Vorbis header packets are a type byte followed by the string "vorbis". */
    private fun ByteArray.isVorbisPacket(type: Byte): Boolean =
        size >= VORBIS_HEADER_BYTES && this[0] == type && matches("vorbis", offset = 1)

    // ---- Vorbis comments ---------------------------------------------------

    /**
     * Vendor string, then a count, then that many "KEY=value" entries — all lengths 32-bit LE.
     * Shared by FLAC, Ogg Vorbis and Opus, which differ only in what precedes the block.
     */
    private fun parseVorbisComments(body: ByteArray, start: Int, gains: GainBuilder) {
        var offset = start
        fun readLength(): Int? {
            if (offset + 4 > body.size) return null
            val value = (body[offset].toInt() and 0xFF) or
                ((body[offset + 1].toInt() and 0xFF) shl 8) or
                ((body[offset + 2].toInt() and 0xFF) shl 16) or
                ((body[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            return value.takeIf { it >= 0 && offset + it <= body.size }
        }

        val vendorLength = readLength() ?: return
        offset += vendorLength
        val count = readLength() ?: return

        repeat(count.coerceAtMost(MAX_COMMENTS)) {
            val length = readLength() ?: return
            val entry = String(body, offset, length, Charsets.UTF_8)
            offset += length
            val separator = entry.indexOf('=')
            if (separator > 0) {
                gains.accept(entry.substring(0, separator), entry.substring(separator + 1))
            }
        }
    }

    // ---- Byte helpers ------------------------------------------------------

    private fun ByteArray.matches(
        text: String,
        offset: Int = 0,
        length: Int = text.length,
    ): Boolean {
        if (size < offset + length) return false
        for (i in 0 until length) if (this[offset + i] != text[i].code.toByte()) return false
        return true
    }

    private fun ByteArray.readShortLe(offset: Int): Short =
        (((this[offset + 1].toInt() and 0xFF) shl 8) or (this[offset].toInt() and 0xFF)).toShort()

    private val GAIN_PATTERN = Regex("^[+-]?\\d+(?:\\.\\d+)?")

    private const val NUL = '\u0000'
    private const val BOM = '\uFEFF'
    private const val ID3_HEADER_BYTES = 10
    private const val FRAME_HEADER_BYTES = 10
    private const val FLAC_BLOCK_HEADER_BYTES = 4
    private const val VORBIS_COMMENT_BLOCK = 4
    private const val EXTENDED_HEADER_FLAG = 0x40
    private const val OGG_PAGE_HEADER_BYTES = 27
    private const val OGG_MAX_SEGMENT = 255
    private const val OPUS_GAIN_OFFSET = 16
    private const val VORBIS_IDENTIFICATION: Byte = 1
    private const val VORBIS_COMMENT: Byte = 3
    private const val VORBIS_HEADER_BYTES = 7
    private const val MIN_HEADER_BYTES = 16
    private const val MAX_BLOCK_BYTES = 1 shl 20
    private const val MAX_OGG_PACKETS = 8
    private const val MAX_COMMENTS = 1024
    private const val MIN_DB = -60f
    private const val MAX_DB = 60f
    private const val MAX_PEAK = 8f

    /** R128 targets -23 LUFS, ReplayGain -18 LUFS; the difference puts both on one scale. */
    private const val R128_TO_REPLAYGAIN_DB = 5f
}
