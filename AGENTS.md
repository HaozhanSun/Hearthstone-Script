# Hearthstone Copilot project rules

These rules apply to every Codex session that changes or builds this project.

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
5. Update the Desktop and Start Menu shortcuts. For Beta, update the existing Taskbar shortcut in place only when `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Taskband\FavoritesResolve` resolves the exact canonical `User Pinned\TaskBar\<shortcut>` path. A `.lnk` file merely existing in that folder is not proof of a pin. Never edit Taskband registry data or attempt silent pin insertion.
6. If Taskband does not resolve the canonical path, fail before creating the runtime directory, stopping processes, copying artifacts, or writing shortcuts. Give the operator the manual Windows taskbar repin instruction in `docs/RELEASE-CHANNELS.md`; rerun deployment only after Taskband resolves the canonical path.
7. After deployment, the secretary must visually confirm the Beta icon is present, click that actual taskbar icon, and verify the app footer/current log identifies the deployment ID from the manifest. Shortcut metadata and Taskband registration alone do not prove that the visible icon launches the intended build.
8. Record the version, deployment ID, JAR hash, launcher target, canonical Taskband-resolved path, and the actual-pin launch evidence in the handoff.

Do not call a build complete if the build succeeds but deployment or shortcut replacement fails. Do not delete user data directories such as `config`, `data`, `log`, or `plugin` backups while deploying.

## Verification checklist

- Confirm `pom.xml` contains a version greater than the previous build.
- Confirm the built artifact exists and the deployment manifest names that exact artifact and hash.
- Confirm Desktop and Start Menu shortcuts target `wscript.exe` with the stable launcher as their argument.
- For Beta, confirm Taskband `FavoritesResolve` contains the exact canonical Taskbar `.lnk` path, not a stale/archived path; confirm the existing file is updated in place without deletion/recreation.
- Require the secretary's visible Beta taskbar-pin click test and matching manifest/deployment identity before reporting the Beta deployment verified.
- Confirm the stable launcher resolves the manifest's current JAR.

## Rank authorization and surrender safety

- Hard eligibility rule: the script may enter constructed matchmaking or
  continue a constructed game only after fresh, positively verified OCR reads
  the current constructed rank as exactly `5` or exactly `10`. League/tier text
  (Silver, Gold, Platinum, etc.) is not required to authorize those two
  numbers; Legendary and every other numeric value are ineligible.
- Enforce this before the first matchmaking input and again at the active-game
  rank preflight. A post-match/pre-mulligan check alone is too late to satisfy
  the no-matchmaking rule.
- Surrender-streak protection may block an automatic surrender, but it must
  never grant rank eligibility or bypass the rank gate. Win-streak policy also
  cannot turn an ineligible/unknown rank into an allowed match.
- PaddleX timeout, cancellation, translated exception, missing/empty badge,
  unknown or conflicting OCR, confidence below `0.90`, stale
  cached evidence, invalid capture bounds, and mode/phase mismatch all fail
  closed: do not queue or continue. Never reuse a prior game's rank as current
  evidence. Legacy OCR may authorize only with repeated agreeing numeric reads.
- Every rank-policy change must have deterministic offline tests for exact
  boundaries `4/5/6`, `9/10/11`, Legendary numeric ratings `>20`, unknown and
  missing evidence, low confidence, PaddleX failure/cancellation/translated
  exception, stale cache, mode mismatch, and surrender-streak precedence. Tests
  must assert that no matchmaking dispatch occurs unless the rank gate allows
  it, and preserve this precedence against future strategy/streak changes.

## Visible build-footer timestamp verification

- On every Beta or Stable invocation of `build-and-deploy.ps1`, capture the
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

## Stable and beta release channels

- GitHub `main` is the stable branch. Keep it on the last known-good source
  line; do not merge experimental work merely because it compiles.
- Experimental work belongs on `beta/*` branches and must declare `channel:
  beta` in `release-channel.json`.
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
- Workers report to the current secretary session; only that session deploys.
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
- Every fix needs an offline test plus a live log/screenshot correlation; a
  green build alone is not evidence of end-to-end correctness.
- Keep app launch, game launch, strategy refresh, and deployment separate;
  verify each explicitly and never launch during build-only work.
- Bump the strategy version on every strategy change and verify the running
  dropdown/runtime contract, not only source files or package contents.
- Keep Beta and Stable artifacts, manifests, logs, PIDs, shortcuts, and user
  data isolated; never kill a live run unless the user authorized that scope.
- For recovery/OCR, prefer the smallest required ROI and a persistent service;
  log provider, timeout, fallback, ROI, and result so failures are actionable.
