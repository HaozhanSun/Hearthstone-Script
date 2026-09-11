# 勾拳3000型（Hookfist-3000）MCTS 审计 checkpoint

## 身份核对

本次实现按稳定卡牌 ID 识别 `CORE_NX2_028` 与 `NX2_028`，不依赖中文名称。当前本地 `hs_cards.db` 与近期运行时日志仍显示旧版数据库/实体文本（“钩拳-3000型”、3 费 4/3）；用户确认的当前牌面是 2 费 2/3 Epic Pirate，效果为“本回合友方海盗造成伤害时 +1”。因此本补丁没有修改数据库或硬编码费用/身材，只在稳定 ID 上实现本回合伤害规则。

## 规则与实现

* 场上存活的 1/2/3 张勾拳3000型，分别让每个友方海盗伤害事件增加 1/2/3。
* 只有我方回合、友方场上、种族为 Pirate（或 Hearthstone 的 ALL）且实际作为伤害来源的随从获得加成。
* 加成在 `CardUtil.simulateAttack` 的单次敌方伤害事件处应用，不改写 `Card.atc`，所以不会因为重复重规划而重复叠加；英雄、武器、非海盗、敌方随从和跨回合状态不加成。
* Warrior 与 Demon Hunter MCTS 的海盗攻击估值、共享斩杀估值和战斗模拟都调用同一政策，避免预测与模拟分叉。

## 离线验收

`PirateHookfist3000AuraTest` 覆盖 0/1/2/3 个来源、非海盗、回合边界，以及模拟伤害与 `PirateLethalAttackPolicy` 的一致性；另有 Warrior/DH 模型估值断言。当前仅做隔离 worktree 离线验证，未启动/停止脚本，未部署 canonical runtime，也没有新增实机截图。

历史运行时参考（只读）：

`C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script Beta\log\decision-trace\game-KennethSun_5122-1789017650389.jsonl`

该日志用于确认实际运行实体 ID/名称；它没有为本 checkpoint 提供新规则的线上证据。线上 gate 仍由主秘书完成：需集成后部署、取得新鲜对局/Power.log/决策遥测，并验证两局连续执行与至少一局新胜局；本 worker 不宣称线上完成。
