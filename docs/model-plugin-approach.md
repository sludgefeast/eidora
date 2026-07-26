# Model Plugin Approach — Exploration (thinking doc)

A sketch of a plugin approach where the **plugin owns the interpretation** of a
model, and Eidora knows nothing about SCRFD/YuNet/normalization/decoding. Not a
decision — a map of the options and their trade-offs.

## The two interfaces

Everything reduces to two tiny contracts — exactly the two you named:

```
DetectorPlugin:  photo (Bitmap)   → List<DetectedFace>   // box + landmarks + score
EmbedderPlugin:  faceCrop (Bitmap) → FloatArray           // the embedding vector
```

That's the whole surface. Whatever a plugin does inside — resize, normalize,
run a tensor graph, decode multi-stride outputs, NMS — is invisible to the app.
The app calls `detect(photo)` / `embed(face)` and consumes the result. It never
sees a tensor. This is the appealing part of your idea: it removes the manifest's
"describe every knob" burden entirely, because the knobs live inside the plugin.

The open question is only: **what is a plugin, physically?** Four levels, from
least to most powerful — and the power comes at a real cost.

## Level 0 — Built-in strategies (no plugins)

Detectors/embedders are ordinary classes compiled into the app; the user only
chooses which. This is where Eidora is today.

- Pro: simple, safe, F-Droid-clean, fast.
- Con: a genuinely new model (new decoder) needs an app update.

## Level 1 — Data-driven plugin = TFLite + manifest (what we've specced)

The plugin is `model.tflite` + `manifest.json`. The decode logic stays in the
app; the manifest selects among the decoders the app already knows and supplies
the non-derivable knobs (normalization, resize, thresholds).

- Pro: no code execution, so F-Droid-clean and safe; user can add models without
  an app update *as long as* the model fits a known decoder family.
- Con: a model whose decoder the app doesn't implement can't be added. The
  "family" ceiling is real (a transformer detector wouldn't fit).
- This is the honest limit of a pure-data plugin: **data can parameterize a
  decoder, but it can't be a new decoder.**

## Level 2 — Code plugin loaded into Eidora (DexClassLoader / WASM)

The plugin ships actual decode logic that Eidora loads and runs in-process.

- **Android DEX/APK via DexClassLoader:** technically works, but the loaded code
  runs *in Eidora's process with Eidora's permissions* — i.e. full access to the
  user's photos. A malicious "detector" is a photo exfiltrator. And F-Droid
  rejects apps that load executable code the build doesn't control, so this
  breaks F-Droid distribution. Strong **no** for a privacy-first, F-Droid app.
- **WASM (sandboxed):** a WebAssembly module is data, runs in a sandbox with only
  the capabilities we grant (here: none but "compute"), and can't touch the
  filesystem or network. This is the one "real code plugin" path that keeps the
  privacy and (probably) F-Droid story intact — the `.wasm` is a data asset, not
  app code. Cost: we'd bundle a WASM runtime, define a host ABI (how a module
  receives pixels and returns boxes), and models would need compiling to WASM.
  Heavier, but architecturally clean.

## Level 3 — Plugin = a separate app (Android Service / AIDL)

A detector is its own APK exposing a bound `Service`; Eidora calls it via IPC.

- Pro: strong process isolation — the plugin can't see Eidora's data unless
  handed each image explicitly; each plugin is separately installed, updated,
  and permissioned. Very extensible.
- Con: heaviest UX (install a second app), IPC overhead moving bitmaps across
  process boundaries, and the plugin app itself needs distribution. Overkill
  unless a real ecosystem of third-party detectors emerges.

## The trade-off, in one line

Power to run *arbitrary new decoders* requires *executing plugin-supplied logic*,
which fights privacy (in-process) or simplicity/F-Droid (dynamic code). Staying
data-only (Level 1) keeps everything clean but caps you at known decoder
families.

## Suggested shape

A pragmatic split that gets most of the benefit without the danger:

1. **Interface first (Level 0 refactor).** Define `FaceDetector` and
   `FaceEmbedder` as the two interfaces above (Eidora already nearly has these).
   Make the built-in models implementations behind them. This is pure cleanup,
   ships value immediately, and is the foundation everything else builds on.

2. **Data plugin next (Level 1).** Load `TFLite + manifest` as an
   implementation of those interfaces, using a small set of parameterized
   decoders (`multistride_*`, `single_vector`). Covers SCRFD/YuNet/SFace/ArcFace
   and anything in the same families — which is the realistic near-term need.

3. **Leave a door for Level 2-WASM.** Because the app only depends on the two
   interfaces, a future `WasmDetectorPlugin` implementing the same interface can
   be added without touching the rest of the app. Don't build it now; just don't
   design anything that precludes it.

4. **Skip Level 2-Dex and Level 3** unless a real third-party model ecosystem
   appears. They trade away exactly the privacy/simplicity that is Eidora's
   point.

The key design rule that keeps all doors open: **the app depends only on the two
interfaces, never on how a plugin fulfills them.** Whether a detector is a
built-in class, a manifest-driven TFLite, or a future WASM module, the rest of
Eidora is identical.

## Where this leaves the manifest work

Not wasted. Levels 0 and 1 are exactly the interface + data-plugin steps above;
the manifest *is* the data-plugin's configuration. WASM (Level 2) would later
replace only the *inside* of a plugin, not the interfaces or the app around them.
