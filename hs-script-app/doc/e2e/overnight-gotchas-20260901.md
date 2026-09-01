# Overnight gotchas — 2026-09-01

## v4.16.135 provenance race

- Canonical manifest selected v4.16.135-local-20260901-011404PDT with JAR
  SHA-256 `6fccd713db787691bb497659283cd3dbd80e00d6049ab12f603d1352fc795545`.
- The exact JAR was found in the readiness owner worktree, while the OCR owner
  independently reported a provider fix based on the same nominal version.
  Nominal version equality is not provenance. The current JAR lacks the new
  `OCR_PROVIDER_CONFIG_RECONCILED` class-string marker and is excluded.
- Prevention: rebuild at a higher version from one integration tree; record
  before/after HEAD, source tree, JAR/ZIP hashes, manifest and shortcuts before
  starting any new E2E.

## v4.16.136 runner packaging miss

- **Status:** `invalid deployment evidence`; no E2E started and no run credit.
- **Observed:** the checked-in script deployed the v4.16.136 JAR correctly but
  its ZIP contained only the JAR, and canonical `run-debug.ps1` remained the
  prior 18,967-byte file instead of the 19,618-byte source runner. The build
  script extracted only the old resource set and the assembly did not include
  `*.ps1`.
- **Fix:** add `*.ps1` to the assembly bat file set and explicitly copy
  `run-debug.ps1` from staging during deployment. Rebuild at v4.16.137 or
  higher; v4.16.136 remains preserved but excluded from E2E.

## stale client after failed run

- Failed run `47710_5759` left Hearthstone PID `12340` alive after Java/runner
  exit. Its Power.log was empty and readiness was blocked. Do not reuse that
  client, Power.log, run id, or any old screenshots as fresh evidence.

## lineage reduction during first merge

- The first DebugRun provisional integration tree had intentionally removed the
  OCR source package; cherry-picking only the two modified OCR files produced a
  modify/delete conflict and would have created a partial provider build.
- Prevention: base the release candidate on the complete readiness/sentinel
  tree, then add the runner and provider fix as explicit commits. This branch
  follows that rule.

## v4.16.137 run 17306_2083 — hub misclassified as loading

- **Status:** `invalid E2E evidence`; no game was created and no run credit.
- **Observed:** after binding to the real window
  `process:D:\Hearthstone\Hearthstone.exe`, the visible hub showed the four
  navigation buttons, but Legacy OCR returned unreadable text. The generic
  warm/dark visual signature then classified the hub as
  `loading-card-back-visual`, paused the script, and left the fresh
  `D:\Hearthstone\Logs\Hearthstone_2026_09_01_01_48_57\Power.log` empty.
- **Fix:** add a measured central navigation-button brightness signal and
  require it, together with hub-compatible central darkness/color, before
  mapping an OCR-unclear screen to HUB. Add a regression test for the hub
  versus loading-card measurements. The run's Java, wrapper, and Hearthstone
  processes were stopped only after exact command-line validation.
