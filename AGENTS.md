# Hearthstone Copilot project rules

These rules apply to every Codex session that changes or builds this project.

## Canonical operations SOP and reconciliation

- Before any deployment, both the implementation worker and the secretary/release owner must read
  [`docs/OPERATIONS-SOP.md`](docs/OPERATIONS-SOP.md) and record `Deployment SOP read` in their
  deployment checklist or handoff. The SOP organizes operational procedure; the stricter
  requirements in this file and `docs/RELEASE-CHANNELS.md` remain authoritative.
- Before editing an SOP, audit this file, `docs/RELEASE-CHANNELS.md`, the current canonical SOP,
  and relevant problem-resolution closeouts for duplicate or conflicting rules. Record what was
  consolidated versus retained, and reconcile to one authoritative rule by cross-reference. The
  user's newest explicit instruction supersedes older local guidance; use the stricter safety rule
  only when authority or intent remains genuinely ambiguous, record that resolution, and never
  silently weaken a channel, safety, retention, or evidence requirement.
- Each deployment-related closeout must classify every gotcha, finding, error, and fix as either
  a reusable categorized SOP step/check/test (then add it to the relevant Deployment subsection
  after that audit) or a one-off item (then record the specific reason it is not reusable).

## Completion requires forced installation

For every user-requested code or configuration change that is reported as complete,
run the checked-in `build-and-deploy.ps1` release path after validation. This means
creating a new version, installing it into the canonical runtime directory, updating
the manifest, and replacing/verifying the Desktop, Start Menu, and Taskbar shortcuts.
Do not report the change as complete while any of those installation or verification
steps are unfinished. A user request that explicitly limits the work to offline/read-
only investigation is the exception; in that case do not build or install.

## Release and Windows shortcut rule

Every build that can be used to run the application is a new build and must:

1. Bump the application version in `pom.xml`. Never reuse the previous build's version or artifact name. Use the checked-in `build-and-deploy.ps1` release path when possible.
2. Build and verify the new JAR/ZIP before deployment.
3. Deploy the new artifact to the canonical Hearthstone Script runtime directory:
   `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script`
4. Update the deployment manifest so the launcher selects the new JAR and verifies its hash.
5. Replace and verify all three user shortcuts: Desktop, Start Menu, and the per-user Taskbar pin. Shortcuts must target the stable admin launcher, which must resolve the newly deployed JAR; they must not point at an old versioned JAR.
6. Record the resulting version, deployment ID, JAR hash, launcher target, and all shortcut targets in the handoff.

Do not call a build complete if the build succeeds but deployment or shortcut replacement fails. Do not delete user data directories such as `config`, `data`, `log`, or `plugin` backups while deploying.

## Diagnostic retention

- The automatic 30-day diagnostic retention applies independently to Stable, Beta, and Release
  Candidate. Every channel deployment must install and verify its channel-scoped
  `diagnostic-retention.ps1` scheduled cleanup.
- Retention may remove only aged diagnostic logs and screenshots. It must never remove live
  configuration/data/plugin backups or source-controlled test fixtures.

## Verification checklist

- Confirm `pom.xml` contains a version greater than the previous build.
- Confirm the built artifact exists and the deployment manifest names that exact artifact and hash.
- Confirm Desktop, Start Menu, and Taskbar shortcuts all target `wscript.exe` with the stable launcher as their argument.
- Confirm the stable launcher resolves the manifest's current JAR.

## Modular implementation and regression boundaries

- Prefer well-isolated, modular, composable changes that can scale without spreading policy across unrelated subsystems. Give each component clear ownership and stable interfaces, with clean plug/unplug boundaries between policy, orchestration, dispatch, and persistence.
- Avoid one-off hacks, special-case branches, and cross-cutting coupling when a reusable policy or adapter boundary fits. Keep behavior changes scoped to the smallest responsible component and preserve unrelated behavior.
- Every behavior change must include focused regression tests at the changed boundary and an integration-level check for its consumers. Tests should verify observable outcomes and rejection/failure behavior, not merely that an action was logged or requested.
- When a request contains multiple verifications, track each item separately; a blocker pauses only dependent checks, while safe independent checks continue sequentially, and the whole task is not complete until every required item passes.

## Problem-resolution closeout gate

- For every issue or requested fix, complete and record the four-part workflow:
  investigate the evidence and cause; implement the authorized fix; verify the
  outcome; and add a concrete future-prevention step. Also record how that
  prevention is enforced, preferably by a regression test or automated guard.
- Use `docs/templates/PROBLEM-RESOLUTION-CLOSEOUT.md` for each issue. Every
  section must contain evidence, or `N/A — <specific reason>` when the step is
  genuinely ineligible. Do not use `PENDING`, `TODO`, or an unexplained blank
  in a closed record. Incomplete verification means the issue remains open.
- Before moving to the next task, run
  `pwsh -NoProfile -File tools/validate-problem-resolution-closeout.ps1 -Path <record>`
  and require it to pass. The validator's own regression suite runs in the
  release-channel CI workflow.

## Rank authorization and surrender safety

- Matchmaking must not be blocked by rank. Always allow the constructed match
  to start; rank policy begins after the game starts at the active-game,
  pre-mulligan preflight.
- Ordinary in-game play requires fresh, positively verified OCR of exactly
  `5` or exactly `10`. League/tier text (Silver, Gold, Platinum, etc.) is not
  required to authorize those two numbers; Legendary and every other numeric
  value, missing evidence, and unknown evidence are ineligible and must route
  to the mandatory surrender path.
- Surrender-streak protection may block an automatic surrender, but it must
  never grant rank eligibility or bypass the rank gate. Win-streak policy also
  cannot turn an ineligible/unknown rank into an allowed match.
- PaddleX timeout, cancellation, translated exception, missing/empty badge,
  unknown or conflicting OCR, confidence below `0.90`, stale
  cached evidence, invalid capture bounds, and active-game mode/phase mismatch
  all fail closed to mandatory surrender. Never reuse a prior game's rank as
  current evidence. Legacy OCR may authorize only with repeated agreeing
  numeric reads.
- Every rank-policy change must have deterministic offline tests for exact
  boundaries `4/5/6`, `9/10/11`, Legendary numeric ratings `>20`, unknown and
  missing evidence, low confidence, PaddleX failure/cancellation/translated
  exception, stale cache, mode mismatch, and surrender-streak precedence. Tests
  must assert matchmaking dispatch remains rank-independent while the
  post-match preflight permits ordinary input only for `5`/`10` and routes all
  other/unknown evidence to surrender; preserve this precedence against future
  strategy/streak changes.

## Visible build-footer timestamp verification

- On every Beta, Stable, or Release Candidate invocation of `build-and-deploy.ps1`, capture the
  build time once and update the root POM's `local-build-timestamp-pacific`
  before Maven packaging, even when the source version is already newer than
  the deployed manifest or no manifest exists yet. Version bumping and
  timestamp stamping are separate decisions.
- Require the exact captured value to appear in the produced JAR's
  `build.info`, verify it before stopping or modifying the deployed runtime,
  and write that same value as `buildTimestampPacific` in the deployment
  manifest. A mismatch is a release failure; do not infer artifact time from
  manifest `generatedAt` or the machine clock after packaging.
- The main-window footer must display the embedded artifact's version, channel,
  and `BuildInfo.BUILD_TIMESTAMP_PACIFIC` in readable Pacific local time. Do
  not derive the footer time from the current machine clock at launch.
- Before every deployment, run the offline UI contract test and verify it
  covers both Beta and Stable footer strings plus the timestamp source in
  filtered `build.info`/`BuildInfo`.
- After deployment and launch, capture the visible app footer and compare its
  timestamp to `buildTimestampPacific` embedded in the exact deployed JAR.
  Retain the screenshot or an equivalent visible-app assertion with the
  release evidence. A successful build, manifest/hash update, and shortcut sync
  do not prove the requested UI reached the user.
- The prior check missed this because `BuildChannelUiContractTest` asserted
  only version and channel, while release verification checked artifact,
  manifest, launcher, and shortcuts without asserting the visible footer's
  build timestamp. Keep the footer assertion and post-launch comparison as
  separate required gates.

## Post-deployment completion notice and queue reconciliation

- After every deployment, send the user a concise completion notice containing
  the deployed version and artifact timestamp, deployment ID and artifact hash,
  Taskbar shortcut verification, running process PID/channel/active log path,
  online E2E status, and an explicit list of requested or worker changes still
  pending.
- Before saying that all requested changes were deployed, reconcile the active
  worker/commit queue against the deployed manifest and artifact. A successful
  latest deployment alone does not prove earlier or parallel requested changes
  are included; never use a blanket “all changes deployed” statement without
  that reconciliation.

## Stable, beta, and Release Candidate channels

- GitHub `main` is the stable branch. Keep it on the last known-good source
  line; do not merge experimental work merely because it compiles.
- Experimental work belongs on `beta/*` branches and must declare `channel:
  beta` in `release-channel.json`.
- Release Candidate source snapshots use `beta/release-candidate-*` branches
  and declare `channel: release-candidate`; their runtime root, manifest, app
  identity, icon, and shortcuts must be distinct from both Stable and Beta.
- Stable and beta deployments use separate runtime roots, manifests, PIDs,
  logs, and shortcut names. A beta deployment must never overwrite the stable
  `Hearthstone Script` runtime or its shortcuts.
- Promote beta to `main` only after the exact beta artifact passes the online
  E2E completion gate. The checked-in channel validation workflow must pass
  before promotion.

## Operational feedback

- Number user-raised topics by date and sequence: use `YYYY-MM-DD · #N`, restarting at 1 each day; keep that identifier in status reports.
- When the user steers the conversation to a new task, preserve the interrupted
  task as pending unless the user explicitly replaces, abandons, or cancels it.
  Complete the steered task, then return to the pending task automatically and
  continue from its last verified phase; do not wait for the user to remind you.
- Maintain a compact pending-task checkpoint containing the original objective,
  completed work, next action, blockers, and any task-specific acceptance
  criteria or evidence requirements. A steering turn may change the active
  focus, but it must not erase that checkpoint.
- If a turn or tool call is interrupted, re-observe the current runtime,
  worktree, and logs before acting, then resume from the checkpoint. Do not
  restart completed work or report the interrupted task as complete merely
  because the steering task finished.
- Only discard the pending task when the user clearly asks to stop or replace
  it; otherwise leave the conversation with the original task either resumed,
  completed with evidence, or explicitly blocked.
- Workers report to the current secretary session; only that session deploys. Every worker must send a direct completion or blocker notification to that current secretary session before ending its turn, including status, changed files/commit, verification evidence, blockers, and the next action; a worker-thread final answer alone is not a handoff.
- Secretary implementation boundary: the current secretary only performs read-only
  investigation, evidence collection, worker routing, review, and deployment
  coordination. It must not implement code, strategy, or configuration fixes
  directly; all implementation work must be performed by a worker session.
- Route workers to the current session ID, never an archived secretary; do not
  wait on workers when a completion notification is available.
- Before diagnosis, capture the exact runtime version, PID, channel, log file,
  and matching screenshot; do not infer state from stale UI text.
- Treat `F2`/pause, crash, idle, and blocked-action as different states; record
  the direct trigger before changing code or restarting anything.
- Record offline tests and live log/screenshot correlation in the closeout
  gate whenever those checks are eligible. A green build alone is not evidence
  of end-to-end correctness; unavailable live checks require a specific N/A
  reason and keep the issue open when live evidence is required.
- Keep app launch, game launch, strategy refresh, and deployment separate;
  verify each explicitly and never launch during build-only work.
- Bump the strategy version on every strategy change and verify the running
  dropdown/runtime contract, not only source files or package contents.
- Keep Beta and Stable artifacts, manifests, logs, PIDs, shortcuts, and user
  data isolated; never kill a live run unless the user authorized that scope.
- For recovery/OCR, prefer the smallest required ROI and a persistent service;
  log provider, timeout, fallback, ROI, and result so failures are actionable.
