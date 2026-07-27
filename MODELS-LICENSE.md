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

In practice the **more restrictive of the two governs** what you may do: a
permissive code license cannot lift a research-only restriction on the weights.
For every model below, the code is open — so the effective license is decided
by the **weights**. That is the license stated as "Effective" for each model.

## Models Eidora can use

Eidora lets you choose, separately, which **detection** model and which
**embedding** model to use. Each task offers a free (Apache-2.0) default and an
optional, more accurate but research-only alternative.

### Face detection

#### YuNet (`yunet_2023mar`) — default, free

- Source: [OpenCV Zoo](https://github.com/opencv/opencv_zoo)
  (`face_detection_yunet`).
- Purpose: locates faces and five facial landmarks in a photo.
- Code: Apache-2.0. Weights: Apache-2.0.
- **Effective license: Apache-2.0** — free for any purpose, included in
  F-Droid builds, redistributable.

#### SCRFD (`scrfd_2.5g_kps_640`) — optional, research-only

- Source: [InsightFace](https://github.com/deepinsight/insightface).
- Purpose: same as YuNet; higher quality on small/rotated faces.
- Code: open. Weights: trained by InsightFace on a research-only dataset.
- **Effective license: non-commercial research use only.** Not part of F-Droid
  builds; downloaded separately only if the user selects it.

### Face recognition (embedding)

#### SFace (`sface_opencv`) — default, free

- Source: [OpenCV](https://huggingface.co/opencv/face_recognition_sface)
  (MobileFaceNet backbone, SFace loss).
- Purpose: computes a compact numeric signature (embedding) per face so the
  same person can be grouped across photos.
- Code: Apache-2.0. Weights: Apache-2.0.
- **Effective license: Apache-2.0** — free for any purpose, included in
  F-Droid builds, redistributable. Designed to pair with YuNet.

#### ArcFace (`arcface_w600k_mbf`) — optional, research-only

- Source: [InsightFace](https://github.com/deepinsight/insightface), trained on
  the WebFace600K dataset.
- Purpose: same as SFace; somewhat higher accuracy.
- Code: open. Weights: trained on a research-only dataset.
- **Effective license: non-commercial research use only.** Not part of F-Droid
  builds; downloaded separately only if the user selects it.

## What this means for you

- **The default configuration (YuNet + SFace) is fully free** under Apache-2.0.
  It may be used for any purpose, including commercially, and ships in the
  F-Droid build.
- **The optional models (SCRFD, ArcFace) are non-commercial research only.**
  Eidora's GPL license permits commercial use of the *code*, but if you select
  an InsightFace model you are bound by its research-only weights license.
- **Redistribution:** the Apache-2.0 models may be re-hosted freely (keep the
  license and attribution). Do **not** re-host the InsightFace models under a
  license claiming broader rights than their original terms.

## How the F-Droid build stays free

F-Droid builds default to YuNet + SFace, both Apache-2.0. The non-free models
are never bundled and are only ever downloaded if a user explicitly switches to
them in Settings, after reading the license shown there. No non-free model is
required for the app to function.

## Replacing the models

The model list (URLs, filenames, expected SHA-256 hashes, purpose and license
labels) lives in
[`ModelDownloader.kt`](app/src/main/java/org/eidora/ml/ModelDownloader.kt), and
the per-model specs (input size, embedding dimension, normalization, thresholds,
license) live in
[`EmbeddingModelSpec.kt`](app/src/main/java/org/eidora/ml/EmbeddingModelSpec.kt)
and [`FaceDetector.kt`](app/src/main/java/org/eidora/ml/FaceDetector.kt).
To use different models, update those entries. Models must be TensorFlow Lite
(`.tflite`) files whose input/output tensors are compatible with the existing
detection and embedding pipeline.

The conversion from an upstream ONNX model to TFLite is reproducible via the
GitHub Actions workflows in [`.github/workflows/`](.github/workflows/): the free
models are built by `build-free-container.yml`, and any other model you're
licensed to use can be built with the generic `build-your-own-container.yml`,
which names no specific model and publishes nothing — you supply the model and
get the container back as a private artifact.

Research-only models (e.g. SCRFD/ArcFace from InsightFace) are deliberately NOT
referenced or fetched by any workflow in this repository, to keep its licensing
clean. If you obtain such a model yourself and its licence permits your use, the
generic workflow (or the local scripts) will build a container from it.

Please verify the license of any model you bundle or link, and update this file
accordingly.
