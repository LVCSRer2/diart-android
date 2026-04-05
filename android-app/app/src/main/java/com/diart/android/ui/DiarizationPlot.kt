package com.diart.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diart.android.pipeline.SpeakerTurn
import com.diart.android.ui.theme.speakerColor
import kotlin.math.max

/**
 * 화자 분리 결과를 간트 차트(타임라인 플롯)로 표시합니다.
 *
 * - 화면에는 항상 [windowSec]초 구간만 표시
 * - 전체 녹음이 길면 가로 스크롤로 탐색
 * - [currentPositionSec] >= 0이면 재생 커서를 표시하고 자동 추적
 */
@Composable
fun DiarizationPlot(
    turns: List<SpeakerTurn>,
    processedSec: Float,
    modifier: Modifier = Modifier,
    windowSec: Float = 30f,
    currentPositionSec: Float = -1f,
    onSeek: ((Float) -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelColor = Color(0xFF9E9E9E)
    val gridColor = Color(0xFF2E2E2E)
    val rowHeight = 28.dp
    val labelWidth = 52.dp
    val axisHeight = 20.dp

    val speakerIds = turns.map { it.speakerId }.toSortedSet().toList()
    val numSpeakers = max(1, speakerIds.size)
    val totalHeight = rowHeight * numSpeakers + axisHeight
    val totalDuration = max(processedSec, windowSec)

    val scrollState = rememberScrollState()

    // 자동 스크롤: 라이브 뷰에서만 최신 구간이 보이도록 스크롤
    if (currentPositionSec < 0f) {
        LaunchedEffect(processedSec) {
            val maxScroll = scrollState.maxValue
            if (maxScroll > 0 && totalDuration > windowSec) {
                val fraction = ((processedSec - windowSec / 2f) / (totalDuration - windowSec))
                    .coerceIn(0f, 1f)
                scrollState.scrollTo((fraction * maxScroll).toInt())
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = "타임라인",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // 전체 캔버스 너비 = windowSec 1개 = 화면 1폭
            val canvasWidthDp = maxWidth * (totalDuration / windowSec)

            Box(modifier = Modifier.horizontalScroll(scrollState)) {
                // onSeek: 탭(짧은 터치)으로만 커서 이동, 드래그는 horizontalScroll에 위임
                val labelWidthPx = with(density) { labelWidth.toPx() }
                val seekModifier = if (onSeek != null) {
                    Modifier.pointerInput(totalDuration) {
                        detectTapGestures { offset ->
                            val plotPx = size.width - labelWidthPx
                            val timeSec = ((offset.x - labelWidthPx) / plotPx * totalDuration)
                                .coerceIn(0f, totalDuration)
                            onSeek(timeSec)
                        }
                    }
                } else Modifier

                Canvas(
                    modifier = Modifier
                        .width(canvasWidthDp)
                        .fillMaxHeight()
                        .then(seekModifier),
                ) {
                    val canvasW = size.width
                    val canvasH = size.height
                    val rowH = (canvasH - axisHeight.toPx()) / numSpeakers
                    val labelW = labelWidth.toPx()
                    val plotW = canvasW - labelW
                    val pxPerSec = plotW / totalDuration

                    fun timeToX(sec: Float) = labelW + sec * pxPerSec

                    // ── 그리드 및 시간 축 ──────────────────────────────────
                    val tickInterval = when {
                        totalDuration <= 60f  -> 5f
                        totalDuration <= 300f -> 10f
                        else                  -> 30f
                    }
                    var t = 0f
                    while (t <= totalDuration + 0.01f) {
                        val x = timeToX(t)
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, canvasH - axisHeight.toPx()),
                            strokeWidth = 1f,
                        )
                        val label = formatTimeSec(t)
                        val measured = textMeasurer.measure(
                            text = label,
                            style = TextStyle(
                                fontSize = 9.sp,
                                color = labelColor,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(
                                x - measured.size.width / 2f,
                                canvasH - axisHeight.toPx() + 4f,
                            ),
                        )
                        t += tickInterval
                    }

                    // 축 구분선
                    drawLine(
                        color = gridColor,
                        start = Offset(labelW, canvasH - axisHeight.toPx()),
                        end = Offset(canvasW, canvasH - axisHeight.toPx()),
                        strokeWidth = 1f,
                    )

                    // ── 화자 행 ───────────────────────────────────────────
                    speakerIds.forEachIndexed { rowIdx, speakerId ->
                        val rowTop = rowIdx * rowH
                        val barTop = rowTop + rowH * 0.15f
                        val barH = rowH * 0.7f
                        val color = speakerColor(speakerId)

                        val labelText = "화자 ${speakerId + 1}"
                        val measured = textMeasurer.measure(
                            text = labelText,
                            style = TextStyle(fontSize = 9.sp, color = color),
                        )
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(4f, rowTop + (rowH - measured.size.height) / 2f),
                        )

                        turns
                            .filter { it.speakerId == speakerId }
                            .forEach { turn ->
                                val xStart = timeToX(turn.startSec).coerceIn(labelW, canvasW)
                                val xEnd = timeToX(turn.endSec).coerceIn(labelW, canvasW)
                                if (xEnd > xStart) {
                                    drawRect(
                                        color = color.copy(alpha = 0.85f),
                                        topLeft = Offset(xStart, barTop),
                                        size = Size(xEnd - xStart, barH),
                                    )
                                }
                            }
                    }

                    // ── 재생 커서 ─────────────────────────────────────────
                    if (currentPositionSec >= 0f) {
                        val cx = timeToX(currentPositionSec).coerceIn(labelW, canvasW)
                        drawLine(
                            color = Color.White.copy(alpha = 0.9f),
                            start = Offset(cx, 0f),
                            end = Offset(cx, canvasH - axisHeight.toPx()),
                            strokeWidth = 2f,
                        )
                    }

                    // ── 화자 없을 때 안내 ──────────────────────────────────
                    if (speakerIds.isEmpty()) {
                        val msg = "발화 감지 대기 중..."
                        val measured = textMeasurer.measure(
                            text = msg,
                            style = TextStyle(fontSize = 11.sp, color = labelColor),
                        )
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(
                                labelW + (plotW - measured.size.width) / 2f,
                                (canvasH - axisHeight.toPx() - measured.size.height) / 2f,
                            ),
                        )
                    }
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
