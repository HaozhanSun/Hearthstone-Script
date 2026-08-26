package club.xiaojiawei.hsscriptbasestrategy.strategy

import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.DEFAULT_WAR_SCORE_CALCULATOR
import club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy

/**
 * MCTS variant for the Pirate Demon Hunter deck.
 *
 * This is intentionally separate from [HsPirateDemonHunterDeckStrategy]: it
 * keeps the deck-specific mulligan, but uses the generic MCTS action search
 * and default battlefield score instead of the hard-coded play-order rules.
 * That makes it possible to compare the two strategies from the UI.
 */
class HsPirateDemonHunterMctsDeckStrategy : MCTSDeckStrategy() {

    private val pirateDemonHunterMulligan = HsPirateDemonHunterDeckStrategy()

    override fun name(): String = "海盗瞎 MCTS"

    override fun description(): String =
        "海盗瞎的通用MCTS策略：使用海盗瞎换牌规则，但由蒙特卡洛树搜索决定出牌和攻击"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.CASUAL, RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.PRACTICE)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-7-pirate-demon-hunter-mcts-97e9-1f4e126cd33b"

    override fun executeChangeCard(cards: HashSet<Card>) {
        // Keep the same working Pirate Demon Hunter mulligan baseline so the
        // experiment compares play decisions rather than changing mulligan.
        pirateDemonHunterMulligan.executeChangeCard(cards)
    }

    override fun executeMCTSOutCard(war: War): List<MCTSArg> {
        val calculator = DEFAULT_WAR_SCORE_CALCULATOR.build()
        val start = System.currentTimeMillis()
        // A full 20k multi-threaded search exhausts the heap because every
        // expansion deep-clones the live War. Keep this comparison strategy
        // bounded and single-threaded so a slow search cannot strand the
        // real turn after the end-turn guard has already found legal actions.
        return listOf(
            MCTSArg(start + 8 * 1000, 1, 0.1, 800, calculator, false, name()),
            MCTSArg(start + 3 * 1000, 1, 0.5, 300, calculator, false, name()),
        )
    }

    override fun executeDiscoverChooseCard(vararg cards: Card): Int = 1
}
