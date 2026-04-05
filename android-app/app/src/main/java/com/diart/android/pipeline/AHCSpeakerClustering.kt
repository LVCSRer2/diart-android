package com.diart.android.pipeline

import kotlin.math.sqrt

/**
 * 녹음 완료 후 전체 임베딩에 적용하는 오프라인 Agglomerative Hierarchical Clustering.
 *
 * - Average linkage (합병 시 가중 평균 센트로이드)
 * - Cosine distance
 * - [clusterAssign]: 각 SegmentEntry에 새 화자 ID를 배정하는 IntArray 반환
 *   → DiarizationPipeline이 온라인 SpeakerTurn의 경계는 유지하고 화자 ID만 교체하는 데 사용
 */
class AHCSpeakerClustering(val threshold: Float = 0.35f) {

    /**
     * AHC를 실행하고 각 세그먼트의 화자 ID(IntArray)를 반환합니다.
     * 화자 ID는 첫 등장 시간 순으로 0, 1, 2, ... 배정됩니다.
     */
    fun clusterAssign(segments: List<SegmentEntry>): IntArray {
        val n = segments.size
        if (n == 0) return IntArray(0)
        if (n == 1) return IntArray(1) { 0 }

        val centroids = Array(n) { segments[it].embedding.copyOf() }
        val sizes     = IntArray(n) { 1 }
        val active    = BooleanArray(n) { true }
        var numActive = n

        val dist = Array(n) { i ->
            FloatArray(n) { j ->
                if (i == j) Float.MAX_VALUE else cosineDistance(centroids[i], centroids[j])
            }
        }

        while (numActive > 1) {
            var minD = Float.MAX_VALUE
            var mi = -1; var mj = -1
            for (i in 0 until n) {
                if (!active[i]) continue
                for (j in i + 1 until n) {
                    if (!active[j]) continue
                    if (dist[i][j] < minD) { minD = dist[i][j]; mi = i; mj = j }
                }
            }
            if (minD >= threshold || mi < 0) break

            val ni = sizes[mi].toFloat(); val nj = sizes[mj].toFloat(); val total = ni + nj
            for (d in centroids[mi].indices) {
                centroids[mi][d] = (centroids[mi][d] * ni + centroids[mj][d] * nj) / total
            }
            val norm = l2Norm(centroids[mi])
            if (norm > 1e-8f) for (d in centroids[mi].indices) centroids[mi][d] /= norm

            sizes[mi] += sizes[mj]; active[mj] = false; numActive--

            for (k in 0 until n) {
                if (!active[k] || k == mi) continue
                val d = cosineDistance(centroids[mi], centroids[k])
                dist[mi][k] = d; dist[k][mi] = d
            }
        }

        // 각 세그먼트를 가장 가까운 활성 클러스터에 배정
        val activeIdx  = (0 until n).filter { active[it] }
        val segCluster = IntArray(n) { i ->
            activeIdx.minByOrNull { c -> cosineDistance(segments[i].embedding, centroids[c]) } ?: 0
        }

        // 첫 등장 시간 순으로 화자 ID 0, 1, 2, ...
        val clusterFirstTime = activeIdx.associateWith { c ->
            (0 until n).filter { segCluster[it] == c }
                .minOfOrNull { segments[it].startSec } ?: Float.MAX_VALUE
        }
        val clusterToSpeaker = activeIdx
            .sortedBy { clusterFirstTime[it] }
            .withIndex()
            .associate { (idx, c) -> c to idx }

        return IntArray(n) { clusterToSpeaker[segCluster[it]]!! }
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-8f) 1f else 1f - dot / denom
    }

    private fun l2Norm(v: FloatArray): Float {
        var s = 0f; for (x in v) s += x * x; return sqrt(s)
    }
}

/** 오프라인 AHC에 사용할 세그먼트 단위 임베딩 */
data class SegmentEntry(
    val startSec: Float,
    val endSec: Float,
    val embedding: FloatArray,
)
