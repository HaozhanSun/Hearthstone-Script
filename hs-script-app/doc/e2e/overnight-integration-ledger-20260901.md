# Overnight integration ledger — 2026-09-01

Coordinator worktree: `C:/Users/yzjsh/.codex/worktrees/ab8c/Hearthstone Copilot`

Coordinator branch: `codex/overnight-release-integration`

Current checkpoint: the rank-badge visibility fix is built and deployed as
v4.16.140 on top of the single-tree release lineage. The latest canonical
deployment is v4.16.140;
the worktree is intended to remain clean between evidence checkpoints. No E2E
credit is assigned until real-game evidence passes the completion gate.

## Ordered queue

1. **Offline integration gate — passed**
   - Verify OCR provider regression tests together with readiness, DebugRun
     lease, runner contract, Power.log, and action guards.
   - The combined test selection passed 85/85 with 0 failures and 0 errors.
     Reports are under `hs-script-app/target/surefire-reports`.
   - The checked-in release script uses only tests present in this complete
     tree and includes the OCR/runner/screen-recovery guard tests.
   - v4.16.138 checked-in build tests passed 119/119 with 0 failures and 0
     errors; the full 8-module reactor passed BUILD SUCCESS.
   - v4.16.139 checked-in build tests passed 120/120 with 0 failures and 0
     errors; the full 8-module reactor passed BUILD SUCCESS. The added test
     covers the bounded pre-game context allowlist.
   - v4.16.140 checked-in build tests passed 121/121 with 0 failures and 0
     errors; the full 8-module reactor passed BUILD SUCCESS. The added test
     covers the transition-frame rank-badge visibility signal.

2. **Release/CU mutex — v4.16.138 deployed and provenance verified**
   - Canonical manifest currently points to v4.16.135-local-20260901-011404PDT,
     SHA-256 `6fccd713db787691bb497659283cd3dbd80e00d6049ab12f603d1352fc795545`.
   - That artifact matches readiness owner target output and does not contain
     the new OCR config-reconciliation marker. It is excluded from freeze.
   - Before deployment, safely close stale Hearthstone PID `12340`, verify no
     Java/runner remains, then use one release slot to deploy a strictly higher
     version from this tree.

   - v4.16.137 release slot completed successfully after the v4.16.136 runner
     packaging miss was fixed. Checked-in release tests: 118/118, 0 failures,
     0 errors. Target and canonical JAR SHA-256:
     `fb1b2fec401b2331aaa4ebc61230e0ab882c7ddac0a5a0dfa750711cf4e66bf6`.
     Target and canonical ZIP SHA-256:
     `4d119e7cc9b83e74ba2344708b1bfce5b59a37681a7b5a03c6fbf32894a03e40`.
   - Source/deployed `run-debug.ps1` SHA-256:
     `f5cb33ff2c81c85e02dfeba62a5e304a082b56e902346cf30eaa64d7c0cb4743`;
     ZIP contains `run-debug.ps1` and the selected JAR. JAR marker inspection
     confirms OCR config reconciliation, fresh `Power.log`/`CREATE_GAME`
     readiness, and sentinel HWND handling.
   - Desktop, Start Menu, and Taskbar shortcuts all target
     `C:/Windows/System32/wscript.exe` with canonical
     `launch-as-admin.vbs`. Process gate was clear after deployment.
   - v4.16.138 manifest selected
     `hs-script_v4.16.138-local-20260901-015730PDT.jar` with JAR SHA-256
     `facb17909e2656ff750ce49a0bf25f1e5d988dc7dff0f4ef85f68f9c9e1b99b5`.
     Target and canonical JAR hashes match. ZIP SHA-256 is
     `ea1992d6d5ab1dbea821a15b20fa71878a5bfec069ff78c91e3664ff60c9ab26`.
     The ZIP contains both the selected JAR and `run-debug.ps1`; the runner
     SHA-256 remains
     `f5cb33ff2c81c85e02dfeba62a5e304a082b56e902346cf30eaa64d7c0cb4743`.
     Class markers confirm OCR reconciliation, hub visual recovery, fresh
     Power.log/CREATE_GAME readiness, and sentinel HWND handling.
   - v4.16.139 manifest selected
     `hs-script_v4.16.139-local-20260901-021338PDT.jar` with JAR SHA-256
     `399cff9aef22a3a595d02b19be03e39e83fcc98bdad523a672b88e33b1ba094c`.
     Target and canonical JAR hashes match. ZIP SHA-256 is
     `a37b9d84c11c58fdfafc28a014ccf9683da566cab22ae9394d3f60aef25b3b76`.
     The runner SHA-256 remains
     `f5cb33ff2c81c85e02dfeba62a5e304a082b56e902346cf30eaa64d7c0cb4743`.
     The compiled PowerLogListener contains the pre-game context allowlist
     and retains the `E2E_READINESS_FAIL_CLOSED` marker.
   - v4.16.140 manifest selected
     `hs-script_v4.16.140-local-20260901-023200PDT.jar` with JAR SHA-256
     `19efe1efeb4bc9bf793ebe99e35214a3f5315ca232ce3b034115769e4cc05135`.
     Target and canonical JAR hashes match. ZIP SHA-256 is
     `1d4fdb5d26f304e11e2fddcd0481bbe1ae69200a4b83922f8f37cbf9d6b2df42`.
     The runner SHA-256 remains
     `f5cb33ff2c81c85e02dfeba62a5e304a082b56e902346cf30eaa64d7c0cb4743`.
     The manifest was generated at `2026-09-01T09:36:51.5180305Z`.
   - v4.16.142 release checkpoint: the dedicated rank numeral crop was widened
     for the live rank-10 shield after the v4.16.141 E2E screenshot showed the
     badge visibly present but the numeral crop empty. The full 8-module
     reactor completed BUILD SUCCESS with 122 tests, 0 failures, and 0 errors.
     Canonical manifest selected
     `hs-script_v4.16.142-local-20260901-025939PDT.jar` with JAR SHA-256
     `4b8a51a0345ef91abd869f31f178fa988e43747cb5e88369bde0f263c6cc7b1e`.
     The ZIP and all three shortcuts were synchronized by the deployment
     script. Fresh E2E remains pending and must use a new run id.
   - v4.16.142 provenance was independently checked: the integration source
     matches provider owner commit `1d64115adb9bf73b3af07ba1c8dfe307eb20336c`
     for the OCR runtime files, and the deployed JAR SHA-256 is
     `4b8a51a0345ef91abd869f31f178fa988e43747cb5e88369bde0f263c6cc7b1e`.
     The canonical runtime config was corrected from
     `USE_PADDLEX_OCR=false`/`OCR_PROVIDER_MODE=LEGACY_ONLY` to
     `USE_PADDLEX_OCR=true`/`OCR_PROVIDER_MODE=AUTO`; its config SHA-256 changed
     from `8cc4c9dfd95207f8f4f254f1e4db3fc1e498e8cea43e0c27331130cf178610b4`
     to `739cbf9146d5eadff4ab8d85565d2adb9bcfdccd766110248874a1a88f382130`.
   - v4.16.143 release checkpoint: rank-crop probe misses now emit the
     non-failure `OCR_PROVIDER_PROBE_EMPTY` marker while preserving fail-closed
     behavior when all candidates are empty. Focused tests passed 47/47 and the
     full 8-module reactor completed BUILD SUCCESS with 123 tests, 0 failures,
     and 0 errors. Canonical manifest selected
     `hs-script_v4.16.143-local-20260901-031539PDT.jar` with JAR SHA-256
     `34ad28dd06ee12de3f11c87ad3e0076d128ba0f52700a39b43fbec4a1a8c0cf4`.
     ZIP SHA-256 is
     `b944a6ed9b32559da1f239e40b4999814c3b91e7f6444caee37d4d1644808fef`;
     the runner SHA-256 remains
     `f5cb33ff2c81c85e02dfeba62a5e304a082b56e902346cf30eaa64d7c0cb4743`.
     All three shortcuts were synchronized by deployment. Runtime config is
     `USE_PADDLEX_OCR=true`/`OCR_PROVIDER_MODE=AUTO` with SHA-256
     `739cbf9146d5eadff4ab8d85565d2adb9bcfdccd766110248874a1a88f382130`.

3. **Fresh DebugRun E2E — pending completion on v4.16.143**
   - Start only after manifest/JAR/ZIP/runner/shortcut provenance is verified.
   - Every attempt gets a new run id and per-run ledger. Preserve all failed
     artifacts and append a gotcha on invalidation.
   - Completion requires two consecutive valid real games, at least one
     current-player WON in the same build/evidence lineage, and zero errors,
     exceptions, timeouts, OCR failures, UNKNOWN/manual recovery, or missing
     lifecycle/exit evidence.
   - Attempt `17598_2257` was invalidated before game start: v4.16.138
     correctly recognized the real hub (`hubNavigationBright=0.182`) and
     Legacy OCR accepted 72–74 characters, but the new readiness guard also
     blocked the pre-game Hub-to-match dispatch. Fresh Power.log remained
     empty; the exact Java/wrapper/Hearthstone processes were stopped after
     command-line validation. Preserve this run and exclude it from credit.
   - Attempt `36241_8735` was invalidated after v4.16.139 reached two real
     game starts: the first ended as `draw-or-unknown` without script
     milestones; the second was still on the matchmaking overlay when the
     model entered `DRAWN_INIT_CARD`, so rank OCR correctly failed closed after
     emitting `rank=UNKNOWN`. The exact processes were stopped after
     command-line validation. Preserve this run and exclude it from credit.
   - Attempt `77111_1981` was invalidated after v4.16.140 reached a real board:
     the player's rank-10 shield was visible, but the dedicated rank numeral
     crop remained unresolved and policy paused. The exact processes were
     stopped after command-line validation. Preserve this run and exclude it
     from credit.
   - Attempt `78321_8560` was invalidated after v4.16.141 reached a real
     mulligan/VS screen. Same-run evidence shows the visible rank-10 shield
     inside the unresolved region while OCR returned no usable numeral. The
     exact processes were stopped after command-line validation. Preserve this
     run and exclude it from credit.
   - Attempt `97851_3200` was invalidated on v4.16.142: provenance was valid,
     but the old persisted Legacy-only runtime config yielded an empty rank
     crop and a non-authoritative unpaired script win. The exact Java/runner/
     Hearthstone processes were stopped after command-line validation. Preserve
     this run and exclude it from credit.

4. **MCTS/Pirate workstreams — locked**
   - Do not start or merge while the release/CU mutex and E2E gate are open.

## Source evidence

- OCR owner: `1d64115adb9bf73b3af07ba1c8dfe307eb20336c`, clean independent
  worktree; focused OCR contract tests reported 15/15.
- Readiness/sentinel: `e53685c4` with sentinel guard `098a2d77`.
- Pre-game readiness compatibility guard: `4edae83d3983a2d955d10dad86c3dabf2c1bd8d6`.
- DebugRun runner: source commit `456ba0df27e69b833916b268fed12d6de18bdbdc`,
  integrated here as `44921340`.
- Prior invalid run: `47710_5759`; it remains excluded because Legacy OCR was
  contract-rejected, fresh Power.log readiness never reached CREATE_GAME, and
  no valid game evidence exists.
- Prior invalid run: `17306_2083`; it remains excluded because the hub was
  visually misclassified as loading and no game was created.

- Current invalid run: `36241_8735`; v4.16.139 created a fresh `Power.log` and
  two real `CREATE_GAME`-paired lifecycle entries. The first game ended as
  `draw-or-unknown` and was rejected because the script milestones were absent.
  The second game reached the real player's opening state but failed closed on
  `RANK_OCR_EVIDENCE rank=UNKNOWN`, followed by
  `RANK_POLICY_BLOCKED ... action=PAUSE`. Exact Java PID `2536`, runner wrapper
  PID `44592`, and Hearthstone PID `36012` were stopped only after
  command-line validation. The run is preserved and excluded from credit.

- Current invalid run: `77111_1981`; v4.16.140 reached a real Hearthstone board
  with fresh Power.log `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_02_38_31\\Power.log`,
  but the live rank-10 shield's numeral crop did not yield a complete usable
  value. It emitted `rank-ocr-unresolved` and paused before any valid game
  result. The run directory, console, ledger, screenshots, and Power.log are
  preserved at `outputs/Hearthstone Script/log/e2e-runs/77111_1981`; exact Java
  PID `45176`, runner PID `45880`, and Hearthstone PID `40796` were stopped only
  after command-line validation. This run is excluded from credit.

- v4.16.141 release checkpoint: the visibility gate now accepts only badge-metal
  or dedicated two-digit visual evidence, never an isolated OCR digit. Targeted
  tests passed 121/121 with 0 failures and 0 errors, the full reactor package
  completed successfully, and the canonical deployment manifest binds
  `hs-script_v4.16.141-local-20260901-024507PDT.jar` to SHA-256
  `f6633aba93908e740749ecb5c6bff27b0d2feb7145cd58273df442d7d075caab`.
  The ZIP and all three shortcuts were synchronized by the deployment script.
  Fresh E2E completion remains pending and must use a new run id.

- Current invalid run: `78321_8560`; v4.16.141 reached a real mulligan/VS
  screen with fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_02_55_10\\Power.log`.
  The same-run screenshot proves the rank-10 shield was visible inside the
  unresolved region, but the dedicated numeral crop returned no usable text;
  policy paused before an authoritative result. Exact Java PID `37476`, runner
  wrapper PID `46312`, outer wrapper PID `45352`, and Hearthstone PID `9252`
  were stopped only after command-line validation. The run and screenshot are
  preserved and excluded from credit. v4.16.142 widens the numeral crop and is
  the current E2E candidate.

- Current invalid run: `97851_3200`; v4.16.142 provenance was valid, but the
  run started with stale canonical config `USE_PADDLEX_OCR=false` and
  `OCR_PROVIDER_MODE=LEGACY_ONLY`. Legacy rank probes returned empty and
  emitted the old contract-rejected failure marker; the script-side win was
  unpaired because no new authoritative Power.log win existed. This run was
  stopped and is excluded from credit. The config is now AUTO/PaddleX with the
  recorded before/after hashes, and the source adds explicit empty rank-probe
  semantics that remain fail-closed without misclassifying a probe miss as a
  provider contract failure. v4.16.143 is the current candidate with this
  semantic fix deployed.

- Current invalid run: `79485_5719`; v4.16.143 used the corrected
  PaddleX/AUTO configuration and reached the visible Hearthstone hub, but
  PaddleX screen recovery completed after the 45-second fresh-log readiness
  deadline. The run failed closed before a new CREATE_GAME, produced no game
  result, and was stopped after exact PID/cmdline validation. Its logs,
  screenshots, ledger, and fresh Power.log are preserved and excluded from
  credit. The bounded readiness timeout is now 180 seconds; the next run must
  use a new run id and the same v4.16.143 manifest/config lineage.

- Current invalid run: `37036_4091`; v4.16.144 completed the slow Battle.net
  startup path and observed fresh `CREATE_GAME` in attempt 3, reaching a real
  mulligan/board. A screen-recovery OCR inspection started before the war and
  finished after `inWar=true`; the stale inspection emitted
  `SCREEN_RECOVERY_PAUSE_ACTIVE reason=unresolved-ocr-or-visual-state` and
  paused the live game before any authoritative result. Attempts 1–3, fresh
  Power.logs, screenshots, and exact validated process stops are preserved;
  the run is excluded from credit. The next candidate adds the
  war-started-during-inspection discard guard and its focused regression test.

- Current invalid run: `49855_7462`; v4.16.145 used the corrected
  PaddleX/AUTO configuration, attached fresh `Power.log`
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_04_10_15\\Power.log`, and
  reached `E2E_READINESS_READY`/`CREATE_GAME` plus a real mulligan screen.
  The same run logged `E2E_INPUT_NATIVE_FAILED` at 04:11:07, with a stack
  trace showing `InterruptedException: sleep interrupted` in the mouse path
  during a mode transition. The zero-error gate rejects this run; it produced
  no accepted result and was stopped before any credit. The run directory,
  console, ledger, screenshots, and Power.log are preserved. Exact Java PID
  `50620` and Hearthstone PID `35804` were stopped only after command-line
  validation; the runner was interrupted with Ctrl+C.

- v4.16.146 release checkpoint: the E2E input handler now classifies an
  interrupted mouse path during a mode transition as
  `E2E_INPUT_CANCELLED_PHASE_CHANGE` at INFO level; unexpected exceptions still
  emit `E2E_INPUT_NATIVE_FAILED`. A focused regression test covers the
  classification. The full reactor completed BUILD SUCCESS with 125 tests,
  0 failures, and 0 errors. The canonical manifest binds
  `hs-script_v4.16.146-local-20260901-041438PDT.jar` to SHA-256
  `2b7f73c0a1a2f21279f6209b7e6727ffea55ebdbeb3f7e736f65d010130caac1`.
  Runtime config remains `USE_PADDLEX_OCR=true`/`OCR_PROVIDER_MODE=AUTO` with
  SHA-256 `8499e27eee1326d1fa499dde157c65ad88c1bb6b784e62ac9dc0931ed1f87af2`.

- Current invalid run: `56559_9767`; v4.16.148 reached fresh
  `E2E_READINESS_READY`/`CREATE_GAME` and completed a real first game without
  native input errors. The rank pipeline resolved the visible badge as
  `rank=10,tier=GOLD`, and the controlled policy path emitted
  `E2E_GAME_RESULT_LOSS`. However, the same-run result screenshot was labeled
  `draw-or-unknown` even though the authoritative result was a current-player
  `LOST`, so the strict no-UNKNOWN gate rejects this run. Its run ledger,
  console, fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_05_03_36\\Power.log`,
  mulligan/result screenshots, and exact validated process-stop evidence are
  preserved and excluded from credit. The next candidate maps authoritative
  false outcomes to an explicit `loss` screenshot outcome.

- v4.16.149 release checkpoint: result screenshot classification now uses the
  authoritative terminal outcome before model-side fallback, so a controlled
  current-player loss cannot be recorded as `draw-or-unknown`. The next fresh
  run must still provide two consecutive valid real games and at least one
  current-player WON in the same manifest/config lineage.

- Current invalid run: `67762_3811`; v4.16.149 reached fresh
  `E2E_READINESS_READY`/`CREATE_GAME` and completed one real controlled game
  with rank `10/GOLD`, authoritative current-player `LOST`, explicit `loss`
  result screenshot, and no native-input error. The next `CREATE_GAME` was
  also authoritative current-player `CONCEDED/LOST`, but the listener had an
  empty `war.me.gameId` during delayed result handling and incorrectly emitted
  `E2E_WIN_RESULT` plus a `win` screenshot from stale model state. The runner
  correctly rejected it as `E2E_WIN_RESULT_UNPAIRED reason=no-new-authoritative-
  powerlog-win`; a third fresh `CREATE_GAME` was then observed before the run
  was stopped. The complete run ledger, console, same-run screenshots, fresh
  Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_05_13_14\\Power.log`, and
  exact validated stops for Java PID `52060`, runner PID `31732`, and
  Hearthstone PID `51964` are preserved and excluded from credit. v4.16.150
  adds an explicit `HS_E2E_PLAYER` identity fallback for authoritative
  Power.log result lookup and makes an unproven fast-concede result fail
  closed instead of becoming a false win.

- v4.16.150 release checkpoint: fast-concede result handling now falls back to
  the explicitly configured E2E player identity when `war.me` is not yet
  populated, and the E2E path no longer treats only a local surrender request
  as sufficient proof of a current-player concession. The full reactor passed
  126/126 with 0 failures and 0 errors; the additional `E2ETraceTest` passed
  3/3. The canonical manifest binds
  `hs-script_v4.16.150-local-20260901-052142PDT.jar` to SHA-256
  `662dfe8256ccb7ac926b289941c490a393fd97170ee91435285ac46dd8324b28`.
  Runtime config remains `USE_PADDLEX_OCR=true`/`OCR_PROVIDER_MODE=AUTO` with
  SHA-256 `8499e27eee1326d1fa499dde157c65ad88c1bb6b784e62ac9dc0931ed1f87af2`.
  A fresh run on this exact manifest/config lineage is required.

- Current invalid run: `50514_5209`; v4.16.146 reached fresh
  `E2E_READINESS_READY`/`CREATE_GAME` and a same-run screenshot showed a real
  board with the player's rank-10 badge. All dedicated rank OCR probes still
  returned empty, and the exploratory `current-tier` probe emitted the generic
  provider-failure marker. No authoritative result was produced, so the run
  is excluded from credit. The run directory, same-run console/ledger, fresh
  Power.log `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_04_20_56\\Power.log`,
  and screenshots are preserved. Exact Java PID `38576` and Hearthstone PID
  `45016` were stopped only after command-line validation; the runner was
  interrupted with Ctrl+C. The next candidate classifies `current-tier` as an
  expected empty probe and widens/relocates the numeral recognition window.

- Current invalid run: `80407_5446`; v4.16.145 loaded with corrected
  PaddleX/AUTO configuration, attached fresh `Power.log`
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_04_04_36\\Power.log`, and
  reached `E2E_READINESS_READY`/`CREATE_GAME` plus a real mulligan screen.
  The same-run log contains `E2E_INPUT_NATIVE_FAILED` at 04:05:28 before a
  later retry succeeded. The zero-error completion gate therefore rejects the
  run; it produced no authoritative accepted result and was stopped before
  any credit. The run directory, console, ledger, screenshots, and Power.log
  are preserved. Exact Java PID `36008` and Hearthstone PID `38288` were
  stopped only after command-line validation; the runner was interrupted with
  Ctrl+C. The next candidate must use a new run id and preserve the v4.16.145
  race-fix/config lineage.

- Current invalid run: `17688_6343`; v4.16.147 reached fresh
  `E2E_READINESS_READY`/`CREATE_GAME`, real mulligan/board states, and a
  same-run result screenshot. The authoritative Power.log recorded
  `MarceloCunha#1657=WON` and `laz#12793=LOST`. The first rank inspection ran
  synchronously through every slow OCR probe while the client transitioned to
  the result page; the listener then replayed stale turn callbacks and did not
  write a timely accepted `game-result` ledger event. The result was captured
  as `draw-or-unknown`/`CONCEDED` because the process-local surrender request
  raced the authoritative loss, so this run is excluded from credit. Its run
  ledger, console, fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_04_41_04\\Power.log`,
  screenshots, and exact validated process-stop evidence are preserved. The
  next candidate short-circuits invisible rank crops, stops OCR after a clean
  rank-10 read, and lets authoritative PLAYSTATE take precedence over an early
  surrender request.

- v4.16.148 release checkpoint: the rank detector now returns immediately when
  the dedicated badge-visibility gate is false and stops its slow OCR sequence
  once a clean rank 10 is accepted. Game-over E2E labeling now keeps a natural
  authoritative LOST/WON result ahead of a stale process-local surrender flag.
  The full reactor completed BUILD SUCCESS with 126 tests, 0 failures, and 0
  errors. The canonical manifest binds
  `hs-script_v4.16.148-local-20260901-045651PDT.jar` to SHA-256
  `b3243a9698d09cab2ae6a673775cc5cc3f9feb47c13e5842abd7f34067d0a20c`.
  Runtime config remains `USE_PADDLEX_OCR=true`/`OCR_PROVIDER_MODE=AUTO` with
  SHA-256 `8499e27eee1326d1fa499dde157c65ad88c1bb6b784e62ac9dc0931ed1f87af2`.

- Current invalid run: `18108_3029`; this run used the deployed v4.16.150
  manifest/config lineage but was started at 05:28:59, only 43 seconds before
  the effective work window ended at 05:29:42. Startup crossed the schedule
  boundary; the app opened Hearthstone only after the boundary and the normal
  `WorkTimeListener` safety path closed it at 05:30:21. No fresh
  `E2E_READINESS_READY`, `CREATE_GAME`, real game, or authoritative result was
  produced. The fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_05_30_01\\Power.log`, run
  directory, console, and schedule evidence are preserved and excluded from
  credit. Exact Java PID `40924` and the run-debug wrapper PIDs `47096` and
  `33640` were stopped only after command-line validation; Hearthstone PID
  `50836` had already been closed by the app's E2E-safe schedule path.

- Current invalid run: `39298_8249`; v4.16.150 started under the outside-
  schedule startup override and reached fresh `E2E_READINESS_READY`/`CREATE_GAME`.
  The selected deck's persisted history had `consecutive-surrenders=7`, so the
  production surrender guard conceded during the initial mulligan. The result
  was correctly rejected for missing `mulligan`, `ourTurn`, and `outCard`
  milestones. Fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_05_35_41\\Power.log`, run
  directory, console, and result screenshots are preserved and excluded from
  credit. The runner's restarted Java child was revalidated and stopped. The
  next candidate uses the existing launcher switch
  `HS_E2E_SKIP_SURRENDER=true` to keep persisted production streak history from
  aborting real-input validation; no game state or database records are
  modified.

- v4.16.151 release checkpoint: the checked-in launcher now supports the
  E2E-only `HS_E2E_SKIP_PERSISTENT_STREAK=true` switch. It bypasses only the
  persisted selected-deck consecutive-surrender guard; rank, hero, turn-start,
  F1/F2, terminal-priority, PaddleX/rank fail-closed, and authoritative
  Power.log result paths remain enabled. The canonical build completed with
  126 tests, 0 failures, and 0 errors. Manifest/actual JAR SHA-256 is
  `c79c24c202628169787fa3cd2ec1b24303f660680791ac77bdb70350ba63bcc4` for
  `hs-script_v4.16.151-local-20260901-060004PDT.jar`. Runtime configuration
  remains PaddleX/AUTO with SHA-256
  `8499e27eee1326d1fa499dde157c65ad88c1bb6b784e62ac9dc0931ed1f87af2`.

- Provisional run `77949_1049` on v4.16.150 produced two real current-player
  authoritative wins with same-run result screenshots and clean accepted
  script markers, but used the broader E2E-only `HS_E2E_SKIP_SURRENDER=true`
  policy bypass. It is preserved as a diagnostic success and explicitly
  excluded from strict release credit.

- Current invalid run: `80542_8073`; v4.16.151 used production surrender/rank
  policy with only `HS_E2E_SKIP_PERSISTENT_STREAK=true`. It completed two real
  games, both current-player `LOST`, then a third attempt was rejected at
  06:23:07 for missing `mulligan=false`, `ourTurn=false`, and `outCard=false`.
  The fresh Power.log was
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_06_04_53\\Power.log`;
  console, ledger, screenshots, and exact process-stop evidence are retained.
  The rejection invalidates this evidence lineage for the zero-rejection gate;
  no win credit is assigned.

- Current environment-blocked run: `79733_5887`; v4.16.151 again used
  production surrender/rank policy with only the persistent-streak bypass.
  Hearthstone rotated to fresh Power.log
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_06_25_55\\Power.log` but
  remained on “unable to reconnect to the game”. The runner stayed
  `pause=true` from 06:28:26 through 06:30 with no fresh
  `E2E_READINESS_READY` or `CREATE_GAME`. One bounded Battle.net restart did
  not recover the client. Run directory, console, ledger, recovery screenshots,
  and process evidence are preserved; exact runner, Hearthstone, and
  Battle.net targets were stopped only after command-line validation. Strict
  E2E release credit remains unmet.

- v4.16.152 release checkpoint: the Launch/OCR diagnosis identified stale
  Hearthstone reconnect/last-game lineage as the primary blocker, with
  secondary PaddleX contract-reject/Legacy-garbage handling on the reconnect
  dialog. The fix keeps Battle.net alive until stable non-STARTUP/LOGIN/
  FATAL_ERROR observations, rejects pre-existing E2E games, resets stale
  lineage, retains the fresh Power.log/CREATE_GAME gate, and records reconnect
  errors while remaining fail-closed at low confidence. The owner reported
  commit `f6553b87`, 95 targeted tests with 0 failures and 0 errors, clean
  worktree, and deployment of
  `hs-script_v4.16.152-local-20260901-064833PDT.jar` with SHA-256
  `62d847194fc54b331294714486839e411a307c6a39bfc3fb9140d1f299d66ecb`.

- Current invalid run: `22822_8809`; v4.16.152 reached the normal Hearthstone
  HUB and rotated fresh Power.log to
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_06_54_10\\Power.log`.
  It then attempted the reconnect recovery path and entered `pause=true` at
  06:55:05 without a fresh `CREATE_GAME`. No real game or result evidence was
  produced. The run directory, console, ledger, and controlled HUB screenshot
  are preserved. Exact Java PID `40640`, Hearthstone PID `24332`, and the
  Battle.net process tree were stopped only after command-line validation; no
  validated target remains. This run is routed back to the Launch/OCR owner
  and receives no E2E credit.

- v4.16.153 release checkpoint: the readiness self-lock after the reconnect
  fix was narrowed so HUB/start-matching pre-game context may observe the
  first current-run `CREATE_GAME`, while gameplay/retry/surrender/replan and
  unknown states remain hard-blocked. A readiness timeout no longer permanently
  poisons `WAITING`; a later current run/PID event past baseline may become
  READY. The owner reported commit `e90e1e78`, 97 targeted tests with 0
  failures and 0 errors, clean worktree, and deployment of
  `hs-script_v4.16.153-local-20260901-070926PDT.jar` with SHA-256
  `4fd13ca6ef62d48e9e5bca717fe0d7b28dfef39fc5cfc7b7ce4b2348845829d4`.

- Current invalid run: `24239_3280`; v4.16.153 reached a real fresh
  `E2E_GAME_READY`/mulligan screen using
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_07_15_51\\Power.log`,
  but the rank path emitted `OCR_PROVIDERS_FAILED` at 07:17:30
  (`paddlex=empty-result`, `legacy=contract-rejected`). The run is excluded
  from the zero-OCR-failure gate. Its run directory, console, ledger,
  controlled mulligan screenshot, and Power.log are preserved. Exact Java
  child and watchdog wrapper processes were revalidated and stopped after the
  failed child auto-restarted; no validated target remains. No game result or
  victory credit is assigned.

- v4.16.155 release checkpoint: terminal-result handling now waits for an
  authoritative Power.log `PLAYSTATE` even when `war.me.gameId` is empty,
  with structured wait diagnostics and a bounded 15-second fail-closed
  timeout. Owner commit `7759153b` reported 97/97 checked-in tests passing;
  deployed JAR is
  `hs-script_v4.16.155-local-20260901-080823PDT.jar` with SHA-256
  `b72f5ab1e867bc870b7a92cb9bb2610ecce22bf5916eca9e1b9b2f9523a688ca`.

- Current invalid run: `32992_4487`; the fresh v4.16.155 run rotated
  `D:\\Hearthstone\\Logs\\Hearthstone_2026_09_01_08_15_05\\Power.log` and
  reached `E2E_GAME_READY`, but emitted `E2E_INPUT_NATIVE_FAILED` with
  `InterruptedException: sleep interrupted` at 08:16:03. It then emitted
  `OCR_PROVIDERS_FAILED paddlex=empty-result legacy=contract-rejected` at
  08:16:55. No result or win credit is assigned. The run directory, console,
  ledger, fresh Power.log, and restart evidence are preserved; the runner
  exited after its safe Hearthstone termination path. New E2E work is paused
  pending owner diagnosis of the input interruption and OCR regression.

- v4.16.154 release checkpoint: PaddleX/Legacy probe semantics from the
  provider diagnosis were integrated while preserving the rank badge/crop
  and readiness changes. Rank/tier detectors explicitly mark their expected
  empty probes, so an empty PaddleX probe no longer falls through into a
  misleading `OCR_PROVIDERS_FAILED`; unexpected provider exceptions and
  non-probe empty results remain fail-closed. The full first build phase
  completed 128 tests with 0 failures and 0 errors. The deployed JAR is
  `hs-script_v4.16.154-local-20260901-073648PDT.jar` with SHA-256
  `5b82bade4664ccf8c3e9ff75eba6ff86a6e5d616168921e44508d4502acad06a`;
  manifest and the three launcher shortcuts were refreshed and verified.
  No strict E2E credit is assigned until a new run satisfies the complete
  three-game/two-current-player-wins gate.

- Current invalid run: `46161_9056`; v4.16.154 started with fresh Power.log
  `D:\Hearthstone\Logs\Hearthstone_2026_09_01_07_45_08\Power.log` and
  reached a real first game. The first game ended as current-player LOST at
  07:51:43 and its loss screenshot was recorded. During the next recovery/
  mulligan path, the app emitted
  `E2E_GAME_RESULT_REJECTED ... missing script milestones: mulligan=false,
  ourTurn=false, outCard=false` at 07:56:46 and saved a
  `draw-or-unknown` screenshot. This violates the zero-rejection gate; the
  run is excluded from all credit. Its full run directory, ledger, console,
  two result screenshots, and fresh Power.log are preserved. Exact Java,
  runner, and Hearthstone targets were validated and stopped after the
  rejection; no runner target remains.

- v4.16.156 integration checkpoint: native input cancellation caused by an
  interrupted phase-change wait is now classified explicitly as
  `E2E_INPUT_CANCELLED_PHASE_CHANGE` with `accepted=false` and
  `input=not-sent`; non-interrupted native failures remain hard failures.
  The owner checkpoint reported 97/97 release tests plus 6/6 targeted tests,
  and deployed `hs-script_v4.16.156-local-20260901-082509PDT.jar` with
  SHA-256 `46f735c600042f3ddda3198f871102e2dc0abbcd59c34d15f02baa50f251691c`.
  This checkpoint was staged but not credited as E2E evidence.

- v4.16.157 integrated release checkpoint: the v4.16.156 input-cancellation
  semantics and PaddleX owner checkpoint `d127a774` are present at the clean
  integration point. Rank/tier empty probes are explicitly expected and no
  longer become a misleading `OCR_PROVIDERS_FAILED`; unexpected provider
  exceptions and non-probe empty results remain fail-closed. The checked-in
  build-and-deploy script completed the full first build phase with 128 tests,
  0 failures, and 0 errors, then deployed
  `hs-script_v4.16.157-local-20260901-084015PDT.jar` with SHA-256
  `681b9ae6ea042f4ee53f0ba2ae2a698b95b248ba7baf35294a11426a875bc004`.
  Independent verification matched the manifest hash and deployment ID;
  `launch-as-admin.vbs` and the desktop, Start Menu, and pinned Taskbar
  shortcuts all target the managed launcher. No strict E2E credit is assigned
  until a new run satisfies the complete three-game/two-current-player-wins
  gate with fresh Power.log, screenshots, ledger, and process evidence.

- Current invalid run: `14329_8033`; v4.16.157 was selected from the verified
  manifest and rotated fresh Power.log
  `D:\Hearthstone\Logs\Hearthstone_2026_09_01_08_47_34\Power.log`, but the
  Hearthstone HUB remained in `E2E_READINESS_BLOCKED`. At 08:50:10 the
  startup probe finished with `attempts=1 working=false paused=true`; the run
  then remained paused without a `CREATE_GAME`, game result, OCR failure, or
  input failure. Exact Java PID `36360`, runner PID `36524`, and Hearthstone
  PID `50632` were validated by command line and safely stopped. The run
  directory, console, ledger, screenshots, and fresh Power.log are preserved;
  no E2E credit is assigned. New E2E work is paused pending Launch/readiness
  owner diagnosis.

- v4.16.158 owner checkpoint: foreground-window ownership was hardened so
  native/Robot input is blocked unless Hearthstone HWND activation and
  foreground/focused/visible/process identity checks all pass. An uncertain
  target now emits `E2E_INPUT_SENDINPUT_SKIPPED accepted=false
  input=not-sent reason=foreground-unconfirmed`. Owner checkpoint `4217e377`
  reported 7/7 targeted tests and 97/97 checked-in release tests, with
  deployed JAR `hs-script_v4.16.158-local-20260901-085801PDT.jar` and
  SHA-256
  `5b72ffc6ac8b4bdff1567b6e35b9f7774ad75a8773c99eddddba035a0d004b51`.

- Current invalid run: `30210_2370`; v4.16.158 loaded from the verified
  manifest, rotated fresh Power.log
  `D:\Hearthstone\Logs\Hearthstone_2026_09_01_09_03_19\Power.log`,
  confirmed the Hearthstone target (`handle=native@0x6ff0ddc`) and reached
  `E2E_GAME_READY`. The input foreground check was confirmed true, but rank
  OCR then emitted `OCR_PROVIDERS_FAILED` for expected rank probes
  (`current-rank-psm6-mask`, followed by `current-rank-psm6-raw` and
  `current-rank-psm7-mask`, all PaddleX empty/Legacy contract-rejected).
  The run is excluded from all credit. Its console, ledger, observed
  mulligan screenshot, and fresh Power.log are preserved. Exact Java PID
  `36660`, runner PID `37468`, Hearthstone PID `31672`, and this run's
  Battle.net process tree were validated and stopped. New E2E work is paused
  pending PaddleX/rank owner diagnosis.
