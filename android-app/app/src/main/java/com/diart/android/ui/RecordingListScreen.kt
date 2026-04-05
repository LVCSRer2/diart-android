package com.diart.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diart.android.data.RecordingInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingListScreen(
    recordings: List<RecordingInfo>,
    onPlay: (RecordingInfo) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var deleteTargetId by remember { mutableStateOf<String?>(null) }

    // 삭제 확인 다이얼로그
    deleteTargetId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text("녹음 삭제") },
            text  = { Text("이 녹음을 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(id)
                    deleteTargetId = null
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) { Text("취소") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        // 헤더
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 뒤로", fontSize = 14.sp) }
            Spacer(Modifier.weight(1f))
            Text(
                text = "저장된 녹음",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "저장된 녹음이 없습니다.\n화자 분리 후 중지하면 자동으로 저장됩니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(recordings, key = { it.id }) { info ->
                    RecordingItem(
                        info = info,
                        onPlay = { onPlay(info) },
                        onDelete = { deleteTargetId = info.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingItem(
    info: RecordingInfo,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormatter = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateFormatter.format(Date(info.createdAt)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBadge(formatDuration(info.durationSec))
                    InfoBadge("${info.speakerCount}명 감지")
                    InfoBadge("${info.turnCount}구간")
                }
            }

            Spacer(Modifier.width(8.dp))

            // 재생 버튼
            FilledTonalButton(
                onClick = onPlay,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("▶ 재생", fontSize = 13.sp)
            }

            Spacer(Modifier.width(6.dp))

            // 삭제 버튼
            OutlinedButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                ),
            ) {
                Text("삭제", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun InfoBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDuration(sec: Float): String {
    val m = (sec / 60).toInt()
    val s = (sec % 60).toInt()
    return if (m > 0) "${m}분 ${s}초" else "${s}초"
}
