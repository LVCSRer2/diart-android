package com.diart.android.audio

/**
 * 슬라이딩 윈도우 오디오 버퍼.
 *
 * diart와 동일한 파라미터:
 *  - window   = 5초 = 80,000 샘플 @ 16kHz
 *  - step     = 0.5초 = 8,000 샘플 @ 16kHz
 *
 * 버퍼가 가득 차면 [onChunkReady]를 호출하고,
 * step만큼 앞으로 슬라이드합니다.
 */
class SlidingWindowBuffer(
    sampleRate: Int = 16000,
    windowSec: Float = 5.0f,
    stepSec: Float = 0.5f,
    private val onChunkReady: (FloatArray) -> Unit,
) {
    val windowSamples: Int = (sampleRate * windowSec).toInt()  // 80,000
    val stepSamples: Int = (sampleRate * stepSec).toInt()       // 8,000

    // 순환 버퍼: 항상 최신 windowSamples 개를 유지
    private val buffer = FloatArray(windowSamples)
    private var filled = 0   // 현재 채워진 샘플 수
    private var accumulator = 0  // step 카운터

    /**
     * 새 오디오 프레임을 추가합니다.
     * 내부적으로 windowSamples가 모이면 콜백을 트리거합니다.
     */
    fun push(samples: FloatArray) {
        for (s in samples) {
            if (filled < windowSamples) {
                buffer[filled++] = s
            } else {
                // 버퍼가 가득 찬 경우: 가장 오래된 샘플을 밀어냄 (ring shift)
                System.arraycopy(buffer, 1, buffer, 0, windowSamples - 1)
                buffer[windowSamples - 1] = s
            }
            accumulator++

            // step마다 콜백 (버퍼가 충분히 찬 경우에만)
            if (accumulator >= stepSamples && filled == windowSamples) {
                accumulator = 0
                onChunkReady(buffer.copyOf())
            }
        }
    }

    fun reset() {
        buffer.fill(0f)
        filled = 0
        accumulator = 0
    }
}
