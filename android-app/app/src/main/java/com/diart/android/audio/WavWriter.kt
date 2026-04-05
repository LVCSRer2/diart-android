package com.diart.android.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
    /**
     * 16-bit PCM 바이트 배열을 WAV 파일로 저장합니다.
     */
    fun write(file: File, pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1) {
        val dataSize = pcmData.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)                                          // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * 2)                   // byte rate
        header.putShort((channels * 2).toShort())                  // block align
        header.putShort(16)                                        // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)

        file.outputStream().use { out ->
            out.write(header.array())
            out.write(pcmData)
        }
    }

    /** FloatArray(-1..1) → 16-bit PCM ByteArray 변환 */
    fun floatToPcm16(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in samples) {
            buf.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        return buf.array()
    }
}
