# Standard Cannon Warrior V1.0 · offline evidence

This worker checkpoint is intentionally offline. It does not deploy, modify the
canonical runtime, replace shortcuts, launch Hearthstone, or start/stop any
application process.

## Strategy and schedule contract

- Display name: `Standard Cannon Warrior V1.0`
- Strategy ID: `e71234fa-9-standard-cannon-warrior-v1-0-9b1f-4d29-8f4f`
- Run mode: `STANDARD` only
- Work-time deck slot: `[3]`
- Persistence model: `WorkTimeRuleSet -> WorkTimeRule -> ConfigExUtil.WORK_TIME_RULE_SET`
- Fixture: `hs-script-app/src/test/resources/offline/standard-cannon-warrior-work-time-schedule.json`
- Contract test: `StandardCannonWarriorWorkTimeContractTest` (1/1 passed)

## Card inventory

`StandardCannonWarriorCardInventory.kt` contains the exact 18 screenshot names
in screenshot order. Local `hs_cards.db` exact-name lookup confirmed ten IDs:
`DRG_024`, `REV_990`, `FIR_939`, `AT_064`, `EDR_457`, `NEW1_027`, `END_021`,
`EDR_260`, `TLC_600`, and `JAIL_384`. The eight names with no unique local row
are deliberately recorded as `UNKNOWN`: 火炮长、跟随引线、戴雾维龙、炸药工程师、钩手拖曳、
手持火炮、眺望陆地、克罗雷船长. No card from the old Pirate Warrior list
or guessed `CAP_*` ID is substituted. Confirmed rows have model-backed
priority assertions; unknown rows require parser-backed actions and otherwise
fail closed. The inventory test checks the exact name/ID sequence, the 10/8
confirmed/unknown split, uniqueness, and the evidence/fallback fields.

## Offline E2E simulation

`StandardCannonWarriorOfflineE2ETest` writes a deterministic JSONL trace and
checks the paired SVG state fixture. It covers:

1. Standard routing with deck slot 3 and the independent strategy ID.
2. First-turn prioritization of confirmed 赤红深渊 (`REV_990`) and 怒袭
   (`AT_064`) against a rival minion.
3. The key-card sequence of confirmed Standard IDs, including 龙巢守护者
   (`EDR_457`), 次元武器匠 (`END_021`), and 破链灾星霍格 (`JAIL_384`).
4. Replanning when reachable mana would otherwise remain unused.
5. Unknown action fail-safe: no opaque fallback without a parser-backed action.

The expanded selected strategy suite passed with 90 tests, 0 failures, and 2
intentional skips. The prior 26/26 focused result and 88-test result remain
valid; this run adds the inventory, schedule, and offline E2E coverage.

## Pre-existing unrelated reactor failure

The unfiltered dependency reactor was rerun with:

```text
./mvnw.cmd -pl hs-script-base-strategy-plugin -am test
```

It stopped at the existing card-SDK assertion:

```text
CardInfoDataTest.testParsePlayAction
【安戈洛宣传单】 / WORK_050
```

The current result was 28 card-SDK tests, 1 failure, 0 errors. The parser log
shows the card description `抽两张随从牌，使其获得+2/+2。（每回合翻面。）`
being parsed into a target action before the assertion fails. This is recorded
as pre-existing and unrelated; the worker does not claim a full-reactor green
result or change the card parser in this strategy workstream.

## Gate status

Offline implementation and focused evidence are ready for secretary review.
The shared online E2E gate is not satisfied: there are no fresh executed games,
authoritative online logs/screenshots, accepted-chain evidence, or deployment
proof from this worker. Canonical release and shortcut verification remain the
daytime secretary's responsibility.


