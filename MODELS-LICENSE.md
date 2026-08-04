# Machine Learning Model Licenses

Eidora's source code is licensed under GPL-3.0-or-later. **The machine learning
models it uses have their own separate licenses** and are **not distributed as
part of this repository or the APK**. They are fetched at runtime from the
project's GitHub releases, only after the user explicitly confirms the download.

## Two licenses per model

ML models carry **two** licenses that are easy to confuse:

1. the **code / architecture** license (the network structure), and
2. the **weights** license (the trained numbers), which is usually tied to the
   dataset the model was trained on.

In practice the **more restrictive of the two governs** what you may do. For the
models Eidora ships, both licenses are Apache-2.0, so the effective license is
Apache-2.0.

## The models Eidora ships

Eidora ships **one** model set — the free container — and it is fully free for
any purpose, including commercial use. It is what the F-Droid build downloads.

### Face detection — YuNet (`yunet_2023mar`)

- Source: [OpenCV Zoo](https://github.com/opencv/opencv_zoo)
  (`face_detection_yunet`).
- Purpose: locates faces and five facial landmarks in a photo.
- Code: Apache-2.0. Weights: Apache-2.0.
- **Effective license: Apache-2.0** — free for any purpose, included in
  F-Droid builds, redistributable.

### Face recognition (embedding) — SFace (`sface_opencv`)

- Source: [OpenCV](https://huggingface.co/opencv/face_recognition_sface)
  (MobileFaceNet backbone, SFace loss).
- Purpose: computes a compact numeric signature (embedding) per face so the
  same person can be grouped across photos.
- Code: Apache-2.0. Weights: Apache-2.0.
- **Effective license: Apache-2.0** — free for any purpose, included in
  F-Droid builds, redistributable. Designed to pair with YuNet.

## What this means for you

- **The models Eidora ships (YuNet + SFace) are fully free** under Apache-2.0.
  They may be used for any purpose, including commercially, and ship in the
  F-Droid build.
- **Redistribution:** these Apache-2.0 models may be re-hosted freely (keep the
  license and attribution).

No non-free model is part of Eidora, bundled in the APK, or required for the app
to function.

## Bringing your own model

Eidora can import a custom model container that you build yourself, if you have
a model you are licensed to use. This is entirely separate from the app and its
free models: Eidora does not host, reference, or fetch any such model.

Some models people build this way — for example InsightFace's SCRFD or ArcFace —
are **non-commercial research use only**. That restriction lives with those
models and their weights, not with Eidora. If you build a container from a model
like that, you are bound by its license, and you must not re-host it under terms
claiming broader rights than its original ones. Eidora's own repository and
workflows deliberately never reference or fetch such models, to keep the
project's licensing clean.

See [`scripts/README.md`](scripts/README.md) for how to build and import a
container. The generic build workflow names no specific model and publishes
nothing — you supply the model and get the container back as a private artifact.

Please verify the license of any model you build a container from.
