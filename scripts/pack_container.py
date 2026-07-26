#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Sebastian (Eidora contributors)
"""
Pack a set of models + a manifest into an Eidora model container
(.eidoramodel), with the same structural checks the app applies on load.

An Eidora model container is just a .zip holding:

    manifest.yml                 # describes the set (see docs/model-container.md)
    <model>.tflite               # one file per model entry's `file:`
    ...

This script takes a manifest.yml and the .tflite files it references (already in
one folder), validates them, and writes <name>.eidoramodel.

What it checks (mirrors docs/model-validation.md, the statically checkable part):

  Class 1 — manifest well-formed:
    - valid YAML, known schema_version
    - container.id present; each model has id/task/file/output.type/input
    - every model.file exists in the source folder
    - enum values are known (task, output.type, normalization, resize)

  Class 2 — output.type fits the .tflite structure:
    - reads each model's tensor shapes and checks they're consistent with the
      declared output.type (e.g. single_vector => one [1,D] output; the
      multistride_* families => several outputs whose cell counts match a
      stride pyramid). Only structure is checked — never a value the .tflite
      already exposes, since the manifest no longer restates those.

Class 3 (the self-test on real faces) is done in the app, not here — it needs
the bundled sample images and a human judgement.

Requirements
------------
    pip install pyyaml
    # optional but recommended, to enable the Class-2 tensor checks:
    pip install "tensorflow==2.17.0"

Usage
-----
    # folder contains manifest.yml + the referenced .tflite files
    python pack_container.py --dir ./my-models --out my-models.eidoramodel

    # skip the tensor checks (e.g. no tensorflow available):
    python pack_container.py --dir ./my-models --out my-models.eidoramodel --no-tensor-check
"""

import argparse
import os
import sys
import zipfile

KNOWN_SCHEMA = {1}
KNOWN_TASKS = {"detection", "embedding"}
KNOWN_TYPES = {"multistride_scrfd", "multistride_yunet", "single_vector"}
KNOWN_NORM = {"raw_0_255", "signed_127_127", "signed_127_128", "zero_to_one"}
KNOWN_RESIZE = {"letterbox", "stretch"}
STRIDES = [8, 16, 32]


def log(m=""):
    print(m, flush=True)


def fail(msg):
    log(f"REJECT: {msg}")
    sys.exit(1)


def load_manifest(path):
    try:
        import yaml
    except ImportError:
        fail("pyyaml not installed. Run: pip install pyyaml")
    try:
        with open(path) as f:
            return yaml.safe_load(f)
    except Exception as e:
        fail(f"manifest.yml is not valid YAML: {e}")


def check_class1(m, src_dir):
    """Manifest well-formed. Returns the list of model dicts."""
    if not isinstance(m, dict):
        fail("manifest root is not a mapping")
    sv = m.get("schema_version")
    if sv not in KNOWN_SCHEMA:
        fail(f"unknown schema_version {sv!r} (this tool knows {sorted(KNOWN_SCHEMA)})")
    cont = m.get("container") or {}
    if not cont.get("id"):
        fail("container.id is required")
    models = m.get("models")
    if not models or not isinstance(models, list):
        fail("models: must be a non-empty list")

    for i, mod in enumerate(models):
        where = f"models[{i}]"
        for req in ("id", "task", "file", "input", "output"):
            if req not in mod:
                fail(f"{where}: missing required field {req!r}")
        if mod["task"] not in KNOWN_TASKS:
            fail(f"{where}: unknown task {mod['task']!r}")
        otype = (mod.get("output") or {}).get("type")
        if otype not in KNOWN_TYPES:
            fail(f"{where}: unknown output.type {otype!r} (known: {sorted(KNOWN_TYPES)})")
        # task/type coherence
        if mod["task"] == "embedding" and otype != "single_vector":
            fail(f"{where}: embedding model must use output.type single_vector")
        if mod["task"] == "detection" and otype == "single_vector":
            fail(f"{where}: detection model cannot use output.type single_vector")
        norm = (mod.get("input") or {}).get("normalization")
        if norm not in KNOWN_NORM:
            fail(f"{where}: missing/unknown input.normalization {norm!r}")
        if mod["task"] == "detection":
            resize = (mod.get("input") or {}).get("resize")
            if resize not in KNOWN_RESIZE:
                fail(f"{where}: detection needs input.resize one of {sorted(KNOWN_RESIZE)}")
        # file present on disk
        fpath = os.path.join(src_dir, mod["file"])
        if not os.path.isfile(fpath):
            fail(f"{where}: file {mod['file']!r} not found in {src_dir}")
        if mod["task"] == "embedding" and "clustering" not in mod:
            log(f"  note: {where} ({mod['id']}) has no clustering block; "
                f"the app will fall back to defaults.")
    return models


def tflite_io_shapes(path):
    """Return (input_shapes, output_shapes) or None if tensorflow is absent."""
    try:
        import tensorflow as tf
    except ImportError:
        return None
    interp = tf.lite.Interpreter(model_path=path)
    interp.allocate_tensors()
    ins = [list(d["shape"]) for d in interp.get_input_details()]
    outs = [list(d["shape"]) for d in interp.get_output_details()]
    return ins, outs


def check_class2(models, src_dir):
    """output.type must fit the model's actual output structure."""
    any_checked = False
    for mod in models:
        shapes = tflite_io_shapes(os.path.join(src_dir, mod["file"]))
        if shapes is None:
            continue  # tensorflow unavailable; skip quietly
        any_checked = True
        ins, outs = shapes
        otype = mod["output"]["type"]
        where = f"{mod['id']} ({otype})"

        if otype == "single_vector":
            if len(outs) != 1 or len(outs[0]) != 2:
                fail(f"{where}: expected exactly one [1,D] output, got {outs}")
        else:  # multistride_*
            # Expect output cell counts consistent with a 640-input stride
            # pyramid: (640/8)^2, (640/16)^2, (640/32)^2 = 6400,1600,400
            # times an anchor count (1 or 2). Derive the input size instead of
            # assuming 640 where possible.
            size = None
            if ins and len(ins[0]) == 4:
                # NHWC [1,S,S,3] or NCHW [1,3,S,S]
                s = ins[0]
                size = s[1] if s[3] == 3 else s[2]
            if not size:
                log(f"  note: {where}: couldn't read input size; skipping stride check")
                continue
            grids = {(size // st) * (size // st) for st in STRIDES}
            cell_counts = {sh[1] for sh in outs if len(sh) >= 2}
            # every output's cell count should be grid*anchors for some grid
            ok = all(
                any(cc % g == 0 and cc // g in (1, 2) for g in grids)
                for cc in cell_counts
            )
            if not ok:
                fail(
                    f"{where}: output cell counts {sorted(cell_counts)} don't match "
                    f"a stride pyramid for input size {size} "
                    f"(grids {sorted(grids)}). Wrong output.type?"
                )
        log(f"  ok: {where} output structure matches.")
    if not any_checked:
        log("  (tensorflow not available — skipped Class-2 tensor checks; "
            "install tensorflow to enable them)")


def pack(src_dir, manifest_name, models, out_path):
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(src_dir, manifest_name), "manifest.yml")
        seen = set()
        for mod in models:
            if mod["file"] in seen:
                continue
            seen.add(mod["file"])
            z.write(os.path.join(src_dir, mod["file"]), mod["file"])
    log(f"\nWrote {out_path} ({os.path.getsize(out_path)//1024} KB) "
        f"with {len(seen)} model file(s).")


def main():
    ap = argparse.ArgumentParser(description="Pack an Eidora .eidoramodel container.")
    ap.add_argument("--dir", required=True, help="Folder with manifest.yml + .tflite files.")
    ap.add_argument("--manifest", default="manifest.yml", help="Manifest filename in --dir.")
    ap.add_argument("--out", required=True, help="Output container path (.eidoramodel).")
    ap.add_argument("--no-tensor-check", action="store_true",
                    help="Skip Class-2 tensor-structure checks.")
    args = ap.parse_args()

    mpath = os.path.join(args.dir, args.manifest)
    if not os.path.isfile(mpath):
        fail(f"{args.manifest} not found in {args.dir}")

    log("Class 1 — manifest well-formed …")
    m = load_manifest(mpath)
    models = check_class1(m, args.dir)
    log(f"  ok: container {m['container']['id']!r}, {len(models)} model(s).")

    if not args.no_tensor_check:
        log("Class 2 — output.type fits each .tflite …")
        check_class2(models, args.dir)

    pack(args.dir, args.manifest, models, args.out)
    log("\nDone. Load this container in Eidora via 'bring your own model'.")
    log("Note: the app still runs its own on-device self-test (Class 3) on real "
        "faces before using the models.")


if __name__ == "__main__":
    main()
