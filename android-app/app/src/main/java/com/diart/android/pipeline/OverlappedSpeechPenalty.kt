package com.diart.android.pipeline

import kotlin.math.exp
import kotlin.math.pow

/**
 * diart의 OverlappedSpeechPenalty와 동일한 알고리즘.
 *
 * 겹친 발화 구간에서 특정 화자의 임베딩이 오염되는 것을 줄이기 위해
 * 활성 확률에 gamma^2 * softmax(beta) 형태의 패널티를 적용합니다.
 *
 * 수식:
 *   weight[t,s] = seg[t,s]^gamma * softmax(beta * seg[t,:])_s ^ gamma
 *
 * 기본값은 diart 논문과 동일: gamma=3, beta=10
 */
object OverlappedSpeechPenalty {

    /**
     * @param segmentation [frames][speakers] 발화 확률
     * @param gamma 지수 (기본 3)
     * @param beta  softmax 온도 (기본 10)
     * @return [frames][speakers] 패널티 적용된 가중치
     */
    fun apply(
        segmentation: Array<FloatArray>,
        gamma: Float = 3f,
        beta: Float = 10f,
    ): Array<FloatArray> {
        val numFrames = segmentation.size
        if (numFrames == 0) return emptyArray()
        val numSpeakers = segmentation[0].size

        return Array(numFrames) { t ->
            val frameSeg = segmentation[t]

            // softmax(beta * seg[t,:])
            val scaled = FloatArray(numSpeakers) { s -> frameSeg[s] * beta }
            val maxVal = scaled.max()
            val expVals = FloatArray(numSpeakers) { s -> exp((scaled[s] - maxVal).toDouble()).toFloat() }
            val expSum = expVals.sum()
            val softmaxVals = FloatArray(numSpeakers) { s -> expVals[s] / expSum }

            // weight = seg^gamma * softmax^gamma, clamped to MIN
            FloatArray(numSpeakers) { s ->
                val w = frameSeg[s].pow(gamma) * softmaxVals[s].pow(gamma)
                maxOf(w, 1e-8f)
            }
        }
    }

    /**
     * 특정 화자(speakerIdx)에 대한 1D 가중치 배열을 반환합니다.
     */
    fun speakerWeights(
        segmentation: Array<FloatArray>,
        speakerIdx: Int,
        gamma: Float = 3f,
        beta: Float = 10f,
    ): FloatArray {
        val penalized = apply(segmentation, gamma, beta)
        return FloatArray(penalized.size) { t -> penalized[t][speakerIdx] }
    }
}
