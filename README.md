<div align="center">

<img src="eidora_icon.svg" width="120" alt="Eidora">

# Eidora

**Face recognition for your photos — entirely on your device.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)

</div>

---

Eidora finds faces in your photo library, groups the ones belonging to the same
person, and lets you name them. Everything happens locally on your phone. There
is no cloud, no account, no telemetry, and no network access beyond a one-time,
explicitly confirmed download of the recognition models.

## Why Eidora

Most photo apps that recognise faces send your pictures to a server, or lock the
results inside their own database. Eidora does neither.

**Your photos stay where they are.** Detection and recognition run on-device
using TensorFlow Lite. Nothing is uploaded, ever.

**Your work isn't locked in.** Names you assign are written back into the photo
files themselves as standard XMP metadata. Eidora deliberately follows the
conventions established by [digiKam](https://www.digikam.org/) and
[Aves](https://github.com/deckerst/aves), so the same folder opened in either of
them shows your names straight away — and reinstalling Eidora restores your
named faces from the files. The database is a cache, not the source of truth.

Concretely, for every confirmed face Eidora writes:

- **`mwg-rs:RegionList`** — a Metadata Working Group face region per face,
  carrying `Type = Face`, the person's `Name`, and an `Area` with normalised
  `x`/`y`/`w`/`h` coordinates, so other tools can draw the box in the right
  place regardless of image size.
- **`Iptc4xmpExt:PersonInImage`** — the list of people shown in the photo, which
  is what most cataloguing software indexes and searches on.

Unconfirmed suggestions are never written; only faces you have named end up in
your files.

**It respects your device.** Analysis runs in the background and pauses
automatically when the battery is low or the phone gets warm — thresholds you
control. You can pause and resume it from the notification at any time.

## How it works

1. **Detection** — a face detector locates faces and their landmarks in each
   photo. The default is [YuNet](https://github.com/opencv/opencv_zoo)
   (Apache-2.0).
2. **Embedding** — a recognition model converts every face into a compact
   numeric signature. The default is
   [SFace](https://huggingface.co/opencv/face_recognition_sface) (Apache-2.0).
3. **Clustering** — a Chinese Whispers graph algorithm groups similar faces into
   suggested persons. Photos taken closer together in time are nudged towards
   the same cluster, which helps when someone's appearance changes over the
   years.
4. **You decide** — confirm, rename, split or merge the suggestions. Confirmed
   names are written to the photo files.

## Features

- On-device face detection and recognition, fully offline
- Automatic grouping of faces into suggested persons
- Names written back as XMP metadata — compatible with digiKam and Aves
- Choose exactly which folders are analysed; everything else stays untouched
- Battery- and temperature-aware processing, pausable from the notification
- Review unknown and ignored faces, merge or split persons
- Choose your detection and embedding models independently — free Apache-2.0
  defaults, with optional higher-accuracy research models
- Material 3 interface, English and German

## Privacy

Eidora requests three things and uses them for nothing else:

| Permission | Why |
|---|---|
| Photos / media | Read the images you asked it to analyse |
| All files access | Write names back into your photo files as XMP metadata |
| Notifications *(optional)* | Show analysis progress; the app works without it |

The only network request Eidora ever makes is downloading the ML models, and
only after you tap the download button. There is no analytics SDK, no crash
reporter, and no advertising library in the build.

## Machine learning models

The models are **not** bundled in the APK. On first run Eidora asks whether to
download them from this repository's releases, showing each model's purpose,
size and licence beforehand. Downloads are verified against a known SHA-256 hash.

Eidora lets you choose the **detection** and **embedding** models separately in
Settings. Each task has a free default and an optional research-only alternative:

| Task | Default (free) | Optional (research only) |
|---|---|---|
| Detection | **YuNet** — Apache-2.0 | SCRFD (InsightFace) |
| Embedding | **SFace** — Apache-2.0 | ArcFace (InsightFace) |

The defaults (YuNet + SFace) are **Apache-2.0**, free for any purpose, and are
what ships with the F-Droid build. The optional InsightFace models are
**non-commercial research use only** and are downloaded only if you explicitly
select them, after reading the licence shown in Settings.

Each model actually has two licences — for its code and its trained weights —
and the more restrictive one governs. See
[`MODELS-LICENSE.md`](MODELS-LICENSE.md) for the full breakdown and what it means
in practice.

## Performance

Measured on a library of **24,000 photos containing ~29,600 faces**, on a modern
mid-range phone (8-core ARM SoC, 8 GB RAM, Android 16), screen off and on mains
power:

| Step | Time | Notes |
|---|---|---|
| Media scan | a few seconds | Enumerating the selected folders via MediaStore |
| Face detection | ~1.5 h | 3 photos in parallel (measured with SCRFD) |
| Face embeddings | ~30 min | one face at a time on the GPU delegate (measured with ArcFace) |
| Clustering | not yet measured | Chinese Whispers over all embeddings, single pass |

On top of these come any **pauses from the PowerGate**: when the battery runs low
or the device gets warm, processing waits until it has recovered, so a run on an
unplugged phone in your pocket takes longer than the figures above.

Detection and embedding dominate, and they scale with the number of photos and
faces. Actual times vary with image resolution, how many faces each photo
contains, and whether the GPU delegate is available.

**This is the one-time initial run.** It only takes this long because it
processes your whole library at once. Afterwards Eidora only looks at what has
changed, so new photos — a handful at a time — are analysed in seconds.

Still, keep in mind this is **a phone** doing work that desktop software does far
faster. If you have a very large library and a computer available, running
[digiKam](https://www.digikam.org/) on a desktop is the quicker route; Eidora
writes the same standard XMP face metadata, so the two interoperate cleanly.

## License

Eidora is free software, licensed under the **GNU General Public License v3.0 or
later**. You may use, study, share and modify it, provided derivative works
remain under the same licence and their source is made available. See
[`LICENSE`](LICENSE) for the full terms.

The GPL covers Eidora's own source code. The ML models it downloads have their
own licences: the default models (YuNet + SFace) are Apache-2.0 and free for any
use, while the optional InsightFace models (SCRFD, ArcFace) are non-commercial
research only. See [`MODELS-LICENSE.md`](MODELS-LICENSE.md) for details.
