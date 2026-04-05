"""
Diart 모델 ONNX 변환 스크립트
=============================
pyannote/segmentation-3.0 → segmentation.onnx
wespeaker-voxceleb-resnet34-LM → embedding.onnx

사용법:
    pip install pyannote.audio huggingface_hub onnx onnxruntime
    python export_models.py --token <HF_TOKEN> --output models/

변환된 .onnx 파일을 android-app/app/src/main/assets/ 에 복사하면 됩니다.
"""

import argparse
import shutil
import sys
from pathlib import Path

import numpy as np
import torch
import onnx
import onnxruntime as ort


# ─── Segmentation Model ──────────────────────────────────────────────────────

def export_segmentation(output_dir: Path, hf_token: str):
    """
    pyannote/segmentation-3.0 을 ONNX로 변환합니다.
    출력 텐서: (batch, frames, speakers) - powerset → multilabel 변환 포함.
    """
    print("[1/2] Exporting segmentation model ...")

    from pyannote.audio import Model
    from pyannote.audio.core.model import Introspection

    # 모델 로드
    model = Model.from_pretrained(
        "pyannote/segmentation-3.0",
        use_auth_token=hf_token,
    )
    model.eval()

    # powerset → multilabel 변환 래퍼
    class SegmentationWrapper(torch.nn.Module):
        def __init__(self, base_model):
            super().__init__()
            self.model = base_model
            specs = base_model.specifications
            # powerset 인코딩 여부 확인
            if hasattr(specs, "powerset") and specs.powerset:
                from pyannote.audio.utils.powerset import Powerset
                self.powerset = Powerset(
                    len(specs.classes),
                    specs.powerset_max_classes,
                )
            else:
                self.powerset = None

        def forward(self, waveform: torch.Tensor) -> torch.Tensor:
            # waveform: (batch, 1, samples)
            output = self.model(waveform)
            if self.powerset is not None:
                output = self.powerset.to_multilabel(output)
            return output  # (batch, frames, num_speakers)

    wrapper = SegmentationWrapper(model)
    wrapper.eval()

    # 더미 입력: 5초 @ 16kHz
    dummy = torch.zeros(1, 1, 80000)

    with torch.no_grad():
        out = wrapper(dummy)
    print(f"  Segmentation output shape: {out.shape}")  # (1, frames, speakers)

    out_path = output_dir / "segmentation.onnx"
    # dynamo=False: SincNet의 clamp 연산 호환성 문제로 구형 TorchScript exporter 사용
    torch.onnx.export(
        wrapper,
        dummy,
        str(out_path),
        input_names=["waveform"],
        output_names=["segmentation"],
        dynamic_axes={
            "waveform": {0: "batch"},
        },
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )

    # 검증
    onnx_model = onnx.load(str(out_path))
    onnx.checker.check_model(onnx_model)

    # ORT 검증
    sess = ort.InferenceSession(str(out_path), providers=["CPUExecutionProvider"])
    result = sess.run(None, {"waveform": dummy.numpy()})[0]
    print(f"  ONNX output shape: {result.shape}")
    print(f"  Saved → {out_path}")


# ─── Embedding Model ─────────────────────────────────────────────────────────

def export_embedding(output_dir: Path, hf_token: str):
    """
    wespeaker-voxceleb-resnet34-LM ONNX 모델을 HuggingFace에서 다운로드합니다.
    입력: waveform (batch, 1, samples)
    출력: embedding (batch, 256)
    """
    print("[2/2] Downloading wespeaker embedding model ...")

    try:
        from huggingface_hub import hf_hub_download
        model_path = hf_hub_download(
            repo_id="hbredin/wespeaker-voxceleb-resnet34-LM",
            filename="wespeaker-voxceleb-resnet34-LM.onnx",
            token=hf_token,
        )
        dst = output_dir / "embedding.onnx"
        shutil.copy(model_path, dst)

        # 입출력 형태 확인
        sess = ort.InferenceSession(str(dst), providers=["CPUExecutionProvider"])
        print(f"  Inputs:  {[(i.name, i.shape) for i in sess.get_inputs()]}")
        print(f"  Outputs: {[(o.name, o.shape) for o in sess.get_outputs()]}")
        print(f"  Saved → {dst}")

    except Exception as e:
        print(f"  WARNING: wespeaker download failed ({e})")
        print("  Falling back to pyannote/embedding ...")
        _export_pyannote_embedding(output_dir, hf_token)


def _export_pyannote_embedding(output_dir: Path, hf_token: str):
    """pyannote/embedding 모델을 ONNX로 변환합니다 (fallback)."""
    from pyannote.audio import Model

    model = Model.from_pretrained(
        "pyannote/embedding",
        use_auth_token=hf_token,
    )
    model.eval()

    # weights 없이 waveform만으로 임베딩 추출하는 래퍼
    class EmbeddingWrapper(torch.nn.Module):
        def __init__(self, base):
            super().__init__()
            self.model = base

        def forward(self, waveform: torch.Tensor) -> torch.Tensor:
            # waveform: (batch, 1, samples)
            return self.model(waveform)  # (batch, emb_dim)

    wrapper = EmbeddingWrapper(model)
    wrapper.eval()

    dummy = torch.zeros(1, 1, 80000)
    with torch.no_grad():
        out = wrapper(dummy)
    print(f"  Embedding output shape: {out.shape}")

    out_path = output_dir / "embedding.onnx"
    torch.onnx.export(
        wrapper,
        dummy,
        str(out_path),
        input_names=["waveform"],
        output_names=["embedding"],
        dynamic_axes={"waveform": {0: "batch"}},
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )

    onnx_model = onnx.load(str(out_path))
    onnx.checker.check_model(onnx_model)
    print(f"  Saved → {out_path}")


# ─── Quantization (모바일 최적화) ────────────────────────────────────────────

def quantize_models(output_dir: Path):
    """
    INT8 dynamic quantization으로 모델 크기를 줄입니다 (~3-4x 압축).
    Android에서 float32 모델이 너무 크면 이 함수를 사용하세요.
    """
    from onnxruntime.quantization import quantize_dynamic, QuantType

    for name in ["segmentation.onnx", "embedding.onnx"]:
        src = output_dir / name
        if not src.exists():
            continue
        dst = output_dir / name.replace(".onnx", "_quantized.onnx")
        quantize_dynamic(str(src), str(dst), weight_type=QuantType.QUInt8)
        src_mb = src.stat().st_size / 1e6
        dst_mb = dst.stat().st_size / 1e6
        print(f"  {name}: {src_mb:.1f}MB → {dst_mb:.1f}MB ({dst_mb/src_mb*100:.0f}%)")


# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Export diart models to ONNX")
    parser.add_argument("--token", required=True, help="HuggingFace access token")
    parser.add_argument("--output", default="models", help="Output directory (default: models/)")
    parser.add_argument("--quantize", action="store_true", help="Apply INT8 dynamic quantization")
    args = parser.parse_args()

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    export_segmentation(output_dir, args.token)
    export_embedding(output_dir, args.token)

    if args.quantize:
        print("\n[Quantizing models ...]")
        quantize_models(output_dir)

    print(f"""
Done! Next steps:
  1. Copy .onnx files to android-app/app/src/main/assets/
     cp {output_dir}/segmentation.onnx ../android-app/app/src/main/assets/
     cp {output_dir}/embedding.onnx    ../android-app/app/src/main/assets/
  2. Build and run the Android app.
""")


if __name__ == "__main__":
    main()
