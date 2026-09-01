# Local PaddleX sidecar measurement

Measurement date: 2026-09-01. This is offline evidence only. It used the local
PaddleX Python runtime against retained screenshots; no ChatGPT image
understanding or visual tokens were used, and no Hearthstone/Battle.net process
was started.

## Runtime

| Item | Value |
| --- | --- |
| Python | `C:\Users\yzjsh\AppData\Local\Temp\hs-script-paddlex-vision-local-copy-20260829\.venv\Scripts\python.exe`, 3.12.13 |
| PaddleX | 3.7.2 |
| PaddlePaddle | 3.3.1 |
| Source module | `C:\Users\yzjsh\.codex\worktrees\6818\Hearthstone Copilot\experiments\paddlex-vision\src` |
| Device | `cpu` |
| Model cache | `C:\Users\yzjsh\.paddlex\official_models` |
| Cache status | PP-LCNet, UVDoc, textline-orientation, PP-OCRv6 det/rec model files reported present |
| MKL-DNN | Disabled with `PADDLEX_DISABLE_MKLDNN=1` and `PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT=0` |

The import/health probe used `import paddlex; import
paddlex_vision_experiment.cli; print('ok')`: provider `PADDLEX`, device
`cpu`, elapsed `16979 ms`, exit code `0`.

## Retained screenshot probes

The following are real local `--ocr-only` sidecar results. The summary is
intentionally truncated; the fixture contains the complete replay text used by
unit tests, while this report does not duplicate it.

| Fixture | Contract | Exit | OCR chars | Boxes | Elapsed | Screenshot SHA-256 | Result |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| `matching-paddlex` | ACCEPTED | 0 | 61 | 9 | 68344 ms | `e8ecf3ddebe95b3f31f2931285b1b959e287f117702404a596562bc112abdde6` | matching text |
| `mulligan-paddlex` | ACCEPTED | 0 | 714 | not retained by CLI summary | 93455 ms | `a3f9d4e7f03f5d1c50f228b05d55c327c4c0079e3add3fab48ec1591c3f0e20a` | OCR includes mulligan plus surrounding script/UI text |
| `rank-badge-empty-probe` | EXPECTED_EMPTY_PROBE | 0 | 61 | 9 | 87525 ms | `e8ecf3ddebe95b3f31f2931285b1b959e287f117702404a596562bc112abdde6` | **discrepancy: this retained image is matching, not a rank crop; it returns matching text** |
| `hub-main-menu` | ACCEPTED | 1 | 0 | 0 | 330 ms | unavailable | **fixture screenshot was removed by another session; sidecar correctly rejected missing input** |

All screenshot and source-log paths are recorded in
`src/test/resources/offline-ocr/replay-fixtures.json`. At fixture creation,
12/12 existed; the current recheck found the HUB path missing after external
cleanup. The rank row is intentionally retained as a discrepancy rather than
being rewritten from stale assumptions.

## Online-like replay

The fixture replays three non-real sequences:

| Sequence | Terminal expectation |
| --- | --- |
| `offline-game-0001` | LOST |
| `offline-game-0002` | LOST |
| `offline-game-0003` | WIN |

They cover HUB, MATCHING, MULLIGAN, RANK_BADGE, GAMEPLAY, WIN, LOST/DEFEAT,
RESULT, UNKNOWN, RECONNECT, and ERROR_DIALOG. These are state-machine replay
assertions only; they do not satisfy the real-game outcome gate.

## Contract validation

The fake bridge tests passed for valid PaddleX direct routing, expected-empty
rank probes, ordinary empty AUTO fallback, ordinary empty `PADDLEX_ONLY`
failure, AUTO exception fallback, and `PADDLEX_ONLY` exception fail-closed.

Command result: `OfflineOcrReplayTest` 8/8, `PaddleXOcrSidecarBridgeTest`
5/5, `ScreenWatchdogTest` 6/6, and `OcrRuntimeTest` 9/9 passed; reactor
command completed with `BUILD SUCCESS` and 28 targeted tests. No deployment,
manifest, shortcut, or shared runtime files were changed.
