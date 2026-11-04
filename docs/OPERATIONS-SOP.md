# Hearthstone Copilot operations SOP

This is the canonical categorized operating procedure for work that can affect a
user-facing Hearthstone Script runtime. It turns repeated deployment findings,
failures, and fixes into a single routing document; it does **not** relax the
binding rules in [`AGENTS.md`](../AGENTS.md) or the channel rules in
[`RELEASE-CHANNELS.md`](RELEASE-CHANNELS.md). When sources differ, use the
stricter requirement and reconcile the duplication before changing either one.

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
   channel, ownership marker, or process identity cannot be verified.

### 2.2 Artifact and build

1. Create a new version in `pom.xml`; never reuse a prior artifact name.
2. Capture one Pacific build timestamp before packaging and ensure the exact
   value is embedded in JAR `build.info`, the visible-app footer contract, and
   the deployment manifest. `manifest.generatedAt` and the current clock are
   not substitutes.
3. Run the checked-in targeted regression selection with the intended
   `-Dbuild-channel`; test behavior may legitimately differ from the root POM's
   default `UNKNOWN` channel. Preserve focused test coverage for new guards.
4. Build and verify the exact JAR/ZIP and required plugin artifacts before any
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
   is insufficient; use exact runtime/JAR or the guarded ownership-marker
   fallback described in `RELEASE-CHANNELS.md`.
3. Keep launch, game launch, strategy refresh, and deployment as separate
   observations. A visual-only check uses a passive launch mode when automation
   could otherwise begin gameplay.

### 2.6 Post-deploy UI, E2E, promotion, and rollback

1. Capture the visible footer and compare it against the embedded artifact and
   manifest timestamp. Record the screenshot or equivalent visible-app evidence.
2. Run the applicable online gate against the exact deployed artifact. The
   current gate requires fresh process/log lineage, accepted-game evidence,
   screenshots, and the specified consecutive games; offline success is never
   a substitute.
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
- Preserve rank authorization and surrender safety in `AGENTS.md`: only fresh
  exact constructed rank 5 or 10 may queue/continue, and all ambiguous OCR,
  cache, mode, or phase evidence fails closed.

## 5. Diagnostics, screenshots, and retention SOP

- Store runtime evidence under the channel's designated diagnostic location;
  retain a provenance link (version/deployment ID, timestamp, log lineage, and
  screenshot hash/path) in the closeout or handoff.
- Follow the user-approved retention period. Cleanup must not delete live
  configuration, data, plugin backups, or source-controlled fixtures.
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
Never silently relax a safety, channel-isolation, retention, evidence, or
release-ownership constraint.

For every fix, create or update the required problem-resolution closeout with
investigation/cause, authorized fix, verification, and an enforceable future
prevention step. An incomplete online gate leaves the issue open even when
offline work and deployment preparation are complete.
