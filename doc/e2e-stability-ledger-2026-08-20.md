# E2E stability and mulligan checkpoint — 2026-08-20

This is an evidence ledger for the deployed build, not a claim that a complete
win/loss game sequence was completed.

## Build under test

- Deployed artifact: `outputs/Hearthstone Script/hs-script_v4.16.3-GA.jar`
- SHA-256: `EC13828569789108A88B3AF01DCCC7F721E5B0D09F9E32103B4973ED3B4EF516`
- Supervisor: `outputs/Hearthstone Script/run-debug.ps1`
- Console log: `outputs/Hearthstone Script/log/java-console-debug.log`
- Application log: `outputs/Hearthstone Script/log/hs_script.log`
- Hearthstone Power.log observed by the app: `D:\Hearthstone\Logs\Hearthstone_2026_08_20_04_33_36\Power.log`

## Five-minute stability evidence

The supervisor launched Java attempt 1 at `2026-08-20 06:39:10` with PID
`20072`. At `06:45:27`, the same PID was still alive and responding. The
console log contains continuous `SUPERVISOR_HEARTBEAT` records from the
interval, and the lifecycle log repeatedly reported:

```text
LIFECYCLE_STATE pid=20072 pause=false working=true mainWindowShowing=true mode=GAMEPLAY inWar=true warPhase=GAME_TURN
```

There were no spontaneous `Java attempt ... exit`, `hs_err_pid`, `OutOfMemory`,
`FATAL`, or uncaught-exception markers during that window. Computer Use also
observed a live `hs-script` window owned by the Java process at the end of the
window.

## Mulligan evidence

At `06:40:11`–`06:40:13`, the current run reported:

```text
当前处于：更换手牌阶段
收到换牌输入 ... scheduled=true
换牌选择：换掉=CORE_NEW1_027(cost=3)；保留=TSC_069(cost=2), VAC_430(cost=2), TSC_069(cost=2)
MULLIGAN_ACTION cardId=CORE_NEW1_027 cost=3 index=2 handSize=5 target=MY_HAND_CARD
MULLIGAN_DECISION_SUBMITTED replace=1 keep=3 rule=cost>2-replace,cost<=2-keep
执行换牌策略完毕
当前玩家MULLIGAN_STATE=DONE
```

The implementation now clicks the fanned player hand (`getMyHandCardRect`),
not the discover-card layout. Normal strategies replace every card costing
more than two mana and keep cards costing zero, one, or two mana.

## Automatic relaunch evidence

This was an intentional supervisor test, not a spontaneous crash. At
`2026-08-20 06:46:21.028`, PID `20072` was explicitly terminated. The
supervisor then recorded:

```text
==== Java attempt 1 exit 2026-08-20T06:46:23.833... code=-1 runtimeSeconds=433 ... ====
==== restarting Java after process exit; retry=1 exitCode=-1 ... ====
==== Java attempt 2 start 2026-08-20T06:46:25.890... ====
SUPERVISOR_HEARTBEAT pid=15924 alive=True runtimeSeconds=5 ...
```

PID `15924` was responding and had a visible `hs-script` window immediately
after relaunch. The supervisor is configured with unlimited retries (`0`) and
only stops when its explicit stop flag is created or the user terminates the
supervisor itself.

The relaunched process also exercised the mulligan path again at
`06:48:57`–`06:49:02`:

```text
换牌选择：换掉=TOY_330t11(cost=7), VAC_929(cost=4), MAW_008(cost=4), BT_355(cost=3)；保留=
MULLIGAN_ACTION ... cost=7 ... target=MY_HAND_CARD
MULLIGAN_ACTION ... cost=4 ... target=MY_HAND_CARD
MULLIGAN_ACTION ... cost=4 ... target=MY_HAND_CARD
MULLIGAN_ACTION ... cost=3 ... target=MY_HAND_CARD
MULLIGAN_DECISION_SUBMITTED replace=4 keep=0 rule=cost>2-replace,cost<=2-keep
```

This second run confirms that the behavior survived the automatic relaunch,
not merely the first Java process.

## Interpretation

This checkpoint proves the five-minute live-process requirement, the normal
mulligan decision/click path, and the short-term automatic relaunch mitigation.
It does not by itself prove a five-game or four-game win/surrender sequence.
