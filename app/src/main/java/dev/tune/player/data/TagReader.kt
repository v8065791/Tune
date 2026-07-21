package dev.tune.player.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Walks a file's header and hands every tag it finds to a [Sink].
 *
 * Deliberately dependency-free — every container we care about is cheap to walk, and only the
 * header is ever read, never the audio. Handled:
 *
 *  - **MP3** — ID3v2.3 and v2.4, `TXXX` frames only
 *  - **FLAC** — native Vorbis comments
 *  - **Ogg Vorbis, Opus and Ogg FLAC** — Vorbis comments inside Ogg pages
 *
 * Not handled: ID3v2.2 (three-character frame ids, effectively extinct), MP4/M4A `----` atoms,
 * and APEv2 trailers. Those yield no tags rather than wrong ones.
 *
 * This object only parses. What the tags *mean* belongs to its callers — see [ReplayGainReader]
 * and [ReleaseDateReader]. Keeping one walker matters: a second copy of the Ogg page logic would
 * be a second place for a byte offset to drift.
 */
internal object TagReader {

    /** Receives each `KEY`/`value` pair as it is decoded. Keys arrive in their original case. */
    fun interface Sink {
        fun accept(key: String, value: String)
    }

    /**
     * Reads [path], feeding every tag to [sink].
     *
     * Returns Opus's header output gain in dB, and 0 for every other format. That value lives in
     * the identification header rather than among the tags, so a sink of key/value pairs has
     * nowhere to put it — hence the odd-looking return type on an otherwise generic reader.
     */
    fun read(path: String, sink: Sink): Float {
        val file = File(path)
        if (!file.isFile) return 0f
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < MIN_HEADER_BYTES) return 0f
                val magic = ByteArray(4)
                raf.readFully(magic)
                when {
                    magic.matches("ID3", length = 3) -> { readId3(raf, sink); 0f }
                    magic.matches("fLaC") -> { readFlac(raf, sink); 0f }
                    magic.matches("OggS") -> readOgg(raf, sink)
                    else -> 0f
                }
            }
        } catch (_: Exception) {
            // A malformed or unreadable file yields no tags rather than crashing the scan. Only
            // Exceptions are swallowed — an Error (OutOfMemoryError, StackOverflowError) signals a
            // real VM problem and is left to propagate rather than being masked as "untagged".
            0f
        }
    }

    // ---- ID3v2 -------------------------------------------------------------

    private fun readId3(raf: RandomAccessFile, sink: Sink) {
        raf.seek(3)
        val major = raf.readUnsignedByte()
        raf.readUnsignedByte() // revision
        val headerFlags = raf.readUnsignedByte()
        val tagSize = raf.readSynchsafe()
        // v2.2 packs frames differently and is vanishingly rare; treat it as untagged.
        if (major < 3 || tagSize <= 0) return

        var pos = ID3_HEADER_BYTES.toLong()
        if (headerFlags and EXTENDED_HEADER_FLAG != 0) {
            raf.seek(pos)
            // v2.4 sizes the extended header synchsafely and counts itself; v2.3 does neither.
            pos += if (major >= 4) raf.readSynchsafe().toLong()
            else raf.readInt().toLong() + 4
        }

        val end = (ID3_HEADER_BYTES + tagSize).toLong().coerceAtMost(raf.length())

        while (pos + FRAME_HEADER_BYTES <= end) {
            raf.seek(pos)
            val id = ByteArray(4)
            raf.readFully(id)
            // Padding after the last frame is zero-filled.
            if (id[0] == 0.toByte()) break
            val size = if (major >= 4) raf.readSynchsafe() else raf.readInt()
            raf.readUnsignedShort() // frame flags
            if (size <= 0 || pos + FRAME_HEADER_BYTES + size > end) break

            if (size <= MAX_BLOCK_BYTES) {
                val frameId = String(id, Charsets.ISO_8859_1)
                if (frameId == "TXXX") {
                    val body = ByteArray(size)
                    raf.readFully(body)
                    decodeTxxx(body)?.let { (key, value) -> sink.accept(key, value) }
                } else if (frameId in TEXT_FRAMES) {
                    // A plain text frame is an encoding byte then one string; the frame id is the
                    // key. Date frames are the reason this branch exists at all.
                    val body = ByteArray(size)
                    raf.readFully(body)
                    decodeTextFrame(body)?.let { sink.accept(frameId, it) }
                }
            }
            pos += FRAME_HEADER_BYTES + size
        }
    }

    /**
     * A TXXX frame is an encoding byte, then a NUL-separated description and value. Decoding the
     * whole body at once and splitting on the NUL character handles all four encodings uniformly,
     * including the two-byte terminator UTF-16 uses.
     */
    private fun decodeTxxx(body: ByteArray): Pair<String, String>? {
        val text = decodeTextFrame(body) ?: return null
        val parts = text.split(NUL)
        if (parts.size < 2) return null
        // A second UTF-16 string carries its own BOM, which survives decoding as a stray char.
        return parts[0].trim(BOM, ' ') to parts[1].trim(BOM, ' ')
    }

    private fun decodeTextFrame(body: ByteArray): String? {
        if (body.size < 2) return null
        val charset = when (body[0].toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(body, 1, body.size - 1, charset).trim(BOM, NUL, ' ').takeIf { it.isNotEmpty() }
    }

    /** ID3 sizes are 28 bits spread over 4 bytes, with the top bit of each left clear. */
    private fun RandomAccessFile.readSynchsafe(): Int {
        var value = 0
        repeat(4) { value = (value shl 7) or (readUnsignedByte() and 0x7F) }
        return value
    }

    // ---- Native FLAC -------------------------------------------------------

    private fun readFlac(raf: RandomAccessFile, sink: Sink) {
        raf.seek(4)
        while (raf.filePointer + FLAC_BLOCK_HEADER_BYTES <= raf.length()) {
            val header = raf.readUnsignedByte()
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val length = (raf.readUnsignedByte() shl 16) or
                (raf.readUnsignedByte() shl 8) or
                raf.readUnsignedByte()
            if (length < 0 || raf.filePointer + length > raf.length()) return

            if (type == VORBIS_COMMENT_BLOCK) {
                if (length > MAX_BLOCK_BYTES) return
                val body = ByteArray(length)
                raf.readFully(body)
                parseVorbisComments(body, 0, sink)
                return
            }
            if (isLast) return
            raf.seek(raf.filePointer + length)
        }
    }

    // ---- Ogg (Vorbis, Opus, Ogg FLAC) --------------------------------------

    /**
     * Ogg wraps everything in pages, and the tag packet routinely spans several of them once
     * cover art is embedded, so the packets are reassembled before anything is parsed. Which
     * codec is inside is decided by the first packet's magic.
     */
    private fun readOgg(raf: RandomAccessFile, sink: Sink): Float {
        val packets = readOggPackets(raf)
        val first = packets.firstOrNull() ?: return 0f

        return when {
            first.matches("OpusHead") -> {
                // Output gain is a signed Q7.8 value in the identification header.
                val headerGain =
                    if (first.size >= OPUS_GAIN_OFFSET + 2) {
                        first.readShortLe(OPUS_GAIN_OFFSET) / 256f
                    } else 0f
                packets.firstOrNull { it.matches("OpusTags") }
                    // OpusTags is the magic followed by a bare comment block — no framing bit.
                    ?.let { parseVorbisComments(it, "OpusTags".length, sink) }
                headerGain
            }

            first.isVorbisPacket(VORBIS_IDENTIFICATION) -> {
                packets.firstOrNull { it.isVorbisPacket(VORBIS_COMMENT) }
                    ?.let { parseVorbisComments(it, VORBIS_HEADER_BYTES, sink) }
                0f
            }

            first.matches("FLAC", offset = 1) && first.firstOrNull() == 0x7F.toByte() -> {
                // In Ogg FLAC each packet after the first holds one native metadata block.
                packets.asSequence()
                    .drop(1)
                    .firstOrNull {
                        it.size > FLAC_BLOCK_HEADER_BYTES &&
                            (it[0].toInt() and 0x7F) == VORBIS_COMMENT_BLOCK
                    }
                    ?.let { parseVorbisComments(it, FLAC_BLOCK_HEADER_BYTES, sink) }
                0f
            }

            else -> 0f
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
    private fun parseVorbisComments(body: ByteArray, start: Int, sink: Sink) {
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
                sink.accept(entry.substring(0, separator), entry.substring(separator + 1))
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

    /** Written as Char(code) rather than an escape: escapes in this file have been mangled before. */
    private val NUL = Char(0)
    private val BOM = Char(0xFEFF)

    /** Plain text frames worth surfacing. Everything else is left to MediaStore. */
    private val TEXT_FRAMES = setOf("TDRC", "TDRL", "TORY", "TDOR", "TYER", "TDAT")

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
}
