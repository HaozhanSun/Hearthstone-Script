# Verification record

This record covers the isolated experiment only. It is not an application
release record and does not satisfy the project's real-Hearthstone E2E gate.

## Results

| Check | Result |
| --- | --- |
| Isolated unit/protocol tests | `6 passed, 1 skipped` |
| PaddleX live model smoke test | `1 passed, 5 deselected` |
| CLI inference and JSON output | Passed; `schema=1`, `objects=1`, `texts=182`, `ocr_chars=1358` on the available desktop screenshot |
| Existing `hs-script-app` Maven test command | Failed during existing Kotlin compilation before tests; unresolved plugin SDK/JDBC/MCTS symbols |
| Real Hearthstone two-game E2E gate | Not run; no stable labeled Hearthstone fixture or fresh game evidence was available |

## Live smoke-test environment

* PaddleX `3.7.2`
* PaddlePaddle `3.3.1`
* CPU device
* Windows oneDNN default disabled inside this experiment via
  `PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT=0`; the default path previously failed
  in PaddlePaddle's PIR/oneDNN conversion step.

The live image was a local desktop screenshot, not a Hearthstone screenshot.
It demonstrated that the provider can run object detection and Chinese OCR,
normalize both outputs, associate boxes, expose the flattened OCR string, and
write JSON. It did not demonstrate Hearthstone object-class accuracy.

## Isolation

No PaddleX dependency, Python bridge, or production OCR replacement was added
to the Maven project. The only integration seam is the opt-in
`PaddleXOcrBridge.do_ocr(image) -> str` wrapper under this directory, matching
the text-only shape of the current OCR consumer.
