# Offline E2E replay suite

This is the deterministic, test-only replay for the supervised Hearthstone
lifecycle. It is intentionally isolated from the shared runtime and does not
launch Hearthstone, Battle.net, JavaFX, PaddleX, OCR, native input, or a
watchdog. It does not replace or deploy an application build.

## Routing

- Fixture manifest: `hs-script-app/src/test/resources/e2e-replay/manifest.json`
- Screenshot metadata: `hs-script-app/src/test/resources/e2e-replay/screenshots/`
- OCR/provider observations: `hs-script-app/src/test/resources/e2e-replay/ocr/`
- Power.log fragments: `hs-script-app/src/test/resources/e2e-replay/powerlog/`
- Test-only state machine: `hs-script-app/src/test/java/club/xiaojiawei/hsscript/e2ereplay/OfflineE2EReplayEngine.kt`
- Test entry point: `club.xiaojiawei.hsscript.e2ereplay.OfflineE2EReplaySuiteTest`

From the repository root, run:

```powershell
.\mvnw.cmd -pl hs-script-app -am -Pjvm '-Djava.version=24' '-Dtest=OfflineE2EReplaySuiteTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The fixture is based on retained evidence from run `77249_6688`, with source
ref `4d08a94818cb6e9f30a007f26548ead090f4a2e4` and upstream ref
`e5d26c8698f1691f1523fd070a8bbd16680f36eb`. Missing source screenshots are
represented explicitly as unretained metadata and are never treated as visual
proof.

## Contract covered

The nine stages are startup, HUB, mode entry, matchmaking, listener attach,
`CREATE_GAME`, mulligan, gameplay controls, and terminal wait. Each stage
declares the screenshot/OCR/Power.log evidence, state-machine input and output,
allowed and forbidden actions, and named failure reasons.

The negative tests fail closed for stale Power.log paths and offsets, missing
`CREATE_GAME`/`E2E_GAME_BOUNDARY`, unconfirmed foreground, invalid F2/F1 pause
transitions, and terminal pages without an authoritative `PLAYSTATE` record.
