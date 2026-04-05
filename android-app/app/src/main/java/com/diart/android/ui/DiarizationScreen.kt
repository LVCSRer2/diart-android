package com.diart.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diart.android.DiarizationViewModel
import com.diart.android.pipeline.SpeakerTurn
import com.diart.android.ui.theme.speakerColor
import kotlinx.coroutines.launch

@Composable
fun DiarizationScreen(vm: DiarizationViewModel) {
    val isRunning by vm.isRunning.collectAsState()
    val isModelLoaded by vm.isModelLoaded.collectAsState()
    val statusMessage by vm.statusMessage.collectAsState()
    val recentTurns by vm.recentTurns.collectAsState()
    val activeSpeaker by vm.activeSpeaker.collectAsState()
    val processedSec by vm.processedSec.collectAsState()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 새 발화가 추가되면 자동 스크롤
    LaunchedEffect(recentTurns.size) {
        if (recentTurns.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(recentTurns.size - 1)
            }
        }
    }

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
            Text(
                text = "Diart 화자 분리",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.openSettings() }) {
                Text("설정 ⚙", fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(4.dp))

        // 상태 메시지
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // 활성 화자 표시
        ActiveSpeakerCard(activeSpeaker, isRunning)
        Spacer(Modifier.height(12.dp))

        // 통계 바
        if (processedSec > 0f) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val totalSpeakers = recentTurns.map { it.speakerId }.toSet().size
                StatChip("${processedSec.toInt()}초 처리")
                StatChip("$totalSpeakers 명 감지")
                StatChip("${recentTurns.size} 구간")
            }
            Spacer(Modifier.height(12.dp))
        }

        // 타임라인 플롯
        DiarizationPlot(
            turns = recentTurns,
            processedSec = processedSec,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        // 발화 타임라인
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (recentTurns.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isModelLoaded) "녹음을 시작하면 여기에 발화 구간이 표시됩니다."
                                   else "모델 로딩 중...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(recentTurns) { turn ->
                    TurnRow(turn)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 시작/중지 버튼
        Button(
            onClick = {
                if (isRunning) vm.stopDiarization()
                else vm.startDiarization()
            },
            enabled = isModelLoaded,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error
                                 else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                text = if (isRunning) "중지" else "화자 분리 시작",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ActiveSpeakerCard(speakerId: Int, isRunning: Boolean) {
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
            // 활성 인디케이터
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isRunning && speakerId >= 0) color else Color.Gray),
            )
            Text(
                text = if (!isRunning) "대기 중"
                       else if (speakerId >= 0) "현재 발화: 화자 ${speakerId + 1}"
                       else "발화 없음 (침묵)",
                style = MaterialTheme.typography.titleMedium,
                color = if (speakerId >= 0) color else Color.Gray,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TurnRow(turn: SpeakerTurn) {
    val color = speakerColor(turn.speakerId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 화자 색상 인디케이터
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = turn.label,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${formatTime(turn.startSec)} – ${formatTime(turn.endSec)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "%.1fs".format(turn.durationSec),
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun StatChip(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTime(sec: Float): String {
    val m = (sec / 60).toInt()
    val s = (sec % 60).toInt()
    val ms = ((sec % 1) * 10).toInt()
    return "%02d:%02d.%d".format(m, s, ms)
}
