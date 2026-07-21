# Machine Learning Model Licenses

Eidora's source code is licensed under GPL-3.0-or-later. **The machine learning
models it uses are not** — they have their own, more restrictive licenses and
are **not distributed as part of this repository or the APK**. They are fetched
at runtime from the project's GitHub releases, only after the user explicitly
confirms the download.

## Models used

### 1. Face detection — SCRFD (`scrfd_2.5g_kps_640`)

- Source: [InsightFace](https://github.com/deepinsight/insightface)
- Purpose: locates faces and five facial landmarks in a photo.
- License: **non-commercial research use only.**

### 2. Face recognition — ArcFace (`arcface_w600k_mbf`)

- Source: [InsightFace](https://github.com/deepinsight/insightface),
  trained on the WebFace600K dataset.
- Purpose: computes a compact numeric signature (embedding) per face so that
  the same person can be grouped across photos.
- License: **non-commercial research use only.**

## What this means for you

- **Personal, non-commercial use:** fine. This is the intended use of Eidora.
- **Commercial use:** the InsightFace models may **not** be used commercially.
  Eidora's GPL license permits commercial use of the *code*, but you would have
  to supply your own, appropriately licensed models to build a commercial
  product.
- **Redistribution:** do not re-host these model files under a license that
  claims to grant broader rights than InsightFace's original terms.

## Replacing the models

The model list (URLs, filenames, expected SHA-256 hashes, purpose and license
labels) lives in
[`ModelDownloader.kt`](app/src/main/java/org/eidora/ml/ModelDownloader.kt).
To use different models, update those entries. The models must be TensorFlow
Lite (`.tflite`) files with input/output tensors compatible with the existing
detection and embedding pipeline.

Please verify the license of any model you bundle or link, and update this file
accordingly.
