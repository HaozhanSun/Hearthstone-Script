# Pirate Warrior MCTS offline case matrix

本文件是 `codex/pirate-warrior-mcts-followup` 独立 worktree 的审计记录。当前阶段只做离线编译与单元/黄金场景验证，不代表 Hearthstone 真实对局 E2E 已通过；没有写入 canonical runtime、manifest、launcher、shortcuts，也没有启动或停止游戏。

## Upstream 对照

- 上游仓库：[Hearthstone-Script](https://github.com/HaozhanSun/Hearthstone-Script.git)
- 对照 ref：`origin/main`，采样 hash `942b09d8a693542202d0af6682d73d13b3cf97a5`
- 基线 ref：`origin/master`，采样 hash `c89e0b9ba1919b99c3535a5e780826a7830a3520`
- 对照文件：`hs-script-card-sdk/.../CardAction.kt`、`.../mcts/MonteCarloTreeNode.kt`、`.../mcts/MctsReplayTrace.kt`、`hs-script-strategy-sdk/.../deck/MCTSDeckStrategy.kt`

行为差异而不是类名差异：`origin/main` 的通用层负责从 parser 生成合法的 play/attack actions、过滤 mana/board-full/uncertain cards、应用 action prior，并在实验模式下处理 `END_TURN`。普通非 tradeable minion/weapon 可以由通用 `CardAction` 生成“支付费用并移入战场”的 generic play action；spell 需要 parser 覆盖。Pirate Warrior 模型只增加职业/卡组时序、先验和评估，不修改通用 legality 或 replay transition。

上游 controller 已经区分日志和实际接受：dispatch 前记录 `action_dispatched`，回调返回后记录 `action_dispatch_returned`，只有可观察 state fingerprint 变化才记录 `action_confirmed`；否则记录 `action_unconfirmed` 并抑制同一失败动作。这个语义必须保留，不能把“日志打印了选择”当成游戏接受了动作。

## Case matrix

| Case | Expected | Actual | 根因/后续 |
|---|---|---|---|
| 船载火炮 P0 | 可支付、未有存活炮时成为唯一 mandatory play | PASS；旧测试和黄金测试通过 | 这是硬 legality 之上的卡组时序约束；炮击随机伤害仍由 parser/E2E 验证 |
| 宝藏经销商其次 | 没有 P0 炮/首回合任务约束时，合法经销商为 mandatory | PASS | 与炮的 precedence 明确；不宣称已验证战吼/生成牌 transition |
| 开进码头首回合 | 首回合有费用且合法时优先于普通动作；炮仍优先 | PASS | 当前按 `me.turn <= 1`；实际回合编号遥测需确认 |
| 海盗帕奇斯 | 起手全部换掉；运行中 action prior 最低但不隐藏唯一合法动作 | PASS | mulligan 与 action prior 分开；召唤/压缩牌库效果不由 opaque fallback 猜测 |
| 南海船长 | 其他海盗的 reward 攻击力加 1；不把自身当作自身 buff 目标 | PASS | golden test 验证队长/粗暴猢狲/攻击海盗的交叉加成 |
| 粗暴的猢狲 | 攻击事件的其他海盗按每个存活猢狲 +1 评估 | PASS | 仅是评估先验，不模拟真实触发事件或过量攻击 |
| 狂暴邪翼蝠 | 满足费用条件时提高 play prior；高费时不强制 | PASS（代码路径） | 仍需实战/回放确认其动态费用和入场语义 |
| 钩拳-3000型 | 已装备武器或当回合能连同武器支付时提高；有其他可下怪且无武器线时降权 | PASS；黄金测试通过 | 武器 replacement 的实际扣耐久/替换由通用 transition 和 E2E 验证 |
| 海盗之锚/前锋战斧 | 无存活武器时较高；已有武器时降权 | PASS（代码路径） | 只读取 `weapon.isAlive()`；武器耐久/替换的 parser 语义未覆盖 |
| 船只炮击/船只招募 | 只能选择已解析且可接受的 action；未知效果不猜 | PASS；CAP 新卡 opaque fallback 明确为 false | 船炮随机目标、船只进度、招募顺序需要 event telemetry + replay fixture |
| 炸药工程师、钩手相关生成牌、克罗雷船长 | parser 有 action 才进入执行；不能因卡面文字自动制造 action | PASS；黄金测试通过 fail-safe | generated card、Discover/randomness、board-full 要在真实状态转移中逐项补 fixture |
| buff/summon 与 board-full | 满场过滤普通随从 play；END_TURN 仍可见 | PASS；黄金测试通过 | 生成随从数量/位置和船位占用还未模拟 |
| 过量攻击 | 评估不假设一次攻击可超出目标血量，也不重复消耗同一攻击者 | 当前 PASS（保守） | 尚无完整 attack transition golden fixture；需记录 attackCount、目标血量和 parser accepted |
| 无合法动作 | 不制造未知卡 action；至少保留 END_TURN（若通用节点允许） | PASS；未知卡/满场黄金测试通过 | 还需覆盖所有 parser action 抛异常的节点 |
| 对手 lethal threat | 可见可攻击伤害达到我方英雄血量时强烈降分，接近斩杀线渐进降分 | PASS；黄金测试通过 | 未推断隐藏手牌/随机伤害；嘲讽只阻止潜在脸部斩杀奖励，不代替 target legality |
| potential lethal | 无嘲讽且我方现有可攻击总攻足以击穿敌方英雄时加分 | PASS；黄金测试通过 | 不是 lethal guarantee；最终必须以目标合法性和 accepted attack 为准 |
| END_TURN | 先扫描可行动作，执行后重新扫描；无可行动作时结束回合 | 通用层 PASS（沿用上游） | 需在游戏回放中确认 turn owner、回合结束动作和状态 fingerprint |

## 当前实现边界

### 可作为通用 MCTS 的部分

- generic parser action 作为唯一可执行候选来源；mana、存活、board-full、taunt/target legality 由通用层处理。
- action prior 只改变搜索排序，不等于 legality；mandatory 只用于已明确且当前可验证的时序规则。
- rollout 后以状态差评估资源、场面、可攻击伤害、潜在 lethal 与对手 lethal threat。
- `END_TURN`、dispatch/return/confirmed/unconfirmed 和失败动作抑制沿用通用 MCTS controller。

### 高风险 card-specific hard-coding

- 船载火炮 P0、首回合任务、宝藏经销商 precedence、帕奇斯 mulligan：依赖具体卡组与真实回合时序，必须保留 telemetry 开关和回放样本。
- 南海船长/猢狲的 +1 评估：依赖光环/攻击事件语义；当前只改 reward，不修改 card stats，也不假装完成 trigger transition。
- 钩拳武器同回合条件：依赖武器费用、可替换武器和 parser 执行结果；当前是 prior，不是硬 legality。
- 新 `CAP_*` 卡、Discover、随机炮击、生成牌：本分支 fail-closed；禁止用 opaque action 补齐未知效果。

## 失败与修复记录

1. 新 worktree 初次编译失败：共享树中存在但未入 git 的 config/DB/PluginScope 基线文件在独立 worktree 缺失。根因是忽略文件未随 worktree 创建；只在独立 worktree 补回同等基线文件，未修改共享树或提交这些 ignored 文件。
2. 第一轮黄金测试失败：队长与猢狲的测试期望把自身基础攻击力误写成未受另一张牌影响的数值。模型实际按“其他海盗”加成；修正断言为队长 4、猢狲 3，并保留交叉目标检查。
3. 防守黄金测试初始阈值与模型的“超杀差值”定义不一致。改为显式 lethal/near-lethal penalty：达到血线固定强惩罚，接近血线渐进惩罚；第二轮全部通过。

## 离线验证结果

命令：

```text
.\mvnw.cmd -pl hs-script-base-strategy-plugin -am -Dtest=PirateWarriorMctsModelTest,PirateWarriorMctsGoldenScenarioTest -Dsurefire.failIfNoSpecifiedTests=false test
```

最终结果：`PirateWarriorMctsGoldenScenarioTest` 11 tests passed，`PirateWarriorMctsModelTest` 5 tests passed，合计 16 tests passed；reactor `BUILD SUCCESS`。编译期间仅有既有 MapStruct `deepClone` unmapped warning。该结果只证明离线模型/动作候选夹具，不证明 parser、UI 点击、游戏服务端接受、随机效果或真实回合 E2E。

## 下一步 telemetry/test

- 每次搜索记录 state fingerprint、turn owner、mana/used resources、hand/board/weapon durability、candidate action、prior、mandatory reason、selected action。
- 每个 dispatch 记录 callback return、state delta、accepted/unconfirmed、失败异常和 retry suppression；尤其区分“生成了船炮/船只 action”与“游戏状态真的产生炮击/招募”。
- 为 weapon replacement、耐久归零、船位/board slot、炮击目标、招募顺序、Discover 选择、随机结果、过量攻击、END_TURN 和对手 lethal 各提供可重放 fixture。
- 通过这些 fixture 后，再考虑把确证过的 CAP 卡从 fail-closed 列表逐个提升为 parser-backed action；不要一次性放开整个 CAP 前缀。
