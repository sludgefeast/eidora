#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Sebastian (Eidora contributors)
"""
Pack a set of models + a manifest into an Eidora model container
(.eidoramodel).

An Eidora model container is just a .zip holding:

    manifest.yml                 # describes the set (see docs/model-container.md)
    <model>.tflite               # one file per model entry's `file:`
    ...

This script takes a manifest.yml and the .tflite files it references (already in
one folder) and writes <name>.eidoramodel.

Scope: this is a packer, not a validator. It does a Class-1 check — that the
manifest is well-formed and every referenced file is present — and verifies any
`sha256` a model entry declares (refusing to pack on a mismatch, but never
rewriting the manifest). The deeper checks live in the app at import time,
deliberately not duplicated here (a second copy would drift):

  - Class 2 (does output.type fit the .tflite tensor structure?) — ContainerValidator
  - per-file SHA-256 verification — ContainerStore.importContainer
  - Class 3 (on-device self-test on real faces) — the self-test screen

If you want per-file `sha256` in the manifest (the app verifies it on import),
add it yourself; `sha256sum <file>` gives the value.

Requirements
------------
    pip install pyyaml

Usage
-----
    # folder contains manifest.yml + the referenced .tflite files
    python pack_container.py --dir ./my-models --out my-models.eidoramodel
"""

import argparse
import hashlib
import os
import sys
import zipfile

KNOWN_SCHEMA = {1}
KNOWN_TASKS = {"detection", "embedding"}
KNOWN_TYPES = {"multistride_scrfd", "multistride_yunet", "single_vector"}
KNOWN_NORM = {"raw_0_255", "signed_127_127", "signed_127_128", "zero_to_one"}
KNOWN_RESIZE = {"letterbox", "stretch"}


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


def check_declared_hashes(models, src_dir):
    """Where a model declares sha256, verify it matches the file. A declared
    hash that's wrong means the manifest and .tflite are out of sync — fail
    rather than ship a container the app will reject on import. Models without
    a sha256 are skipped (the field is optional)."""
    for mod in models:
        declared = (mod.get("sha256") or "").strip().lower()
        if not declared:
            continue
        actual = hashlib.sha256(
            open(os.path.join(src_dir, mod["file"]), "rb").read()
        ).hexdigest()
        if actual != declared:
            fail(f"{mod['file']}: manifest sha256 {declared[:16]}… "
                 f"but file is {actual[:16]}…")
        log(f"  ok: {mod['file']} matches declared sha256")


def main():
    ap = argparse.ArgumentParser(description="Pack an Eidora .eidoramodel container.")
    ap.add_argument("--dir", required=True, help="Folder with manifest.yml + .tflite files.")
    ap.add_argument("--manifest", default="manifest.yml", help="Manifest filename in --dir.")
    ap.add_argument("--out", required=True, help="Output container path (.eidoramodel).")
    args = ap.parse_args()

    mpath = os.path.join(args.dir, args.manifest)
    if not os.path.isfile(mpath):
        fail(f"{args.manifest} not found in {args.dir}")

    # Class-1 only: manifest well-formed + every referenced file present, so we
    # don't produce an obviously-broken container. Deeper structural checks
    # (tensor shape vs output.type) are the app's job at import time; duplicating
    # them here would be a second source of truth that can drift. We do verify
    # any sha256 the author declared, so a mismatched hash is caught before the
    # container ships (not written — only checked; the manifest is left as-is).
    log("Checking manifest is well-formed …")
    m = load_manifest(mpath)
    models = check_class1(m, args.dir)
    log(f"  ok: container {m['container']['id']!r}, {len(models)} model(s).")

    check_declared_hashes(models, args.dir)

    pack(args.dir, args.manifest, models, args.out)
    log("\nDone. Load this container in Eidora via 'bring your own model'.")
    log("Note: the app validates the models (tensor structure, hashes) and runs "
        "an on-device self-test on import — this tool only packs.")


if __name__ == "__main__":
    main()
