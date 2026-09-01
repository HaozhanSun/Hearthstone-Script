# Debug/Test Run override ledger

Date: 2026-08-31 (Pacific Time)

## Scope

This change adds a general-purpose, user-visible Debug/Test Run mode. It is
not a deck strategy or a PaddleX-specific feature. It only bypasses the
ordinary work-time gate for one short, in-memory lease.

## Reference checked

The upstream application repository was checked at `origin/master`:
`e5d26c8698f1691f1523fd070a8bbd16680f36eb`.

The upstream `WorkTimeListener`/`WorkTime` path has ordinary configured time
windows only. It has no debug lease, deadline, or Debug/Test Run control. The
local change preserves that schedule behavior.

## Implemented behavior

- `DEBUG_RUN_MODE` is a persisted checkbox preference with a false default.
- Main-window startup clears the preference and all live state, so a restart
  cannot resurrect an active lease.
- Checking the box creates one monotonic lease, capped at 30 minutes.
- Checking it again while active is idempotent and does not renew the lease.
- Unchecking cancels immediately. Expiry transitions to `EXPIRED`, logs the
  reason, and returns the script to idle when it is outside a normal schedule.
- The normal schedule, preset selection, cross-midnight handling, DST/clock
  semantics, F1/F2 pause/resume, terminal-state handling, OCR contracts,
  surrender gates, and process-stop behavior are not bypassed.
- Required structured lifecycle events include `start`, `end`, `reason`, and
  `remainingMs`: `DEBUG_OVERRIDE_REQUESTED`, `DEBUG_OVERRIDE_ACTIVE`,
  `DEBUG_OVERRIDE_EXPIRED`, and `DEBUG_OVERRIDE_DISABLED`.

## Offline verification

`DebugRunLeaseTest` covers default-off behavior, schedule-gate composition,
30-minute cap, non-renewal, monotonic expiry despite a wall-clock jump,
disable/stale-callback safety, restart clearing, and concurrent enable requests.

The targeted command
`.\mvnw.cmd '-Dtest=DebugRunLeaseTest' '-DfailIfNoTests=false' test` was
attempted in this isolated app worktree. It failed during the existing app
compile before the test phase because sibling plugin SDK and JDBC artifacts
are not present in the standalone app worktree; the first reported failures
were unresolved `hsscriptpluginsdk`/`PluginWrapper` and Spring JDBC symbols in
pre-existing files. This is an integration-environment blocker, not a passed
test result.

This checkpoint was not deployed or exercised against Hearthstone or
Battle.net in this worktree. Real E2E, package creation, release manifests,
launchers, and shortcuts remain pending the shared release window.

## Current E2E gate from total control

When this checkpoint is integrated and deployed, validation must use the same
current build and evidence lineage for at least two consecutive valid games,
with at least one current-player `PLAYSTATE=WON` result. The win may be
outside the consecutive pair only if it remains in the same current-build
lineage. Each game requires a fresh screenshot, authoritative `Power.log`,
ledger entry, and PID/stability evidence. Any error log, exception, timeout,
OCR/provider failure, `UNKNOWN`, or manual recovery invalidates the sequence.


