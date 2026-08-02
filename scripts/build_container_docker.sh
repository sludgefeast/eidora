#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Sebastian (Eidora contributors)
#
# Build an Eidora model container locally, using the pinned Docker toolchain so
# you don't have to install tensorflow/onnx2tf yourself. Same conversion path as
# the GitHub workflow, so the output matches CI.
#
# Usage:
#   scripts/build_container_docker.sh [MANIFEST] [OUTPUT]
#
# Defaults build the RESEARCH container (SCRFD + ArcFace):
#   MANIFEST = docs/containers/research-models/manifest.yml
#   OUTPUT   = eidora-research.eidoramodel   (in the repo root)
#
# The research models (InsightFace SCRFD/ArcFace) are non-commercial research
# use only and are downloaded from the onnx_url in the manifest — Eidora does
# not host them. You are responsible for being licensed to use them.
#
# Requirements: Docker. Everything else lives in the image.

set -euo pipefail

# Resolve repo root from this script's location, so it works from anywhere.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MANIFEST="${1:-docs/containers/research-models/manifest.yml}"
OUTPUT="${2:-eidora-research.eidoramodel}"
IMAGE="eidora-container-builder"

cd "$REPO_ROOT"

if ! command -v docker >/dev/null 2>&1; then
    echo "error: docker is not installed or not on PATH." >&2
    exit 1
fi

if [ ! -f "$MANIFEST" ]; then
    echo "error: manifest not found: $MANIFEST" >&2
    exit 1
fi

# Build the image if it isn't present yet (first run only).
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "==> Building the builder image (first run, this takes a few minutes)…"
    docker build -t "$IMAGE" "$REPO_ROOT/docker"
fi

echo "==> Building container"
echo "    manifest: $MANIFEST"
echo "    output:   $OUTPUT"

# Mount the repo read-write so the build can fetch, convert, and write output.
# --network is needed because the build downloads the ONNX from onnx_url.
docker run --rm \
    -v "$REPO_ROOT:/work" \
    "$IMAGE" \
    --manifest "$MANIFEST" \
    --out "$OUTPUT"

echo ""
echo "==> Done: $REPO_ROOT/$OUTPUT"
echo "    Import it in Eidora via Settings -> Models -> Import container."
