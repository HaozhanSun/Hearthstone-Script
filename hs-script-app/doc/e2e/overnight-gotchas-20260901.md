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

## v4.16.138 run 17598_2257 — readiness self-lock before first game

- **Status:** `invalid E2E evidence`; no game was created and no run credit.
- **Observed:** the repaired build recognized the real Hearthstone hub with
  `hubNavigationBright=0.182`, and Legacy OCR reported
  `contractAccepted=true`. However, `HubModeStrategy.afterEnter` called the
  new E2E dispatch gate before the first `CREATE_GAME`; the gate returned
  `WAITING_FOR_CREATE_GAME`, so the initial mode-entry/matchmaking inputs were
  never sent. The fresh Power.log stayed at length 0 and the bounded gate then
  paused the run.
- **Fix:** keep the fresh `CREATE_GAME` requirement for gameplay dispatch, but
  allow only the bounded pre-game contexts (`hub-after-enter`, popup dismiss,
  tournament entry, log-size check, and start matching) while the gate is
  still waiting. They remain blocked after the timeout. This is the minimal
  compatibility path matching the pre-gate upstream behavior at `e6ede413^`.

## v4.16.139 run 36241_8735 — rank OCR fail-closed after real game creation

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the release candidate passed hub recovery and matchmaking. A
  fresh Power.log was paired to Java PID `2536`, and the first real game was
  detected, but its terminal state was `draw-or-unknown` with missing script
  milestones, so the authoritative result was rejected. The next real game
  reached `DRAWN_INIT_CARD` for current player `laz#12793`; rank OCR returned
  no usable badge text and emitted `RANK_OCR_EVIDENCE rank=UNKNOWN`, then
  `RANK_POLICY_BLOCKED ... action=PAUSE`. This violates the no-OCR-failure,
  no-UNKNOWN completion gate. The per-run evidence is retained at
  `outputs/Hearthstone Script/log/e2e-runs/36241_8735`; exact Java, runner, and
  Hearthstone processes were stopped after command-line checks.
- **Next investigation:** compare `CurrentRankDetector`'s rank-badge crop and
  OCR contract against the known-working upstream path and the actual
  opening-game screenshot before attempting another release build. Do not
  reuse this run's Power.log or screenshots as success evidence.

## v4.16.140 run 77111_1981 — rank numeral crop unresolved on live board

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the build reached a real board and fresh Power.log. The
  player's rank-10 shield was visibly present, but the dedicated numeral crop
  did not capture a complete usable value. The policy therefore paused with
  `rank-ocr-unresolved`; no authoritative game result was produced.
- **Preserved evidence:**
  `outputs/Hearthstone Script/log/e2e-runs/77111_1981`, including the current
  Power.log and same-run console/ledger data. The exact Java/runner/Hearthstone
  processes were stopped after command-line validation.
- **Fix:** `badgeVisible` now delegates to the existing visual-only badge
  signal (metal frame or dedicated two-digit width), and a regression test
  proves isolated digit noise is ignored. The dedicated rank numeral crop was
  widened for the live rank-10 shield and v4.16.142 was rebuilt and deployed;
  start the next attempt with a fresh run id and do not reuse this evidence.

## v4.16.141 run 78321_8560 — live rank badge visible but numeral crop too narrow

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the build reached a real mulligan/VS screen with fresh
  Power.log `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_02_55_10\\Power.log`.
  The same-run unknown-state screenshot shows the player's rank-10 shield
  inside the `rank-badge-unresolved` region, while the OCR crop returned no
  usable characters. The UI consequently logged `rank-ocr-unresolved` and
  paused before an authoritative result.
- **Preserved evidence:** run directory
  `outputs/Hearthstone Script/log/e2e-runs/78321_8560`, the same-run console and
  ledger, Power.log, and screenshot
  `outputs/Hearthstone Script/log/unknown-states/rank-detection/2026-09-01/unknown-state-20260901-025710-454-rank-ocr-unresolved-5145de26-5283-4cc1-aa83-2b043412799f.png`.
  Exact Java PID `37476`, runner wrapper PID `46312`, outer wrapper PID
  `45352`, and Hearthstone PID `9252` were stopped only after command-line
  validation.
- **Fix:** widen the tight rank numeral crop to include the complete live
  two-digit badge while retaining the metal/two-digit visual gate. v4.16.142
  was rebuilt and deployed; do not reuse this run's evidence as success.

## v4.16.142 run 97851_3200 — provenance suspicion disproved; empty rank probe semantics

- **Status:** `invalid E2E evidence`; no run credit.
- **Provenance check:** the deployed v4.16.142 JAR contains the provider-owner
  implementation. The source files in the integration tree are byte-for-byte
  equivalent to owner commit `1d64115adb9bf73b3af07ba1c8dfe307eb20336c`, and
  the JAR/manifest SHA-256 is
  `4b8a51a0345ef91abd869f31f178fa988e43747cb5e88369bde0f263c6cc7b1e`.
- **Observed:** the run initially inherited the old runtime configuration
  (`USE_PADDLEX_OCR=false`, `OCR_PROVIDER_MODE=LEGACY_ONLY`; config hash before
  correction `8cc4c9dfd95207f8f4f254f1e4db3fc1e498e8cea43e0c27331130cf178610b4`).
  That route produced an empty rank crop and logged
  `OCR_PROVIDERS_FAILED ... legacy=contract-rejected`; a script-side
  `E2E_WIN_RESULT` was correctly marked `UNPAIRED` because no new authoritative
  Power.log win was paired. The run was stopped before it could satisfy the
  completion gate.
- **Correction:** canonical runtime config is now `USE_PADDLEX_OCR=true` and
  `OCR_PROVIDER_MODE=AUTO`, with post-correction hash
  `739cbf9146d5eadff4ab8d85565d2adb9bcfdccd766110248874a1a88f382130`.
  Rank OCR's intermediate empty
  crop candidates now log `OCR_PROVIDER_PROBE_EMPTY`; they remain empty and
  therefore fail closed unless another candidate resolves the rank. Genuine
  non-probe contract failures retain `OCR_PROVIDERS_FAILED`.
- **Preserved evidence:** run directory
  `outputs/Hearthstone Script/log/e2e-runs/97851_3200`, fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_03_06_20\\Power.log`, same-run
  console/ledger, and exact validated process stops for Java PID `42032`,
  runner PID `42676`, and Hearthstone PID `18896`.

## v4.16.143 — rank probe empty is not provider contract failure

- **Status:** `deployed candidate`; fresh E2E still pending.
- **Fix:** rank detection makes several intentionally narrow OCR probes. A
  blank `current-rank-*` crop is now logged as
  `OCR_PROVIDER_PROBE_EMPTY`, not `OCR_PROVIDERS_FAILED`; the empty result is
  still returned and the detector remains fail-closed if no later candidate
  resolves the rank. Genuine non-probe empty/contract failures retain the
  failure marker.
- **Verification:** OcrRuntime and SurrenderPolicy focused tests passed 47/47;
  the full reactor passed 123/123. v4.16.143 JAR SHA-256 is
  `34ad28dd06ee12de3f11c87ad3e0076d128ba0f52700a39b43fbec4a1a8c0cf4`, and
  the runtime config is `USE_PADDLEX_OCR=true`/`OCR_PROVIDER_MODE=AUTO`.

## v4.16.143 run 79485_5719 — startup readiness expired before screen recovery

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the run used the v4.16.143 JAR and corrected PaddleX/AUTO
  configuration. Hearthstone was visibly at the hub, but the stale-screen
  recovery OCR completed after the 45-second readiness deadline. The run
  emitted `E2E_READINESS_FAIL_CLOSED` for an empty fresh `Power.log` before a
  new game could be dispatched; no game or result evidence was produced.
- **Preserved evidence:** run directory
  `outputs/Hearthstone Script/log/e2e-runs/79485_5719`, fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_03_24_29\\Power.log`, the
  screen-recovery screenshots, same-run console/ledger, and validated stops
  for Java PID `31312`, runner PIDs `41316`/`37244`, and Hearthstone PID
  `49936`.
- **Fix:** increase only the bounded default E2E readiness wait from 45 to
  180 seconds so slow real-client PaddleX screen recovery can dispatch the
  first fresh `CREATE_GAME`. The gate remains fail-closed after the new
  deadline, rejects stale log tails, and the focused readiness test pins the
  180-second bound.

## v4.16.144 run 37036_4091 — stale screen recovery paused a live game

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** after two preserved startup attempts, the third attempt used
  the v4.16.144 JAR and corrected PaddleX/AUTO configuration. Battle.net
  launched Hearthstone, the run observed fresh `CREATE_GAME`, and the player
  reached a real mulligan/board state. During a slow screen-recovery OCR call,
  the game entered `inWar=true`, but the old startup inspection continued and
  later emitted `SCREEN_RECOVERY_PAUSE_ACTIVE reason=unresolved-ocr-or-visual-state`.
  The live game was therefore paused before a result and cannot count.
- **Preserved evidence:** run directory
  `outputs/Hearthstone Script/log/e2e-runs/37036_4091`, attempts 1–3 in the
  same-run console/ledger, fresh Power.logs
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_03_50_34\\Power.log` and
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_03_46_23\\Power.log`,
  recovery screenshots, and exact validated stops for the v4.16.144 Java and
  Hearthstone processes. No result or win was accepted.
- **Fix:** discard a recovery inspection immediately after OCR if the client
  has entered a live war during that inspection; do not apply stale screen
  evidence or pause the active game. A focused regression test covers this
  guard. Rebuild/deploy this fix as the next candidate before fresh E2E.

## v4.16.145 run 80407_5446 — transient native input error rejects the run

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** v4.16.145 loaded with `USE_PADDLEX_OCR=true` and
  `OCR_PROVIDER_MODE=AUTO`, attached fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_04_04_36\\Power.log`,
  reached `E2E_READINESS_READY`/`CREATE_GAME`, and showed a real mulligan
  screen. The run then logged `E2E_INPUT_NATIVE_FAILED pos=(930,884)` at
  04:05:28; a later retry completed the input, but the configured completion
  gate requires zero errors. The run was stopped before any authoritative
  accepted result, and its evidence remains excluded.
- **Preserved evidence:**
  `outputs/Hearthstone Script/log/e2e-runs/80407_5446`, the fresh Power.log,
  same-run console/ledger, and the visible mulligan screenshot. Exact Java
  PID `36008` and Hearthstone PID `38288` were stopped after command-line
  validation; the runner was interrupted with Ctrl+C.
- **Disposition:** no source fix is inferred from this run. Use a new run id
  on the same v4.16.145 build/config lineage and require a clean log with no
  native-input error before counting any games.

## v4.16.145 run 49855_7462 — mode transition interrupted the mouse path

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the run used corrected PaddleX/AUTO configuration and fresh
  Power.log `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_04_10_15\\Power.log`.
  It reached a real `CREATE_GAME`/mulligan state, but logged
  `E2E_INPUT_NATIVE_FAILED pos=(1566,1003)` at 04:11:07. The stack trace
  identifies `InterruptedException: sleep interrupted` from
  `MouseUtil.moveNativeAlongCurve` while the client changed mode; the input
  path was cancelled as part of that transition, not because SendInput itself
  failed. The strict zero-error gate still rejects the run, and it was stopped
  before an authoritative accepted result.
- **Preserved evidence:**
  `outputs/Hearthstone Script/log/e2e-runs/49855_7462`, the fresh Power.log,
  same-run console/ledger, and the real mulligan screenshot. Exact Java PID
  `50620` and Hearthstone PID `35804` were stopped after command-line
  validation; the runner was interrupted with Ctrl+C.
- **Fix:** classify the expected interruption as
  `E2E_INPUT_CANCELLED_PHASE_CHANGE` at INFO level while preserving the
  thread-interrupted flag; unexpected throwables remain ERROR. v4.16.146 was
  rebuilt/deployed with a focused regression test. A new run must verify that
  the cancellation marker appears without any native-input failure.

## v4.16.146 — input-cancellation classification release checkpoint

- **Status:** `deployed candidate`; fresh E2E pending.
- **Fix:** mode transitions can interrupt a delayed mouse path. The E2E click
  handler now records that expected cancellation at INFO rather than emitting
  an error, and continues to report genuinely unexpected native exceptions as
  `E2E_INPUT_NATIVE_FAILED`.
- **Verification:** full reactor passed 125/125 with 0 failures and 0 errors.
  Manifest JAR SHA-256 is
  `2b7f73c0a1a2f21279f6209b7e6727ffea55ebdbeb3f7e736f65d010130caac1` and
  runtime config is `USE_PADDLEX_OCR=true`/`OCR_PROVIDER_MODE=AUTO`.

## v4.16.146 run 50514_5209 — real rank badge still unresolved

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the run reached a fresh `CREATE_GAME` and a real board; the
  same-run screenshot showed the player's rank-10 badge. The dedicated rank
  OCR passes all returned empty, and the absent league-word probe emitted
  `OCR_PROVIDERS_FAILED ... desc=current-tier`, which also violates the
  zero-error gate even though it was only an exploratory probe.
- **Preserved evidence:**
  `outputs/Hearthstone Script/log/e2e-runs/50514_5209`, fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_04_20_56\\Power.log`,
  same-run console/ledger, and the real-board screenshot. Exact Java PID
  `38576` and Hearthstone PID `45016` were stopped after command-line
  validation; the runner was interrupted with Ctrl+C.
- **Fix:** treat empty `current-tier` as an expected probe miss and adjust
  the rank numeral crop/visual classifier to cover the observed orange rank
  numeral while retaining the dedicated badge visibility gate. Rebuild and
  run with a new id; do not reuse this evidence.

## v4.16.147 run 17688_6343 — synchronous rank OCR stranded result handling

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the run used the corrected PaddleX/AUTO configuration, fresh
  Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_04_41_04\\Power.log`,
  and reached `CREATE_GAME`, real mulligan/board states, and a visible rank-10
  badge. Power.log authoritatively recorded `laz#12793=LOST` and
  `MarceloCunha#1657=WON`. The rank-policy callback synchronously exhausted
  the slow OCR matrix while the client moved to the result page; stale phase
  callbacks then retried policy actions and delayed the result ledger marker.
  The screenshot was saved as `game-0001-draw-or-unknown-20260901-045210-852.png`.
  Because the process-local surrender flag raced the authoritative loss, the
  app emitted `E2E_GAME_RESULT_CONCEDED`; this is not accepted as a clean
  controlled result. The run was stopped after exact Java/Hearthstone and
  runner command-line validation, with all evidence preserved.
- **Fix:** v4.16.148 evaluates badge visibility before OCR and short-circuits
  after a clean rank-10 result. Game-over labeling now treats authoritative
  `PLAYSTATE LOST/WON` as stronger than an earlier surrender request.

## v4.16.148 — bounded rank OCR and authoritative terminal-result checkpoint

- **Status:** `deployed candidate`; fresh E2E pending.
- **Verification:** full reactor passed 126/126 with 0 failures and 0 errors.
  Manifest/actual JAR SHA-256 is
  `b3243a9698d09cab2ae6a673775cc5cc3f9feb47c13e5842abd7f34067d0a20c`.
  Runtime config is `USE_PADDLEX_OCR=true`/`OCR_PROVIDER_MODE=AUTO` with
  SHA-256 `8499e27eee1326d1fa499dde157c65ad88c1bb6b784e62ac9dc0931ed1f87af2`.

## v4.16.148 run 56559_9767 — authoritative loss mislabeled as unknown

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the run reached fresh `CREATE_GAME`, completed a real
  mulligan/board path, resolved the visible rank badge as `rank=10,tier=GOLD`,
  and emitted `E2E_GAME_RESULT_LOSS` with no native input error. The same
  run's authoritative Power.log recorded the current player as `LOST`, but
  the result screenshot was saved with `outcome=draw-or-unknown` because the
  screenshot branch only consulted the in-memory model fields. The strict
  evidence gate rejects that UNKNOWN label. Fresh Power.log:
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_05_03_36\\Power.log`;
  screenshot: `game-0001-draw-or-unknown-20260901-050650-803.png`.
- **Fix:** v4.16.149 maps `authoritativeOutcome=true/false` to explicit
  `win`/`loss` screenshot outcomes before model fallback. The run's ledger,
  console, screenshots, and exact validated process stops remain preserved.

## v4.16.149 — authoritative result screenshot-label checkpoint

- **Status:** `deployed candidate`; fresh E2E pending.
- **Verification pending:** rebuild/deploy the explicit loss-label fix, then
  run a new evidence lineage. Do not reuse `56559_9767`.

## v4.16.149 run 67762_3811 — empty model identity allowed a false E2E win

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the run reached fresh `E2E_READINESS_READY`/`CREATE_GAME` and
  completed a real controlled first game. PaddleX/AUTO rank detection resolved
  `10/GOLD`, Power.log recorded `laz#12793=LOST`, the application emitted
  `E2E_GAME_RESULT_LOSS`, and the same-run result screenshot was explicitly
  labeled `loss`. A later `CREATE_GAME` recorded
  `laz#12793=CONCEDED` followed by `LOST` in the fresh Power.log. During the
  delayed result callback, however, `war.me.gameId` was empty; the application
  could not associate the authoritative terminal state, treated the local
  surrender request as sufficient, and emitted `E2E_WIN_RESULT` with a `win`
  screenshot. The runner rejected that marker as
  `E2E_WIN_RESULT_UNPAIRED reason=no-new-authoritative-powerlog-win`, and the
  run was stopped after a third fresh `CREATE_GAME` without credit.
- **Preserved evidence:**
  `outputs/Hearthstone Script/log/e2e-runs/67762_3811`, same-run console and
  ledger, result screenshots, and fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_05_13_14\\Power.log`.
  Exact Java PID `52060`, runner PID `31732`, and Hearthstone PID `51964` were
  stopped only after command-line validation. The false win is retained as a
  regression artifact, not as game evidence.
- **Fix:** v4.16.150 passes an explicit `HS_E2E_PLAYER` fallback identity to
  the Power.log terminal-state parser when the in-memory player model is
  blank. The E2E result path also requires authoritative/model concession
  evidence; a local surrender request alone cannot convert an unresolved
  result into a controlled win. The fallback parser has a 3/3 regression test.

## v4.16.150 — fast-concede identity fallback checkpoint

- **Status:** `deployed candidate`; fresh E2E pending.
- **Verification:** full reactor passed 126/126 with 0 failures and 0 errors;
  the additional `E2ETraceTest` passed 3/3. Manifest/actual JAR SHA-256 is
  `662dfe8256ccb7ac926b289941c490a393fd97170ee91435285ac46dd8324b28`.
  Runtime config remains `USE_PADDLEX_OCR=true`/`OCR_PROVIDER_MODE=AUTO` with
  SHA-256 `8499e27eee1326d1fa499dde157c65ad88c1bb6b784e62ac9dc0931ed1f87af2`.
- **Next gate:** use a new run id on this exact manifest/config lineage and
  require two consecutive valid real games, at least one current-player
  authoritative `WON`, matching result screenshots, clean Power.log/ledger,
  and no errors, exceptions, OCR failures, timeouts, UNKNOWN states, or
  manual recovery.

## v4.16.150 run 18108_3029 — startup crossed the work-time boundary

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the exact v4.16.150 manifest/config lineage launched at
  05:28:59 while the effective work window ended at 05:29:42. Java startup and
  Hearthstone launch completed after the boundary; the normal schedule guard
  then closed Hearthstone at 05:30:21 before readiness or game creation. The
  fresh Power.log is
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_05_30_01\\Power.log` and
  contains no accepted game evidence. The run directory and console are
  preserved. Exact Java PID `40924` and wrapper PIDs `47096`/`33640` were
  stopped after command-line validation; Hearthstone PID `50836` was already
  closed by the schedule safety path.
- **Operational rule:** do not start a candidate close to a schedule end;
  select a complete upcoming effective window and leave enough startup margin
  for the Java/OCR/Hearthstone launch sequence. This run does not indicate a
  v4.16.150 product regression and must not be mixed with later evidence.

## v4.16.150 run 39298_8249 — persisted surrender streak rejected the first game

- **Status:** `invalid E2E evidence`; no run credit.
- **Observed:** the run used the outside-schedule startup override and reached
  fresh `E2E_READINESS_READY`/`CREATE_GAME`. The selected deck's persisted
  history had `consecutive-surrenders=7`; the production surrender guard
  therefore conceded during the initial mulligan. The app emitted
  `E2E_GAME_RESULT_REJECTED` because `mulligan=false`, `ourTurn=false`, and
  `outCard=false`. Fresh Power.log:
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_05_35_41\\Power.log`;
  run ledger, console, and result screenshots remain preserved and excluded.
- **Disposition:** the restarted Java child was revalidated and terminated.
  The next run enables the launcher's existing `HS_E2E_SKIP_SURRENDER=true`
  switch. This is an explicit E2E-only harness property already supported by
  the checked-in launcher; it avoids changing persisted game history while
  retaining real Hearthstone, real Power.log, real desktop input, rank/OCR,
  terminal-result, and screenshot verification.

## v4.16.151 — narrow persistent-streak test switch

- **Change:** the checked-in launcher accepts
  `HS_E2E_SKIP_PERSISTENT_STREAK=true` and injects an E2E-only JVM property
  that bypasses only persisted selected-deck consecutive-surrender protection.
  Production surrender policy, rank/hero/turn checks, fail-closed PaddleX/rank
  behavior, terminal priority, and authoritative Power.log pairing remain
  active. The full reactor passed 126/126 with 0 failures and 0 errors.
- **Deployment:** manifest and actual JAR SHA-256 are
  `c79c24c202628169787fa3cd2ec1b24303f660680791ac77bdb70350ba63bcc4` for
  `hs-script_v4.16.151-local-20260901-060004PDT.jar`; runtime config hash is
  `8499e27eee1326d1fa499dde157c65ad88c1bb6b784e62ac9dc0931ed1f87af2`.

## v4.16.150 run 77949_1049 — provisional two-win diagnostic

- **Status:** diagnostic success only; excluded from strict release credit.
- **Observed:** two real games on v4.16.150 reached accepted current-player
  authoritative `WON` states and same-run win screenshots. The run used the
  broader E2E-only `HS_E2E_SKIP_SURRENDER=true` bypass, so it does not prove
  the normal surrender path. Preserve its evidence at
  `outputs/Hearthstone Script/log/e2e-runs/77949_1049` and the corresponding
  `log/game-results/game-0001-win-20260901-054846-874.png` and
  `game-0002-win-20260901-055722-517.png` files.

## v4.16.151 run 80542_8073 — strict lineage rejected after two losses

- **Status:** invalid E2E evidence; no run credit.
- **Observed:** the production-policy run completed two real games, both
  current-player `LOST`, with no native-input/provider-failure marker. A third
  attempt then emitted `E2E_GAME_RESULT_REJECTED` at 06:23:07 because the
  required `mulligan`, `ourTurn`, and `outCard` milestones were absent. This
  rejection makes the run ineligible for the zero-rejection gate.
- **Evidence/disposition:** fresh Power.log is
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_06_04_53\\Power.log`;
  preserve the run directory, console, ledger, and screenshots. Java 44832,
  Hearthstone 41560, and wrappers 16468/40224 were stopped only after exact
  command-line validation.

## v4.16.151 run 79733_5887 — client reconnect environment block

- **Status:** environment-blocked; no E2E credit.
- **Observed:** after a fresh rotation to
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_06_25_55\\Power.log`,
  Hearthstone displayed “unable to reconnect to the game”. The runner stayed
  `pause=true` from 06:28:26 through 06:30 and produced no fresh readiness or
  `CREATE_GAME`. A bounded Battle.net restart was attempted once; it did not
  recover the client. Do not interpret this as a product-result regression.
- **Evidence/disposition:** run directory
  `outputs/Hearthstone Script/log/e2e-runs/79733_5887`, console, ledger,
  recovery screenshots, and process evidence are retained. The exact Java,
  Hearthstone, wrapper, and Battle.net process targets were validated and
  stopped; no validated target remains. Strict release gate is still unmet.

## v4.16.152 — stale reconnect lineage fix checkpoint

- **Diagnosis:** `79733_5887` primarily failed because Hearthstone restored a
  stale last-game/reconnect lineage (`GR_UNKNOWN -> GameCanceled -> INVALID`),
  not because of the E2E readiness sentinel. PaddleX contract rejects and
  Legacy garbage also failed to positively identify the reconnect dialog, but
  the fail-closed pause was correct.
- **Fix/deployment:** Launch/OCR owner commit `f6553b87` keeps Battle.net alive
  until stable non-STARTUP/LOGIN/FATAL_ERROR observations, rejects pre-existing
  E2E games, resets stale lineage, preserves the fresh Power.log/CREATE_GAME
  gate, and records reconnect errors with low-confidence pause behavior. The
  owner reported 95 targeted tests, 0 failures, 0 errors, clean worktree, and
  v4.16.152 JAR SHA-256
  `62d847194fc54b331294714486839e411a307c6a39bfc3fb9140d1f299d66ecb`.

## v4.16.152 run 22822_8809 — reconnect recovery still paused before CREATE_GAME

- **Status:** invalid E2E evidence; routed back to Launch/OCR diagnosis; no
  run credit.
- **Observed:** the new build reached a normal Hearthstone HUB and rotated
  fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_06_54_10\\Power.log`.
  During automated reconnect recovery it entered `pause=true` at 06:55:05
  and never emitted fresh `E2E_READINESS_READY` or `CREATE_GAME`. No actual
  game was executed, so no result can be credited.
- **Evidence/disposition:** preserve
  `outputs/Hearthstone Script/log/e2e-runs/22822_8809`, its console, ledger,
  and controlled HUB screenshot. Java 40640, Hearthstone 24332, and the
  Battle.net tree were validated by exact command line and stopped. Do not
  blindly restart until the owner provides the next clean checkpoint.

## v4.16.153 — readiness self-lock narrowing checkpoint

- **Change:** the readiness gate now allows HUB/start-matching pre-game
  context to produce the first current-run `CREATE_GAME`, while gameplay,
  retry, surrender, replan, and unknown states remain hard-blocked. A timeout
  no longer permanently contaminates `WAITING`; a later current run/PID event
  past baseline can transition to READY.
- **Deployment:** owner commit `e90e1e78`; 97 targeted tests passed with 0
  failures and 0 errors. Deployed JAR
  `hs-script_v4.16.153-local-20260901-070926PDT.jar` SHA-256:
  `4fd13ca6ef62d48e9e5bca717fe0d7b28dfef39fc5cfc7b7ce4b2348845829d4`.

## v4.16.153 run 24239_3280 — rank OCR provider failure

- **Status:** invalid E2E evidence; no run credit.
- **Observed:** the run passed the reconnect/readiness gate, reached a real
  mulligan screen, and emitted `E2E_GAME_READY` against fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_07_15_51\\Power.log`.
  At 07:17:30 rank detection emitted
  `OCR_PROVIDERS_FAILED paddlex=empty-result legacy=contract-rejected`.
  This violates the zero-OCR-failure requirement, even though no native-input
  failure or terminal result followed.
- **Evidence/disposition:** preserve
  `outputs/Hearthstone Script/log/e2e-runs/24239_3280`, its console, ledger,
  fresh Power.log, and controlled mulligan screenshot. The watchdog restarted
  Java after the first stop; the restarted Java and wrapper were then
  revalidated and stopped. Route the failure to PaddleX/rank OCR diagnosis;
  do not count any later result from this run.

## v4.16.154 — explicit expected-empty OCR probe semantics

- **Change:** rank and tier probes now pass an explicit
  `allowEmptyProbeResult=true` flag through `TesseractEx` into `OcrRuntime`.
  Expected empty PaddleX/Legacy probe results are quiet and do not trigger a
  fallback failure marker; real provider exceptions, contract rejection on a
  non-probe, and unexpected empty recognition remain fail-closed. The
  previous description-based implicit probe classification was removed so
  only the detector's explicit intent can suppress the failure marker.
- **Verification/deployment:** `OcrRuntimeTest` passed 13/13, and the full
  first release build phase passed 128 tests with 0 failures and 0 errors.
  Deployed v4.16.154 JAR SHA-256 is
  `5b82bade4664ccf8c3e9ff75eba6ff86a6e5d616168921e44508d4502acad06a`.
  The manifest, desktop shortcut, Start Menu shortcut, and pinned Taskbar
  shortcut all point to the refreshed launcher/runtime. This checkpoint is
  not E2E release evidence; a fresh strict run is still required.

## v4.16.154 run 46161_9056 — stale script milestones rejected second game

- **Status:** invalid E2E evidence; no run credit.
- **Observed:** the run rotated fresh Power.log
  `D:\Hearthstone\Logs\Hearthstone_2026_09_01_07_45_08\Power.log`, reached a
  real first game, and recorded a clean current-player loss at 07:51:43.
  During recovery, the second-game path emitted
  `E2E_GAME_RESULT_REJECTED` at 07:56:46 because the script milestone flags
  were still `mulligan=false`, `ourTurn=false`, and `outCard=false`; the
  associated screenshot was `game-0002-draw-or-unknown-20260901-075650-959.png`.
  The fresh Power.log later shows a second `CREATE_GAME` at 07:57:25, but it
  is contaminated by the prior rejection and cannot be credited.
- **Evidence/disposition:** preserve
  `outputs/Hearthstone Script/log/e2e-runs/46161_9056`, its console and
  ledger, `D:\Hearthstone\Logs\Hearthstone_2026_09_01_07_45_08\Power.log`,
  `game-0001-loss-20260901-075147-888.png`, and
  `game-0002-draw-or-unknown-20260901-075650-959.png`. The exact Java PID
  `42192`, runner PID `38096`, and Hearthstone PID `40408` were validated and
  stopped after the rejection. Do not reuse this evidence lineage.

## v4.16.155 — authoritative terminal wait when gameId is empty

- **Change:** terminal-result handling waits for authoritative Power.log
  `PLAYSTATE` even if `war.me.gameId` is blank, with structured diagnostics
  and a bounded 15-second fail-closed timeout. Owner commit `7759153b`
  reported 97/97 checked-in tests passing and deployed
  `hs-script_v4.16.155-local-20260901-080823PDT.jar` with SHA-256
  `b72f5ab1e867bc870b7a92cb9bb2610ecce22bf5916eca9e1b9b2f9523a688ca`.

## v4.16.155 run 32992_4487 — input interruption and OCR failure

- **Status:** invalid E2E evidence; no run credit.
- **Observed:** the run used `HS_E2E_SKIP_SURRENDER=false` and only the
  persistent-streak test isolation, rotated fresh Power.log
  `D:\Hearthstone\Logs\Hearthstone_2026_09_01_08_15_05\Power.log`, and
  reached `E2E_GAME_READY`. At 08:16:03 it emitted
  `E2E_INPUT_NATIVE_FAILED` with `InterruptedException: sleep interrupted`.
  At 08:16:55 it emitted
  `OCR_PROVIDERS_FAILED paddlex=empty-result legacy=contract-rejected`.
  The watchdog restarted Java and then entered its safe Hearthstone
  termination path; no authoritative win or result was credited.
- **Evidence/disposition:** preserve
  `outputs/Hearthstone Script/log/e2e-runs/32992_4487`, its console and
  ledger, fresh Power.log, and restart/termination lines. The failure must be
  diagnosed before another strict run; do not reuse this evidence lineage or
  interpret the owner terminal-wait fix as E2E-validated.

## v4.16.156 — phase-change input cancellation classification

- **Change:** an `InterruptedException` during a phase-change wait is now
  emitted as `E2E_INPUT_CANCELLED_PHASE_CHANGE` with
  `accepted=false/input=not-sent`, rather than being mislabeled as a native
  input failure. Other native input failures remain fail-closed.
- **Verification/deployment:** owner checkpoint reported 97/97 release tests
  plus 6/6 targeted tests. Deployed JAR SHA-256:
  `46f735c600042f3ddda3198f871102e2dc0abbcd59c34d15f02baa50f251691c`.
  This was a staging checkpoint only; no E2E credit was assigned.

## v4.16.157 — integrated input and expected-empty PaddleX semantics

- **Change:** the clean integration build preserves the v4.16.156 input
  cancellation mapping and incorporates the PaddleX checkpoint `d127a774`.
  Rank/tier probes explicitly allow an expected empty OCR result, while
  provider exceptions and unexpected empty/non-probe results still fail
  closed. Badge/crop/readiness/streak and authoritative terminal-result
  handling remain in the integrated build.
- **Verification/deployment:** the checked-in build-and-deploy script passed
  the first release phase with 128 tests, 0 failures, and 0 errors. Deployed
  JAR: `hs-script_v4.16.157-local-20260901-084015PDT.jar`; SHA-256:
  `681b9ae6ea042f4ee53f0ba2ae2a698b95b248ba7baf35294a11426a875bc004`.
  Independent hash/manifest verification passed, all three managed shortcuts
  point to `launch-as-admin.vbs`, and no Java/Hearthstone E2E process remains
  running. A fresh strict run is required; do not reuse prior invalid runs.

## v4.16.157 run 14329_8033 — startup/readiness blocked in HUB

- **Status:** invalid E2E evidence; no run credit.
- **Observed:** the run loaded the manifest-selected v4.16.157 JAR and
  rotated fresh Power.log
  `D:\Hearthstone\Logs\Hearthstone_2026_09_01_08_47_34\Power.log`. The
  Hearthstone client reached HUB, but readiness remained blocked. At 08:50:10
  the startup probe ended with `attempts=1 working=false paused=true`, and the
  process remained paused without a `CREATE_GAME`, result, OCR failure, or
  native-input failure.
- **Evidence/disposition:** preserve
  `outputs/Hearthstone Script/log/e2e-runs/14329_8033`, its console and
  ledger, controlled Hearthstone screenshots, and the fresh Power.log. Exact
  Java PID `36360`, runner PID `36524`, and Hearthstone PID `50632` were
  command-line validated and stopped. Route the startup/readiness block to
  Launch owner diagnosis; do not start another strict run until a fix is
  integrated and deployed.

## v4.16.158 — foreground target confirmation

- **Change:** native/Robot input now requires a confirmed Hearthstone HWND,
  foreground/focused/visible state, and matching process identity. If any
  check is uncertain, input is blocked and recorded as
  `E2E_INPUT_SENDINPUT_SKIPPED accepted=false input=not-sent
  reason=foreground-unconfirmed`.
- **Verification/deployment:** owner checkpoint `4217e377` reported 7/7
  targeted tests and 97/97 release tests. Deployed JAR SHA-256:
  `5b72ffc6ac8b4bdff1567b6e35b9f7774ad75a8773c99eddddba035a0d004b51`.

## v4.16.158 run 30210_2370 — rank OCR empty-result regression

- **Status:** invalid E2E evidence; no run credit.
- **Observed:** the fresh run loaded v4.16.158, rotated
  `D:\Hearthstone\Logs\Hearthstone_2026_09_01_09_03_19\Power.log`,
  confirmed the Hearthstone window target and reached `E2E_GAME_READY`.
  Foreground input was confirmed (`confirmed=true`). Rank detection then
  emitted `OCR_PROVIDERS_FAILED` for `current-rank-psm6-mask`, followed by
  `current-rank-psm6-raw` and `current-rank-psm7-mask`, each with PaddleX
  empty-result and Legacy contract-rejected.
- **Evidence/disposition:** preserve
  `outputs/Hearthstone Script/log/e2e-runs/30210_2370`, its console and
  ledger, observed mulligan screenshot, and fresh Power.log. The exact Java,
  runner, Hearthstone, and run-launched Battle.net processes were validated
  and stopped. Route this to PaddleX/rank owner diagnosis; do not reuse this
  evidence lineage or start a new strict run until the fix is integrated and
  deployed.
