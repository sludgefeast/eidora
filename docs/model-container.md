# Eidora Model Container — Specification (draft)

A **model container** is how models are distributed to and loaded by Eidora. It
bundles a coherent **set** of models — typically one detector plus a matching
embedder — behind a single YAML manifest. Detection and embedding still use
separate schemas (per model entry); the container just groups them.

```
free-models.eidoramodel        # a .zip
├── manifest.yml               # describes the set (below)
├── yunet_2023mar.tflite       # detector weights
└── sface_opencv.tflite        # embedder weights
```

The user picks one container file; it may add a detector, an embedder, or both
at once.

## Design decisions (fixed)

- **YAML** manifest (`manifest.yml`), not JSON — easier to hand-write/read.
- **Container has a stable `id`**; each model has its own `id`. Identity for
  collision checks is the pair `(container.id, model.id)`.
- **Decoder family is `output.type`** on each model entry (a separate `family`
  node was dropped as redundant). See the family list below.
- **Multiple models per container**, mixed by task: a container holds a *set*
  (e.g. detector + embedder that belong together). Our own models ship as two
  containers: a **free** set (YuNet + SFace) and a **research** set
  (SCRFD + ArcFace) — the split matches the licence, so a container is wholly
  free or wholly research-only.
- **Per-model acceptance:** good models are taken into Eidora; invalid ones are
  rejected with a message. One bad entry doesn't sink the rest.
- **Collision handling:** if a loaded `(container.id, model.id)` already exists,
  the user decides (replace / keep both / cancel) — no silent overwrite.
- **Only non-derivable values** live in the manifest; anything the `.tflite`
  exposes (input size, layout, embedding dim, strides, anchor count) is read
  from the model, never restated. See `model-validation.md`.

## Resolved (previously open) questions

1. **Partial acceptance** → accept per-model; reject an invalid model with a
   specific message, keep the valid ones.
2. **Same id across containers** → identity is `(container.id, model.id)`; on
   collision the user decides rather than a silent last-wins.
3. **`output.type` vs `family`** → keep `output.type`, drop `family`.

## Decoder families (the `output.type` node)

`output.type` selects the built-in decoder. Eidora currently implements three:

| `output.type` | task | decode |
|---|---|---|
| `multistride_scrfd` | detection | distance-to-edge boxes, 2 anchors/cell, rotation from eyes |
| `multistride_yunet` | detection | centre-offset + exp-size boxes, anchor-free, score = √(cls·obj) |
| `single_vector` | embedding | one vector out, cosine distance |

A model whose decode fits none of these can't be added by manifest alone (it
needs a new decoder in the app). This is the honest ceiling of the data-only
approach.

## Manifest structure

```yaml
schema_version: 1

container:
  id: <stable unique container id, e.g. "eidora-free">
  name: <display name of the set>
  description: <one line>            # optional
  # A convenience only; each model still carries its own licence.
  free: <true|false>

models:
  - id: <stable id, settings key>
    task: <detection|embedding>
    file: <the .tflite filename in this container>
    sha256: <optional hex SHA-256 of the .tflite>
    name: <display name>
    description: <one line>          # optional
    version: <string>               # optional
    source_url: <string>            # optional

    license:
      name: <e.g. "Apache-2.0">
      free: <true|false>
      reason: <why>                 # optional
      url: <link>                   # optional

    input:
      color: <RGB|BGR>              # optional, default RGB
      normalization: <raw_0_255|signed_127_127|signed_127_128|zero_to_one>
      # detection only:
      resize: <letterbox|stretch>
      pad_color: <0-255>            # optional, default 0

    output:
      # names the decoder family — the authoritative field:
      type: <multistride_scrfd|multistride_yunet|single_vector>
      # detection only:
      score_threshold: <float>      # optional
      nms_iou_threshold: <float>    # optional
      rotation_from_eyes: <bool>    # optional
      score_is_cls_times_obj: <bool> # optional
      # embedding only:
      distance: <cosine>            # optional, default cosine
      l2_normalized: <bool>         # optional, default false

    # embedding only — model-specific clustering defaults (cosine distance):
    clustering:
      edge: <float>
      cluster_match: <float>
      individual_match: <float>
```

Notes:
- `model.sha256` is optional. When present, it's the hex SHA-256 of that model's
  `.tflite`. On **import** of a user-supplied container Eidora verifies each
  declared hash and rejects a mismatch (the source isn't trusted). On the
  **repo download** the whole-container hash already covers integrity, so
  per-file hashes aren't required there. Add it with `sha256sum <file>` if you
  want import-time verification for a container you distribute.
- `container.id` is a stable unique id for the whole set, used together with
  each model's `id` when checking for collisions on load (see Validation).
- `output.type` names the decoder family — it is the authoritative field. (An
  earlier draft had a separate `family` node; that was redundant with
  `output.type`, so `family` is dropped and `output.type` kept.)
- `container.free` is a display convenience; the authoritative licence is per
  model. (For our two containers they happen to agree.)
- Fields removed vs. earlier drafts because they're derivable: `input.size`,
  `input.layout`, `output.embedding_dim`, `output.strides`,
  `output.anchors_per_cell`, `output.has_landmarks`.

## Validation

Per model in the container, apply `model-validation.md`:
Class 1 (container/manifest well-formed), Class 2 (`output.type` fits the model's
output structure), Class 3 (self-test: detection boxes on sample images /
embedding distances on known faces).

**Per-model acceptance (decided).** A container is accepted model by model: a
model that passes its self-test is taken into Eidora; an invalid model is
rejected with a specific message. One broken entry does not block a working one.

**Collision check includes the container id (decided).** Identity is
`(container.id, model.id)`. On loading a container whose `(container.id,
model.id)` already exists, Eidora does not silently overwrite — it asks the user
to decide (replace the existing model, or keep both / cancel). Different
containers with different ids never collide; re-loading the same container id is
what triggers the prompt.

## Building a container

Two ways, both using `scripts/convert_model.py` (ONNX → TFLite) and
`scripts/pack_container.py` (manifest + TFLite → `.eidoramodel`):

**Locally.** Convert your ONNX, write a `manifest.yml`, pack. See
`scripts/README.md`. This is the simplest path and keeps everything on your
machine.

**Via GitHub Actions** — the `Build your own model container` workflow. It's a
generic builder: you give it a JSON list of models (each with an ONNX URL, input
name, size, and output filename) and a manifest URL; it converts every model,
packs them into one container, and uploads the result as a workflow *artifact*
(downloadable only by you, never a public release). It handles any number of
models, so a multi-model container (e.g. a detector + an embedder) builds in one
run. The workflow names no specific model and fetches nothing on its own — you
supply everything, including models you're licensed to use. This is how you'd
build a container for models whose licence doesn't permit Eidora to host them,
without those models or their sources ever entering this repository.

Note: `workflow_dispatch` only accepts text, so ONNX files and the manifest are
given as URLs; put them somewhere the runner can reach.
