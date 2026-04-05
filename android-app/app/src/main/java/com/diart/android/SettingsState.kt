package com.diart.android

data class SettingsState(
    /** 활성 화자 감지 임계값 (낮을수록 민감) */
    val tauActive: Float = 0.4f,
    /** 새 화자 등록 코사인 거리 임계값 (낮을수록 화자 분리 잘 됨) */
    val deltaNow: Float = 0.40f,
    /** 센트로이드 EMA 업데이트 비율 (낮을수록 안정적) */
    val rhoUpdate: Float = 0.10f,
    /** 겹침 발화 패널티 gamma */
    val gamma: Float = 3f,
    /** 겹침 발화 패널티 beta */
    val beta: Float = 10f,
    /** 최대 추적 화자 수 */
    val maxSpeakers: Int = 20,
    /** AHC 오프라인 분석 합병 임계값 (cosine distance, 낮을수록 화자 구분 세밀) */
    val ahcThreshold: Float = 0.35f,
)
