# Pirate conditional damage spell audit (2026-09-10)

## Finding

`SW_027` is 海上威胁. The local card database/runtime parser describes it as:

> 对一个随从造成 2 点伤害。如果你控制一个海盗，则改为造成 5 点伤害。

The parser did create `GeneratedCardAction_SW_027`; this was not an unknown-card
or missing-target-parser failure.

In `hs_script-2026-09-10.12.log`, the reproduced turn began at
`19:43:17.880` with `mana=5` and `hand=SW_027, OG_312, BAR_844,
CORE_NX2_028, CAP_104`. Power.log shows enemy `SC_003` (巢群虫后) as a
legal target with `ATK=2`, `HEALTH=5` and action option `target 4 ...
error=NONE`. The MCTS root nevertheless reported only four candidates:
`CAP_104`, `CORE_NX2_028`, `BAR_844`, and `OG_312`.

The reason was the action-order fence: generated minion plays were in the
earlier `MINION_PLAY` phase, so the usable `SW_027` action was left for the
later `SPELL_PLAY` phase. It could appear in a descendant plan, but could not
compete at the root. This is why the card looked ignored despite being parsed
and targetable.

## Change

`PirateConditionalDamageSpellPolicy` is a shared Pirate Warrior/Demon Hunter
model boundary. It preserves parser-owned target generation and adds only the
conditional rule the generic parser cannot represent:

- 5 damage when a living friendly Pirate is controlled at cast time;
- otherwise 2 damage;
- no target means no action is fabricated;
- a target inside the current damage range is classified as `TACTICAL_SPELL`,
  before ordinary minion plays;
- non-kill uses stay in normal `SPELL_PLAY` and receive only a soft prior;
- cloned simulation adds the missing conditional damage after the parser's
  base damage, with no live-state mutation or second-application on a re-plan.

The new phase is an opt-in MCTS phase, not a global SW_027 auto-play rule.
Within it, the ordinary MCTS score still chooses among legal target actions.

## Evidence of the working action chain

Before this change, later turns in the same runtime game selected SW_027
successfully, proving the executor path was already usable. For example:

- log: `hs_script-2026-09-10.12.log` around `19:45:44.352`, where the plan
  selected `SW_027` after `SW_028t5`;
- before screenshot:
  `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log\mcts-replay\game-laz_12793-1789094370587\action-screenshots\turn-0007-step-02-before-SPELL_PLAY-selected-SW_027_cost_1_entity_47-20260910-194544-446.png`;
- confirmed screenshot:
  `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log\mcts-replay\game-laz_12793-1789094370587\action-screenshots\turn-0007-step-02-after-confirmed-SPELL_PLAY-confirmed-SW_027_cost_1_entity_47-20260910-194551-544.png`.

## Offline coverage

`PirateConditionalDamageSpellPolicyTest` covers Pirate 5-damage kills,
non-Pirate 2-damage kills, nonlethal soft scoring, no-target fail-closed
behavior, both Pirate models' tactical phase selection, and one-time
conditional damage across a re-plan clone.

Online deployment/E2E is intentionally not performed by this worker. The
daytime secretary must integrate the commit, build the canonical package, and
run the shared online completion gate.
