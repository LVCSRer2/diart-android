package com.diart.android.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * pyannote/segmentation-3.0 ONNX 추론 래퍼.
 *
 * 입력:  waveform  (1, 1, 80000)  float32
 * 출력:  segmentation  (1, frames, num_speakers)  float32
 *         각 값은 해당 프레임에서 해당 화자가 발화 중일 확률 [0, 1]
 */
class SegmentationModel(context: Context) : AutoCloseable {

    companion object {
        const val CHUNK_SAMPLES = 80000   // 5s @ 16kHz
        const val NUM_SPEAKERS = 3        // pyannote/segmentation-3.0 최대 동시 화자 수
    }

    private val ortEnv = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    /** 모델이 반환하는 프레임 수 (첫 추론 후 설정됨) */
    var numFrames: Int = -1
        private set

    init {
        val modelBytes = context.assets.open("segmentation.onnx").readBytes()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
        }
        session = ortEnv.createSession(modelBytes, opts)
    }

    /**
     * @param waveform 80,000샘플의 5초 오디오 (정규화된 float32)
     * @return [frames][speakers] 확률 배열
     */
    fun segment(waveform: FloatArray): Array<FloatArray> {
        require(waveform.size == CHUNK_SAMPLES) {
            "Expected $CHUNK_SAMPLES samples, got ${waveform.size}"
        }

        val inputTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(waveform),
            longArrayOf(1L, 1L, CHUNK_SAMPLES.toLong()),
        )

        inputTensor.use { tensor ->
            val inputName = session.inputNames.first()
            session.run(mapOf(inputName to tensor)).use { output ->
                // 출력 shape: [1, frames, speakers]
                @Suppress("UNCHECKED_CAST")
                val arr = output[0].value as Array<Array<FloatArray>>
                val frames = arr[0]
                numFrames = frames.size
                return frames
            }
        }
    }

    override fun close() {
        session.close()
    }
}
