package dev.tune.player.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class Mp4TagReaderTest {

    @Test
    fun `reads release date from m4a day atom`() {
        withMp4(date = "2026-07-22") { file ->
            assertEquals(20260722, ReleaseDateReader.read(file.absolutePath))
        }
    }

    @Test
    fun `reads replaygain from iTunes freeform atom`() {
        withMp4(gain = "-7.50 dB") { file ->
            assertEquals(-7.5f, ReplayGainReader.read(file.absolutePath).trackGainDb)
        }
    }

    private fun withMp4(date: String? = null, gain: String? = null, test: (File) -> Unit) {
        val items = buildList {
            date?.let { add(atom("©day", dataAtom(it))) }
            gain?.let {
                add(
                    atom(
                        "----",
                        atom("mean", flags() + "com.apple.iTunes".toByteArray()),
                        atom("name", flags() + "REPLAYGAIN_TRACK_GAIN".toByteArray()),
                        dataAtom(it),
                    )
                )
            }
        }
        val bytes = atom("ftyp", "M4A \u0000\u0000\u0000\u0000".toByteArray()) +
            atom("moov", atom("udta", atom("meta", flags() + atom("ilst", *items.toTypedArray()))))
        val file = File.createTempFile("tune-tags", ".m4a")
        try {
            file.writeBytes(bytes)
            test(file)
        } finally {
            file.delete()
        }
    }

    private fun dataAtom(value: String) = atom("data", ByteArray(8) + value.toByteArray())

    private fun flags() = ByteArray(4)

    private fun atom(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0), ByteArray::plus)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(body.size + 8)
                output.write(type.toByteArray(Charsets.ISO_8859_1))
                output.write(body)
            }
        }.toByteArray()
    }
}
