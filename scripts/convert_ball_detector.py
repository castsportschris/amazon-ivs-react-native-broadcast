#!/usr/bin/env python3
"""
Convert TorchVision's SSDLite320-MobileNetV3-Large (BSD license, COCO-trained)
to a CoreML .mlpackage for use with the Cast Sports auto-tracking feature.

Why this model:
  - BSD-licensed weights (TorchVision) — fully ship-safe for commercial use
  - COCO-trained, includes "sports ball" class (label ID 37 in 91-class COCO)
  - 320x320 input, ~10 MB compiled, ~10ms inference on Apple Neural Engine
  - PyTorch source converts cleanly via coremltools' mature PyTorch path
    (TF Hub object-detection models all hit graph-conversion bugs in
    coremltools as of late 2025)

Setup (one-time, pinned versions tested with coremltools):
    python3.11 -m venv .venv
    source .venv/bin/activate
    pip install "torch==2.1.0" "torchvision==0.16.0" "coremltools==7.2" \\
                "numpy<2" "protobuf<5" "setuptools<81"

Run:
    source .venv/bin/activate
    python convert_ball_detector.py

Output:
    BallDetector.mlpackage in the current directory.

Then move it into the iOS bundle and refresh pods:
    mv BallDetector.mlpackage ../ios/MLModels/
    cd ../../../cast-sports-mobile/ios && pod install

Pass `autoTrackingModelName="BallDetector"` to IVSBroadcastCameraView when
the auto-tracking feature is enabled.
"""

import os
import shutil
import sys

OUTPUT_NAME = "BallDetector.mlpackage"
INPUT_SIZE = 320  # SSDLite320-MobileNetV3 native input size

# COCO 91-class label map (1-indexed, with non-contiguous IDs — that's the
# canonical COCO format TorchVision uses). The model returns these IDs
# directly in its `labels` output. Swift filters by SPORTS_BALL_LABEL_ID = 37.
COCO_LABELS = {
    1: "person", 2: "bicycle", 3: "car", 4: "motorcycle", 5: "airplane",
    6: "bus", 7: "train", 8: "truck", 9: "boat", 10: "traffic light",
    11: "fire hydrant", 13: "stop sign", 14: "parking meter", 15: "bench",
    16: "bird", 17: "cat", 18: "dog", 19: "horse", 20: "sheep", 21: "cow",
    22: "elephant", 23: "bear", 24: "zebra", 25: "giraffe", 27: "backpack",
    28: "umbrella", 31: "handbag", 32: "tie", 33: "suitcase", 34: "frisbee",
    35: "skis", 36: "snowboard", 37: "sports ball", 38: "kite",
    39: "baseball bat", 40: "baseball glove", 41: "skateboard",
    42: "surfboard", 43: "tennis racket", 44: "bottle", 46: "wine glass",
    47: "cup", 48: "fork", 49: "knife", 50: "spoon", 51: "bowl", 52: "banana",
    53: "apple", 54: "sandwich", 55: "orange", 56: "broccoli", 57: "carrot",
    58: "hot dog", 59: "pizza", 60: "donut", 61: "cake", 62: "chair",
    63: "couch", 64: "potted plant", 65: "bed", 67: "dining table",
    70: "toilet", 72: "tv", 73: "laptop", 74: "mouse", 75: "remote",
    76: "keyboard", 77: "cell phone", 78: "microwave", 79: "oven",
    80: "toaster", 81: "sink", 82: "refrigerator", 84: "book", 85: "clock",
    86: "vase", 87: "scissors", 88: "teddy bear", 89: "hair drier",
    90: "toothbrush",
}
SPORTS_BALL_LABEL_ID = 37


def require_dependencies():
    missing = []
    for module, pkg in [
        ("torch", "torch"),
        ("torchvision", "torchvision"),
        ("coremltools", "coremltools"),
    ]:
        try:
            __import__(module)
        except ImportError:
            missing.append(pkg)
    if missing:
        print("Missing Python packages: " + ", ".join(missing))
        print("Install with:")
        print('  pip install "torch==2.1.0" "torchvision==0.16.0" "coremltools==7.2" "numpy<2" "protobuf<5" "setuptools<81"')
        sys.exit(1)


def main():
    require_dependencies()

    import torch
    import torch.nn as nn
    import torchvision
    import coremltools as ct

    print("[1/4] Loading TorchVision SSDLite320-MobileNetV3-Large...")
    base_model = torchvision.models.detection.ssdlite320_mobilenet_v3_large(
        weights=torchvision.models.detection.SSDLite320_MobileNet_V3_Large_Weights.DEFAULT
    )
    base_model.eval()
    print("      Loaded.")

    print("[2/4] Wrapping the model so it traces to fixed-shape tensor outputs...")

    class TraceableDetector(nn.Module):
        """
        Wraps a TorchVision detection model so torch.jit.trace produces a
        graph with tensor-only outputs (boxes, scores, labels) suitable for
        CoreML conversion. Also bakes ImageNet normalization into the model
        so callers can pass raw [0,1] pixel values.
        """

        def __init__(self, model):
            super().__init__()
            self.model = model
            self.register_buffer(
                "mean",
                torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1),
            )
            self.register_buffer(
                "std",
                torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1),
            )

        def forward(self, image: torch.Tensor):
            # image: [1, 3, H, W] in [0, 1]
            normalized = (image - self.mean) / self.std
            # SSDLite returns List[Dict[str, Tensor]]; first element is for
            # the only image in the batch.
            detections = self.model(normalized)[0]
            return (
                detections["boxes"],
                detections["scores"],
                detections["labels"],
            )

    wrapped = TraceableDetector(base_model)
    wrapped.eval()

    example_image = torch.rand(1, 3, INPUT_SIZE, INPUT_SIZE)

    with torch.no_grad():
        traced = torch.jit.trace(wrapped, example_image, strict=False)
    print("      Traced.")

    print("[3/4] Converting to CoreML ML Program (this can take 1–3 minutes)...")
    # ImageType with scale=1/255 lets the model accept raw uint8 pixel values
    # delivered by AVCaptureSession; no client-side preprocessing needed.
    mlmodel = ct.convert(
        traced,
        inputs=[
            ct.ImageType(
                name="image",
                shape=(1, 3, INPUT_SIZE, INPUT_SIZE),
                scale=1.0 / 255.0,
                bias=[0.0, 0.0, 0.0],
                color_layout=ct.colorlayout.RGB,
            ),
        ],
        outputs=[
            ct.TensorType(name="boxes"),
            ct.TensorType(name="scores"),
            ct.TensorType(name="labels"),
        ],
        minimum_deployment_target=ct.target.iOS16,
        convert_to="mlprogram",
    )

    # Embed metadata so the iOS side can read it if it ever needs to
    mlmodel.author = "Cast Sports"
    mlmodel.short_description = (
        "SSDLite320-MobileNetV3-Large COCO object detector for "
        "Cast Sports auto-tracking. Filter labels output by COCO label ID "
        "37 (sports ball)."
    )
    mlmodel.license = "BSD (TorchVision weights)"
    mlmodel.version = "1.0"
    mlmodel.user_defined_metadata["sports_ball_label_id"] = str(SPORTS_BALL_LABEL_ID)
    mlmodel.user_defined_metadata["input_size"] = str(INPUT_SIZE)
    mlmodel.user_defined_metadata["coco_labels_json"] = str(COCO_LABELS)
    print("      Converted.")

    print(f"[4/4] Saving {OUTPUT_NAME}...")
    if os.path.isdir(OUTPUT_NAME):
        shutil.rmtree(OUTPUT_NAME)
    elif os.path.isfile(OUTPUT_NAME):
        os.remove(OUTPUT_NAME)
    mlmodel.save(OUTPUT_NAME)
    print("      Saved.")

    print()
    print("=" * 60)
    print(f"Done. Output: {os.path.abspath(OUTPUT_NAME)}")
    print()
    print("Next steps:")
    print(f"  mv {OUTPUT_NAME} ../ios/MLModels/")
    print("  cd ../../../cast-sports-mobile/ios && pod install")
    print()
    print("In broadcast.tsx (for testing):")
    print('  autoTrackingEnabled={true}')
    print('  autoTrackingModelName="BallDetector"')
    print("=" * 60)


if __name__ == "__main__":
    main()
