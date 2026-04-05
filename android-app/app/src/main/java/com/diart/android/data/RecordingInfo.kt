package com.diart.android.data

import java.io.File

data class RecordingInfo(
    val id: String,
    val createdAt: Long,        // epoch ms
    val durationSec: Float,
    val speakerCount: Int,
    val turnCount: Int,
    val wavFile: File,
    val turnsFile: File,
    val segmentsFile: File?,  // AHC 재분석용 임베딩 (없으면 null)
)
