# Rank Detection Replay: 2026-09-03

This is an offline, image-only replay. It uses the configured PaddleX sidecar
and never changes Hearthstone state. The machine-readable result and copied
source/annotated images are outside Git at:

`C:\Users\yzjsh\AppData\Local\Temp\paddlex-rank-replay-20260903-v2`

## ROI

The supplied 389x341 reference image showed the old outer frame at attachment
pixels x=27..120, y=206..296 and the inner numeric red frame at x=42..78,
y=242..271. Mapping that inner frame through the known 1920x1080 outer badge
rectangle `(0,885,144,140)` produces the production ROI:

- normalized: left=0.01198, top=0.87130, width=0.02969, height=0.04352
- 1920x1080 image: x=23, y=941, width=57, height=47
- right/bottom exclusive: x=80, y=988

The Python replay reports the equivalent exclusive rectangle `[23,941,80,988]`.
The application red annotation uses the inclusive-width form `(23,941,57,47)`.

## Replay Results

All six samples were run with provider `PADDLEX`, CPU device, PaddleX 3.7.2,
and PaddlePaddle from `C:\Users\yzjsh\.codex\paddlex-ocr-venv`. Numeric rank
is the only decision field; tier classification is intentionally `UNKNOWN` in
this numeric-only replay.

| sample | expected/visible | raw OCR | numeric rank | confidence | decision |
|---|---:|---|---:|---:|---|
| paddlex-ke-evil-20260831 | 8 | 8 | 8 | 0.9999674559 | RANK_RESOLVED |
| legacy-10-proxy-20260902 | 10 | 10 | 10 | 0.9999213219 | RANK_RESOLVED |
| legacy-9-20260902 | 9 | 9 | 9 | 0.9999845028 | RANK_RESOLVED |
| paddlex-shang8-20260903 | 8 | 8 | 8 | 0.9999743700 | RANK_RESOLVED |
| empty-20260830 | UNKNOWN | empty | UNKNOWN | UNKNOWN | UNKNOWN_FAIL_CLOSED |
| empty-20260901 | UNKNOWN | empty | UNKNOWN | UNKNOWN | UNKNOWN_FAIL_CLOSED |

The legacy `10` row is explicitly a proxy: no rank-detection success image
exists in that historical run, so the same-run mulligan frame was replayed.
The old PaddleX `Ke...` and `商8` failures are now reduced to the numeric ROI;
the latter is normalized to `8`, while unrelated/empty text remains fail-closed.

## Evidence Contract

Application rank evidence saves the original screenshot with a red ROI and a
diagnostic panel containing time, stage, run id, provider, ROI, raw/normalized
OCR, numeric/resolved rank, confidence, tier, unknown reason, and final
decision. Successful evidence is logged at INFO; unresolved evidence is WARN.
The panel is placed in a corner that avoids the marked ROI where possible.
