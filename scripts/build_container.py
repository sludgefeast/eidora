#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Sebastian (Eidora contributors)
"""
Build an Eidora container from a manifest that carries build metadata.

This is the single conversion path both the free-model workflow and the
"bring your own model" workflow go through, so there's one source of truth for
how a model is fetched, converted, and packed. It:

  1. reads the manifest;
  2. for every model with a `build:` block, downloads its ONNX and converts it
     to TFLite via scripts/convert_model.py (same onnx2tf invocation for all);
  3. packs the TFLite files + manifest into a .eidoramodel via
     scripts/pack_container.py.

The `build:` block per model (all optional at the container level, but required
to build that model here):

    build:
      onnx_url:   URL to the ONNX to fetch
      input_name: ONNX input tensor name, or `auto` to read it from the ONNX
      size:       spatial input size (e.g. 640 or 112)

`build:` is purely build-time metadata. The app ignores it — it's here so the
provenance of each model (where it came from, how it was converted) lives with
the model description, and so both workflows can build straight from a manifest
with no separate parameter list.

Usage:
    python scripts/build_container.py --manifest path/to/manifest.yml \
        --out my-container.eidoramodel
"""

import argparse
import os
import subprocess
import sys
import urllib.request

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))


def log(m=""):
    print(m, flush=True)


def fail(msg):
    sys.exit(f"build_container: {msg}")


def resolve_input_name(onnx_path):
    """Reads the first input tensor name from an ONNX (for input_name: auto)."""
    import onnx

    graph = onnx.load(onnx_path).graph
    return graph.input[0].name


def build(manifest_path, out_path, work_dir):
    with open(manifest_path) as f:
        manifest = yaml.safe_load(f)

    models = manifest.get("models") or []
    if not models:
        fail("manifest has no models")

    os.makedirs(work_dir, exist_ok=True)
    # The packer needs the manifest alongside the .tflite files.
    staged_manifest = os.path.join(work_dir, "manifest.yml")
    with open(staged_manifest, "w") as f:
        yaml.safe_dump(manifest, f, sort_keys=False, allow_unicode=True)

    for i, model in enumerate(models):
        build_meta = model.get("build")
        target = model.get("file")
        if not build_meta:
            fail(f"model '{model.get('id')}' has no build: block — cannot build it here")
        if not target:
            fail(f"model '{model.get('id')}' has no file:")

        url = build_meta.get("onnx_url")
        size = build_meta.get("size")
        input_name = build_meta.get("input_name")
        if not url or not size or not input_name:
            fail(f"model '{model.get('id')}' build: needs onnx_url, size, input_name")

        onnx_path = os.path.join(work_dir, f"model_{i}.onnx")
        log(f"=== [{i + 1}/{len(models)}] {target} ===")
        log(f"downloading {url}")
        urllib.request.urlretrieve(url, onnx_path)
        log(f"  {os.path.getsize(onnx_path)} bytes")

        if str(input_name).strip().lower() == "auto":
            input_name = resolve_input_name(onnx_path)
            log(f"  resolved input name: {input_name}")

        subprocess.run(
            [
                sys.executable, os.path.join(HERE, "convert_model.py"),
                "--onnx", onnx_path,
                "--input-name", str(input_name),
                "--size", str(size),
                "--out", os.path.join(work_dir, target),
            ],
            check=True,
        )

    log("=== packing container ===")
    subprocess.run(
        [
            sys.executable, os.path.join(HERE, "pack_container.py"),
            "--dir", work_dir,
            "--out", out_path,
        ],
        check=True,
    )
    log(f"\nBuilt {out_path}")


def main():
    ap = argparse.ArgumentParser(description="Build an Eidora container from a manifest.")
    ap.add_argument("--manifest", required=True, help="Path to the manifest.yml.")
    ap.add_argument("--out", required=True, help="Output .eidoramodel path.")
    ap.add_argument("--work-dir", default="build-container", help="Scratch folder.")
    args = ap.parse_args()

    if not os.path.isfile(args.manifest):
        fail(f"manifest not found: {args.manifest}")
    build(args.manifest, args.out, args.work_dir)


if __name__ == "__main__":
    main()
