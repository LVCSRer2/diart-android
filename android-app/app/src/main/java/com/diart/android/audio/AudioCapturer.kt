package com.diart.android.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 16kHz mono PCM16 실시간 오디오 캡처.
 * 10ms(160샘플) 단위로 콜백을 호출합니다.
 */
class AudioCapturer(
    val sampleRate: Int = 16000,
    private val frameSizeMs: Int = 10,
) {
    val frameSizeSamples: Int = sampleRate * frameSizeMs / 1000  // 160

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onFrames: (FloatArray) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf, frameSizeSamples * 2 * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        ).also { it.startRecording() }

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val shortBuf = ShortArray(frameSizeSamples)
            while (isActive) {
                val read = audioRecord?.read(shortBuf, 0, frameSizeSamples) ?: 0
                if (read > 0) {
                    onFrames(FloatArray(read) { shortBuf[it] / 32768.0f })
                }
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
