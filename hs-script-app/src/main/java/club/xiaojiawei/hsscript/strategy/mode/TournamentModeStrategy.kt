package club.xiaojiawei.hsscript.strategy.mode

import club.xiaojiawei.hsscript.bean.GameRect
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.listener.log.PowerLogListener
import club.xiaojiawei.hsscript.status.DeckStrategyManager
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.strategy.AbstractModeStrategy
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.GameUtil.reconnectAction
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.bean.LRunnable
import club.xiaojiawei.hsscriptbase.config.EXTRA_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 传统对战
 * @author 肖嘉威
 * @date 2022/11/25 12:39
 */
object TournamentModeStrategy : AbstractModeStrategy<Any?>() {
    val START_RECT: GameRect by lazy { GameRect(0.2586, 0.3459, 0.2706, 0.3794) }

    /** 自动补全不完整套牌确认按钮（新版套牌选择页） */
    val COMPLETE_DECK_CONFIRM_RECT: GameRect by lazy {
        GameRect(-0.1200, -0.0135, 0.0530, 0.1080)
    }

    val ERROR_RECT: GameRect by lazy { GameRect(-0.0397, 0.0325, 0.0856, 0.1249) }

    val CHANGE_MODE_RECT: GameRect by lazy { GameRect(0.2868, 0.3256, -0.4672, -0.4279) }

    val WILD_MODE_RECT: GameRect by lazy { GameRect(-0.3072, -0.1755, -0.1924, -0.0647) }

    val STANDARD_MODE_RECT: GameRect by lazy { GameRect(-0.0691, 0.0612, -0.2446, -0.1097) }

    val CASUAL_MODE_RECT: GameRect by lazy { GameRect(0.1622, 0.2886, -0.1817, -0.0540) }

    @Deprecated("已被移除")
    val CLASSIC_MODE_RECT: GameRect by lazy { GameRect(-0.4278, -0.2557, -0.1769, 0.0014) }

    val TOURNAMENT_MODE_RECT: GameRect by lazy { GameRect(-0.0790, 0.0811, -0.2090, -0.1737) }

    /**
     * 顶栏有限时借用套牌时使用
     */
    val FIRST_DECK_RECT_LIMIT: GameRect by lazy { GameRect(-0.4072, -0.2516, -0.0696, 0.0139) }

    val PREV_DECK_PAGE: GameRect by lazy { GameRect(-0.4755, -0.4473, -0.0302, 0.0095) }

    val BACK_RECT: GameRect by lazy { GameRect(0.4041, 0.4575, 0.4083, 0.4410) }

    val CANCEL_RECT: GameRect by lazy { GameRect(-0.0251, 0.0530, 0.3203, 0.3802) }

    override fun wantEnter() {
        val seed = RandomUtil.rerollSeed()
        log.info { "本局共享随机种子：$seed" }
        addWantEnterTask(
            EXTRA_THREAD_POOL.scheduleWithFixedDelay(
                LRunnable {
                    log.info { "模式入口轮询：当前模式=${Mode.currMode}，暂停=${PauseStatus.isPause}" }
                    if (PauseStatus.isPause) {
                        cancelAllWantEnterTasks()
                    } else if (Mode.currMode == ModeEnum.HUB) {
                        log.info { "点击传统对战入口" }
                        TOURNAMENT_MODE_RECT.lClick()
                    } else if (Mode.currMode == ModeEnum.GAME_MODE) {
                        cancelAllWantEnterTasks()
                        BACK_RECT.lClick()
                    } else {
                        cancelAllWantEnterTasks()
                    }
                },
                randomizedModeEntryDelay(),
                randomizedModeEntryInterval(),
                TimeUnit.MILLISECONDS,
            ),
        )
    }

    override fun afterEnter(t: Any?) {
        if (WorkTimeListener.canWork()) {
            val deckStrategy = DeckStrategyManager.currentDeckStrategy
            if (deckStrategy == null) {
                SystemUtil.notice("未配置卡组策略")
                log.warn { "未配置卡组策略" }
                PauseStatus.isPause = true
                return
            }
            val runMode = DeckStrategyManager.currentRunMode
            if (runMode === RunModeEnum.STANDARD
                || runMode === RunModeEnum.WILD
                || runMode === RunModeEnum.CASUAL
                || runMode === RunModeEnum.TWIST
                || runMode === RunModeEnum.CLASSIC
            ) {
                if (!runMode.isEnable) {
                    log.warn { "${runMode.comment}未启用" }
                    PauseStatus.isPause = false
                    return
                }
                if (!PowerLogListener.checkPowerLogSize()) {
                    return
                }
                SystemUtil.delayShort()
                clickModeChangeButton()
                SystemUtil.delayShort()
                changeMode(runMode)
                SystemUtil.delayShort()
                selectDeck(deckStrategy)
                SystemUtil.delayShort()
                startMatching()
            } else {
                addEnteredTask(
                    EXTRA_THREAD_POOL.scheduleWithFixedDelay(
                        LRunnable {
                            if (PauseStatus.isPause) {
                                cancelAllEnteredTasks()
                            } else if (Mode.currMode === ModeEnum.TOURNAMENT) {
                                BACK_RECT.lClick()
                            } else {
                                cancelAllEnteredTasks()
                            }
                        },
                        0,
                        200,
                        TimeUnit.MILLISECONDS,
                    ),
                )
            }
        }
    }

    private fun clickModeChangeButton() {
        log.info { "点击切换模式按钮" }
        CHANGE_MODE_RECT.lClick()
    }

    private fun changeMode(runModeEnum: RunModeEnum) {
        when (runModeEnum) {
            RunModeEnum.CLASSIC, RunModeEnum.TWIST -> changeModeToClassic()
            RunModeEnum.STANDARD -> changeModeToStandard()
            RunModeEnum.WILD -> changeModeToWild()
            RunModeEnum.CASUAL -> changeModeToCasual()
            else -> throw RuntimeException("不支持此模式：" + runModeEnum.comment)
        }
    }

    fun selectDeck(deckStrategy: DeckStrategy) {
//        val decks: List<Deck> = DECKS
//        for (i in decks.indices.reversed()) {
//            val d = decks[i]
//            if (d.code == deckStrategy.deckCode() || d.name == deckStrategy.name()) {
//                log.debug { "找到套牌:" + deckStrategy.name() }
//                break
//            }
//        }
        log.info { "选择套牌" }

        PREV_DECK_PAGE.lClick()
        SystemUtil.delayTiny()
        GameUtil.lClickDeckPos(3)
    }

    private fun changeModeToClassic() {
        log.info { "切换至经典模式" }
        CLASSIC_MODE_RECT.lClick()
    }

    private fun changeModeToStandard() {
        log.info { "切换至标准模式" }
        STANDARD_MODE_RECT.lClick()
    }

    private fun changeModeToWild() {
        log.info { "切换至狂野模式" }
        WILD_MODE_RECT.lClick()
    }

    private fun changeModeToCasual() {
        log.info { "切换至休闲模式" }
        CASUAL_MODE_RECT.lClick()
    }

    fun startMatching() {
        log.info { "开始匹配" }
        START_RECT.lClick()
        // Hearthstone may show a modal when the selected deck is missing cards.
        // The green confirmation is harmless when the modal is absent and
        // prevents the matchmaking flow from stopping at that modal.
        SystemUtil.delayLong()
        log.info { "尝试确认自动补全套牌弹窗" }
        COMPLETE_DECK_CONFIRM_RECT.lClick()
        SystemUtil.delayShortMedium()
        log.info { "重试确认自动补全套牌弹窗" }
        COMPLETE_DECK_CONFIRM_RECT.lClick()
        SystemUtil.delayShortMedium()
        log.info { "确认补全后再次点击开始" }
        START_RECT.lClick()
        generateTimer()
        scheduleMatchmakingDialogRecovery()
    }

    /**
     * A failed opponent connection can leave a modal confirmation dialog on
     * top of the tournament screen long before the normal matchmaking timeout
     * fires.  It is safe to probe this centered button while the mode is still
     * TOURNAMENT, but never keep probing after a game starts.
     */
    private fun scheduleMatchmakingDialogRecovery() {
        var attempts = 0
        lateinit var recoveryTask: ScheduledFuture<*>
        recoveryTask = EXTRA_THREAD_POOL.scheduleWithFixedDelay(
            LRunnable {
                if (PauseStatus.isPause || Mode.currMode !== ModeEnum.TOURNAMENT) {
                    recoveryTask.cancel(false)
                    return@LRunnable
                }
                attempts++
                if (attempts > 20) {
                    log.info { "匹配入口弹窗恢复结束：未再检测到可处理对话框" }
                    recoveryTask.cancel(false)
                    return@LRunnable
                }
                log.info { "匹配入口弹窗恢复尝试 #$attempts" }
                ERROR_RECT.lClickCenter(false)
            },
            800,
            1_000,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * 生成匹配失败时兜底的定时器
     */
    private fun generateTimer() {
        cancelAllEnteredTasks()
        val matchMaximumTime = if (System.getProperty("hs.script.e2e") == "true") {
            minOf(ConfigUtil.getLong(ConfigEnum.MATCH_MAXIMUM_TIME), 45L)
        } else {
            ConfigUtil.getLong(ConfigEnum.MATCH_MAXIMUM_TIME)
        }
        addEnteredTask(
            EXTRA_THREAD_POOL.schedule(
                LRunnable {
                    if (PauseStatus.isPause || Thread.currentThread().isInterrupted || Mode.currMode === ModeEnum.GAMEPLAY) {
                        cancelAllEnteredTasks()
                    } else {
                        log.info { "匹配失败，再次匹配中" }
                        SystemUtil.notice("匹配失败，再次匹配中")
//                点击取消匹配按钮
                        CANCEL_RECT.lClick()
                        SystemUtil.delayLong()
//                点击错误按钮
                        ERROR_RECT.lClick()
                        SystemUtil.delayShort()
                        reconnectAction()
                        val seed = RandomUtil.rerollSeed()
                        log.info { "重新匹配，共享随机种子：$seed" }
                        afterEnter(null)
                    }
                },
                matchMaximumTime,
                TimeUnit.SECONDS,
            ),
        )
    }
}
