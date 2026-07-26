# Eidora Embedding Model Manifest — Specification (draft)

> **Superseded packaging:** models now ship in a **container** with a single
> `manifest.yml` describing a *set* of models — see
> [`model-container.md`](model-container.md), which is authoritative for
> structure and packaging. This document remains the **field-level reference for
> the embedding entry** (input/output/clustering details); read it for what each
> embedding field means, but package per `model-container.md`.

This document defines the **manifest** for a "bring your own" face-**embedding**
(recognition) model — the counterpart to the detection manifest. An embedding
model is distributed as two files:

```
my_embedder.tflite        # the model weights
my_embedder.json          # this manifest — how to run and read the model
```

An embedding model takes an aligned face crop and produces a fixed-length
numeric vector (the "embedding") so the same person can be grouped across
photos. As with detection, different embedders are not interchangeable: they
differ in input normalization, output dimension, and the distance thresholds
that make clustering work. The manifest supplies these so one generic loader
runs any compatible model — including Eidora's default (SFace), packaged the
same way.

**Packaging (decided):** same as detection — a single **container** (`.zip`,
suggested `.eidoramodel`) holding `model.tflite` + `manifest.json`. Detection and
embedding use **separate schemas**. Validation is described in
[`model-validation.md`](model-validation.md).

This is a specification only. Nothing here is wired into the app yet.

---

## Scope and assumptions

This targets the embedder family Eidora supports: a single-input CNN taking a
square RGB face crop and emitting one embedding vector, compared by cosine
distance (MobileFaceNet-style: ArcFace, SFace). Models needing multi-crop input,
non-cosine metrics, or built-in landmark alignment are out of scope for now.

### Family: only one, and both models share it

Unlike detection (two decode families), embedding has just **one family**. Both
ArcFace and SFace run the identical path — crop in → `interpreter.run` → vector
out → the same `cosineDistance`. There is no per-model decode logic; they differ
**only** in parameters (normalization and — derivable — output dimension) plus
their clustering thresholds. So there is effectively one embedding family,
`single_vector`, and any MobileFaceNet-style embedder fits it by manifest alone.
This is why the embedding manifest is so much smaller than the detection one.

**Known simplification:** the current pipeline feeds the detector's face box
directly (scaled to the model's input size) **without** landmark-based
alignment. ArcFace/SFace nominally expect a 5-point aligned crop; skipping
alignment costs some accuracy but keeps the pipeline simple. The manifest
reflects what the app does today; an `alignment` field is listed under open
questions for later.

A field marked **required** must be present; **optional** fields have defaults.

---

## Fields

### Identity & display

| Field | Type | Req | Meaning |
|---|---|---|---|
| `schema_version` | int | required | Manifest schema version. Currently `1`. |
| `id` | string | required | Stable unique id, e.g. `"sface_opencv"`. Settings key. |
| `name` | string | required | Short display name, e.g. `"SFace"`. |
| `description` | string | optional | One line shown under the name. |
| `filename` | string | required | The `.tflite` filename this manifest belongs to. |
| `version` | string | optional | Model version/date. |
| `source_url` | string | optional | Where the weights came from. |

### License (shown before use) — identical shape to the detection manifest

| Field | Type | Req | Meaning |
|---|---|---|---|
| `license.name` | string | required | Effective license, e.g. `"Apache-2.0"`. |
| `license.free` | bool | required | `true` if free for any use. |
| `license.reason` | string | optional | Why. |
| `license.url` | string | optional | Link to full license. |

### Input

Derivable from the input tensor, so **not** in the manifest:
- **input size** → input tensor spatial dim.
- **input layout** → channel-axis position (NHWC vs NCHW).

In the manifest (training conventions, not stored in the model):

| Field | Type | Req | Meaning |
|---|---|---|---|
| `input.color` | enum | optional | `"RGB"` (default) or `"BGR"`. Not derivable. |
| `input.normalization` | enum | required | Pixel scaling — see below. Not derivable. |

`input.normalization` (shared vocabulary with the detection manifest):

- `"raw_0_255"` — pixels as-is `[0,255]`. (SFace — verified: raw pixels
  reproduce OpenCV's reference embeddings at cosine 1.00000.)
- `"signed_127_127"` — `(pixel - 127.5) / 127.5` → `[-1,1]`. (ArcFace)
- `"zero_to_one"` — `pixel / 255` → `[0,1]`.
- `"signed_127_128"` — `(pixel - 127.5) / 128`. (detector-side; listed for
  completeness)

> Using the wrong normalization silently produces garbage embeddings — SFace
> with `signed_127_127` scores ~0.03 cosine vs. the correct 1.00000. This is the
> single most important field to get right, which is why a one-photo self-test
> is proposed under open questions.

### Output

**embedding_dim** is derivable (the output tensor's last dim), so it is **not**
in the manifest. Only the interpretation of the vector is:

| Field | Type | Req | Meaning |
|---|---|---|---|
| `output.distance` | enum | optional | `"cosine"` (default). Only cosine supported today. |
| `output.l2_normalized` | bool | optional | Whether the model already L2-normalizes its output. Default `false` (the app normalizes when computing cosine distance). Not derivable. |

### Clustering thresholds (required for embedding models)

Embeddings from different models live in different spaces, so thresholds do not
transfer between models. These become the defaults when the model is selected;
the user can still override them in settings. All are **cosine distance**
(`1 - similarity`); larger = more permissive.

| Field | Type | Req | Meaning |
|---|---|---|---|
| `clustering.edge` | float | required | Graph edge threshold for clustering. |
| `clustering.cluster_match` | float | required | Cluster-to-person match threshold. |
| `clustering.individual_match` | float | required | Single-face-to-person match threshold. |

---

## Example: SFace (Eidora default, free)

```json
{
  "schema_version": 1,
  "id": "sface_opencv",
  "name": "SFace",
  "description": "Compact 128-dim embedding. Pairs with YuNet. Apache-2.0.",
  "filename": "sface_opencv_float32.tflite",
  "version": "2021dec",
  "source_url": "https://huggingface.co/opencv/face_recognition_sface",
  "license": {
    "name": "Apache-2.0",
    "free": true,
    "reason": "Both code and weights are Apache-2.0 (OpenCV). Free for any purpose, including commercial.",
    "url": "https://github.com/opencv/opencv_zoo/blob/main/LICENSE"
  },
  "input": {
    "size": 112,
    "layout": "NHWC",
    "color": "RGB",
    "normalization": "raw_0_255"
  },
  "output": {
    "embedding_dim": 128,
    "distance": "cosine",
    "l2_normalized": false
  },
  "clustering": {
    "edge": 0.64,
    "cluster_match": 0.68,
    "individual_match": 0.64
  }
}
```

## Example: ArcFace (bring-your-own, research only)

```json
{
  "schema_version": 1,
  "id": "arcface_w600k_mbf",
  "name": "ArcFace w600k",
  "description": "512-dim embedding, higher accuracy.",
  "filename": "arcface_w600k_mbf_float32.tflite",
  "version": "w600k_mbf",
  "source_url": "https://github.com/deepinsight/insightface",
  "license": {
    "name": "Non-commercial (research only)",
    "free": false,
    "reason": "Weights trained by InsightFace on WebFace600K, a research-only dataset. The code is open, but the weights restrict use.",
    "url": "https://github.com/deepinsight/insightface#license"
  },
  "input": {
    "size": 112,
    "layout": "NHWC",
    "color": "RGB",
    "normalization": "signed_127_127"
  },
  "output": {
    "embedding_dim": 512,
    "distance": "cosine",
    "l2_normalized": false
  },
  "clustering": {
    "edge": 0.50,
    "cluster_match": 0.55,
    "individual_match": 0.50
  }
}
```

---

## Detection vs. embedding manifest — shared and different

**Shared** (keep identical across both, so the loader reuses code):
`schema_version`, `id`, `name`, `description`, `filename`, `version`,
`source_url`, the entire `license` block, and the `input.normalization`
vocabulary.

**Detection-only:** `input.resize`, `input.pad_color`, the whole `output`
decoding block (`type`, `strides`, `anchors_per_cell`, thresholds, landmark/
rotation flags).

**Embedding-only:** `output.embedding_dim`, `output.distance`,
`output.l2_normalized`, and the `clustering` block.

This suggests a shared manifest header (identity + license + input) plus a
task-specific section — worth factoring in when we design the loader.

---

## Open questions (to resolve before implementing)

1. **Alignment.** Add an optional `input.alignment` field (`"none"` today,
   `"5point_arcface"` later) so a future aligned pipeline is manifest-driven?
   For now the app does no alignment, so `"none"` would be the only value.

2. **Self-test.** Because a wrong `normalization` fails silently, a "run this
   model on one face and show the result" step would catch mistakes before a
   full library scan. Same idea flagged in the detection manifest.

3. **Threshold discovery.** Clustering thresholds are model-specific and hard to
   guess for a truly novel model. Do we ship guidance (e.g. "start at the
   model's published cosine threshold, convert to distance") or a small
   calibration helper?

4. **Shared header factoring.** Given the large shared section, do we define one
   `manifest.json` with a `task: "detection" | "embedding"` discriminator, or
   keep two separate files? A single schema with a discriminator may be cleaner.

5. **Pinning.** Optional `sha256` of the `.tflite` so a manifest can verify its
   own model file, mirroring how the app pins the built-in downloads today.
