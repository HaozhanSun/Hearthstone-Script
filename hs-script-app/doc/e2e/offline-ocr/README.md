# Offline OCR replay suite

This suite is the offline contract for the two OCR providers. It is deliberately
separate from the real Hearthstone E2E gate: it replays OCR text and references
screenshots already retained by the runtime, but it does not launch Hearthstone,
download a model, or claim an end-to-end game result.

## Fixture layout

`src/test/resources/offline-ocr/replay-fixtures.json` contains two sections:

- `frames`: representative retained captures for HUB, matching, mulligan, rank
  badge, gameplay, WIN, LOST/DEFEAT, result, unknown, reconnect, and error dialog.
- `providerProbes`: deterministic fake-sidecar cases for valid PaddleX JSON,
  expected-empty rank probes, empty ordinary OCR, thrown sidecar errors, and
  `PADDLEX_ONLY` fail-closed behavior.
- `onlineLikeSequences`: three offline state sequences whose terminal outcomes
  are LOST, LOST, and WIN. They are replay assertions, not real-game outcomes.

Each frame records:

| Field | Meaning |
| --- | --- |
| `id`, `sequence` | Stable replay identity and ordering. |
| `screenshotPath` | Absolute path to the retained screenshot on the capture host. |
| `sourceRunId`, `sourceLogPath` | Run lineage and the log used for correlation. |
| `evidenceKind` | `observed-screenshot`, `ocr-log`, or `correlated-replay`. |
| `provider` | Provider that produced the recorded OCR (`PADDLEX` or `LEGACY`). |
| `ocrText`, `contract`, `confidence` | Text and contract outcome replayed by the test. |
| `expectedScreenState` | Product-level state, including states that the watchdog must treat as unknown. |
| `expectedWatchdogKind` | Current watchdog classification, where applicable. |
| `expectedFailClosed` | Whether an uncertain/non-gameplay frame must pause rather than click. |

The paths intentionally point at external evidence under the canonical runtime
log. The images are not copied into Git because they are large and machine-local;
the test validates that every fixture has a non-empty, auditable evidence path.
When a screenshot and OCR line are from the same run but not the same capture,
the fixture says `correlated-replay` rather than presenting it as a pixel-perfect
OCR transcript.

`src/test/resources/offline-ocr/mulligan-e2e-fixture.json` is the stricter
replay fixture for the rank/mulligan boundary. It points only at retained
screenshots that are present on the capture host (mulligan, WIN, LOST, and
UNKNOWN) and includes a simulated Power.log timeline. The
`OfflinePaddleXOcrMulliganE2ETest` loads those images, runs the real JVM
`OcrRuntime`/rank parser/ScreenWatchdog boundary with a fake sidecar, and
asserts the resulting state, action, retry schedule, provider, and
`pause=false`. Missing retained files fail the test with the exact path.

Run it with the targeted reactor test command:

`mvnw.cmd -pl hs-script-app -am -Dtest=MulliganRankPreflightTest,OfflinePaddleXOcrMulliganE2ETest,OcrRuntimeTest,PaddleXOcrSidecarBridgeTest,PersistentPaddleXOcrSidecarBridgeTest,ScreenStateRoiSelectorTest,ScreenWatchdogTest,SurrenderPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

`MulliganRankPreflightTest` covers the live scheduling contract around that
fixture: the first read starts after a 7-second grace period, retries are
independent of new Power.log lines and bounded, each read is cancellable after
5 seconds, and duplicate `MULLIGAN_STATE=INPUT` reserves only one change-card
action. Empty/UNKNOWN/exception/timeout reads continue without pausing;
phase transitions and surrender requests cancel pending work before another
click. Its timing values can be overridden with
`hs.script.mulligan.rank.initial-delay-ms`,
`hs.script.mulligan.rank.retry-interval-ms`,
`hs.script.mulligan.rank.max-attempts`, and
`hs.script.mulligan.rank.attempt-timeout-ms`.

## Routing rules

1. `OcrRuntime` remains the single JVM boundary. PaddleX is reached only through
   the persistent `PersistentPaddleXOcrSidecarBridge`; the JVM does not import
   PaddleX or its Python dependencies. One shared coordinator serializes all
   PaddleX requests and records queue/latency/ROI telemetry.
2. `AUTO` tries PaddleX first and may record an explicit `PADDLEX_FALLBACK_TO_LEGACY`
   event for a real contract/sidecar failure. `PADDLEX_ONLY` never falls back and
   must fail closed. `LEGACY_ONLY` does not construct the sidecar.
3. A blank rank/tier probe is an expected empty probe and must not be turned into
   provider failure. A blank ordinary OCR result remains a contract failure.
4. WIN and LOST/DEFEAT outrank surrender/retry actions. Result, reconnect, error,
   mulligan, rank, and unknown frames must be handled conservatively; they are
   not evidence that gameplay perception succeeded.
5. These tests use a fake bridge. Real PaddleX runtime health, model availability,
   screenshots, Power.log markers, and process stability remain separate E2E
   evidence requirements.

`PersistentPaddleXOcrSidecarBridgeTest` uses fake sessions to verify one
session/pipeline reuse, single-flight behavior, cancellation while queued, and
fresh-session recovery after a failed request. `ScreenStateRoiSelectorTest`
verifies that a missing `GAME_RECT` produces bounded menu ROIs rather than a
whole-desktop PaddleX request, and that `AUTO` uses an explicit legacy
fallback while `PADDLEX_ONLY` skips unsafe OCR. The rank detection regression
also asserts the PaddleX request is labeled `rank-badge` and receives only the
57x47 badge ROI, never a screen-state crop. The Python `test_cli.py` server
test verifies one provider initialization across health, OCR, and
request-local error lines.

The fixture is a replay index, not a replacement for the E2E ledger.
