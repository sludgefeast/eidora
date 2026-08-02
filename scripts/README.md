# Scripts

Developer utilities that are **not** part of the Android build. They build and
pack model containers and other one-off conversion tasks.

## build_container.py

The one-shot builder both workflows use: give it a manifest whose models carry
`build:` blocks and it downloads, converts, and packs the whole container.

```sh
pip install pyyaml onnx "tensorflow==2.17.0" onnx2tf  # (see the workflows for the full set)
python scripts/build_container.py \
  --manifest docs/containers/free-models/manifest.yml \
  --out eidora-free.eidoramodel
```

Each model needs a `build:` block:

```yaml
    build:
      onnx_url: <URL to the ONNX>
      input_name: <input tensor name, or `auto` to read it from the ONNX>
      size: <e.g. 640 or 112>
```

It calls `convert_model.py` per model, then `pack_container.py` once — the same
path whether you run it locally or through the free / bring-your-own workflows.
`build:` is build-time metadata; the app ignores it.

## convert_model.py

Converts an ONNX face model **you provide** into a TFLite model Eidora can load.
This is how you use a model that Eidora does not ship — for example InsightFace's
SCRFD or ArcFace, which are licensed for non-commercial research only and are
therefore not hosted or distributed by Eidora.

The script downloads nothing: you obtain the ONNX file yourself (accepting its
license) and point the script at it. It performs the same conversion Eidora's CI
uses for the free models, so the result is compatible with the app.

### Setup

```sh
pip install "tensorflow==2.17.0" tf_keras "onnx==1.16.1" \
    "onnxruntime==1.18.1" "onnx2tf==1.26.3" onnx_graphsurgeon \
    sng4onnx "onnxsim==0.4.36" psutil ml_dtypes \
    --extra-index-url https://pypi.ngc.nvidia.com
```

### Use

First inspect the model to find its input name and size:

```sh
python scripts/convert_model.py --onnx det_2.5g.onnx --inspect
```

Then convert, using the input name you saw:

```sh
# SCRFD-style detector (640x640):
python scripts/convert_model.py --onnx det_2.5g.onnx \
    --input-name input.1 --size 640 --out scrfd_2.5g_kps_640_float32.tflite

# ArcFace-style embedder (112x112):
python scripts/convert_model.py --onnx w600k_mbf.onnx \
    --input-name input.1 --size 112 --out arcface_w600k_mbf_float32.tflite
```

The script prints the output's SHA-256 and tensor layout so you can confirm it
matches the format Eidora expects. After converting, package the model(s) into a
container with `pack_container.py` (below).

## pack_container.py

Packs a manifest + its `.tflite` files into an Eidora **container**
(`.eidoramodel`). It's a packer, not a validator: it does a Class-1 check (the
manifest is well-formed and every referenced file is present) and zips. The
deeper model checks — tensor structure and per-file hashes — are done by the app
at import time, not duplicated here. See
[`../docs/model-container.md`](../docs/model-container.md).

### Setup

```sh
pip install pyyaml
```

### Use

Put a `manifest.yml` and the `.tflite` files it references in one folder, then:

```sh
python scripts/pack_container.py --dir ./my-models --out my-models.eidoramodel
```

A malformed manifest or a missing file is rejected with a specific message.
If a model entry declares a `sha256`, the packer verifies it matches the file
and refuses to pack on a mismatch (it never rewrites the manifest — only checks).
Add hashes yourself with `sha256sum <file>`; the app verifies them again on
import.

### Licensing

You are responsible for the license of any ONNX you convert. Some models
(InsightFace SCRFD, ArcFace, …) are non-commercial research use only. Do not
redistribute converted models unless their license permits it.

## Self-test photos

The on-device model self-test reads its test photos from
`app/src/main/assets/selftest/` — ordinary JPGs whose MWG face regions (name +
box) are stored in their XMP metadata, the same format Eidora reads from any
photo. The test is fully data-driven: it lists every JPG in that folder, reads
the regions, and builds the detection and embedding checks from them. To extend
or swap the test set, just add or replace JPGs there — no code changes. At least
one person must appear in two photos so the embedding check has a same-person
pair. See `docs/model-selftest.md`.
