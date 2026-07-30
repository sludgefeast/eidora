# Model Self-Test (Class-3 validation) — Design

When a user imports a model container, static checks (Class-1 manifest
well-formedness, Class-2 output.type vs tensor structure) confirm it's
*loadable*, but not that its pre-processing is *correct*. A wrong normalization
passes both and silently produces garbage. The self-test closes that gap by
running the model on known data and letting the **user judge** the result.

Decisions (fixed): test images are **bundled in the APK**; the self-test is
**optional** — we show the result, the user decides, nothing is blocked.

## The two tests

### Detection — boxes on sample images

Run the imported detector on 1–2 bundled photos containing several faces. Draw
the returned boxes over each photo. Ask the user: *do the boxes sit on the
faces?* A wrong normalization/resize/decode produces boxes in absurd places, or
none, or hundreds — obvious to the eye. No threshold needed.

### Embedding — distances between known faces

Run the imported embedder on bundled **pre-cropped** faces: 2 people × 2 shots.
Compute cosine distances:

- same person:      d(A1, A2)
- different people: d(A1, B1), d(A2, B1)

Show the crops and the numbers. A correct model puts the same person clearly
closer than different people; a wrong one collapses the separation.

**Show the manifest thresholds alongside.** The embedding manifest carries
`clustering.edge / cluster_match / individual_match`. Displaying them next to the
measured distances lets the user see the decision the app would make, e.g.:

```
Same person      A1–A2 = 0.21   ✓ below edge (0.64) → would group
Different people A1–B1 = 0.71   ✓ above edge (0.64) → would separate
                 A2–B1 = 0.68   ✓ above edge (0.64) → would separate

Manifest thresholds:  edge 0.64 · cluster 0.68 · individual 0.64
```

This is the most informative view: measured numbers + the model's own declared
thresholds, so "similar / not similar" is grounded in what the model will
actually do, not an abstract scale.

## Bundled test assets (APK)

Real photos bundled under `app/src/main/assets/selftest/`, each an ordinary JPG
whose **MWG face regions** (name + normalized box) are in its XMP metadata — the
same format Eidora reads from any photo. The self-test is fully data-driven: it
lists every `.jpg`/`.jpeg` in that folder, reads the regions, and builds both
checks from them.

- **Detection:** the detector runs on each photo; the number of faces found is
  compared against the number of regions the metadata declares.
- **Embedding:** each named face is cropped straight from its metadata region
  (independent of the detector), embedded, and all pairs are compared. Pairs are
  labelled same/different by the region **name**, so at least one person must
  appear in two photos to give a same-person pair.

**Extending or swapping the test set:** just add or replace JPGs in the folder —
no code changes. The only requirement is that some person appears in ≥2 photos.

Each asset is copied to the cache once at test time so the existing File-based
`XmpHelper.readFaceRegions` and `BitmapLoader`/`ThumbnailHelper` work on it
unchanged (Android assets have no file path of their own).

Verified against SFace on the current test photos: the same-person pair sits far
closer (~0.17) than the nearest different-person pair (~0.56) — a wide, clear
margin, so "same vs different" reads unambiguously.

The two variants per person differ by a small scale change (as if shot from a
slightly different distance), which keeps every face reliably detectable while
preserving the margin — a blur-based variation proved unstable for the
narrower-featured person.

## What running the test requires (the real dependency)

The self-test is the **first place a container model actually runs**. Today's
`YuNetDetector` / `ScrfdDetector` / `EmbeddingModel` load their file from a
fixed spec filename and (for the detectors) hard-code input size, thresholds,
normalization. They can't run an arbitrary imported model.

So the self-test needs a **container-backed model runner**: something that, given
an unpacked model file + its manifest entry, builds a working detector/embedder
using the manifest's `output.type` (family), normalization, resize, thresholds.
This is the same bridge the eventual "use the selected container model in the
pipeline" needs — the self-test just exercises it first, in isolation.

Proposed shape (interfaces already exist):

```
object ContainerModelRunner {
    fun openDetector(dir: File, model: ModelEntry): FaceDetector
    fun openEmbedder(dir: File, model: ModelEntry): FaceEmbedder   // small new iface
}
```

- `openDetector` picks the decode family from `output.type`
  (`multistride_yunet` / `multistride_scrfd`) and feeds it the manifest's
  normalization/resize/thresholds and the container's tflite path.
- `openEmbedder` runs the single-vector path with the manifest's normalization.

Building this cleanly means generalizing the current detectors to take their
parameters (size is derivable from the tensor; normalization/resize/thresholds
from the manifest) instead of hard-coded constants — the bulk of the work, and
exactly what the later pipeline switch needs too.

## Flow in the app

1. User imports a container → Class-1 + Class-2 pass.
2. Models screen shows the container. Each model gets a **"Test" action**.
3. Tapping Test opens a screen that runs the bundled data through that model via
   `ContainerModelRunner` and shows: detector → annotated images; embedder →
   distance table with manifest thresholds.
4. A ✓/✗ hint summarizes ("same-person clearly closer" / "boxes on faces"), but
   the user decides. No gating — optional, per the decision.

## Staging (so we don't build it all at once)

1. Bundle the assets (independent, small).  ← start here once we have the images
2. Build `ContainerModelRunner` + generalize detector/embedder to manifest
   params (the substantial bridge; shared with the pipeline switch).
3. Build the self-test screen on top.

Note: step 2 is the same work as switching the live pipeline to the selected
container model, so doing it for the self-test is not throwaway — it's the
foundation for both.
