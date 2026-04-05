package com.diart.android.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diart.android.pipeline.SegmentEntry
import com.diart.android.pipeline.SpeakerTurn
import com.diart.android.ui.theme.speakerColor
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max

@Composable
fun PlaybackScreen(
    audioFile: File,
    turns: List<SpeakerTurn>,
    refinedTurns: List<SpeakerTurn>?,
    segments: List<SegmentEntry>,
    isReAnalyzing: Boolean,
    initialAhcThreshold: Float,
    onReAnalyze: (Float) -> Unit,
    totalDurationSec: Float,
    onBack: () -> Unit,
) {
    val mediaPlayer = remember { MediaPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionSec by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(max(totalDurationSec, 1f)) }
    var prepared by remember { mutableStateOf(false) }

    // AHC threshold 슬라이더 상태
    var ahcThreshold by remember(initialAhcThreshold) { mutableFloatStateOf(initialAhcThreshold) }

    // 표시할 turns: 정밀 결과 우선
    val displayTurns = refinedTurns ?: turns

    // MediaPlayer 초기화
    DisposableEffect(audioFile) {
        try {
            mediaPlayer.setDataSource(audioFile.absolutePath)
            mediaPlayer.prepare()
            duration = mediaPlayer.duration / 1000f
            prepared = true
        } catch (e: Exception) {
            prepared = false
        }
        mediaPlayer.setOnCompletionListener { isPlaying = false }
        onDispose { mediaPlayer.release() }
    }

    // 재생 위치 폴링
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPositionSec = mediaPlayer.currentPosition / 1000f
            delay(80)
        }
    }

    // 현재 위치에서 활성 화자
    val activeSpeaker = displayTurns
        .filter { it.startSec <= currentPositionSec && it.endSec >= currentPositionSec }
        .maxByOrNull { it.durationSec }
        ?.speakerId ?: -1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // 헤더
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                if (isPlaying) mediaPlayer.pause()
                onBack()
            }) {
                Text("← 뒤로", fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "녹음 재생",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // 현재 발화 화자 표시
        PlaybackSpeakerCard(activeSpeaker, isPlaying)
        Spacer(Modifier.height(12.dp))

        // 타임라인 플롯
        DiarizationPlot(
            turns = displayTurns,
            processedSec = duration,
            windowSec = 30f,
            currentPositionSec = currentPositionSec,
            onSeek = { timeSec ->
                currentPositionSec = timeSec
                mediaPlayer.seekTo((timeSec * 1000).toInt())
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Spacer(Modifier.height(8.dp))

        // AHC 재분석 슬라이더 (segments 있을 때만)
        if (segments.isNotEmpty()) {
            AhcSection(
                threshold = ahcThreshold,
                isAnalyzing = isReAnalyzing,
                onThresholdChange = { ahcThreshold = it },
                onReAnalyze = { onReAnalyze(ahcThreshold) },
            )
            Spacer(Modifier.height(8.dp))
        }

        // 시간 표시
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPlaybackTime(currentPositionSec),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatPlaybackTime(duration),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Seek 슬라이더
        Slider(
            value = currentPositionSec,
            onValueChange = { pos ->
                currentPositionSec = pos
                mediaPlayer.seekTo((pos * 1000).toInt())
            },
            valueRange = 0f..duration,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        // 재생 컨트롤
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = {
                    val pos = (currentPositionSec - 10f).coerceAtLeast(0f)
                    currentPositionSec = pos
                    mediaPlayer.seekTo((pos * 1000).toInt())
                },
                modifier = Modifier.size(48.dp),
            ) { Text("−10s", fontSize = 10.sp) }

            Spacer(Modifier.width(16.dp))

            Button(
                onClick = {
                    if (!prepared) return@Button
                    if (isPlaying) {
                        mediaPlayer.pause(); isPlaying = false
                    } else {
                        if (currentPositionSec >= duration - 0.1f) {
                            mediaPlayer.seekTo(0); currentPositionSec = 0f
                        }
                        mediaPlayer.start(); isPlaying = true
                    }
                },
                enabled = prepared,
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(text = if (isPlaying) "⏸" else "▶", fontSize = 24.sp)
            }

            Spacer(Modifier.width(16.dp))

            FilledTonalIconButton(
                onClick = {
                    val pos = (currentPositionSec + 10f).coerceAtMost(duration)
                    currentPositionSec = pos
                    mediaPlayer.seekTo((pos * 1000).toInt())
                },
                modifier = Modifier.size(48.dp),
            ) { Text("+10s", fontSize = 10.sp) }
        }

        Spacer(Modifier.height(12.dp))

        // 통계
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val speakerCount = displayTurns.map { it.speakerId }.toSet().size
            StatChip("${speakerCount}명 감지")
            StatChip("${displayTurns.size}개 구간")
            StatChip("총 ${duration.toInt()}초")
        }
    }
}

@Composable
private fun AhcSection(
    threshold: Float,
    isAnalyzing: Boolean,
    onThresholdChange: (Float) -> Unit,
    onReAnalyze: () -> Unit,   // 슬라이더에서 손을 뗄 때 호출
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "정밀 분석 (AHC)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "낮을수록 화자 구분이 세밀해짐",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "%.2f".format(threshold),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
                    if (isAnalyzing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp).padding(start = 8.dp), strokeWidth = 2.dp)
                }
            }
            Slider(
                value = threshold,
                onValueChange = onThresholdChange,
                onValueChangeFinished = onReAnalyze,
                valueRange = 0.1f..0.8f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PlaybackSpeakerCard(speakerId: Int, isPlaying: Boolean) {
    val color = if (speakerId >= 0) speakerColor(speakerId) else Color.Gray
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(modifier = Modifier.size(12.dp), shape = CircleShape,
                color = if (speakerId >= 0) color else Color.Gray) {}
            Text(
                text = if (speakerId >= 0) "발화 중: 화자 ${speakerId + 1}"
                       else if (isPlaying) "침묵 구간"
                       else "재생 대기",
                style = MaterialTheme.typography.titleMedium,
                color = if (speakerId >= 0) color else Color.Gray,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatPlaybackTime(sec: Float): String {
    val m = (sec / 60).toInt()
    val s = (sec % 60).toInt()
    return "%02d:%02d".format(m, s)
}
