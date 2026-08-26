# Strategy decision trace

The Pirate Demon Hunter strategy writes a machine-readable JSON Lines file for
each observed game:

```text
log/decision-trace/game-<game-id>-<start-time>.jsonl
```

Each line is one event. The important events are:

- `decision_cycle_started`: the hand, turn, resource count, and phase seen at
  the start of a decision cycle.
- `candidate_evaluated`: a rule was considered, including no-match and
  currently-unplayable outcomes.
- `decision_selected`: the strategy selected a card and records the reason,
  rule, priority, and hand snapshot.
- `action_dispatched`: the selected card action was sent to the card action
  engine and links back to `decision_selected` with `relatedSequence`.
- `action_failed`: the action engine raised an exception; the exception still
  propagates normally, but the trace preserves the card and selection that
  preceded it.

Every card object includes both a human-readable `displayName` and the raw
`cardId`/`entityId` diagnostic fields. Known Pirate Demon Hunter cards are
explicitly mapped as follows:

| Card ID | Display name |
| --- | --- |
| `TOY_518` | 宝藏经销商 |
| `GVG_075` | 船载火炮 |

The trace is deliberately separate from `log/hs_script.log`, so the normal UI
log does not receive the full hand snapshot and candidate list. The strategy
logs only a concise decision line and the path of the trace file.
