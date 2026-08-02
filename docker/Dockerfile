# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Sebastian (Eidora contributors)
#
# Reproducible build environment for Eidora model containers.
#
# This mirrors the exact toolchain the GitHub "bring your own model" workflow
# uses (Python 3.10 + pinned tensorflow/onnx/onnx2tf), so building a container
# locally gives the same result as building it in CI — without installing the
# fragile ONNX -> TFLite stack on your own machine.
#
# It is intended mainly for the RESEARCH container (SCRFD + ArcFace). Those
# InsightFace weights are non-commercial research use only and are NOT hosted by
# Eidora; the build downloads them from the onnx_url in the manifest, which you
# are responsible for being licensed to use.
#
# Build the image:
#   docker build -t eidora-container-builder ./docker
#
# Then use scripts/build_container_docker.sh (see docker/README.md).

FROM python:3.10-slim

# System libs onnxruntime / opencv-style wheels expect at runtime.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
        curl \
    && rm -rf /var/lib/apt/lists/*

# Pinned to match .github/workflows/build-your-own-container.yml exactly.
# Keep these in sync with that workflow if it changes.
RUN pip install --no-cache-dir \
        "tensorflow==2.17.0" tf_keras "onnx==1.16.1" \
        "onnxruntime==1.18.1" "onnx2tf==1.26.3" onnx_graphsurgeon \
        sng4onnx "onnxsim==0.4.36" psutil ml_dtypes pyyaml \
        --extra-index-url https://pypi.ngc.nvidia.com

# onnx2tf tries to download a calibration sample on first run; some models fail
# to convert offline without it. Pre-seed a synthetic one in the locations
# onnx2tf looks (same workaround as the workflow).
RUN python - <<'PY'
import os, site
import numpy as np
name = "calibration_image_sample_data_20x128x128x3_float32.npy"
data = (np.random.rand(20, 128, 128, 3) * 255).astype(np.float32)
targets = [
    name,
    os.path.join(site.getsitepackages()[0], "onnx2tf", name),
    os.path.join(site.getsitepackages()[0], "onnx2tf", "utils", name),
]
for t in targets:
    os.makedirs(os.path.dirname(t) or ".", exist_ok=True)
    np.save(t, data)
print("calibration sample seeded")
PY

WORKDIR /work

# The repo's scripts are mounted at /work/scripts at run time (see the wrapper),
# so the image stays generic and always uses your checked-out scripts+manifest.
ENTRYPOINT ["python", "scripts/build_container.py"]
