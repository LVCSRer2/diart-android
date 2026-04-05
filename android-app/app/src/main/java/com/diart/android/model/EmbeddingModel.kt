package com.diart.android.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.diart.android.audio.FeatureExtractor
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * wespeaker-voxceleb-resnet34-LM ONNX 임베딩 모델 래퍼.
 *
 * 모델 입출력:
 *   입력: feats  (B, T, 80)  – 80차원 log mel filterbank
 *   출력: embs   (B, 256)    – L2 정규화된 화자 임베딩
 *
 * 성능 최적화:
 *  - mel 특징을 청크 단위로 미리 추출 (extractMelCache) → 화자별 재사용
 *  - 프레임 서브샘플링: 4배 다운샘플 (498 → ~125 프레임)로 ResNet 부하 감소
 */
class EmbeddingModel(context: Context) : AutoCloseable {

    companion object {
        const val EMBEDDING_DIM = 256
        const val N_MELS = 80
        private const val N_FFT = 512
        private const val HOP_LENGTH = 160   // 10ms
        private const val SUBSAMPLE = 2      // mel 프레임 2개 중 1개만 사용 → 임베딩 품질/속도 균형
        private const val MIN_WEIGHT = 1e-8f
    }

    private val ortEnv = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val featureExtractor: FeatureExtractor

    /** 현재 청크의 mel 특징 캐시 (한 번만 계산) */
    private var cachedMelFrames: Array<FloatArray>? = null

    init {
        val modelBytes = context.assets.open("embedding.onnx").readBytes()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
        }
        session = ortEnv.createSession(modelBytes, opts)

        featureExtractor = FeatureExtractor.create(
            context = context,
            sampleRate = 16000,
            nFft = N_FFT,
            hopLength = HOP_LENGTH,
            nMels = N_MELS,
            applyLog = true,
            assetFileName = "mel_filterbank_80.bin",
        )
    }

    /**
     * 새 청크가 시작될 때 mel 특징을 미리 계산합니다.
     * 같은 청크에서 여러 화자의 임베딩을 추출할 때 재사용됩니다.
     */
    fun warmup(waveform: FloatArray) {
        val allFrames = featureExtractor.extract(waveform)
        // SUBSAMPLE 배 다운샘플
        cachedMelFrames = Array(allFrames.size / SUBSAMPLE) { i ->
            allFrames[i * SUBSAMPLE]
        }
    }

    /**
     * 캐시된 mel 특징에 화자 가중치를 적용해 임베딩을 추출합니다.
     * warmup()을 먼저 호출해야 합니다.
     *
     * @param speakerWeights [segFrames] 크기의 화자 활성 가중치
     */
    fun extractForSpeaker(speakerWeights: FloatArray): FloatArray {
        val mel = cachedMelFrames ?: return FloatArray(EMBEDDING_DIM)
        val numMelFrames = mel.size
        val numSegFrames = speakerWeights.size

        // segmentation 프레임 → (서브샘플된) mel 프레임 가중치 업샘플링
        val melWeights = FloatArray(numMelFrames) { melIdx ->
            // 원래 mel 인덱스로 복원 (서브샘플 고려)
            val origMelIdx = melIdx * SUBSAMPLE
            val segIdx = (origMelIdx.toFloat() / (numMelFrames * SUBSAMPLE) * numSegFrames)
                .toInt().coerceIn(0, numSegFrames - 1)
            max(speakerWeights[segIdx], MIN_WEIGHT)
        }

        // 소프트 마스크 적용
        val weightedFrames = Array(numMelFrames) { t ->
            FloatArray(N_MELS) { m -> mel[t][m] * melWeights[t] }
        }

        return runInference(weightedFrames)
    }

    private fun runInference(frames: Array<FloatArray>): FloatArray {
        val numFrames = frames.size
        val flatData = FloatArray(numFrames * N_MELS) { i ->
            frames[i / N_MELS][i % N_MELS]
        }

        val tensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(flatData),
            longArrayOf(1L, numFrames.toLong(), N_MELS.toLong()),
        )

        tensor.use {
            val inputName = session.inputNames.first()
            session.run(mapOf(inputName to it)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val raw = output[0].value as Array<FloatArray>
                return l2Normalize(raw[0])
            }
        }
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm < 1e-8f) return vec
        return FloatArray(vec.size) { vec[it] / norm }
    }

    fun clearCache() { cachedMelFrames = null }

    override fun close() { session.close() }
}
