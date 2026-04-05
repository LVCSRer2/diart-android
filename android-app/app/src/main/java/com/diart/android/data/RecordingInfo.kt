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
)
