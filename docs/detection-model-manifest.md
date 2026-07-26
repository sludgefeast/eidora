# Eidora Detection Model Manifest — Specification (draft)

> **Superseded packaging:** models now ship in a **container** with a single
> `manifest.yml` describing a *set* of models — see
> [`model-container.md`](model-container.md), which is authoritative for
> structure and packaging. This document remains the **field-level reference for
> the detection entry** (input/output/family details); read it for what each
> detection field means, but package per `model-container.md`.

This document defines the **manifest** that accompanies a "bring your own"
face-**detection** model. A detection model is distributed as two files:

```
my_detector.tflite        # the model weights
my_detector.json          # this manifest — how to run and read the model
```

The manifest exists because different detectors are **not interchangeable**:
they use different input layouts, normalizations, pre-processing, and output
decoding. The app cannot guess these. The manifest supplies exactly the
parameters the detector code needs, so one generic loader can run any compatible
model — including Eidora's own defaults, which are packaged the same way (no
special-casing for built-ins).

**Packaging (decided):** a model is distributed as a single **container** — a
`.zip` (suggested extension `.eidoramodel`) holding exactly `model.tflite` and
`manifest.json`. The user picks one file, not two. Detection and embedding use
**separate schemas** (no shared `task` discriminator); the loader picks the
schema from which slot the user is filling.

This is a specification only. Nothing here is wired into the app yet.

---

## Scope and assumptions

This first draft targets the detector **family** Eidora already supports:
anchor-based / anchor-free multi-stride detectors (SCRFD, YuNet) that output,
per stride, a face score, a bounding box, and 5 facial landmarks. Models far
outside this family (e.g. a single-output SSD, or a transformer detector) are
**out of scope** for now — the manifest describes this family well and honestly,
rather than pretending to be universal.

### What "family" means here, and which we currently have

A **family** = one box-decode implementation in the app. Two models are the same
family if the same decode code turns their raw tensors into faces; they are
different families if the decode arithmetic itself differs (a couple of flags
can't bridge it). `output.type` in the manifest names the family.

Eidora currently implements **two detection families**:

1. **`multistride_scrfd`** — SCRFD-style. Box via `distance2bbox`: the four
   outputs are distances from the cell centre to the box edges
   (`x1 = cx − d_left·stride`, `x2 = cx + d_right·stride`). 2 anchors/cell,
   score = cls, rotation derived from eye landmarks.

2. **`multistride_yunet`** — YuNet-style. Box via centre-offset + exponential
   size (`cx = (col + Δx)·stride`, `w = exp(w')·stride`). Anchor-free (1/cell),
   score = √(cls·obj), no rotation.

These aren't reconcilable by a boolean — the box math is genuinely different —
which is why they're two families, not one parameterized decoder. A new model
that decodes boxes some third way (e.g. an SSD or DETR-style head) would need a
new family (new decode code), and until then can't be added by manifest alone.
That is the honest ceiling of the data-only manifest approach.

A field marked **required** must be present; **optional** fields have defaults
noted.

---

## Fields

### Identity & display

| Field | Type | Req | Meaning |
|---|---|---|---|
| `schema_version` | int | required | Manifest schema version. Currently `1`. |
| `id` | string | required | Stable unique id, e.g. `"yunet"`. Used as the settings key. |
| `name` | string | required | Short display name, e.g. `"YuNet"`. |
| `description` | string | optional | One line shown under the name. |
| `filename` | string | required | The `.tflite` filename this manifest belongs to. |
| `version` | string | optional | Model version/date, e.g. `"2023mar"`. |
| `source_url` | string | optional | Where the weights came from (attribution/traceability). |

### License (shown to the user before use)

| Field | Type | Req | Meaning |
|---|---|---|---|
| `license.name` | string | required | Effective license, e.g. `"Apache-2.0"` or `"Non-commercial (research only)"`. |
| `license.free` | bool | required | `true` if free for any use (incl. commercial); `false` if restricted. |
| `license.reason` | string | optional | Why — e.g. what the weights were trained on. |
| `license.url` | string | optional | Link to the full license text. |

### Input

Only fields that the `.tflite` does **not** expose belong here. The app reads
the input tensor directly for everything derivable:

- **input size** → from the input tensor shape (e.g. `[1,640,640,3]` → 640).
- **input layout** → NHWC vs NCHW is visible from where the channel axis sits
  (`[1,S,S,3]` = NHWC, `[1,3,S,S]` = NCHW).

So the manifest does **not** carry `size` or `layout`. It carries only the
pre-processing conventions, which are training choices not stored in the model:

| Field | Type | Req | Meaning |
|---|---|---|---|
| `input.color` | enum | optional | `"RGB"` (default) or `"BGR"`. Not derivable. |
| `input.normalization` | enum | required | Pixel scaling — see below. Not derivable. |
| `input.resize` | enum | required | `"letterbox"` (keep aspect, pad) or `"stretch"` (fill square). Not derivable. |
| `input.pad_color` | int | optional | Letterbox pad value, 0–255. Default `0`. |

`input.normalization` is one of:

- `"raw_0_255"` — pixels used as-is, `[0,255]`. (YuNet)
- `"signed_127_128"` — `(pixel - 127.5) / 128`. (SCRFD)
- `"signed_127_127"` — `(pixel - 127.5) / 127.5` → `[-1,1]`.
- `"zero_to_one"` — `pixel / 255` → `[0,1]`.

(These mirror the embedding side's normalization enum, extended with the
detector-specific `signed_127_128`.)

### Output decoding

Some of this is derivable from the output tensor shapes, some is pure decode
convention. Derivable → the app computes it; not derivable → manifest.

Derivable (NOT in the manifest):
- **strides** → from the ratios of output cell counts. For a 640 input, cell
  counts 6400 / 1600 / 400 imply grids 80/40/20, i.e. strides 8/16/32.
- **anchors_per_cell** → output cell count ÷ (grid²). If stride-8 output has
  12800 rows and the grid is 80×80=6400, anchors = 2.
- **has_landmarks** → whether a width-10 output tensor exists per stride.

Not derivable (in the manifest) — pure decode/post-process convention:

| Field | Type | Req | Meaning |
|---|---|---|---|
| `output.type` | enum | required | Decoder family: `"multistride_scrfd"` or `"multistride_yunet"`. Selects box-decode math. |
| `output.score_threshold` | float | optional | Min score to keep. Default `0.5`. |
| `output.nms_iou_threshold` | float | optional | IoU to merge boxes. Default `0.4`. |
| `output.rotation_from_eyes` | bool | optional | Face roll from eye landmarks. Default `false`. |
| `output.score_is_cls_times_obj` | bool | optional | Score = sqrt(cls·obj) (YuNet) vs cls only (SCRFD). Default `false`. |

Tensor-role mapping (which output is cls/bbox/kps per stride) is resolved at load
time from tensor shapes, as both current detectors already do — so it is never
in the manifest.

Note: `output.type` overlaps with the two flags below it. It stays because the
box-decode arithmetic itself (SCRFD's distance-to-corner vs YuNet's
centre+exp-size) differs in a way a couple of booleans don't fully capture. See
open question 4.

### Clustering defaults (optional)

Detection doesn't produce embeddings, so it has no clustering thresholds of its
own. This section is omitted for detection manifests. (It appears in the
embedding-model manifest instead.)

---

## Example: YuNet (Eidora default, free)

```json
{
  "schema_version": 1,
  "id": "yunet",
  "name": "YuNet",
  "description": "Locates faces and 5 landmarks. Light and fast.",
  "filename": "yunet_2023mar_float32.tflite",
  "version": "2023mar",
  "source_url": "https://github.com/opencv/opencv_zoo",
  "license": {
    "name": "Apache-2.0",
    "free": true,
    "reason": "Both code and weights are Apache-2.0 (OpenCV Zoo). Free for any purpose, including commercial.",
    "url": "https://github.com/opencv/opencv_zoo/blob/main/LICENSE"
  },
  "input": {
    "size": 640,
    "layout": "NCHW",
    "color": "RGB",
    "normalization": "raw_0_255",
    "resize": "letterbox",
    "pad_color": 0
  },
  "output": {
    "type": "multistride_yunet",
    "strides": [8, 16, 32],
    "anchors_per_cell": 1,
    "score_threshold": 0.6,
    "nms_iou_threshold": 0.3,
    "has_landmarks": true,
    "rotation_from_eyes": false,
    "score_is_cls_times_obj": true
  }
}
```

## Example: SCRFD (bring-your-own, research only)

```json
{
  "schema_version": 1,
  "id": "scrfd_2.5g_kps",
  "name": "SCRFD 2.5G",
  "description": "Higher accuracy on small/rotated faces.",
  "filename": "scrfd_2.5g_kps_640_float32.tflite",
  "version": "2.5g_kps",
  "source_url": "https://github.com/deepinsight/insightface",
  "license": {
    "name": "Non-commercial (research only)",
    "free": false,
    "reason": "Weights trained by InsightFace on a research-only dataset. The code is open, but the weights restrict use.",
    "url": "https://github.com/deepinsight/insightface#license"
  },
  "input": {
    "size": 640,
    "layout": "NHWC",
    "color": "RGB",
    "normalization": "signed_127_128",
    "resize": "stretch",
    "pad_color": 0
  },
  "output": {
    "type": "multistride_scrfd",
    "strides": [8, 16, 32],
    "anchors_per_cell": 2,
    "score_threshold": 0.5,
    "nms_iou_threshold": 0.4,
    "has_landmarks": true,
    "rotation_from_eyes": true,
    "score_is_cls_times_obj": false
  }
}
```

---

## Open questions (to resolve before implementing)

1. **Validation strategy.** On load, how strictly do we validate the `.tflite`
   against the manifest? Minimum: check input tensor shape matches
   `input.size`/`input.layout`. Stronger: check the output tensor count/shapes
   are consistent with `strides` × `anchors_per_cell`. Stronger validation =
   fewer silent "garbage results" but more code.

2. **Where do the two files live?** One user-picked `.tflite` + one `.json`,
   picked together? Or a small zip/folder? Picking two files is clumsy; a
   folder or a single container may be nicer.

3. **Trust.** The manifest is user-supplied text. It can't do harm by itself,
   but a wrong `normalization` silently produces bad detections. Do we surface
   a "test on one photo" step so the user sees it works before a full run?

4. **Do we keep `multistride_*` as two decoder types, or unify?** The two
   decoders share most logic; the `output.*` flags may be enough to collapse
   them into one parameterized decoder later. For now, keeping them explicit is
   clearer.

5. **Signing/versioning.** `schema_version` lets us evolve the format. Do we
   also want an optional `sha256` field so a manifest can pin its `.tflite`?
