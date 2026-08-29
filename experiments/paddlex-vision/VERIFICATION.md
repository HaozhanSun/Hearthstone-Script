# Verification record

This record covers the isolated experiment only. It is not an application
release record and does not satisfy the project's real-Hearthstone E2E gate.

## Results

| Check | Result |
| --- | --- |
| Isolated unit/protocol tests | `9 passed, 1 skipped` |
| Rank-badge offline probe | 16 saved screenshots classified correctly: 12 rank 10, 2 rank 8, and 2 non-rank screens returned unresolved |
| PaddleX live model smoke test | `1 passed, 5 deselected` |
| CLI inference and JSON output | Passed; `schema=1`, `objects=1`, `texts=182`, `ocr_chars=1358` on a saved desktop screenshot |
| Existing `hs-script-app` Maven test command | Failed during existing Kotlin compilation before tests; unresolved plugin SDK/JDBC/MCTS symbols |
| Real Hearthstone two-game E2E gate | Not run; no stable labeled Hearthstone fixture or fresh game evidence was available |

## Live smoke-test environment

* PaddleX `3.7.2`
* PaddlePaddle `3.3.1`
* CPU device
* Windows oneDNN default disabled inside this experiment via
  `PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT=0`; the default path previously failed
  in PaddlePaddle's PIR/oneDNN conversion step.

* Full-screen inference on a saved Hearthstone screenshot produced 64 text
  boxes but did not isolate the rank reliably; card text, the script window,
  and other HUD elements were present in the result.
* The controlled `RankBadgeProbe` then cropped `(0, 920, 100, 1010)` on the
  1920x1080 captures, upscaled it 4x, and used the OCR-only pipeline. It
  returned `8` for the known Silver 8 captures and `10` for known Silver 10
  captures, while result/matchmaking screens without a badge returned no rank.
  This is offline evidence only, not a claim of production integration.

## Isolation

No PaddleX dependency, Python bridge, or production OCR replacement was added
to the Maven project. The only integration seam is the opt-in
`PaddleXOcrBridge.do_ocr(image) -> str` wrapper under this directory, matching
the text-only shape of the current OCR consumer.
