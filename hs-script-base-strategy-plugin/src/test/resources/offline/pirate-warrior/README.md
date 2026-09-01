# Pirate Warrior offline replay fixtures

These fixtures are synthetic, deterministic state snapshots derived from the
current Pirate Warrior deck rules. They are not Hearthstone client logs and do
not claim parser acceptance or real-game state transitions.

`fixtures.tsv` records the expected strategy, selection, and evaluator labels:

- `pass`: the isolated MCTS model produced the expected conservative result.
- `needs-review`: runtime/parser/client evidence is still required.
- `bug`: reserved for an observed model mismatch; no current fixture is marked
  as a model bug.

The `PirateWarriorOfflineReplayTest` prints one structured line per fixture:
`fixture`, `strategy`, `candidates`, `reason`, `expected`, `selected`,
`evaluator`, and `runtime_review`. The test checks model behavior only; the
runtime review label remains `needs-review` because this workstream does not
start E2E or deploy a runtime.

`online-like-scenarios.tsv` contains two synthetic `LOST` replays and one
synthetic `WON` replay. These outcomes are labels for offline scenario
evaluation, not claims about a real match. The test prints each replay step as
`action`, `reason`, `expected`, `verdict`, `evidence=offline-simulation`, and
`e2e=not-run`.

`ab-calibration-tape.tsv` pairs normal (`A`) and conservative/fail-closed
(`B`) arms for target legality, effective versus ineffective Axe triggers,
Crowley board space, Warrior hero power ordering, Hozen target scope, and
opaque fallback. Each pair is evaluated by the model and printed with the
same offline/E2E boundary.
