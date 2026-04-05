package com.diart.android.pipeline

import kotlin.math.sqrt

/**
 * diart의 OnlineSpeakerClustering을 Kotlin으로 구현.
 *
 * 알고리즘:
 *  1. 새 임베딩이 들어오면 기존 센트로이드들과 코사인 거리를 계산
 *  2. 가장 가까운 센트로이드가 delta_new 임계값보다 가까우면 해당 화자로 배정
 *  3. 아니면 새 화자로 등록
 *  4. 배정된 센트로이드는 지수 이동 평균으로 업데이트 (rho_update)
 *
 * 기본값은 diart 논문과 동일:
 *   tau_active = 0.6, rho_update = 0.3, delta_new = 1.0
 */
class OnlineSpeakerClustering(
    var tauActive: Float = 0.4f,
    var rhoUpdate: Float = 0.10f,
    var deltaNow: Float = 0.40f,
    var maxSpeakers: Int = 20,
    private val embDim: Int = 512,
) {
    /** 전역 화자 ID → 센트로이드 임베딩 */
    private val centroids = mutableMapOf<Int, FloatArray>()
    private var nextSpeakerId = 0

    /**
     * 한 청크의 [localSpeakers]개 임베딩을 받아 전역 화자 ID로 매핑합니다.
     *
     * @param embeddings       [localSpeakers][embDim]
     * @param activityProbs    [localSpeakers] – 각 로컬 화자의 평균 활성 확률
     * @return localSpeakerIdx → globalSpeakerId 매핑
     */
    fun assign(
        embeddings: Array<FloatArray>,
        activityProbs: FloatArray,
    ): IntArray {
        val numLocal = embeddings.size
        val assignments = IntArray(numLocal) { -1 }

        for (localIdx in 0 until numLocal) {
            val activity = activityProbs[localIdx]
            if (activity < tauActive) {
                // 활성이 낮은 로컬 화자는 무시
                continue
            }

            val emb = embeddings[localIdx]

            if (centroids.isEmpty()) {
                // 첫 화자 등록
                val id = registerNew(emb)
                assignments[localIdx] = id
                continue
            }

            // 기존 센트로이드와 코사인 거리 계산
            val (bestId, bestDist) = centroids.entries
                .minByOrNull { (_, centroid) -> cosineDistance(emb, centroid) }
                ?.let { (id, c) -> id to cosineDistance(emb, c) }
                ?: continue

            if (bestDist < deltaNow) {
                // 기존 화자에 배정
                assignments[localIdx] = bestId
                updateCentroid(bestId, emb, rhoUpdate)
            } else if (centroids.size < maxSpeakers) {
                // 새 화자 등록
                val id = registerNew(emb)
                assignments[localIdx] = id
            }
        }

        return assignments
    }

    private fun registerNew(emb: FloatArray): Int {
        val id = nextSpeakerId++
        centroids[id] = emb.copyOf()
        return id
    }

    private fun updateCentroid(id: Int, emb: FloatArray, rho: Float) {
        val c = centroids[id] ?: return
        for (i in c.indices) {
            c[i] = (1f - rho) * c[i] + rho * emb[i]
        }
        // 업데이트 후 재정규화
        val norm = l2Norm(c)
        if (norm > 1e-8f) {
            for (i in c.indices) c[i] /= norm
        }
    }

    /** 코사인 거리 = 1 - cosine_similarity */
    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-8f) 1f else 1f - dot / denom
    }

    private fun l2Norm(v: FloatArray): Float {
        var s = 0f
        for (x in v) s += x * x
        return sqrt(s)
    }

    /** 현재 등록된 전역 화자 목록 */
    val speakerIds: Set<Int> get() = centroids.keys.toSet()

    fun reset() {
        centroids.clear()
        nextSpeakerId = 0
    }
}
