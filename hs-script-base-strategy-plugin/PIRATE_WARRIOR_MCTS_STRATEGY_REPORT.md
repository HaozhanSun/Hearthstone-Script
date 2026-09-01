# Pirate Warrior MCTS strategy report

本报告对应截图中的“天下第一”海盗战，并记录 `codex/pirate-warrior-mcts-followup` 当前离线实现。当前没有修改 canonical runtime、POM、manifest、launcher 或 shortcuts，也没有启动/停止游戏；没有真实 Hearthstone E2E 结论。

## 识别结论

截图可稳定读出 26 个不同卡名；但截图中的右侧复制数标记与“完整 40 张”口径无法在当前视图中无歧义相加（部分单卡标记为空、部分传奇显示星标，且两图有重叠）。因此本轮不能诚实地声称已经从截图确认“40/40 张及每张数量”。下表覆盖全部 26 个可见卡名；`未确认` 表示不能由本地 DB + 已有在线资料同时闭环，不代表卡名一定错误。

本地数据库为 [hs_cards.db](<C:/Users/yzjsh/Documents/ChatGPT/Hearthstone Copilot/hs_cards.db>)，查询目标为 `cards(cardId,name,text,...)`；CAP 新卡在本地库中没有记录。已使用的在线资料包括 [Captain Crowley - New Hearthstone Wiki](https://hearthstone.wiki.gg/wiki/Captain_Crowley)、[Frontline Axe - HSTOPDECKS](https://www.hstopdecks.com/cards/BAR_844)、[Warrior Armor Up](https://outof.games/realms/hearthstone/cards/36096-armor-up) 和 [Blizzard Warrior set discussion](https://us.forums.blizzard.com/en/hearthstone/t/new-warrior-class-set-revealed/164811)。

| 卡牌 / ID（截图数量） | 本地确认效果 | 何时有用 / 常见联动 | MCTS priority / timing | 置信度 / 待实测 |
|---|---|---|---|---|
| 宝藏经销商 / `TOY_518` (2) | 每当召唤海盗，使其 +1 攻击力。 | 最佳开局发动机；和船载火炮、海盗铺场、帕奇斯/任务进度联动。 | P0 级开局；但若可下船载火炮，先炮；没有炮时 mandatory。 | DB 高；实际 summon/生成牌 transition 待实测。 |
| 开进码头 / `SW_028` (1) | 任务线：使用 3 张海盗牌；奖励抽一张武器牌。 | 首回合完成任务计数价值高，后续把海盗牌转成武器资源。 | 首回合必须上；与炮冲突时炮优先，随后仍要重新扫描。 | DB 高；turn 编号与任务事件待实测。 |
| 恩佐斯的副官 / `OG_312` (1) | 战吼：装备一把 1/3 的锈蚀鱼叉。 | 早期海盗、补武器、推进任务；可为钩拳/英雄攻击联动。 | 低费海盗动作；在炮/经销商/首回合任务硬约束后评估。 | DB 高；鱼叉实体与换武器待实测。 |
| 海上威胁 / `SW_027` (2) | 对一个敌人造成 2 点伤害；相关受伤/随机后续效果按 DB 文本处理。 | 补刀、触发受伤、清小怪；和炮击/邪翼蝠伤害计数联动。 | 只在 parser 提供 target action 时进入；不要用文字猜 target。 | DB 高（文本需按运行时语言核对）；target/随机待实测。 |
| 海盗帕奇斯 / `CFM_637` (1) | 使用海盗牌后，从牌库召唤本牌。 | 压缩牌库、额外站场、和低费海盗链联动。 | 起手直接换掉；运行时 prior 最低，仅无其他合法动作时保留。 | DB 高；mulligan/召唤位置待实测。 |
| 火炮长 / `CAP_107`（截图标记 2） | 截图卡名可读，但本地 DB 与 parser 未找到闭环文本。 | 推测为炮/海盗链卡，不能把猜测作为 action。 | 仅 parser-backed action 可入候选；未知时 fail-closed。 | ID 来自已有模型映射，效果未确认。 |
| 空中悍匪 / 未确认（截图标记 2） | 本地 DB 未按该中文名找到唯一行；ID/文本未确认。 | 作为低费海盗候选，可能参与任务/炮/经销商链。 | 不写 card-specific opaque action；等待 parser/卡 ID。 | 未确认。 |
| 跟随引线 / 未确认（截图标记 2） | 本地 DB 未找到唯一行；ID/文本未确认。 | 可能是低费节奏或发现/随机资源牌。 | 由 parser 的真实 action 决定；Discover/randomness 不预填。 | 未确认。 |
| 掌声雷动 / `ETC_372` (2) | 抽一张牌；本回合每打出一种不同类型的牌，重复抽牌。 | 牌型混合、任务推进后的资源补充；先用海盗/随从/武器再扩大价值。 | 资源动作，通常后置于场面建立；需按当前牌型计数排序。 | DB 高；类型计数/手牌上限待实测。 |
| 海盗藏品 / `BT_124` (1) | 抽一张武器牌，使其 +1/+1。 | 找前锋战斧/海盗之锚/锈蚀鱼叉；配合钩拳的同回合武器条件。 | 武器缺口时升权；已有合适武器时避免无意义替换。 | DB 高；weapon replacement 待实测。 |
| 港口匪徒 / `SW_029` (2) | 战吼：抽一张海盗牌。 | 低费站场并找海盗，推进开进码头。 | 普通节奏动作；在 P0 开局器后按抽牌/站场价值。 | DB 高；抽到牌与 hand cap 待实测。 |
| 炸药工程师 / `CAP_104`（截图标记 2） | 本地 DB/parser 未找到 CAP_104。 | 卡名/截图不能证明 battlecry、炮击或目标效果。 | 不开放 generic opaque fallback；只接受真实 parser action。 | ID 来自已有模型映射，效果未确认。 |
| 空降歹徒 / `DRG_056` (2) | 使用海盗牌后，从牌库召唤两个 1/1 海盗（按本地文本摘要）。 | 低费海盗链、经销商加攻、炮随机射击、扩大任务进度。 | 有经销商/炮时提高；board-full 时必须由通用 legality 过滤。 | DB 高；召唤数量/位置待实测。 |
| 船载火炮 / `GVG_075` (2) | 每当召唤海盗，随机对一个敌人造成 2 点伤害。 | 本套最强开局发动机；和任何海盗召唤、帕奇斯、空降歹徒联动。 | P0；永远先下。和宝藏经销商同时在手时船载火炮先下。 | DB 高；随机目标/伤害是否被 parser 接受待实测。 |
| 血帆征兵员 / `VAC_430`（截图未清晰显示复制数） | 战吼：发现一张海盗牌。 | 补充手牌、任务/炮/经销商链；发现优先找低费海盗或武器联动。 | 中高资源 prior；Discover 候选不得由启发式伪造。 | DB 高；Discover action 未确认。 |
| 误炸 / `WW_348`（截图未清晰显示复制数） | 对随机敌人依次造成 3、2、1 点伤害，并带有炮击/目标文本约束。 | 清场、补刀、邪翼蝠动态减费；随机结果对 MCTS 方差很高。 | 只有 parser target/action 才执行；低血量时先评估 lethal/risk。 | DB 高；随机 target、过量伤害待实测。 |
| 钩手拖曳 / `CAP_105`（截图标记 2） | 本地 DB/parser 未找到 CAP_105。 | 可能是钩手/武器/目标联动，不能按卡名猜。 | unknown action fail-closed；不制造 generated card。 | ID 来自已有模型映射，效果未确认。 |
| 南海船长 / `NEW1_027` (2) | 你的其他海盗获得 +1/+1。 | 多海盗场面、炮/经销商铺场后放大 board；不应给自己算自身光环。 | 其他海盗存在时升权；在已有攻击/炮触发顺序中由搜索决定。 | DB 高；光环更新时序待实测。 |
| 海关执法者 / `VAC_440` (2) | 战吼：对手手牌中的随机海盗牌费用增加 2。 | 对海盗对局或可识别手牌有价值；不是纯 aggro face 动作。 | 低于能立即产生场面/伤害的海盗；对手信息不完整时降置信度。 | DB 高；对手手牌可见性/费用修改待实测。 |
| 海盗之锚 / `DRG_025` (1) | 英雄攻击后，从牌库抽一张海盗牌。 | 装备后连续英雄攻击，补充海盗资源；与前锋战斧规则不同。 | 无存活武器时高；已有武器时谨慎，避免错误 replacement。 | DB 高；攻击触发/替换待实测。 |
| 粗暴的猢狲 / `VAC_938` (2) | 按用户确认的实际语义：打出时，使当时场上的其他 Pirate minions +1/+1；不包含触发者自身。第二张同名猢狲属于其他 Pirate，会被 buff。 | 只影响已在场的其他海盗；手牌 Pirate、非 Pirate 和触发者自身不计入。后续新下海盗不继承这次一次性 Battlecry。 | 打出时按场面兑现 +1/+1；不再把猢狲当作攻击事件临时 aura。攻击 reward 读取已经物化的 stats。 | ID/本地文本存在冲突；本轮按用户确认语义修正并用 offline state tests 锁定，真实 parser/Battlecry 仍待实测。 |
| 钩拳-3000型 / `CORE_NX2_028` (2) | 英雄攻击后，获得 4 点护甲并抽一张牌。 | 当回合能打武器或已经装备武器时价值大；兼顾生存和资源。 | 最好仅在同回合可打武器/已装备武器时；无可用随从时才裸上战场。 | DB 高；武器攻击、抽牌、护甲 transition 待实测。 |
| 雷纳索尔王子 / `CORE_REV_018`（1，传奇星标） | 起始生命值为 40。 | 40 张牌构筑许可/长局生命缓冲；不是战场节奏牌。 | 不把它当普通海盗联动；构筑 metadata 与实战起始血量需核验。 | DB 高；截图 card ID 可能为 `REV_018`，需运行时 identity 确认。 |
| 前锋战斧 / `BAR_844`（截图未清晰显示复制数） | 3/3 武器；英雄攻击并消灭随从后抽一张牌。 | 只用来击杀随从触发抽牌；对手英雄血量 >=10 禁止直接打脸。 | hero-weapon attack 放在可行攻击序列最后；非击杀随从线负 prior/零 effect reward；英雄血量 <10 才评估打脸，斩杀优先。 | DB 高，在线卡页可核对；通用 AttackAction 无 target 字段，weapon wear/trigger 待实测。 |
| 狂暴邪翼蝠 / `YOD_032` (2) | 本回合对手每受到 1 点伤害，本牌费用减少 1。 | 炮击/误炸/英雄攻击造成伤害后低费落地。 | 伤害已经发生或可可靠预测时升权；不要预估随机炮击为确定减费。 | DB 高；动态费用和 random damage 待实测。 |
| 克罗雷船长 / `CAP_106`（传奇星标） | 5 费 4/5 Warrior Pirate；你的 Cannoneers 额外射击；战吼召唤两个 1/1 Cannoneers。 | 只有有足够空位且炮/炮兵已形成时才值得；召唤两个 token 需要至少 2 格，但本策略按用户要求保守要求空位 >=3。 | `freeSlots < 3` 是 hard legality，不是低 prior；3/7 格可选。 | 在线卡面确认；本地 DB/parser 未确认，token action/额外射击待实测。 |

## priority 约定

## 本轮离线价值核对

当前 deck 的保守价值理解以“可观察状态变化优先、未知 parser 效果 fail-closed”为准：

| 卡牌 / ID | 当前模型价值理解 | 保守边界 |
|---|---|---|
| 前锋战斧 / `BAR_844` | 不是普通 face weapon；优先寻找可击杀随从并兑现抽牌，攻击本身后置。 | `AttackAction` 无 target 字段，因此用 clone delta 分类；对手英雄血量 `>=10` 硬禁打脸，未知目标禁用，非击杀随从不给 kill reward；武器耐久只在模拟 hook 显式消耗一次。 |
| 克罗雷船长 / `CAP_106` | 5 费大幅扩展炮兵/海盗场面，只有 token 空位和炮联动都值得时才有高价值。 | `freeSlots < 3` 是 hard legality，不是低 prior；CAP parser、两个 token 的位置和额外炮击未确认，不开放 opaque fallback。 |
| 粗暴的猢狲 / `VAC_938` | 打出时把 +1/+1 物化到当时其他场上 Pirate，攻击 reward 直接读取物化后的 stats。 | 不给触发者自身、非 Pirate、手牌 Pirate 或之后才打出的 Pirate；第二张同名且 entityId 不同的猢狲是合法目标。一次性 Battlecry 与本地 DB 文本冲突，真实 parser 仍待验证。 |
| Warrior 英雄技能 / `HERO_01bp` | 主要是 Armor Up 的生存兜底，不应抢占海盗、武器、攻击或联动动作。 | 使用 `isDeferredAction` + 极低 prior 后置，不做全局 hard ban；只有技能可用、没有其他有价值动作时解除后置，法力不足不制造动作。 |

完整 26 个可见卡名的逐卡价值、ID、priority 和待实测项仍以本报告上方逐卡表为准；本节只固定本轮四张高风险语义牌。

- **P0 / hard mandatory**：从搜索候选中排除其他动作。当前只有“可支付船载火炮”最高；首回合“开进码头”是 deadline，且在没有 P0 炮时 mandatory；没有炮/首回合任务时，宝藏经销商是下一层 mandatory 开局器。
- **高 prior**：只改变 action prior/访问顺序，不保证选择，也不等于合法性。英雄攻击、资源、联动和斩杀仍由 MCTS 比较。
- **deferred / near-last**：英雄技能和前锋战斧非击杀攻击在仍有其他有价值动作时后置；只有真实候选集扫描后才允许成为最后手段。
- **hard legality**：`MctsDecisionModel.isActionLegal` 是新增的默认开放扩展点；本模型只用它硬过滤 CAP_106 空位和前锋战斧不合法打脸/未知目标。它不能替代 parser 的 target legality，也不应推广到其他职业。

## 全局决策树

1. **起手**：保留低费海盗/武器链，直接换掉海盗帕奇斯；优先寻找船载火炮、宝藏经销商、开进码头、能形成武器的牌。不要因 Patches 的牌面强度把它留在手中。
2. **每次动作前重扫**：先检查可支付的船载火炮；有则只考虑船载火炮。否则首回合检查开进码头；无首回合 deadline 时检查宝藏经销商。每次召唤、抽牌、武器变化、随机伤害后重新生成候选。
3. **武器/炮/海盗铺场**：炮优先落地，随后让海盗召唤触发炮击；经销商在炮后或无炮时落地。南海船长、粗暴猢狲只在有其他海盗能兑现 reward 时提高价值。克罗雷必须先检查空位 >=3。
4. **解场/打脸**：先处理对手 lethal threat；普通攻击按击杀、保留场面和 lethal 比较。前锋战斧对手英雄血量 >=10 禁止打脸，只能打随从，且放在其他动作之后；攻击不能击杀时不得当作抽牌/高价值 kill line。血量 <10 才允许脸部攻击，若为 lethal 则优先。
5. **抽牌/资源**：掌声雷动、海盗藏品、港口匪徒、血帆征兵员、锚、钩拳分别依赖牌型、武器、海盗、Discover、英雄攻击；效果未由 parser 确认时不能人工制造 generated/Discover action。
6. **结束回合前**：再扫描攻击、武器、召唤触发、任务进度、斩杀、对手可见 lethal 和英雄技能；只有没有其他合法且有价值动作时才 END_TURN/Armor Up。结束回合后等待状态 fingerprint 并重新扫描。

## 通用 MCTS 与 card-specific 边界

可通用化的是 parser-backed action generation、mana/board-full/taunt legality、候选 prior、clone rollout、状态 fingerprint、END_TURN、dispatch/return/confirmed/unconfirmed 区分。高风险 hard-code 是炮/经销商/任务的顺序、Patches mulligan、前锋战斧目标/血线/最后攻击约束、CAP_106 三空位门槛、南海/猢狲事件 reward 和英雄技能 near-last。随机炮击、Discover、generated cards、board-full token placement、weapon replacement 和 parser unknown actions 必须继续 fail-closed。

补充语义边界：南海船长仍按 ongoing aura 计算；粗暴猢狲不再按攻击事件临时加攻，而是在 PlayAction 的模拟结果中只对当时已在场、且 entityId 不同的 Pirate minions 物化一次性 +1/+1。这样第二张同名猢狲会被第一张/当前张视为“其他 Pirate”，触发者自身、非 Pirate 和手牌 Pirate 不会被计算。

## Implementation readiness

当前结论：**offline implementation checkpoint ready；release/E2E not ready**。

已实现并隔离在本分支的内容：Pirate Warrior model/strategy、默认开放的 action legality hook、前锋战斧的血线/目标/击杀/耐久模拟规则、CAP_106 三空位硬门槛、Warrior hero power 后置、南海/猢狲 reward、golden cases 与 gotcha ledger。新增测试覆盖 hero health 10/9、lethal、目标血量等于/高于武器伤害、多个目标、无目标、耐久 1/3、Crowley 空位 0/1/2/3/7、only-power/insufficient mana。

发布前还需要：真实 parser card identity/action trace；weapon replacement/wear 与 BAR_844 draw trigger；CAP_106 token/extra-shot；炮击随机目标；Discover/generated card；任务计数；board-full placement；accepted/unconfirmed fingerprint；以及不与其他任务共享安装的 release mutex。当前轮按协作约束不 build/deploy，故不能宣称安装完成。

## Handoff evidence

最终更新：Hozen 语义修正后的离线验证为 Golden 19/19 + Model 5/5 = 24/24，reactor `BUILD SUCCESS`；此前的 22/22 是战斧/CAP/英雄技能 checkpoint。该数字仍不构成真实 E2E 或 release evidence。

- 本分支：`codex/pirate-warrior-mcts-followup`
- 前一实现 checkpoint：`0ef9d9f3`；gotcha ledger checkpoint：`ac1f0134`
- 本轮前一提交：`682f8e7b Correct Pirate Warrior Hozen battlecry model`；本轮新增“猢狲不对后续才打出的 Pirate 形成持续光环”的反例测试后，需以新的收尾 commit/hash 为准。
- 上游对照：[Hearthstone-Script](https://github.com/HaozhanSun/Hearthstone-Script.git)，`origin/main` 对照 hash `942b09d8a693542202d0af6682d73d13b3cf97a5`；基线 `origin/master` 为 `c89e0b9ba1919b99c3535a5e780826a7830a3520`。
