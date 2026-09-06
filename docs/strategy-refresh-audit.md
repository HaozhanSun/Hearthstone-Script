# Beta MCTS strategy refresh audit

## Safe boundary

The current application does not support true in-process code hot replacement.
The main application classes are loaded by the application classloader, while
external plugins are loaded by a new `URLClassLoader` for each scan. The
`ServiceLoader` instances are short-lived, but the strategy objects they create
can remain referenced by a running `OutCardThread`. Closing or replacing a
classloader in the middle of that thread would be unsafe.

The implemented boundary is therefore **deferred strategy refresh**:

1. A caller queues `DeckStrategyManager.requestStrategyRefresh(reason)`.
2. The current turn continues with its already captured strategy instance.
3. At the next own-turn `MAIN_ACTION` boundary, the request is applied.
4. Plugin catalogs are built off to the side and published together. If loading
   or catalog publication fails, the old catalog and active strategy remain.
5. A matching strategy ID is replaced with the newly loaded instance. If the
   selected ID disappeared, the old instance is retained and the event is
   logged; the turn is not stopped.

The event stream is intentionally auditable:
`STRATEGY_REFRESH_REQUESTED`, `STRATEGY_REFRESH_COALESCED`,
`STRATEGY_REFRESH_DEFERRED`, `STRATEGY_REFRESH_APPLIED`,
`STRATEGY_REFRESH_FAILED`, and `STRATEGY_REFRESH_ROLLBACK`.

This is safe for a Beta runtime where a new external plugin JAR is already
present. It does not make new classes inside the running application JAR
visible; that still requires an application restart. Retired external
classloaders are not forcibly closed because an old strategy may still hold a
reference until its turn thread returns. Repeated refreshes should therefore
remain infrequent; a later lifecycle change can close loaders after explicit
quiescence tracking is added.

## Historical nine-request evidence audit

This is an evidence-coverage audit, not a claim that the online completion gate
has passed. The checked-in offline report is
`docs/offline-mcts-review/PIRATE-DH-SCREENSHOT-JUDGMENTS-10.md`; its source
evidence is the runtime `log/mcts-replay` and the runtime `evidence` directory.

| Workstream | Evidence found | Remaining gap |
|---|---|---|
| Per-card Pirate DH MCTS priorities and card interactions | Offline screenshot/replay report; card IDs and action paths are recorded | No fresh online pair proving every card-specific branch; tactical optimality is not established by state matching alone |
| Full MCTS decision telemetry and reproduction storage | `decisions.jsonl` exists under MCTS replay runs | No single audited ledger proving the required retained-game window and complete branch coverage for the current lineage |
| Round-end screenshots/FIFO retention | `round-screenshots` and `MctsRoundScreenshot` are documented; ten raw samples were reviewed | The reviewed set is one historical game, not three fresh games on the latest deployment |
| Unused mana / playable hand / board-slot end-turn guard | The ten-sample report records full-rescan fields; offline guard tests exist in prior work | No current-build online gate evidence with authoritative acceptance for every remaining action |
| `VAC_929` location activation and post-attack reactivation | Replay report identifies location actions; model retry work is in the shared lineage | No independently accepted current-build online run proving both activations in one game |
| `VAC_938` 猢狲 aura and Pirate attack-value propagation | Card/model tests and prior replay context exist | No current-build screenshot plus Power.log pair isolating the aura contribution |
| Hero-power ordering, weapons, attacks, and deferred timing cards | Existing MCTS and turn-end regression tests; replay report contains action sequences | Duplicate dispatch/acceptance is still a known gap; replay snapshots do not include taunt or client acceptance fields |
| Rank/win-rate/streak persistence and surrender safety | Runtime evidence has rank/surrender ledgers and dedicated tests | The evidence is split across older runs; no current deployed two-game lineage independently proves all persistence guards together |
| Recovery, settlement, launcher, deployment, and statistics UI | Runtime has recovery/PaddleX/legacy ledgers and deployment artifacts | Those artifacts do not prove this refresh branch; no deployment is allowed for this worker, and no fresh online refresh result exists |

The most important audit limitation is the distinction between a script
`dispatch` and Hearthstone accepting that action. The existing replay report
explicitly avoids treating dispatch as acceptance and notes missing taunt and
per-action `Power.log` correlation. Those are evidence gaps, not reasons to
claim a false pass.

## Offline verification for this change

`StrategyRefreshCoordinatorTest` covers:

- a request during an active turn being deferred and applied at the next
  boundary;
- a loader failure retaining the old strategy and producing failure telemetry;
- duplicate requests being coalesced until the boundary.

The app reactor compiles successfully and the targeted test suite passes 3/3.
The online gate is intentionally **not** satisfied: this worker did not build,
deploy, start, or stop the canonical runtime.
