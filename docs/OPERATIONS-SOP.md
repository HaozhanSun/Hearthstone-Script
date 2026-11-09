# Hearthstone Copilot operations SOP

This is the canonical categorized operating procedure for work that can affect a
user-facing Hearthstone Script runtime. It turns repeated deployment findings,
failures, and fixes into a single routing document; it does **not** relax the
binding rules in [`AGENTS.md`](../AGENTS.md) or the channel rules in
[`RELEASE-CHANNELS.md`](RELEASE-CHANNELS.md). When sources differ, reconcile
the duplication before changing either one: the user's newest explicit
instruction supersedes older local text; use the stricter safety requirement
only when authority or intent remains genuinely ambiguous, and record why.

## Reconciliation record — 2026-11-03

- Consolidated: cross-domain execution order, deployment preflight, release
  artifact/shortcut/lineage checks, test invocation findings, retention-safe
  fixture handling, and the current online gate are indexed here.
- Retained as authoritative: binding release/installation and closeout rules
  in `AGENTS.md`; channel isolation, promotion, rollback, and Beta process
  identity rules in `RELEASE-CHANNELS.md`; issue-specific evidence in
  `docs/problem-resolution/closeouts/`.
- Resolved conflict: an older branch rule rank-gated queue entry. The user's
  newest explicit instruction supersedes it: constructed queue admission is
  rank-independent, while the active-game pre-mulligan gate permits ordinary
  play only for fresh exact `5`/`10` and otherwise requires surrender. The
  reconciled `AGENTS.md` rule and its queue/preflight tests own that behavior.
- 2026-11-03 recovery/retention follow-up audit: reviewed `AGENTS.md`, this
  SOP, `RELEASE-CHANNELS.md`, the retention script allowlist, and
  `2026-11-01-season-rank-award-continue.md`. Retained the channel-wide
  30-day requirement in `AGENTS.md`; consolidated the exact extension/root
  allowlist and manual Pester gate here. The five-chest no-input rule is an
  issue-specific runtime exception retained here and its closeout, not copied
  into unrelated card-back classifier policy.

## 1. Operating model and evidence ownership

- A worker may investigate, implement, and run scoped offline validation. The
  current secretary/release owner alone approves integration, deploys, restarts
  the canonical runtime, and evaluates the online completion gate.
- Before an action that can change a runtime, record the current channel,
  version, deployment ID/hash, source branch/commit, app PID, Hearthstone PID
  and state, active log path, and a fresh screenshot where visual state matters.
  Do not substitute a stale footer, log, or screenshot for current lineage.
- Every handoff states the commit, changed files, checks run, exact evidence,
  dirty remainder, blocker, and one next action. A build, commit, or offline
  test result is not an online-E2E completion claim.

## 2. Deployment SOP

Both the worker and release owner must read this section before deployment and
record `Deployment SOP read` in their checklist/handoff. The owner follows the
checked-in `build-and-deploy.ps1` path; no manual JAR copy, ad-hoc launcher, or
shortcut edit can substitute for it.

### 2.1 Preflight

1. Confirm the named branch, channel declaration in `release-channel.json`,
   intended runtime root, and that Beta, Stable, and Release Candidate identities
   cannot overlap. Beta never overwrites Stable.
2. Compare a regression against its last known-good source/artifact before
   designing a fix; keep the concrete delta and test evidence in the closeout
   and handoff.
3. Check for unrelated staged, unstaged, or untracked work. A release artifact
   must have a known source lineage; do not absorb another worker's changes.
4. Record the pre-deploy process/manifest/log ledger above. Preserve user
   `config`, `data`, `log`, and `plugin` backups. Fail closed if a target path,
   channel, ownership marker, or process identity cannot be verified. A blank
   Java command line may be excluded as a foreign Release Candidate only when
   an exact PID lookup reports the exact title `HS Script Release Candidate`;
   record that PID/title as foreign and never stop it. This is not Beta
   ownership evidence. Any missing, altered, or unrelated title remains a
   hard refusal, as do all marker/PID/start-time mismatches.
5. Validate every launcher/watchdog/bootstrap source before acquiring the
   runtime gate, stopping processes, creating runtime directories, or copying
   files. A component copied by the release path but omitted from preflight is
   a release-blocking packaging defect.

### 2.2 Artifact and build

1. Create a new version in `pom.xml`; never reuse a prior artifact name. For a
   Beta retry, collision selection must inspect both the canonical Beta runtime
   and the local `hs-script-app/target` packaged JAR/ZIP output before
   packaging. Preserve prior artifacts; if either location already has the
   candidate name, advance to a fresh unique version and timestamp rather than
   overwrite or reuse the prior attempt.
2. Capture one Pacific build timestamp before packaging and ensure the exact
   value is embedded in JAR `build.info`, the visible-app footer contract, and
   the deployment manifest. `manifest.generatedAt` and the current clock are
   not substitutes.
3. Run the checked-in targeted regression selection with the intended
   `-Dbuild-channel`; test behavior may legitimately differ from the root POM's
   default `UNKNOWN` channel. Preserve focused test coverage for new guards.
4. For Beta releases, `build-and-deploy.ps1` runs
   `hs-script-app/src/test/resources/bat/DebugRunHardCapLauncher.Tests.ps1`
   before packaging whenever tests are enabled. This contract verifies the
   elevated `RunAs` launch, READY publication, and launch-failure refusal; it is
   automated by the release path and is not repeated in the manual list below.
5. Before runtime mutation, manually run the applicable PowerShell contract
   gates. `build-and-deploy.ps1` does **not** invoke Pester automatically:
   `pwsh -NoProfile -File tests/DiagnosticRetentionScheduler.Tests.ps1`;
   `pwsh -NoProfile -File tests/BetaDeploymentProcessStop.Tests.ps1`;
   `pwsh -NoProfile -File tests/ReleaseChannelIsolation.Tests.ps1`; and
   `pwsh -NoProfile -File tests/SyncShortcutsBetaDuplicate.Tests.ps1`.
   Record results and add any changed deployment-contract test to this list.
6. Build and verify the exact JAR/ZIP and required plugin artifacts before any
   runtime replacement. A failing test, missing artifact, or metadata mismatch
   stops the release.

### 2.3 Install, manifest, and launcher

1. Install only through `build-and-deploy.ps1`; it serializes the channel
   runtime, copies the package and support scripts, and updates the deployment
   contract.
2. Verify the manifest names the exact artifact, SHA-256, source branch/commit,
   channel, runtime root, and embedded build timestamp. Verify the canonical
   launcher resolves that manifest-selected JAR, not an old versioned JAR.
3. Preserve installed state that the release path is designed to retain. Never
   use deletion as a workaround for a failed package or stale manifest.

### 2.4 Desktop, Start Menu, and Taskbar shortcuts

1. On every deploy, replace and verify all three user entrypoints. They must
   point to `wscript.exe` with the channel's canonical admin VBS launcher
   argument and the correct working directory.
2. Treat the Taskbar pin as release source of truth, not decoration. Remove
   only an exact stale/numbered alias for the same channel; never remove Stable,
   Release Candidate, or unrelated shortcuts while updating Beta.
3. Launch through the verified shortcut only after the runtime/manifest checks;
   then confirm the app reports the selected version, channel, deployment ID,
   and footer timestamp.

### 2.5 Restart and lineage

1. After install, force-close only the authorized channel runtime and the
   corresponding Hearthstone process, then restart from the canonical
   manifest-backed launcher. Do not leave an old process using replaced files.
2. Verify the new app PID, deployment ID, manifest/artifact hash, log lineage,
   and Hearthstone PID/state. A path-only or executable-name-only process match
   is insufficient; use the exact runtime/JAR identity and fail closed when
   process ownership cannot be independently verified.
3. Keep launch, game launch, strategy refresh, and deployment as separate
   observations. A visual-only check uses a passive launch mode when automation
   could otherwise begin gameplay.
4. For a temporary Beta Never Surrender run, verify exact Beta channel and
   deployment identity, fresh numeric rank evidence, and an active Debug Run
   lease with an absolute start/deadline span of exactly 30 minutes. Require
   elevated watchdog `READY` for the same run ID, deployment ID, and process
   identity and a positive PID for the actual elevated watchdog before admitting
   the exception. The short-lived non-elevated launcher PID and elevated
   watchdog PID are different identities and must be logged distinctly. When
   launching through `Start-Process -Verb RunAs`, do not use stream redirection;
   forward every mandatory watchdog parameter (including `WatchdogLogPath`) and
   quote PowerShell command-line metacharacters, notably the `|` in a manifest
   `jar|hash` deployment ID. Across a JVM restart, restore only
   that run ID and original absolute deadline when persisted state is valid,
   unexpired, and bound to the current deployment; never renew from process
   startup. If READY is absent/refused, deployment binding differs, or the
   deadline expired, refuse `START_ON_OPEN` and do not auto-arm a replacement
   lease. Before E2E, record that the watchdog actually stopped the exact
   Beta-owned Hearthstone process at expiry; an armed watchdog or STOP request
   alone is not proof. This supplements `AGENTS.md` and
   `RELEASE-CHANNELS.md`; it does not replace their deployment, shortcut,
   retention, or online gates.

### 2.6 Post-deploy UI, E2E, promotion, and rollback

1. Capture the visible footer and compare it against the embedded artifact and
   manifest timestamp. Record the screenshot or equivalent visible-app evidence.
2. Run the online gate against the exact deployed artifact: execute two
   consecutive new games, with at least one non-conceded player win in the
   authoritative `Power.log`; correlate screenshots to that same run/log;
   and accept zero accepted-chain errors, exceptions, timeouts, OCR failures,
   unhandled `UNKNOWN` states, or stuck screens longer than 60 seconds. A
   screen still stuck beyond the user's two-minute recovery threshold requires
   a clean restart of the Beta Hearthstone and Battle.net processes, then a
   fresh gate evaluation. Offline success is never a substitute.
3. Reconcile all requested/worker commits against the deployed manifest before
   announcing completion. Report the version, timestamp, deployment ID/hash,
   shortcut targets, PIDs/log path, online-gate status, and pending items.
4. Promote Beta only through the reviewed channel procedure after its exact
   artifact passes the online gate. For Beta-to-RC evidence fields and rollback,
   follow `RELEASE-CHANNELS.md`; never copy a JAR manually to Stable or RC.

## 3. Testing and regression SOP

- Reproduce a regression against the last known-good commit, artifact, or
  fixture before adding heuristics. Record whether it reproduces there.
- Add focused boundary and consumer/integration tests that prove both the
  intended success and rejected/failure paths. Keep release-script test lists
  synchronized with new deployment-critical behavior.
- Invoke tests with the same channel and fork/cwd assumptions as the release
  path. A no-fork reactor run can expose a reactor-root working directory;
  diagnose source-relative fixture failures with a normal fork before calling
  them product failures.
- Do not make source tests depend on ephemeral runtime screenshots subject to
  retention. Check in sanitized fixture copies or mark an external-evidence
  test as explicitly unavailable; do not weaken its assertions just to obtain
  green output.
- Run `git diff --check`, retain the exact command and report/test XML result,
  and keep full-reactor inherited failures distinct from owned focused results.

## 4. Runtime, recovery, and OCR SOP

- Observe the actual screen, current Power.log/session state, process/window
  authority, and current mode before changing recovery logic. Treat pause,
  crash, idle, blocked action, and terminal result as different states.
- Screen/OCR actions must be narrowly classified, fresh, foreground/process
  verified, bounded, and fail closed. Capture a smallest-needed ROI and log the
  provider, fallback, confidence, timeout, ROI, and decision.
- A dispatched click is not a completed transition. Require fresh authoritative
  post-action observation, bound retries, and retain action scope so recovery
  cannot broaden into generic gameplay, matchmaking, or surrender input.
- Rank authorization is a separate safety gate. Matchmaking admission is not
  rank-gated: allow the constructed match to start. After the game begins, the
   active-game pre-mulligan preflight permits ordinary input only for fresh exact
   rank `5` or `10` unless the narrowly scoped temporary Beta exception below is
   live; without it, every other, missing, or unknown rank routes to mandatory
   surrender. Keep this queue-versus-in-game split and its exact tests in sync
   with `AGENTS.md`; ambiguous OCR, cache, mode, or phase evidence never
   broadens ordinary-play authorization.
- Apply the temporary Beta Never Surrender deployment/restart checklist in
  §2.5 together with the separate rank authorization boundaries above.
- The monthly five-chest/season-reward flow is a user-owned exception: on a
  fresh recognized award, chest, or rewards frame, notify once and apply a
  manual pause. Do not click its chest or Continue-looking controls, restart
  the client, or treat it as a card-back result page. Resume only after the
  user dismisses it and a fresh non-reward destination is observed.

## 5. Diagnostics, screenshots, and retention SOP

- Store runtime evidence under the channel's designated diagnostic location;
  retain a provenance link (version/deployment ID, timestamp, log lineage, and
  screenshot hash/path) in the closeout or handoff.
- The automatic 30-day diagnostic retention applies separately to Stable, Beta,
  and Release Candidate. Each channel deployment installs and verifies that
  channel's scheduled `diagnostic-retention.ps1` cleanup before release
  completion.
- The checked-in allowlist removes only aged files inside approved diagnostic
  roots: `.log`, `.jsonl`, images (`.png`, `.jpg`, `.jpeg`, `.webp`), `.txt`,
  `.state`, `.json`, `.csv`, and `.tsv`. It explicitly rejects config/settings/
  user-data JSON, statistics databases, deployment manifests, rollback files,
  and rollback backups. Never broaden it without matching Pester coverage;
  live configuration, data, plugin backups, source-controlled fixtures, and
  rollback/deployment records remain protected.
- Rollout status (2026-11-03): the audited Beta `4.16.746` and Release
  Candidate `4.16.711` runtimes have the cleanup script/task; Stable `4.16.399`
  has a manifest but lacks both. Treat Stable retention installation as an open
  channel-owned deployment item, not as proof that all-channel retention is
  complete. Do not cross-install a Beta/RC artifact or task into Stable.
- If retention removes historical runtime screenshots, state that precise
  limitation rather than treating a missing file as an application regression.
  Replace only with a new capture or a checked-in sanitized test fixture when
  authorized.
- Keep noisy debug diagnostics in files where possible, while presenting
  actionable state transitions and errors in the UI log.

## 6. SOP maintenance and closeouts

Before editing this document or another SOP, audit `AGENTS.md`,
`RELEASE-CHANNELS.md`, this document, and relevant problem-resolution closeouts.
List duplicate/conflicting rules in the change record; retain an existing
authoritative rule by reference, or consolidate it here with a clear owner.
The user's newest explicit instruction supersedes older local text; when that
does not resolve the conflict, retain the stricter safety rule and record the
reason. Never silently relax a safety, channel-isolation, retention, evidence,
or release-ownership constraint.

Every deployment-related closeout classifies each gotcha, finding, error, and
fix. A reusable item becomes a named step, check, or test in the applicable
Deployment subsection after this audit; a genuinely one-off item records the
specific reason it is not reusable. This prevents historical closeouts from
becoming an unindexed substitute for operating procedure.

For every fix, create or update the required problem-resolution closeout with
investigation/cause, authorized fix, verification, and an enforceable future
prevention step. An incomplete online gate leaves the issue open even when
offline work and deployment preparation are complete.
