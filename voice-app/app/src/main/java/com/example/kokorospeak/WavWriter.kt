package com.example.kokorospeak

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes float audio (-1..1) as a 16-bit mono PCM WAV file. */
object WavWriter {

    fun write(out: OutputStream, chunks: List<FloatArray>, sampleRate: Int) {
        var totalSamples = 0L
        for (c in chunks) totalSamples += c.size
        val dataBytes = (totalSamples * 2).toInt()

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                 // PCM header size
        header.putShort(1)                // PCM format
        header.putShort(1)                // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)     // byte rate
        header.putShort(2)                // block align
        header.putShort(16)               // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        out.write(header.array())

        val buffer = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        for (chunk in chunks) {
            for (sample in chunk) {
                val clamped = sample.coerceIn(-1f, 1f)
                buffer.putShort((clamped * 32767f).toInt().toShort())
                if (!buffer.hasRemaining()) {
                    out.write(buffer.array(), 0, buffer.position())
                    buffer.clear()
                }
            }
        }
        if (buffer.position() > 0) out.write(buffer.array(), 0, buffer.position())
        out.flush()
    }
}
