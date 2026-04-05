package com.diart.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diart.android.SettingsState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    current: SettingsState,
    onApply: (SettingsState) -> Unit,
    onBack: () -> Unit,
) {
    var tauActive    by remember(current) { mutableFloatStateOf(current.tauActive) }
    var deltaNow     by remember(current) { mutableFloatStateOf(current.deltaNow) }
    var rhoUpdate    by remember(current) { mutableFloatStateOf(current.rhoUpdate) }
    var gamma        by remember(current) { mutableFloatStateOf(current.gamma) }
    var beta         by remember(current) { mutableFloatStateOf(current.beta) }
    var maxSpeakers  by remember(current) { mutableIntStateOf(current.maxSpeakers) }
    var ahcThreshold by remember(current) { mutableFloatStateOf(current.ahcThreshold) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // 헤더
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onBack) {
                Text("← 뒤로", fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "설정",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader("화자 감지")

            SliderRow(
                label = "활성 임계값 (tauActive)",
                description = "낮을수록 작은 소리도 화자로 감지",
                value = tauActive,
                onValueChange = { tauActive = it },
                valueRange = 0.1f..0.9f,
                displayText = "%.2f".format(tauActive),
            )

            SliderRow(
                label = "새 화자 등록 임계값 (deltaNow)",
                description = "낮을수록 화자 구분이 민감해짐",
                value = deltaNow,
                onValueChange = { deltaNow = it },
                valueRange = 0.10f..1.0f,
                displayText = "%.2f".format(deltaNow),
            )

            SliderRow(
                label = "센트로이드 업데이트 비율 (rhoUpdate)",
                description = "낮을수록 화자 특성이 안정적으로 유지됨",
                value = rhoUpdate,
                onValueChange = { rhoUpdate = it },
                valueRange = 0.01f..0.5f,
                displayText = "%.2f".format(rhoUpdate),
            )

            IntStepperRow(
                label = "최대 화자 수 (maxSpeakers)",
                description = "추적할 최대 화자 인원",
                value = maxSpeakers,
                onValueChange = { maxSpeakers = it },
                range = 2..20,
            )

            Spacer(Modifier.height(8.dp))
            SectionHeader("겹침 발화 패널티")

            SliderRow(
                label = "Gamma",
                description = "겹침 발화 억제 강도 (높을수록 강함)",
                value = gamma,
                onValueChange = { gamma = it },
                valueRange = 1f..10f,
                displayText = "%.1f".format(gamma),
            )

            SliderRow(
                label = "Beta",
                description = "겹침 발화 소프트맥스 온도",
                value = beta,
                onValueChange = { beta = it },
                valueRange = 1f..30f,
                displayText = "%.1f".format(beta),
            )

            Spacer(Modifier.height(8.dp))
            SectionHeader("정밀 분석 (오프라인 AHC)")

            SliderRow(
                label = "AHC 합병 임계값 (ahcThreshold)",
                description = "낮을수록 화자 구분이 세밀해짐 (코사인 거리)",
                value = ahcThreshold,
                onValueChange = { ahcThreshold = it },
                valueRange = 0.2f..0.9f,
                displayText = "%.2f".format(ahcThreshold),
            )

            Spacer(Modifier.height(8.dp))
            SectionHeader("기본값 참고")
            InfoCard(
                "tauActive=0.4  deltaNow=0.40  rhoUpdate=0.10\n" +
                "gamma=3.0  beta=10.0  maxSpeakers=20\n" +
                "ahcThreshold=0.50"
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                onApply(
                    SettingsState(
                        tauActive    = tauActive,
                        deltaNow     = deltaNow,
                        rhoUpdate    = rhoUpdate,
                        gamma        = gamma,
                        beta         = beta,
                        maxSpeakers  = maxSpeakers,
                        ahcThreshold = ahcThreshold,
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("적용", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayText: String,
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
                    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun IntStepperRow(
    label: String,
    description: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalIconButton(
                    onClick = { if (value > range.first) onValueChange(value - 1) },
                    enabled = value > range.first,
                ) { Text("−", fontSize = 18.sp) }

                Text(
                    text = "$value 명",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )

                FilledTonalIconButton(
                    onClick = { if (value < range.last) onValueChange(value + 1) },
                    enabled = value < range.last,
                ) { Text("+", fontSize = 18.sp) }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(10.dp),
        )
    }
}
