package com.diart.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 화자별 색상 팔레트 (최대 10명)
val SpeakerColors = listOf(
    Color(0xFF4FC3F7), // 연파랑
    Color(0xFF81C784), // 연초록
    Color(0xFFFFB74D), // 주황
    Color(0xFFE57373), // 빨강
    Color(0xFFBA68C8), // 보라
    Color(0xFF4DD0E1), // 청록
    Color(0xFFFFF176), // 노랑
    Color(0xFFFF8A65), // 연주황
    Color(0xFF90A4AE), // 회청
    Color(0xFFA5D6A7), // 민트
)

fun speakerColor(speakerId: Int): Color =
    SpeakerColors[speakerId % SpeakerColors.size]

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    secondary = Color(0xFF81C784),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
)

@Composable
fun DiartTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
