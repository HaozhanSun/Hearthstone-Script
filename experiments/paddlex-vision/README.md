# PaddleX vision experiment

This is an intentionally isolated experiment for combining object detection and
Chinese OCR on the same screenshot. It is not included in the Maven reactor and
does not change `hs-script-app` or its existing Tesseract OCR path.

## What it does

The adapter runs two PaddleX pipelines against one image:

1. `object_detection` returns object labels, confidence, and pixel coordinates.
2. `OCR` returns detected text, confidence, and text boxes.
3. A small deterministic association layer relates nearby objects and text, for
   example, `"猫" -> "猫粮" (right_of)`.

The adapter also exposes `ocr_text`, a flattened string compatible with the
current application's OCR-oriented decision code. The production application
uses the CLI's OCR-only JSON mode through an out-of-process sidecar when its
PaddleX OCR switch is enabled.

`PaddleXOcrBridge.do_ocr(image)` is the text-only compatibility wrapper. Its
shape mirrors the current `TesseractEx.doOCR` contract (`image -> String`) so it
can be exercised independently before any production integration is considered.
The CLI also supports `--ocr-only`, which runs only the PaddleX OCR pipeline
and emits the same JSON contract with empty `objects` and `relations`. The main
application uses this out-of-process shape when PaddleX OCR is enabled.

For the current rank incident, `RankBadgeProbe` is the controlled experiment:
it crops only the lower-left numeric badge, upscales that crop, runs the
OCR-only adapter, and accepts a rank only from a clean numeric token. This
prevents card text, player names, and the script window from participating in
rank recognition. The production Kotlin detector now routes its existing OCR
calls through the shared provider switch rather than importing this Python
probe directly.

## Installation

Use a separate Python environment. PaddleX and its model weights are deliberately
not Maven dependencies:

```powershell
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install "paddlex[base]" pytest
```

If PaddlePaddle requires a hardware-specific wheel, install the matching
PaddlePaddle CPU/GPU wheel first, following the [official PaddleX installation
guide](https://paddlepaddle.github.io/PaddleX/latest/en/installation/installation.html).

## Run

From this directory:

```powershell
..\.venv\Scripts\python.exe -m paddlex_vision_experiment.cli `
  --input C:\path\to\screenshot.png `
  --device cpu `
  --json-out .\output\result.json
```

For the production text-only bridge:

```powershell
..\.venv\Scripts\python.exe -m paddlex_vision_experiment.cli `
  --ocr-only `
  --input C:\path\to\ocr-crop.png `
  --device cpu
```

The first real run may download model weights. Use an actual Hearthstone
screenshot for an application-level result; the repository currently does not
contain a stable labeled Hearthstone image fixture.

The production application does not bundle PaddleX, PaddlePaddle, or model
weights. Configure `PADDLEX_OCR_PYTHON` to a Python/venv that has PaddleX
installed. `PADDLEX_OCR_MODULE_PATH`, `PADDLEX_OCR_DEVICE`,
`PADDLEX_OCR_MODEL_CACHE`, and `PADDLEX_OCR_TIMEOUT_MS` can be set in the
application `dev` config group or through environment variables of the same
name; an empty model cache value leaves PaddleX/Paddle on their default cache.

On Windows, the adapter disables PaddleX's default oneDNN run mode by default
because the PaddlePaddle 3.x CPU runner can otherwise fail in a PIR/oneDNN
conversion path.
Set `PADDLEX_DISABLE_MKLDNN=0` only after validating a local runtime where the
oneDNN path works.

The JSON shape is:

```json
{
  "schema_version": 1,
  "input": "screenshot.png",
  "ocr_text": "中文文字",
  "objects": [
    {"label": "cat", "score": 0.96, "bbox": [100, 200, 350, 500]}
  ],
  "texts": [
    {"text": "猫粮", "score": 0.94, "bbox": [370, 250, 600, 300]}
  ],
  "relations": [
    {"object_index": 0, "text_index": 0, "relation": "right_of", "distance": 20.0}
  ]
}
```

## Verification levels

* `pytest` verifies the protocol normalization and spatial association without
  needing PaddleX or model downloads.
* `pytest -m paddlex` runs the optional live model smoke test when PaddleX is
  installed and `PADDLEX_VISION_FIXTURE` points at a real image.
* The existing Maven tests are run separately and unchanged to verify that this
  isolated experiment does not regress the main application.

This experiment is not a claim that PaddleX will recognize Hearthstone-specific
cards or stylized UI elements without fine-tuning. Those targets need labeled
screenshots and a custom object-detection model; the OCR pipeline can still be
used for the accompanying Chinese text.
