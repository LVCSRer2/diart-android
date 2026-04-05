package com.diart.android

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.diart.android.audio.AudioCapturer
import com.diart.android.audio.SlidingWindowBuffer
import com.diart.android.audio.WavWriter
import com.diart.android.data.RecordingInfo
import com.diart.android.data.RecordingRepository
import com.diart.android.pipeline.DiarizationPipeline
import com.diart.android.pipeline.SegmentEntry
import com.diart.android.pipeline.SpeakerTurn
import com.diart.android.pipeline.remapTurnsWithAHC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections

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

    // ── 네비게이션 상태 ────────────────────────────────────────────────────

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings

    private val _showRecordingList = MutableStateFlow(false)
    val showRecordingList: StateFlow<Boolean> = _showRecordingList

    private val _showPlayback = MutableStateFlow(false)
    val showPlayback: StateFlow<Boolean> = _showPlayback

    // ── 재생 / 저장 목록 상태 ──────────────────────────────────────────────

    private val _playbackFile = MutableStateFlow<File?>(null)
    val playbackFile: StateFlow<File?> = _playbackFile

    private val _playbackTurns = MutableStateFlow<List<SpeakerTurn>>(emptyList())
    val playbackTurns: StateFlow<List<SpeakerTurn>> = _playbackTurns

    private val _refinedPlaybackTurns = MutableStateFlow<List<SpeakerTurn>?>(null)
    val refinedPlaybackTurns: StateFlow<List<SpeakerTurn>?> = _refinedPlaybackTurns

    private val _playbackSegments = MutableStateFlow<List<SegmentEntry>>(emptyList())
    val playbackSegments: StateFlow<List<SegmentEntry>> = _playbackSegments

    private val _isReAnalyzing = MutableStateFlow(false)
    val isReAnalyzing: StateFlow<Boolean> = _isReAnalyzing

    private val _totalRecordedSec = MutableStateFlow(0f)
    val totalRecordedSec: StateFlow<Float> = _totalRecordedSec

    private val _recordings = MutableStateFlow<List<RecordingInfo>>(emptyList())
    val recordings: StateFlow<List<RecordingInfo>> = _recordings

    // ── 내부 컴포넌트 ──────────────────────────────────────────────────────

    private val context = application.applicationContext
    private val audioCapturer = AudioCapturer()
    private var pipeline: DiarizationPipeline? = null
    private var slidingWindow: SlidingWindowBuffer? = null

    private val chunkChannel = Channel<Pair<FloatArray, Float>>(capacity = Channel.CONFLATED)
    private var inferenceJob: Job? = null
    private var elapsedSec = 0f

    private val pcmBuffer = ByteArrayOutputStream()
    private val allTurns = Collections.synchronizedList(mutableListOf<SpeakerTurn>())

    init {
        loadModels()
        refreshRecordings()
    }

    // ── 모델 로드 ──────────────────────────────────────────────────────────

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

    // ── 화자 분리 시작 / 중지 ─────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startDiarization() {
        val p = pipeline ?: return
        _isRunning.value = true
        elapsedSec = 0f
        _processedSec.value = 0f
        _recentTurns.value = emptyList()
        _activeSpeaker.value = -1
        _statusMessage.value = "화자 분리 중..."
        _showPlayback.value = false
        p.reset()

        pcmBuffer.reset()
        allTurns.clear()

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
                        allTurns.addAll(result.turns)
                        _recentTurns.value = (_recentTurns.value + result.turns).takeLast(200)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Inference error", e)
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

        audioCapturer.start { frames ->
            val pcm = WavWriter.floatToPcm16(frames)
            synchronized(pcmBuffer) { pcmBuffer.write(pcm) }
            slidingWindow?.push(frames)
        }
    }

    fun stopDiarization() {
        audioCapturer.stop()
        slidingWindow?.reset()
        inferenceJob?.cancel()
        inferenceJob = null
        _isRunning.value = false
        _activeSpeaker.value = -1
        _statusMessage.value = "저장 중..."

        val totalSec = _processedSec.value
        val turns    = allTurns.toList()
        val segs     = pipeline?.collectedSegments ?: emptyList()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(100)
                val pcm = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }

                val info = RecordingRepository.save(context, pcm, turns, segs, totalSec, audioCapturer.sampleRate)
                Log.d(TAG, "Saved: ${info.id}, ${pcm.size / 1024}KB, ${turns.size} turns, ${segs.size} segments")

                _recordings.value = RecordingRepository.loadAll(context)
                _playbackFile.value = info.wavFile
                _playbackTurns.value = turns
                _playbackSegments.value = segs
                _totalRecordedSec.value = totalSec

                // 초기 AHC 분석
                if (segs.size >= 2) {
                    _statusMessage.value = "정밀 분석 중..."
                    _refinedPlaybackTurns.value = remapTurnsWithAHC(_settings.value.ahcThreshold, segs, turns)
                } else {
                    _refinedPlaybackTurns.value = null
                }
                _statusMessage.value = "저장 완료"
                _showPlayback.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
                _statusMessage.value = "저장 실패: ${e.message}"
            }
        }
    }

    // ── AHC 재분석 ────────────────────────────────────────────────────────

    fun reAnalyze(threshold: Float) {
        val segs  = _playbackSegments.value
        val turns = _playbackTurns.value
        if (segs.size < 2) return
        viewModelScope.launch(Dispatchers.Default) {
            _isReAnalyzing.value = true
            _refinedPlaybackTurns.value = remapTurnsWithAHC(threshold, segs, turns)
            _isReAnalyzing.value = false
        }
    }

    // ── 설정 ──────────────────────────────────────────────────────────────

    fun openSettings() { _showSettings.value = true }
    fun closeSettings() { _showSettings.value = false }

    fun applySettings(s: SettingsState) {
        _settings.value = s
        pipeline?.apply {
            tauActive   = s.tauActive
            deltaNow    = s.deltaNow
            rhoUpdate   = s.rhoUpdate
            gamma       = s.gamma
            beta        = s.beta
            maxSpeakers = s.maxSpeakers
        }
        _showSettings.value = false
    }

    // ── 재생 ──────────────────────────────────────────────────────────────

    fun closePlayback() { _showPlayback.value = false }

    fun playRecording(info: RecordingInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val turns = RecordingRepository.loadTurns(info.turnsFile)
            val segs  = info.segmentsFile?.let { RecordingRepository.loadSegments(it) } ?: emptyList()
            _playbackFile.value = info.wavFile
            _playbackTurns.value = turns
            _playbackSegments.value = segs
            _totalRecordedSec.value = info.durationSec
            _refinedPlaybackTurns.value = if (segs.size >= 2) {
                remapTurnsWithAHC(_settings.value.ahcThreshold, segs, turns)
            } else null
            _showPlayback.value = true
        }
    }

    // ── 저장 목록 ─────────────────────────────────────────────────────────

    fun openRecordingList() { _showRecordingList.value = true }
    fun closeRecordingList() { _showRecordingList.value = false }

    fun deleteRecording(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            RecordingRepository.delete(context, id)
            _recordings.value = RecordingRepository.loadAll(context)
        }
    }

    private fun refreshRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            _recordings.value = RecordingRepository.loadAll(context)
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioCapturer.stop()
        pipeline?.close()
    }
}
