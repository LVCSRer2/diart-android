package com.diart.android

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.diart.android.audio.AudioCapturer
import com.diart.android.audio.SlidingWindowBuffer
import com.diart.android.pipeline.DiarizationPipeline
import com.diart.android.pipeline.SpeakerTurn
import com.diart.android.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "DiartVM"

class DiarizationViewModel(application: Application) : AndroidViewModel(application) {

    // ── 상태 ───────────────────────────────────────────────────────────────

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded

    private val _statusMessage = MutableStateFlow("모델 로딩 중...")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _recentTurns = MutableStateFlow<List<SpeakerTurn>>(emptyList())
    val recentTurns: StateFlow<List<SpeakerTurn>> = _recentTurns

    private val _activeSpeaker = MutableStateFlow(-1)
    val activeSpeaker: StateFlow<Int> = _activeSpeaker

    private val _processedSec = MutableStateFlow(0f)
    val processedSec: StateFlow<Float> = _processedSec

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings

    // ── 내부 컴포넌트 ──────────────────────────────────────────────────────

    private val context = application.applicationContext
    private val audioCapturer = AudioCapturer()
    private var pipeline: DiarizationPipeline? = null
    private var slidingWindow: SlidingWindowBuffer? = null

    // 청크 큐: capacity=1 → 처리 중이면 이전 청크를 버리고 최신 청크만 유지
    private val chunkChannel = Channel<Pair<FloatArray, Float>>(capacity = Channel.CONFLATED)
    private var inferenceJob: Job? = null

    private var elapsedSec = 0f

    init {
        loadModels()
    }

    private fun loadModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pipeline = DiarizationPipeline(context)
                _isModelLoaded.value = true
                _statusMessage.value = "준비 완료. 녹음 버튼을 누르세요."
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed", e)
                _statusMessage.value = "모델 로드 실패: ${e.message}"
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startDiarization() {
        val p = pipeline ?: run {
            Log.e(TAG, "startDiarization called but pipeline is null!")
            return
        }
        _isRunning.value = true
        elapsedSec = 0f
        _processedSec.value = 0f
        _recentTurns.value = emptyList()
        _activeSpeaker.value = -1
        _statusMessage.value = "화자 분리 중..."
        p.reset()

        // 단일 추론 코루틴: 순차 처리로 ONNX 경합 방지
        inferenceJob = viewModelScope.launch(Dispatchers.Default) {
            for ((waveform, chunkStart) in chunkChannel) {
                try {
                    val t0 = System.currentTimeMillis()
                    val result = p.process(waveform, chunkStart)
                    val elapsed = System.currentTimeMillis() - t0

                    _processedSec.value = chunkStart + 5f
                    Log.d(TAG, "chunk=${chunkStart}s done in ${elapsed}ms  turns=${result.turns.size}")

                    val latestTurn = result.turns.maxByOrNull { it.endSec }
                    _activeSpeaker.value = latestTurn?.speakerId ?: -1

                    if (result.turns.isNotEmpty()) {
                        _recentTurns.value = (_recentTurns.value + result.turns).takeLast(200)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Inference error at chunk=${chunkChannel}", e)
                    _statusMessage.value = "추론 오류: ${e.message}"
                }
            }
        }

        slidingWindow = SlidingWindowBuffer(
            sampleRate = audioCapturer.sampleRate,
            onChunkReady = { waveform ->
                val chunkStart = elapsedSec
                elapsedSec += 0.5f
                chunkChannel.trySend(waveform to chunkStart)
            }
        )

        Log.d(TAG, "Starting audio capture")
        audioCapturer.start { frames ->
            slidingWindow?.push(frames)
        }
        Log.d(TAG, "Audio capture started")
    }

    fun openSettings() { _showSettings.value = true }
    fun closeSettings() { _showSettings.value = false }

    fun applySettings(s: SettingsState) {
        _settings.value = s
        pipeline?.apply {
            tauActive = s.tauActive
            deltaNow = s.deltaNow
            rhoUpdate = s.rhoUpdate
            gamma = s.gamma
            beta = s.beta
            maxSpeakers = s.maxSpeakers
        }
        _showSettings.value = false
    }

    fun stopDiarization() {
        audioCapturer.stop()
        slidingWindow?.reset()
        inferenceJob?.cancel()
        inferenceJob = null
        _isRunning.value = false
        _activeSpeaker.value = -1
        _statusMessage.value = "중지됨. 총 ${_processedSec.value.toInt()}초 처리."
    }

    override fun onCleared() {
        super.onCleared()
        audioCapturer.stop()
        pipeline?.close()
    }
}
