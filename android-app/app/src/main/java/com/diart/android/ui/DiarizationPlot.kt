package com.diart.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diart.android.pipeline.SpeakerTurn
import com.diart.android.ui.theme.speakerColor
import kotlin.math.ceil
import kotlin.math.max

/**
 * 화자 분리 결과를 간트 차트(타임라인 플롯)로 표시합니다.
 *
 * - 각 행: 화자 한 명
 * - X축: 시간(초)
 * - 색상 블록: 해당 화자의 발화 구간
 * - 최근 [windowSec]초 구간을 보여줌 (처리 시간이 윈도우를 초과하면 스크롤)
 */
@Composable
fun DiarizationPlot(
    turns: List<SpeakerTurn>,
    processedSec: Float,
    modifier: Modifier = Modifier,
    windowSec: Float = 30f,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelColor = Color(0xFF9E9E9E)
    val gridColor = Color(0xFF2E2E2E)
    val rowHeight = 28.dp
    val labelWidth = 52.dp
    val axisHeight = 20.dp

    // 등장한 화자 목록 (ID 순 정렬)
    val speakerIds = turns.map { it.speakerId }.toSortedSet().toList()
    val numSpeakers = max(1, speakerIds.size)

    val totalHeight = rowHeight * numSpeakers + axisHeight

    Column(modifier = modifier) {
        Text(
            text = "타임라인",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasW = size.width
                val canvasH = size.height
                val rowH = (canvasH - axisHeight.toPx()) / numSpeakers
                val plotW = canvasW - labelWidth.toPx()

                // 표시 시간 범위
                val endTime = max(processedSec, windowSec)
                val startTime = max(0f, endTime - windowSec)

                fun timeToX(sec: Float): Float =
                    labelWidth.toPx() + (sec - startTime) / windowSec * plotW

                // ── 그리드 및 시간 축 ──────────────────────────────────────
                val tickInterval = if (windowSec <= 30f) 5f else 10f
                val firstTick = ceil(startTime / tickInterval) * tickInterval
                var t = firstTick
                while (t <= endTime + 0.01f) {
                    val x = timeToX(t)
                    // 그리드 선
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, canvasH - axisHeight.toPx()),
                        strokeWidth = 1f,
                    )
                    // 시간 레이블
                    val label = formatTimeSec(t)
                    val measured = textMeasurer.measure(
                        text = label,
                        style = TextStyle(fontSize = 9.sp, color = labelColor, fontFamily = FontFamily.Monospace),
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(x - measured.size.width / 2f, canvasH - axisHeight.toPx() + 4f),
                    )
                    t += tickInterval
                }

                // 축 구분선
                drawLine(
                    color = gridColor,
                    start = Offset(labelWidth.toPx(), canvasH - axisHeight.toPx()),
                    end = Offset(canvasW, canvasH - axisHeight.toPx()),
                    strokeWidth = 1f,
                )

                // ── 화자 행 ───────────────────────────────────────────────
                speakerIds.forEachIndexed { rowIdx, speakerId ->
                    val rowTop = rowIdx * rowH
                    val barTop = rowTop + rowH * 0.15f
                    val barH = rowH * 0.7f
                    val color = speakerColor(speakerId)

                    // 화자 레이블
                    val label = "화자 ${speakerId + 1}"
                    val measured = textMeasurer.measure(
                        text = label,
                        style = TextStyle(fontSize = 9.sp, color = color, fontFamily = FontFamily.Default),
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(4f, rowTop + (rowH - measured.size.height) / 2f),
                    )

                    // 발화 블록 그리기
                    turns
                        .filter { it.speakerId == speakerId }
                        .forEach { turn ->
                            val xStart = timeToX(turn.startSec).coerceIn(labelWidth.toPx(), canvasW)
                            val xEnd = timeToX(turn.endSec).coerceIn(labelWidth.toPx(), canvasW)
                            if (xEnd > xStart) {
                                drawRect(
                                    color = color.copy(alpha = 0.85f),
                                    topLeft = Offset(xStart, barTop),
                                    size = Size(xEnd - xStart, barH),
                                )
                            }
                        }
                }

                // 화자가 없을 때 안내 텍스트
                if (speakerIds.isEmpty()) {
                    val msg = "발화 감지 대기 중..."
                    val measured = textMeasurer.measure(
                        text = msg,
                        style = TextStyle(fontSize = 11.sp, color = labelColor),
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            labelWidth.toPx() + (plotW - measured.size.width) / 2f,
                            (canvasH - axisHeight.toPx() - measured.size.height) / 2f,
                        ),
                    )
                }
            }
        }
    }
}

private fun formatTimeSec(sec: Float): String {
    val m = (sec / 60).toInt()
    val s = (sec % 60).toInt()
    return if (m > 0) "${m}m${"%02d".format(s)}" else "${s}s"
}
