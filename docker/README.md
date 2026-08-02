# Building an Eidora model container with Docker

This lets you build a model container **locally**, on your own machine, using
the same pinned toolchain the GitHub workflow uses — without installing
tensorflow, onnx2tf and the rest of the fragile ONNX → TFLite stack yourself.

It's aimed mainly at the **research container** (SCRFD detector + ArcFace
embedder), which Eidora does not ship: those InsightFace weights are
non-commercial research use only, so you build the container yourself from the
manifest in the repo.

## Requirements

- Docker (nothing else — the toolchain lives in the image).
- An internet connection for the build (the models are downloaded from the
  `onnx_url` in the manifest).

## Quick start

From the repo root:

```sh
scripts/build_container_docker.sh
```

That builds the research container with the defaults:

- manifest: `docs/containers/research-models/manifest.yml`
- output: `eidora-research.eidoramodel` in the repo root

On the first run it builds the Docker image (a few minutes); later runs reuse
it and go straight to converting.

## Building a different container

Pass a manifest and output name:

```sh
scripts/build_container_docker.sh path/to/manifest.yml my-container.eidoramodel
```

Any manifest whose models carry a `build:` block (with `onnx_url`, `input_name`,
`size`) works — the same manifests both GitHub workflows build from.

## What it does

1. Builds a `python:3.10-slim` image with the exact pinned versions from
   `.github/workflows/build-your-own-container.yml`
   (`tensorflow==2.17.0`, `onnx2tf==1.26.3`, …) and the onnx2tf calibration
   workaround baked in.
2. Mounts your checked-out repo at `/work` and runs
   `scripts/build_container.py`, which for each model in the manifest downloads
   the ONNX, converts it to TFLite, and packs the `.tflite` files + manifest
   into a `.eidoramodel`.
3. Writes the finished `.eidoramodel` back into your repo root (via the mount).

Because the image is generic and your scripts + manifest are mounted at run
time, editing a manifest or a script takes effect on the next run with no image
rebuild.

## Installing the result

Copy the `.eidoramodel` to your device and import it in Eidora via
**Settings → Models → Import container**.

## Licensing

The research models (InsightFace SCRFD / ArcFace) are **non-commercial research
use only**. Eidora does not host or redistribute them — the build downloads them
from the `onnx_url` in the manifest, and you are responsible for being licensed
to use them. See `docs/containers/research-models/manifest.yml` and the
project's `ATTRIBUTION.md` for the details of each model.

This is not legal advice.
