# Pirate Warrior MCTS offline case matrix

本文件是 `codex/pirate-warrior-mcts-followup` 独立 worktree 的审计记录。当前阶段只做离线编译与单元/黄金场景验证，不代表 Hearthstone 真实对局 E2E 已通过；没有写入 canonical runtime、manifest、launcher、shortcuts，也没有启动或停止游戏。

## Upstream 对照

- 上游仓库：[Hearthstone-Script](https://github.com/HaozhanSun/Hearthstone-Script.git)
- 对照 ref：`origin/main`，采样 hash `942b09d8a693542202d0af6682d73d13b3cf97a5`
- 基线 ref：`origin/master`，采样 hash `c89e0b9ba1919b99c3535a5e780826a7830a3520`
- 对照文件：`hs-script-card-sdk/.../CardAction.kt`、`.../mcts/MonteCarloTreeNode.kt`、`.../mcts/MctsReplayTrace.kt`、`hs-script-strategy-sdk/.../deck/MCTSDeckStrategy.kt`

行为差异而不是类名差异：`origin/main` 的通用层负责从 parser 生成合法的 play/attack actions、过滤 mana/board-full/uncertain cards、应用 action prior，并在实验模式下处理 `END_TURN`。普通非 tradeable minion/weapon 可以由通用 `CardAction` 生成“支付费用并移入战场”的 generic play action；spell 需要 parser 覆盖。此次只在 `MctsDecisionModel` 增加默认返回 `true` 的 `isActionLegal` 扩展点，由 Pirate Warrior 使用；其他职业的默认 action 集合不变。

上游 controller 已经区分日志和实际接受：dispatch 前记录 `action_dispatched`，回调返回后记录 `action_dispatch_returned`，只有可观察 state fingerprint 变化才记录 `action_confirmed`；否则记录 `action_unconfirmed` 并抑制同一失败动作。这个语义必须保留，不能把“日志打印了选择”当成游戏接受了动作。

## Case matrix

| Case | Expected | Actual | 根因/后续 |
|---|---|---|---|
| 船载火炮 P0 | 可支付、未有存活炮时成为唯一 mandatory play | PASS；旧测试和黄金测试通过 | 这是 action-prior/mandatory 时序，不等于炮击随机伤害已由 parser/E2E 验证 |
| 宝藏经销商其次 | 没有 P0 炮/首回合任务约束时，合法经销商为 mandatory | PASS | 与炮的 precedence 明确；不宣称已验证战吼/生成牌 transition |
| 开进码头首回合 | 首回合有费用且合法时优先于普通动作；炮仍优先 | PASS | 当前按 `me.turn <= 1`；实际回合编号遥测需确认 |
| 海盗帕奇斯 | 起手全部换掉；运行中 action prior 最低但不隐藏唯一合法动作 | PASS | mulligan 与 action prior 分开；召唤/压缩牌库效果不由 opaque fallback 猜测 |
| 南海船长 | 其他海盗的 reward 攻击力加 1；不把自身当作自身 buff 目标 | PASS | golden test 验证队长/粗暴猢狲/攻击海盗的交叉加成 |
| 粗暴的猢狲 | 打出时只对当时场上的其他 Pirate minions +1/+1；同名第二张属于其他 Pirate；自身、非 Pirate、手牌 Pirate 不计入 | PASS；新增 0/1/2/同名/非 Pirate/手牌/自身与 attack+health golden coverage | 本地 DB 文本与用户确认语义冲突；当前按用户确认语义物化到 clone state，真实 parser/Battlecry 待确认 |
| 狂暴邪翼蝠 | 满足费用条件时提高 play prior；高费时不强制 | PASS（代码路径） | 仍需实战/回放确认其动态费用和入场语义 |
| 钩拳-3000型 | 已装备武器或当回合能连同武器支付时提高；有其他可下怪且无武器线时降权 | PASS；黄金测试通过 | 武器 replacement 的实际扣耐久/替换由通用 transition 和 E2E 验证 |
| 前锋战斧 | 对手英雄血量 >=10 时硬禁英雄打脸；只能打随从且放在其他可行动作之后；不能击杀不加 kill/effect reward；血量 <10 才允许打脸，斩杀提高优先级 | PASS；新增 5 个 golden edge cases | upstream `AttackAction` 没有 target 字段，当前用 clone delta 识别目标；多个随从的相对排序仍受通用 API 限制。通用 `CardUtil` 的 `canHurt()` 不覆盖 WEAPON，模型暂时在 simulation hook 中补耐久损耗；weapon replacement/真实 accepted action 未确认 |
| 海盗之锚 | 无存活武器时较高；已有武器时降权 | PASS（代码路径） | 只读取 `weapon.isAlive()`；武器替换的 parser 语义未覆盖 |
| 船只炮击/船只招募 | 只能选择已解析且可接受的 action；未知效果不猜 | PASS；CAP 新卡 opaque fallback 明确为 false | 船炮随机目标、船只进度、招募顺序需要 event telemetry + replay fixture |
| 炸药工程师、钩手相关生成牌 | parser 有 action 才进入执行；不能因卡面文字自动制造 action | PASS；黄金测试通过 fail-safe | generated card、Discover/randomness 要在真实状态转移中逐项补 fixture |
| 克罗雷船长 | 空位 <3 时 action hard-filter；空位 3/7 才允许；实际 Battlecry 是召唤两个 1/1 Cannoneers | PASS；0/1/2/3/7 golden coverage | 本地 DB/parser 无 `CAP_106`；卡面由在线资料确认。用户指定的 >=3 空位门槛保留为 card-specific hard legality |
| Warrior 英雄技能 | 有可下海盗、武器、攻击、任务/联动动作时最后候选；只有技能或无其他合法有价值动作时才可选；不足法力不制造动作 | PASS；可行动作、攻击、only-power、insufficient-mana covered | 这是 Pirate Warrior 的 deferred/near-hard-last-resort prior，不改通用 legality；防守/斩杀价值仍需真实回放校准 |
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
- 南海船长 +1 仍按 ongoing aura reward；粗暴猢狲是打出时的一次性 +1/+1 Battlecry，已在 clone state 物化，只作用于当时其他场上 Pirate；真实 parser/trigger transition 仍待确认。
- 钩拳武器同回合条件：依赖武器费用、可替换武器和 parser 执行结果；当前是 prior，不是硬 legality。
- 新 `CAP_*` 卡、Discover、随机炮击、生成牌：本分支 fail-closed；禁止用 opaque action 补齐未知效果。
- `isActionLegal` 的前锋战斧目标过滤与克罗雷三空位门槛是真正 hard gate，不是调低 prior；目标无法由 clone delta 可靠识别时 fail-closed。此处不能推广为所有职业/所有卡牌的通用规则。
- 英雄技能“最后候选”目前是 `isDeferredAction` + 极低 prior，不是全局禁止；只有可观察到其他有价值动作时才后置，避免阻断唯一技能、斩杀或必要防守。

## 失败与修复记录

1. 新 worktree 初次编译失败：共享树中存在但未入 git 的 config/DB/PluginScope 基线文件在独立 worktree 缺失。根因是忽略文件未随 worktree 创建；只在独立 worktree 补回同等基线文件，未修改共享树或提交这些 ignored 文件。
2. 第一轮黄金测试失败：队长与猢狲的测试期望把自身基础攻击力误写成未受另一张牌影响的数值。模型实际按“其他海盗”加成；修正断言为队长 4、猢狲 3，并保留交叉目标检查。
3. 防守黄金测试初始阈值与模型的“超杀差值”定义不一致。改为显式 lethal/near-lethal penalty：达到血线固定强惩罚，接近血线渐进惩罚；第二轮全部通过。
4. 前锋战斧耐久断言初始读取了静态 `durability` 字段。upstream 通用模拟通过 `damage` 表示武器已消耗耐久，而 `Card.injured()` 对 WEAPON 返回不可受伤；根因是共享 simulator 的武器 wear gap。模型 hook 现在显式增加一次 `damage`，测试改为断言 `damage/blood/isAlive`，并保留该 gap 为真实 transition 待确认项。

## 离线验证结果

命令：

```text
.\mvnw.cmd -pl hs-script-base-strategy-plugin -am -Dtest=PirateWarriorMctsModelTest,PirateWarriorMctsGoldenScenarioTest -Dsurefire.failIfNoSpecifiedTests=false test
```

最终结果（本轮）：命令 `.\mvnw.cmd -pl hs-script-base-strategy-plugin -am '-Dtest=PirateWarriorMctsModelTest,PirateWarriorMctsGoldenScenarioTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；`PirateWarriorMctsGoldenScenarioTest` 17 tests passed，`PirateWarriorMctsModelTest` 5 tests passed，合计 22 tests passed；reactor `BUILD SUCCESS`。编译期间仅有既有 MapStruct `deepClone` unmapped warning。该结果只证明离线模型/动作候选夹具，不证明 parser、UI 点击、游戏服务端接受、随机效果或真实回合 E2E。

本轮 Hozen 语义修正后的最终离线结果：`PirateWarriorMctsGoldenScenarioTest` 19/19 passed + `PirateWarriorMctsModelTest` 5/5 passed = 24/24 passed；reactor `BUILD SUCCESS`。新增用例覆盖 0 个 Pirate、1 个其他 Pirate、2 个 Pirate、第二张同名猢狲、非 Pirate、手牌 Pirate 排除、触发者自身排除，以及 attack/health 两项状态。

## Verification gotcha ledger

下表专门记录容易把离线结果误读成真实 E2E 的验证陷阱。`status` 是本分支当前状态，不是发布结论。

| symptom | root cause | safe response | required evidence | status |
|---|---|---|---|---|
| 日志显示 MCTS 选中了动作，但游戏没有变化 | dispatch/log event 只代表本地尝试；parser、UI 或客户端可能拒绝动作 | 把 `action_dispatched`、callback return、state delta、`action_confirmed`/`action_unconfirmed` 分开；无变化时抑制重复动作 | 同一 action 的 before/after state fingerprint、callback result、异常、retry suppression、实际 UI/游戏状态变化 | 通用 controller 已有离线语义；真实 accepted 仍待验证 |
| fingerprint 相同但动作被误认为成功，或短暂变化被误判 | fingerprint 字段不完整、采样太早，或只比较日志/对象引用 | fingerprint 至少包含 turn owner、turn、mana/used resources、hand/board/weapon durability、hero health、target/card IDs；在 bounded wait 内重复采样 | action 前后 canonical fingerprint、采样时间、变化字段、稳定窗口和 timeout 结果 | 设计已记录；尚无真实客户端采样 fixture |
| `END_TURN` 被提前执行，或结束后没有重新扫描 | experimental search 的候选过滤与 live executor 的 turn ownership/结束回合时序可能不同 | 仅在当前节点无可执行优先动作时允许 `END_TURN`；结束后等待 turn/state fingerprint，再重新扫描 | turn owner、END_TURN dispatch/accept、下一回合 fingerprint、重复扫描结果 | 通用离线节点覆盖；真实回合边界待验证 |
| 新武器打出后旧武器仍被错误计分，或 Hookfist 被错误降/升权 | weapon replacement、耐久归零和攻击消耗是 transition 语义，不是单纯 hand/board presence | prior 只把已存活武器/同回合可支付武器当作证据；不伪造 replacement 或 durability 变化 | before/after equipped weapon entity、attack/durability、mana、旧武器移除、新武器生效和 accepted action | prior/耐久夹具已通过；真实 replacement transition 未确认 |
| 船载火炮日志显示已下场，但随机炮击/船只招募没有发生 | 卡面文本已知不代表本地 parser 有完整 battlecry/ship/random transition | CAP 新卡和未验证生成效果保持 fail-closed；船炮只能使用 parser-backed action，随机效果只作待验证 reward | cannon/ship entity、trigger event、随机目标、伤害、招募卡 ID/位置、state fingerprint 前后差异 | fail-closed 离线覆盖；炮击/招募 E2E 未验证 |
| 未知卡被 MCTS 当作普通 minion/opaque action 执行 | parser unknown action 与 generic minion fallback 可能只支付费用并移入战场，漏掉真实效果 | `isUncertain` 或无可靠 parser action 时过滤；禁止按卡名/前缀批量开启 opaque fallback | action scan outcome、parser class/card ID、uncertain 标记、无 action 的节点结果、无重复 retry | 未知卡和 CAP fail-closed 测试通过 |
| replay 只能复现选择，不能复现游戏状态 | 只保存 selected action 或日志，不保存足够的 state/transition 输入 | 保存 redacted deterministic state snapshot、候选列表/prior、mandatory reason、chosen action、transition result 和 seed/random outcome | fixture 可离线重放并逐字段比较 expected/actual fingerprint；随机效果保存 seed 或 outcome | replay schema 已有基础 trace；Pirate Warrior 专用 fixture 待补 |
| 两张同名 Pirate 在 clone 后只剩一张 | 测试夹具复用了相同 entityId，`cardMap`/target lookup 覆盖了前一实体；真实运行时 entityId 必须逐实体唯一 | golden fixture 为每个实体分配唯一 ID；按 entityId 排除触发者，允许第二张同名牌作为其他 Pirate | clone 前后 entity IDs、board count、触发者/目标 stats 逐项比较 | 已修复并由 Hozen 24-case offline 集覆盖；真实 snapshot identity 待 E2E |
| 测试通过但运行的仍是旧 JAR/旧插件进程 | stale process、旧 manifest、旧 classpath 或未重启 worker 与当前源码不一致 | 当前阶段不宣称运行验证；发布阶段必须记录 build ID、JAR SHA-256、manifest、进程 PID/classpath 和启动时间 | 新版本 identity 与运行时 identity 一致，旧进程不存在，manifest/hash/launcher 指向同一 artifact | 本轮未 build/deploy，故未适用；发布 gate 必须补证据 |
| 离线 action accepted 被误写成真实 E2E 通过 | `TestCardAction`/generic simulator 的 callback 返回成功只证明测试夹具接受 | 报告中明确使用“offline model/action candidate pass”；禁止使用“游戏已接受/真实回合通过” | 真实客户端截图或状态采样、dispatch-to-accepted trace、回放 fixture、失败重试记录 | 当前报告明确为 offline-only |
| 前锋战斧攻击被误判为合法打脸或有效 kill line | `AttackAction` 没有 target 字段；仅凭 creator/card name 无法知道目标，且共享 simulator 当前不自动扣 WEAPON wear | 用 clone 前后 delta 分类 HERO/MINION/UNKNOWN；UNKNOWN 禁止，非击杀随从线给负 prior/零 effect reward；保留最小兼容约束 | hero health 10/9、target health =/> weapon damage、多个目标、durability 1/3、terminal/lethal 的 accepted transition | 离线规则通过；target ordering、真实 axe trigger/weapon consumption 待 E2E |
| 克罗雷在两空位时被 generic minion play 放下 | generic play action 只知道卡牌可下，不知道 Battlecry 需要足够 slot | `isActionLegal` hard-filter `freeSlots >= 3`，不只调 prior；CAP_106 parser 未确认时不开放 opaque fallback | 0/1/2/3/7 slots 的 action scan、唯一候选、Battlecry token 数量和位置 | 离线 hard gate 通过；真实 CAP_106 parser 待确认 |
| 英雄技能抢在海盗/武器/攻击之前 | 通用层把 HERO_POWER 当普通可用 action；低优先级不等于搜索阶段后置 | Pirate model 使用 deferred + near-hard-last；only-power/insufficient mana/斩杀/防守单独采样 | candidate list、prior、deferred reason、selected action、mana、hero health、lethal/defense outcome | 离线覆盖通过；数值仍需回放校准 |

## 下一步 telemetry/test

- 每次搜索记录 state fingerprint、turn owner、mana/used resources、hand/board/weapon durability、candidate action、prior、mandatory reason、selected action。
- 每个 dispatch 记录 callback return、state delta、accepted/unconfirmed、失败异常和 retry suppression；尤其区分“生成了船炮/船只 action”与“游戏状态真的产生炮击/招募”。
- 为 weapon replacement、耐久归零、船位/board slot、炮击目标、招募顺序、Discover 选择、随机结果、过量攻击、END_TURN 和对手 lethal 各提供可重放 fixture。
- 通过这些 fixture 后，再考虑把确证过的 CAP 卡从 fail-closed 列表逐个提升为 parser-backed action；不要一次性放开整个 CAP 前缀。
