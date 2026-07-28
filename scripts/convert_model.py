#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Sebastian (Eidora contributors)
"""
Convert an ONNX face model you provide into a TFLite model Eidora can load.

Why this exists
---------------
Eidora ships with free, Apache-2.0 models (YuNet + SFace). Other models — for
example InsightFace's SCRFD or ArcFace — are licensed for non-commercial
research only, so Eidora does not host or distribute them. If you want to use
such a model, you obtain the ONNX file yourself (accepting its license) and
convert it locally with this script. Nothing is downloaded or uploaded here:
you point the script at an ONNX file already on your machine.

This mirrors exactly what Eidora's CI does for the free models, so a model you
convert here is byte-compatible with what the app expects.

What it does
------------
1. Inspects your ONNX (prints input/output names and shapes) so you can pick
   the right input name and size.
2. Converts to TFLite (float32) with a fixed input shape via onnx2tf.
3. Prints the output's SHA-256 and its tensor layout so you can confirm it
   matches the detection/embedding format Eidora needs.

Requirements
------------
    pip install "tensorflow==2.17.0" tf_keras "onnx==1.16.1" \
        "onnxruntime==1.18.1" "onnx2tf==1.26.3" onnx_graphsurgeon \
        sng4onnx "onnxsim==0.4.36" psutil ml_dtypes \
        --extra-index-url https://pypi.ngc.nvidia.com

(These are the same pinned versions Eidora's conversion workflows use. Other
versions may work but are untested.)

Usage
-----
Inspect a model first (find the input name and shape):

    python convert_model.py --onnx det_2.5g.onnx --inspect

Then convert, giving the input name and size you saw:

    # SCRFD-style detector (640x640):
    python convert_model.py --onnx det_2.5g.onnx \
        --input-name input.1 --size 640 --out scrfd_2.5g_kps_640_float32.tflite

    # ArcFace-style embedder (112x112):
    python convert_model.py --onnx w600k_mbf.onnx \
        --input-name input.1 --size 112 --out arcface_w600k_mbf_float32.tflite

The output filename should match the `file:` you'll reference in the container
manifest. After converting, package the .tflite(s) + a manifest.yml into an
Eidora container with `pack_container.py` (see docs/model-container.md for the
manifest format).

IMPORTANT — licensing
---------------------
This script does not fetch any model. You are responsible for obtaining your
ONNX file and complying with its license. Some models (e.g. InsightFace SCRFD,
ArcFace) are non-commercial research use only. Do not redistribute converted
models unless their license allows it.
"""

import argparse
import hashlib
import os
import site
import subprocess
import sys


def log(msg=""):
    print(msg, flush=True)


def die(msg):
    log(f"ERROR: {msg}")
    sys.exit(1)


def inspect_onnx(path):
    """Print the ONNX model's inputs and outputs so the user can choose."""
    try:
        import onnxruntime as ort
    except ImportError:
        die("onnxruntime not installed. Run the pip install line from this script's header.")

    ort.set_default_logger_severity(3)
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    log("=" * 60)
    log(f"ONNX model: {path}")
    log("=" * 60)
    log("Inputs:")
    for inp in sess.get_inputs():
        log(f"  name={inp.name!r}  shape={inp.shape}")
    log("Outputs:")
    for out in sess.get_outputs():
        log(f"  name={out.name!r}  shape={out.shape}")
    log("=" * 60)
    log("Use the input name above with --input-name, and the spatial size")
    log("(e.g. 640 for detectors, 112 for embedders) with --size.")


def provide_calibration_data():
    """
    onnx2tf tries to download a calibration sample on first run. In an offline
    or CI environment that fails, so we pre-create the file it looks for — the
    same workaround Eidora's conversion workflows use. The data is only used
    for optional INT8 calibration, which we don't request, but its presence
    avoids the download attempt.
    """
    try:
        import numpy as np
    except ImportError:
        die("numpy not installed. Run the pip install line from this script's header.")

    name = "calibration_image_sample_data_20x128x128x3_float32.npy"
    data = (np.random.rand(20, 128, 128, 3) * 255).astype(np.float32)
    targets = [name]
    try:
        pkg = site.getsitepackages()[0]
        targets.append(os.path.join(pkg, "onnx2tf", name))
        targets.append(os.path.join(pkg, "onnx2tf", "utils", name))
    except Exception:
        pass
    for t in targets:
        try:
            os.makedirs(os.path.dirname(t) or ".", exist_ok=True)
            np.save(t, data)
        except Exception:
            pass  # best-effort


def convert(onnx_path, input_name, size, out_path):
    provide_calibration_data()

    saved_dir = "eidora_convert_saved_model"
    # Remove any output from a previous conversion — onnx2tf writes into this
    # dir, and a leftover *_float32.tflite from an earlier model would make the
    # "pick the float32 file" step below grab the wrong (stale) model. This is
    # what caused two-model builds to end up with the first model duplicated.
    import shutil
    shutil.rmtree(saved_dir, ignore_errors=True)

    ois = f"{input_name}:1,3,{size},{size}"
    cmd = ["onnx2tf", "-i", onnx_path, "-ois", ois, "-o", saved_dir]
    log(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        log(result.stdout[-2000:])
        log(result.stderr[-2000:])
        die(
            "onnx2tf failed. Most often the --input-name is wrong: run with "
            "--inspect and copy the exact input name. Some models use 'input', "
            "'input.1', 'images', or 'data'."
        )

    # onnx2tf writes several files; we want the float32 TFLite.
    candidates = [
        f for f in os.listdir(saved_dir) if f.endswith("_float32.tflite")
    ]
    if not candidates:
        die(f"No *_float32.tflite produced in {saved_dir}/. Check the onnx2tf output above.")
    produced = os.path.join(saved_dir, sorted(candidates)[0])

    # Copy to the requested output name.
    shutil.copyfile(produced, out_path)

    sha = hashlib.sha256(open(out_path, "rb").read()).hexdigest()
    log("")
    log("=" * 60)
    log("Conversion successful.")
    log(f"  Output:  {out_path}")
    log(f"  SHA-256: {sha}")
    log("=" * 60)

    # Show the TFLite tensor layout so the user can sanity-check compatibility.
    describe_tflite(out_path)

    log("")
    log("Next step: package this .tflite (and its detector/embedder partner)")
    log("into an Eidora container:")
    log("  python pack_container.py --dir <folder-with-manifest.yml> --out my.eidoramodel")


def describe_tflite(path):
    """Print TFLite input/output tensors to confirm the format matches Eidora."""
    try:
        import tensorflow as tf
    except ImportError:
        log("(tensorflow not available to describe the TFLite; skipping layout print)")
        return
    try:
        interp = tf.lite.Interpreter(model_path=path)
        interp.allocate_tensors()
        log("TFLite inputs:")
        for d in interp.get_input_details():
            log(f"  name={d['name']!r} shape={list(d['shape'])} dtype={d['dtype'].__name__}")
        log("TFLite outputs:")
        for d in interp.get_output_details():
            log(f"  name={d['name']!r} shape={list(d['shape'])} dtype={d['dtype'].__name__}")
        log("")
        log("Sanity check against Eidora's expectations:")
        log("  Detector  (e.g. SCRFD): input [1,3,640,640]; several outputs")
        log("             (scores/bboxes/kps per stride).")
        log("  Embedder  (e.g. ArcFace): input [1,3,112,112]; one output")
        log("             [1,512] (ArcFace) or [1,128] (SFace-style).")
    except Exception as e:
        log(f"(could not load TFLite to describe it: {e})")


def main():
    ap = argparse.ArgumentParser(
        description="Convert a user-provided ONNX face model to TFLite for Eidora.",
    )
    ap.add_argument("--onnx", required=True, help="Path to your ONNX file (you provide it).")
    ap.add_argument("--inspect", action="store_true", help="Only print the ONNX inputs/outputs and exit.")
    ap.add_argument("--input-name", help="ONNX input tensor name (see --inspect).")
    ap.add_argument("--size", type=int, help="Spatial input size, e.g. 640 or 112.")
    ap.add_argument("--out", help="Output .tflite path (name it as Eidora expects).")
    args = ap.parse_args()

    if not os.path.isfile(args.onnx):
        die(f"ONNX file not found: {args.onnx}")

    if args.inspect:
        inspect_onnx(args.onnx)
        return

    if not (args.input_name and args.size and args.out):
        die("Conversion needs --input-name, --size and --out. Run --inspect first to find the input name.")

    convert(args.onnx, args.input_name, args.size, args.out)


if __name__ == "__main__":
    main()
