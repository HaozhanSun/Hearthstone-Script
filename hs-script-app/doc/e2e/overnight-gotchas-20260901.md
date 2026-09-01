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

