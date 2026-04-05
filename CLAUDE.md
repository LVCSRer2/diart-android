# CLAUDE.md — diart-android

## 프로젝트 개요

diart(Python 실시간 화자 분리 프레임워크)의 Android 포팅.
ONNX Runtime Mobile을 사용한 완전 온디바이스 추론 — 서버 불필요.

## 빌드 & 설치

```bash
# Android SDK 위치
export ANDROID_HOME=/home/jieunstage/android-sdk

# 빌드에 번들 JDK 필수 (시스템 JRE만 설치되어 있어 javac 없음)
export JAVA_HOME=/home/jieunstage/android-sdk/jdk-17.0.2

cd android-app
./gradlew installDebug
```

## 모델 내보내기 (재생성 시)

```bash
conda activate torch21
pip install onnxscript  # 필요 시
python export/export_models.py
```

- `segmentation.onnx` (5.7MB) — pyannote/segmentation-3.0, TorchScript 방식(`dynamo=False`)
- `embedding.onnx` (26MB) — wespeaker-voxceleb-resnet34-LM
- `mel_filterbank_80.bin` (81KB) — librosa 호환 80-mel 필터뱅크 (float32 LE)

생성 후 `android-app/app/src/main/assets/`에 복사.

## 아키텍처

```
AudioCapturer (16kHz, 10ms frames)
  → SlidingWindowBuffer (5s window, 0.5s step)
    → DiarizationPipeline.process()
        1. SegmentationModel  → [293 frames][3 speakers] 활성 확률
        2. OverlappedSpeechPenalty(gamma=3, beta=10) → 화자별 가중치
        3. EmbeddingModel.warmup() + extractForSpeaker() → [3][256] 임베딩
        4. OnlineSpeakerClustering.assign() → 전역 화자 ID
        5. buildSpeakerTurns() → List<SpeakerTurn>
  → DiarizationViewModel (StateFlow)
    → DiarizationScreen (Jetpack Compose)
```

## 핵심 파라미터

| 파라미터 | 값 | 설명 |
|---------|-----|------|
| `tauActive` | 0.4 | 활성 화자 임계값 (기본 0.6에서 낮춤 — 미드레인지 폰 마이크) |
| `rhoUpdate` | 0.3 | 센트로이드 EMA 업데이트 비율 |
| `deltaNow` | 0.8 | 새 화자 등록 코사인 거리 임계값 (기본 1.0에서 낮춤) |
| `SUBSAMPLE` | 4 | 임베딩용 mel 프레임 서브샘플링 배율 (498→125) |

## 성능 (Exynos 980 기준)

- 무음 청크: 37–71ms (실시간)
- 발화 청크: ~2.2s (wespeaker ResNet34 한계)

## 동시성 설계

`Channel.CONFLATED` + 단일 추론 코루틴 → ONNX 세션 경합 방지.
이전 청크는 버리고 최신 청크만 처리 (실시간성 우선).

## 양자화 시도 이력

`onnxruntime.quantization.quantize_dynamic()` INT8 양자화(26MB→6.4MB)는
`onnxruntime-android` Mobile 빌드에서 `QLinearMatMul` 연산자 미지원으로 모델 로드 실패.
현재 원본 FP32 모델 사용.

## 주요 파일

```
android-app/app/src/main/
  java/com/diart/android/
    MainActivity.kt                      # Compose entry point
    DiarizationViewModel.kt              # 상태 관리, 청크 채널
    audio/
      AudioCapturer.kt                   # AudioRecord 래퍼, 16kHz mono PCM16
      SlidingWindowBuffer.kt             # 링버퍼, 80000샘플 window / 8000샘플 step
      FeatureExtractor.kt                # Bluestein FFT, librosa 호환 mel 특징
    model/
      SegmentationModel.kt               # ONNX 추론, 입력 (1,1,80000)
      EmbeddingModel.kt                  # ONNX 추론, 입력 (1,T,80) mel
    pipeline/
      DiarizationPipeline.kt             # 파이프라인 오케스트레이터
      OnlineSpeakerClustering.kt         # 코사인 거리 클러스터링
      OverlappedSpeechPenalty.kt         # 겹침 발화 소프트맥스 패널티
    ui/
      DiarizationScreen.kt               # Compose UI, 화자별 색상
      theme/Theme.kt                     # 다크 테마
  assets/
    segmentation.onnx
    embedding.onnx
    mel_filterbank_80.bin
export/
  export_models.py                       # Python 모델 내보내기 스크립트
```

## 의존성 버전

- ONNX Runtime Android: 1.20.0
- Jetpack Compose BOM: 2024.12.01
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.0.21
- Min SDK: 26 (Android 8.0)
