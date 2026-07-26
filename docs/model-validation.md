# Model Validation Strategy (draft)

How Eidora should validate a "bring your own" model container before using it.
This covers both detection and embedding models. Nothing here is implemented
yet — it's the design we agreed to think through before writing the loader.

## Packaging recap

A model is a single container the user picks:

```
my_model.eidoramodel        # a .zip
├── model.tflite            # the weights
└── manifest.json           # the schema (detection OR embedding)
```

## The core insight: three failure classes

A user-supplied model can be wrong in three fundamentally different ways, and
they need different handling. The most dangerous one cannot be caught by static
checks at all.

### Class 1 — Container or manifest is broken

Examples: not a valid zip; `manifest.json` missing; not valid JSON; a required
field absent; `schema_version` unknown/newer than we support.

- **Detectable:** yes, trivially.
- **Action:** reject hard, with a specific message ("manifest.json is missing",
  "unknown schema_version 2 — update Eidora"). The model is not loadable at all.

### Class 2 — Manifest contradicts the .tflite

This class **largely disappears by design.** The manifest no longer restates
anything the `.tflite` already exposes (input size, layout, embedding dim,
strides, anchor count). Those are read directly from the model, so there is no
second source of truth to contradict. This was a real flaw in an earlier draft:
duplicating derivable values into the manifest created exactly the mismatch that
then needed validating — a self-inflicted problem.

What remains is a small consistency check between the manifest's decode
convention and the model's actual output structure:

- `output.type` implies a certain per-stride tensor set (cls/bbox/kps widths).
  If the model's outputs can't be grouped that way, the type is wrong → reject.
- For embedding, if `distance` is `cosine` the output must be a single vector
  (rank-2, `[1,D]`); a multi-output model doesn't fit → reject.

Everything else the app simply **reads** from the model rather than checking
against the manifest. Derivable values are computed, not validated.

### Class 3 — Everything fits, but a value is semantically wrong

Example: SFace packaged with `normalization: signed_127_127` instead of
`raw_0_255`. Every shape matches. The model runs. It silently produces garbage
embeddings (cosine ~0.03 instead of ~1.0 against a reference).

- **Detectable statically:** **no.** Nothing in the tensor shapes reveals the
  intended normalization, color order, or resize mode. The model is structurally
  valid and simply produces bad numbers.
- **Action:** cannot be rejected at load. This is what the **self-test** is for
  (below).

**This is the key takeaway:** static validation (Class 1 + 2) is necessary but
never sufficient. Class 3 — the silent one — is the most likely real-world
mistake and needs a runtime self-test, not a stricter schema.

## Proposed validation, in order

### Step 1 — Container & manifest (Class 1)

1. Open the zip; require exactly `model.tflite` and `manifest.json` present.
2. Parse `manifest.json`; require `schema_version` we understand.
3. Require all mandatory fields for the slot (detection vs embedding) and check
   enum values are known (`normalization`, `layout`, `resize`, `output.type`…).
4. Any failure → reject with a field-specific message. No interpreter created.

### Step 2 — Read derived values; light structural check (Class 2)

1. Instantiate the TFLite interpreter from `model.tflite`.
2. **Read** (do not validate against the manifest, because the manifest no
   longer states them):
   - input size and layout from the input tensor shape;
   - embedding dim from the output tensor (embedding models);
   - strides and anchors-per-cell from the output cell counts (detectors).
3. Light consistency check only where the manifest's *convention* must fit the
   model's *structure*:
   - detector `output.type` → the outputs must group into the cls/bbox/kps set
     it expects per stride;
   - embedding `cosine` distance → output must be a single `[1,D]` vector.
4. A structural mismatch → reject with the specific reason.

### Step 3 — Runtime self-test (Class 3) — the important one

Because Step 1–2 can't catch a wrong normalization/color/resize, run the model
once on **known input** and let the **user judge the result**. We don't impose a
hard automatic threshold — for a foreign model we don't know the right one — we
show what the model produced and let the user decide it's correct.

#### Detection self-test — show detections on sample images

Ship a handful of sample images (bundled assets) with faces in varied
positions/sizes. On adding a detection model:

1. Run the model on each sample image using the manifest's pre-processing.
2. Draw the resulting boxes (and, if present, landmarks) over each image.
3. Show them to the user: "Here's what this model detected. Do the boxes sit on
   the faces?"
4. The user accepts or rejects.

Why this works: a wrong normalization/resize/decode doesn't produce *slightly*
worse boxes — it produces boxes in absurd places, or none, or hundreds. The
error is obvious to a human eye at a glance. No threshold needed; the user's
judgement is the check.

#### Embedding self-test — show distances between known faces

An embedding model can't be tested with whole photos (that would also exercise
the detector). Instead we bundle **pre-cropped face images** — the same kind of
square box-crop the real pipeline feeds the embedder (no landmark alignment, to
match today's behaviour). We provide the faces; the model computes embeddings;
we show the user the distances.

Bundle at least three crops of at least two people, e.g.:

```
person_A_1.jpg   person_A_2.jpg   (same person, two shots)
person_B_1.jpg                    (a different person)
```

On adding an embedding model:

1. Compute an embedding for each crop with the manifest's normalization.
2. Compute cosine distances:
   - **same-person:** `d(A1, A2)`
   - **different-person:** `d(A1, B1)`, `d(A2, B1)`
3. Show the user the crops and the numbers, e.g.:

   > Same person:      A1 ↔ A2 = **0.21**
   > Different people:  A1 ↔ B1 = **0.68**,  A2 ↔ B1 = **0.66**
   > ✓ Same-person distance is clearly smaller — the model looks correct.

4. The app can pre-judge (same-pair distance meaningfully below different-pairs)
   to show a ✓/✗ hint, but the user makes the final call.

Why this works: a correct model always puts the same person closer than
different people. A wrong normalization collapses this — same and different come
out roughly equal, or the ordering flips. The gap (or its absence) is visible in
the numbers without needing an absolute threshold, because we're comparing
same-vs-different *within this model's own scale*.

This also gives a bonus: the observed same/different distances suggest a
reasonable starting clustering threshold for the model (roughly between the two),
helping with the "threshold discovery" open question.

#### Notes on the sample assets

- Reuse **one** bundled set for both tests where possible: the detection sample
  images can be full photos; the embedding crops can be cut from those same
  photos at known face locations, so we ship one small image set, not two.
- Licensing of the sample faces matters (we're bundling them in the APK). Use
  images we can redistribute — e.g. CC0/public-domain face photos, or synthetic
  faces from a "this person does not exist"-style generator (no real identity,
  no consent issue). This is an open item below.

## What we deliberately do NOT validate

- **License correctness.** The manifest states a license; we display it, we don't
  verify it. That's the user's responsibility (they supplied the model).
- **Model quality/accuracy.** We confirm it's not obviously broken, not that it's
  good. Accuracy tuning (thresholds) stays a user concern.
- **Security of the .tflite itself.** TFLite runs in the app's sandbox; a
  manifest can't execute code. We don't attempt malware scanning.

## Open sub-questions

1. **Sample asset licensing.** We bundle sample faces in the APK, so they must be
   redistributable. Options: CC0/public-domain photos, or synthetic faces (a
   "this person does not exist"-style generator) — synthetic avoids any real
   identity/consent question and is probably the cleanest. Decide the source.

2. **How many samples.** Detection: ~3 images covering easy + hard (small,
   rotated, multiple faces). Embedding: 2 people × 2 shots is the minimum that
   gives one same-pair and two different-pairs; more people makes the signal
   clearer but adds APK size.

3. **Failed self-test: block or warn?** Since we now show the user the result and
   let them judge, the natural answer is **warn-and-allow**: present the result,
   show a ✓/✗ hint, but let the user proceed (they may know something we don't).
   A hard block would be wrong for an exotic-but-valid model our samples don't
   suit.

4. **Reuse crops from detection samples.** Cutting the embedding crops from the
   same photos used for detection keeps the asset set to one small bundle.
