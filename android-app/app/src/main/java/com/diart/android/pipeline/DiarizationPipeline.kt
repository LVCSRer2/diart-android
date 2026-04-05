package com.diart.android.pipeline

import android.content.Context
import android.util.Log
import com.diart.android.model.EmbeddingModel
import com.diart.android.model.SegmentationModel

private const val TAG = "DiartPipeline"

/**
 * diart SpeakerDiarization 파이프라인의 Android/ONNX 구현.
 *
 * 처리 흐름 (5초 청크마다):
 *  1. SegmentationModel  → [frames][speakers] 활성 확률
 *  2. OverlappedSpeechPenalty → 화자별 가중치
 *  3. EmbeddingModel (화자별) → [speakers][256] 임베딩
 *  4. OnlineSpeakerClustering → 로컬 화자 ID를 전역 화자 ID로 매핑
 *  5. 결과: [DiarizationResult] 반환
 */
class DiarizationPipeline(
    context: Context,
    tauActive: Float = 0.4f,
    rhoUpdate: Float = 0.10f,
    deltaNow: Float = 0.40f,
    var gamma: Float = 3f,
    var beta: Float = 10f,
) : AutoCloseable {

    private val segmentationModel = SegmentationModel(context)
    private val embeddingModel = EmbeddingModel(context)
    private val clustering = OnlineSpeakerClustering(
        tauActive = tauActive,
        rhoUpdate = rhoUpdate,
        deltaNow = deltaNow,
    )

    var tauActive: Float
        get() = clustering.tauActive
        set(v) { clustering.tauActive = v }

    var rhoUpdate: Float
        get() = clustering.rhoUpdate
        set(v) { clustering.rhoUpdate = v }

    var deltaNow: Float
        get() = clustering.deltaNow
        set(v) { clustering.deltaNow = v }

    var maxSpeakers: Int
        get() = clustering.maxSpeakers
        set(v) { clustering.maxSpeakers = v }

    /**
     * 5초 오디오 청크를 처리하고 화자 분리 결과를 반환합니다.
     *
     * @param waveform 80,000샘플 float32 배열
     * @param chunkStartSec 이 청크의 시작 시간 (초)
     * @return [DiarizationResult] – 화자별 발화 구간 정보
     */
    fun process(waveform: FloatArray, chunkStartSec: Float): DiarizationResult {
        // 1. 화자 분리 (segmentation)
        val segmentation = segmentationModel.segment(waveform)
        val numFrames = segmentation.size
        val numLocalSpeakers = if (numFrames > 0) segmentation[0].size else 0

        if (numFrames == 0 || numLocalSpeakers == 0) {
            return DiarizationResult(chunkStartSec, emptyList())
        }

        // 2. 겹침 발화 패널티 적용 → 화자별 임베딩 가중치
        val penalizedWeights = OverlappedSpeechPenalty.apply(segmentation, gamma, beta)

        // 3. 화자별 평균 활성도 계산
        val activityProbs = FloatArray(numLocalSpeakers) { s ->
            segmentation.map { it[s] }.average().toFloat()
        }

        Log.d(TAG, "chunk=${chunkStartSec}s  activity=${activityProbs.map { "%.2f".format(it) }}")

        // 4. 활성 화자가 있을 때만 mel 특징 추출 (warmup 조건부 실행)
        val hasActiveSpeaker = activityProbs.any { it >= tauActive }
        if (hasActiveSpeaker) {
            embeddingModel.warmup(waveform)
        }

        // 5. 활성 화자만 임베딩 추출
        val embeddings = Array(numLocalSpeakers) { s ->
            if (activityProbs[s] >= tauActive) {
                val weights = FloatArray(numFrames) { t -> penalizedWeights[t][s] }
                embeddingModel.extractForSpeaker(weights)
            } else {
                FloatArray(256)
            }
        }

        // 6. 클러스터링으로 전역 화자 ID 배정
        val globalIds = clustering.assign(embeddings, activityProbs)
        Log.d(TAG, "globalIds=${globalIds.toList()}  totalSpeakers=${clustering.speakerIds.size}")

        // 7. 프레임별 활성 화자 → 발화 구간으로 변환
        val secPerFrame = 5.0f / numFrames
        val speakerTurns = buildSpeakerTurns(
            segmentation, globalIds, chunkStartSec, secPerFrame, tauActive
        )

        // 8. AHC용 세그먼트 수집 (COLLECT_INTERVAL 청크마다 1회)
        chunkCount++
        if (chunkCount % COLLECT_INTERVAL == 0) {
            for (localIdx in 0 until numLocalSpeakers) {
                if (activityProbs[localIdx] < tauActive) continue
                val emb = embeddings[localIdx]
                if (emb.any { it != 0f }) {
                    _collectedSegments.add(
                        SegmentEntry(chunkStartSec, chunkStartSec + 5f, emb.copyOf())
                    )
                }
            }
        }

        return DiarizationResult(chunkStartSec, speakerTurns)
    }

    /**
     * 프레임 단위 세그멘테이션 → 시간 구간(speaker turn) 목록으로 변환
     */
    private fun buildSpeakerTurns(
        segmentation: Array<FloatArray>,
        globalIds: IntArray,
        chunkStartSec: Float,
        secPerFrame: Float,
        threshold: Float,
    ): List<SpeakerTurn> {
        val turns = mutableListOf<SpeakerTurn>()
        val numFrames = segmentation.size
        val numLocalSpeakers = globalIds.size

        for (localIdx in 0 until numLocalSpeakers) {
            val globalId = globalIds[localIdx]
            if (globalId < 0) continue

            var segStart = -1f
            for (t in 0 until numFrames) {
                val active = segmentation[t][localIdx] >= threshold
                val timeSec = chunkStartSec + t * secPerFrame
                if (active && segStart < 0f) {
                    segStart = timeSec
                } else if (!active && segStart >= 0f) {
                    turns.add(SpeakerTurn(globalId, segStart, timeSec))
                    segStart = -1f
                }
            }
            if (segStart >= 0f) {
                turns.add(SpeakerTurn(globalId, segStart, chunkStartSec + numFrames * secPerFrame))
            }
        }

        return turns.sortedBy { it.startSec }
    }

    // ── 오프라인 AHC ─────────────────────────────────────────────────────

    private val _collectedSegments = mutableListOf<SegmentEntry>()
    private var chunkCount = 0
    private val COLLECT_INTERVAL = 4  // 0.5s × 4 = 2초마다 1회 수집

    val collectedSegments: List<SegmentEntry> get() = _collectedSegments.toList()
    val collectedSegmentCount: Int get() = _collectedSegments.size

    fun reset() {
        clustering.reset()
        _collectedSegments.clear()
        chunkCount = 0
    }

    override fun close() {
        segmentationModel.close()
        embeddingModel.close()
    }
}

/** 화자 한 명의 발화 구간 */
data class SpeakerTurn(
    val speakerId: Int,
    val startSec: Float,
    val endSec: Float,
) {
    val durationSec: Float get() = endSec - startSec
    val label: String get() = "화자 ${speakerId + 1}"
}

/** 한 청크의 처리 결과 */
data class DiarizationResult(
    val chunkStartSec: Float,
    val turns: List<SpeakerTurn>,
)
