# diart-android

실시간 화자 분리(Speaker Diarization) Android 앱.
[diart](https://github.com/juanmc2005/diart) Python 파이프라인을 ONNX Runtime Mobile로 포팅하여 완전 온디바이스 추론을 구현합니다.

## 특징

- 서버 없이 스마트폰에서 직접 화자 분리 실행
- 0.5초 단위 슬라이딩 윈도우 실시간 처리
- 최대 20명 화자 추적 (온라인 클러스터링)
- 겹침 발화(overlapped speech) 처리
- Jetpack Compose 기반 UI

## 모델

| 모델 | 크기 | 역할 |
|------|------|------|
| pyannote/segmentation-3.0 | 5.7MB | 프레임별 화자 활성 확률 |
| wespeaker-voxceleb-resnet34-LM | 26MB | 256차원 화자 임베딩 |

## 요구 사항

- Android 8.0 (API 26) 이상
- 마이크 권한

## 빌드

### 사전 준비

1. 모델 내보내기 (최초 1회)

```bash
conda activate torch21
python export/export_models.py
```

생성된 파일을 `android-app/app/src/main/assets/`에 복사:
- `segmentation.onnx`
- `embedding.onnx`
- `mel_filterbank_80.bin`

2. 빌드 & 설치

```bash
cd android-app
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
./gradlew installDebug
```

## 파이프라인 구조

```
마이크 (16kHz, mono)
  └─ SlidingWindowBuffer (5s / 0.5s step)
       └─ SegmentationModel → 화자별 활성 확률 [293 frames × 3 speakers]
            └─ OverlappedSpeechPenalty → 임베딩 가중치
                 └─ EmbeddingModel → 화자 임베딩 [3 × 256]
                      └─ OnlineSpeakerClustering → 전역 화자 ID
                           └─ UI (화자별 발화 구간 표시)
```

## 주요 파라미터

- `tauActive = 0.4` — 활성 화자 감지 임계값
- `deltaNow = 0.8` — 새 화자 등록 코사인 거리 임계값
- `rhoUpdate = 0.3` — 화자 임베딩 EMA 업데이트 비율

## 성능 (Samsung Galaxy A52 5G / Exynos 980)

| 상황 | 처리 시간 |
|------|----------|
| 무음 청크 | 37–71ms |
| 발화 청크 | ~2.2s |

## 기반 프로젝트

- [diart](https://github.com/juanmc2005/diart) — Python 실시간 화자 분리
- [pyannote-audio](https://github.com/pyannote/pyannote-audio) — segmentation 모델
- [wespeaker](https://github.com/wenet-e2e/wespeaker) — 화자 임베딩 모델
