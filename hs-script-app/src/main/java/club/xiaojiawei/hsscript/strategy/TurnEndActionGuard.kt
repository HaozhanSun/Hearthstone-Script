package club.xiaojiawei.hsscript.strategy

import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.listener.log.PowerActionEvidence
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import club.xiaojiawei.hsscriptcardsdk.mcts.CardTimingPolicy
import club.xiaojiawei.hsscriptcardsdk.mcts.MctsDecisionModel
import club.xiaojiawei.hsscriptcardsdk.status.WAR
import java.awt.Color
import java.awt.Rectangle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Last safety pass before the common actuator clicks End Turn.
 *
 * Strategies are allowed to be selective, but the actuator must not finish
 * a turn while the live model still exposes a basic legal action.  This is a
 * bounded, cheap re-check and deliberately uses CardAction's real input path
 * so a log line is accompanied by an actual click/drag attempt.
 */
object TurnEndActionGuard {

    private const val MAX_GUARD_PASSES = 3
    /**
     * The button can remain yellow during the client animation after a legal
     * action has already been consumed.  Do not let that visual-only state
     * strand the turn forever when the authoritative WAR snapshot is clear.
     * UNKNOWN remains fail-closed because it can also mean screen capture
     * failed.
     */
    private const val MAX_CLEAR_YELLOW_RETRIES = 2
    private const val HERO_ATTACK_CONFIRMATION_ATTEMPTS = 2
    private const val HERO_EVIDENCE_MAX_AGE_MS = 8_000L
    private const val HERO_POWER_CONFIRMATION_WAIT_MS = 5_000L
    private const val HERO_POWER_CONFIRMATION_POLL_MS = 200
    private const val HERO_POWER_INPUT_ATTEMPTS_PER_TURN = 2
    private const val HAND_ACTION_MAX_PER_PASS = 8
    private const val HAND_STATE_SETTLE_TIMEOUT_MS = 4_000L
    private const val HAND_STATE_SETTLE_POLL_MS = 200

    internal enum class EndTurnButtonColor {
        GREEN,
        YELLOW,
        UNKNOWN,
    }

    internal data class EndTurnColorSample(
        val red: Int,
        val green: Int,
        val blue: Int,
    )

    private val screenRobot by lazy { java.awt.Robot() }

    @Volatile
    private var lastHeroAttackAttemptKey: String? = null

    @Volatile
    private var lastHeroPowerAttemptKey: String? = null

    @Volatile
    private var heroPowerAttemptCount = 0

    internal data class TurnEndObservation(
        val attackableMinions: Int,
        val playableHandCards: Int,
        val attackableHero: Boolean,
        val playableHeroPower: Boolean = false,
    ) {
        val blocksEndTurn: Boolean
            get() = attackableMinions > 0 || playableHandCards > 0 || attackableHero || playableHeroPower
    }

    /** Snapshot used by MCTS without dispatching legacy fallback actions. */
    internal data class MctsTurnEndInspection(
        val observation: TurnEndObservation,
        val buttonColor: EndTurnButtonColor,
        val safeToEnd: Boolean,
    ) {
        val requiresReplan: Boolean
            get() = observation.blocksEndTurn
    }

    internal fun shouldBlockEndTurn(observation: TurnEndObservation): Boolean =
        observation.blocksEndTurn

    /**
     * MCTS owns action selection. This method only observes live legality and
     * the end-turn affordance so the actuator can request a bounded replan.
     */
    internal fun inspectForMctsEndTurn(
        clearYellowRetries: Int = 0,
        decisionModel: MctsDecisionModel? = null,
    ): MctsTurnEndInspection {
        if (!WAR.isMyTurn || PauseStatus.isPause) {
            return MctsTurnEndInspection(
                TurnEndObservation(0, 0, false, false),
                EndTurnButtonColor.UNKNOWN,
                safeToEnd = true,
            )
        }

        val observation = observe(decisionModel)
        val buttonColor = if (observation.blocksEndTurn) {
            EndTurnButtonColor.UNKNOWN
        } else {
            readEndTurnButtonColor()
        }
        val safe = !observation.blocksEndTurn &&
            (allowsEndTurnForColor(buttonColor) ||
                shouldAllowClearStateFallback(buttonColor, clearYellowRetries))
        log.info {
            "MCTS_TURN_END_OBSERVE turn=${WAR.me.turn} actions=${observation.blocksEndTurn} " +
                "minions=${observation.attackableMinions} hand=${observation.playableHandCards} " +
                "hero=${observation.attackableHero} heroPower=${observation.playableHeroPower} " +
                "buttonColor=$buttonColor clearYellowRetries=$clearYellowRetries safe=$safe"
        }
        return MctsTurnEndInspection(observation, buttonColor, safe)
    }

    /**
     * Hearthstone can expose weapon attack before it has merged that attack
     * into the hero entity. Keep the hero's other legality checks, but do not
     * lose a legal hero attack during that parser/update window.
     */
    internal fun hasWeaponBackedHeroAttack(
        weaponAttack: Int,
        heroCanAttackIgnoringAttack: Boolean,
    ): Boolean = weaponAttack > 0 && heroCanAttackIgnoringAttack

    internal fun isHandCardPlayable(
        cardType: CardTypeEnum,
        cost: Int,
        usableMana: Int,
        boardFull: Boolean,
    ): Boolean =
        cost <= usableMana && !(boardFull && (cardType === CardTypeEnum.MINION || cardType === CardTypeEnum.LOCATION))

    internal fun isHeroPowerPlayable(
        powerCost: Int,
        usableMana: Int,
        canPower: Boolean,
    ): Boolean = canPower && powerCost <= usableMana

    /**
     * Returns true only when the live state is clear to finish.  If an input
     * is rejected or state does not refresh, this returns false and the
     * caller deliberately does not click End Turn.
     */
    fun ensureSafeToEndTurn(): Boolean {
        // WAR is the authoritative live-game state.  Mode.currMode can lag
        // briefly during replay/recovery and must not bypass the last safety
        // pass while the live model already says it is our turn.
        if (!WAR.isMyTurn || PauseStatus.isPause) {
            return true
        }

        var pass = 0
        var clearYellowRetries = 0
        while (pass < MAX_GUARD_PASSES) {
            pass++
            if (!WAR.isMyTurn || PauseStatus.isPause) return true

            val before = observe()
            log.info {
                "TURN_END_GUARD_SCAN pass=$pass turn=${WAR.me.turn} " +
                    "minionsCanAttack=${before.attackableMinions} " +
                    "playableHand=${before.playableHandCards} " +
                    "heroCanAttack=${before.attackableHero} " +
                    "heroPowerPlayable=${before.playableHeroPower} " +
                    "heroPower=${WAR.me.playArea.power?.let(::displayName) ?: "none"} " +
                    "heroPowerCost=${WAR.me.playArea.power?.cost ?: 0} " +
                    "heroAttack=${WAR.me.playArea.hero?.atc ?: 0} " +
                    "weapon=${WAR.me.playArea.weapon?.let(::displayName) ?: "none"} " +
                    "weaponAttack=${WAR.me.playArea.weapon?.atc ?: 0} " +
                    "mana=${WAR.me.usableResource}"
            }
            if (!shouldBlockEndTurn(before)) {
                val buttonColor = readEndTurnButtonColor()
                if (!allowsEndTurnForColor(buttonColor)) {
                    if (shouldAllowClearStateFallback(buttonColor, clearYellowRetries)) {
                        log.warn {
                            "TURN_END_COLOR_GUARD_FALLBACK_ALLOWED pass=$pass turn=${WAR.me.turn} " +
                                "color=$buttonColor clearYellowRetries=$clearYellowRetries " +
                                "reason=authoritative-state-clear-after-bounded-visual-retries"
                        }
                        return true
                    }
                    if (buttonColor == EndTurnButtonColor.YELLOW) clearYellowRetries++
                    log.warn {
                        "TURN_END_COLOR_GUARD_BLOCKED pass=$pass turn=${WAR.me.turn} " +
                            "color=$buttonColor reason=button-indicates-action-available"
                    }
                    SystemUtil.delayShortMedium()
                    continue
                }
                log.info {
                        "TURN_END_GUARD_RESULT pass=$pass turn=${WAR.me.turn} " +
                        "dispatched=0 remainingMinions=0 remainingPlayableHand=0 " +
                        "remainingHeroAttack=false remainingHeroPower=false safe=true " +
                        "endTurnButtonColor=$buttonColor"
                }
                return true
            }

            var dispatched = 0
            dispatched += playAvailableHandCards()
            if (!WAR.isMyTurn || PauseStatus.isPause) return true
            dispatched += attackAvailableMinions()
            dispatched += useHeroPowerIfAvailable()
            dispatched += attackHeroIfAvailable()

            SystemUtil.delayShortMedium()
            val after = observe()
            log.info {
                "TURN_END_GUARD_RESULT pass=$pass turn=${WAR.me.turn} " +
                    "dispatched=$dispatched remainingMinions=${after.attackableMinions} " +
                    "remainingPlayableHand=${after.playableHandCards} " +
                    "remainingHeroAttack=${after.attackableHero} " +
                    "remainingHeroPower=${after.playableHeroPower}"
            }
            if (!shouldBlockEndTurn(after)) {
                val buttonColor = readEndTurnButtonColor()
                if (!allowsEndTurnForColor(buttonColor)) {
                    if (shouldAllowClearStateFallback(buttonColor, clearYellowRetries)) {
                        log.warn {
                            "TURN_END_COLOR_GUARD_FALLBACK_ALLOWED pass=$pass turn=${WAR.me.turn} " +
                                "color=$buttonColor clearYellowRetries=$clearYellowRetries " +
                                "reason=authoritative-state-clear-after-dispatch-and-bounded-visual-retries"
                        }
                        return true
                    }
                    if (buttonColor == EndTurnButtonColor.YELLOW) clearYellowRetries++
                    log.warn {
                        "TURN_END_COLOR_GUARD_BLOCKED pass=$pass turn=${WAR.me.turn} " +
                            "color=$buttonColor reason=button-indicates-action-available-after-dispatch"
                    }
                    SystemUtil.delayShortMedium()
                    continue
                }
                log.info {
                    "TURN_END_GUARD_CLEAR_AFTER_DISPATCH pass=$pass turn=${WAR.me.turn} " +
                        "endTurnButtonColor=$buttonColor"
                }
                return true
            }
            if (dispatched == 0) break
        }

        val blocked = observe()
            log.warn {
                "TURN_END_BLOCKED turn=${WAR.me.turn} " +
                    "minionsCanAttack=${blocked.attackableMinions} " +
                    "playableHand=${blocked.playableHandCards} " +
                    "heroCanAttack=${blocked.attackableHero} " +
                    "heroPowerPlayable=${blocked.playableHeroPower} " +
                    "heroPower=${WAR.me.playArea.power?.let(::displayName) ?: "none"} " +
                    "heroPowerCost=${WAR.me.playArea.power?.cost ?: 0} " +
                    "heroAttack=${WAR.me.playArea.hero?.atc ?: 0} " +
                    "weapon=${WAR.me.playArea.weapon?.let(::displayName) ?: "none"} " +
                    "weaponAttack=${WAR.me.playArea.weapon?.atc ?: 0} " +
                    "mana=${WAR.me.usableResource}"
        }
        return false
    }

    private fun observe(decisionModel: MctsDecisionModel? = null): TurnEndObservation {
        val me = WAR.me
        return TurnEndObservation(
            attackableMinions = me.playArea.cards.count { it.canAttack() },
            playableHandCards = me.handArea.cards.count { card ->
                isLiveHandActionable(card, me, decisionModel)
            },
            playableHeroPower = me.playArea.power?.let { power ->
                isHeroPowerPlayable(
                    powerCost = power.cost,
                    usableMana = me.usableResource,
                    canPower = power.canPower(),
                )
            } == true,
            attackableHero = me.playArea.hero?.let { hero ->
                val modelSaysAttackable = hero.canAttack() || hasWeaponBackedHeroAttack(
                    weaponAttack = me.playArea.weapon?.atc ?: 0,
                    heroCanAttackIgnoringAttack = hero.canAttack(ignoreAtc = true),
                )
                modelSaysAttackable && !PowerActionEvidence.heroAttackRejectedRecently(
                    hero.entityId,
                    System.currentTimeMillis() - HERO_EVIDENCE_MAX_AGE_MS,
                )
            } == true,
        )
    }

    private fun isLiveHandActionable(
        card: Card,
        me: Player,
        decisionModel: MctsDecisionModel?,
    ): Boolean {
        if (!isHandCardPlayable(
                    cardType = card.cardType,
                    cost = card.cost,
                    usableMana = me.usableResource,
                    boardFull = me.playArea.isFull,
                )
        ) return false

        // A timing policy is an intentional "not yet" decision, not a reason
        // to keep the turn-end replan loop alive.  The model-specific check
        // covers opaque and deferred actions that the generic guard cannot
        // infer from CardAction alone.
        if (CardTimingPolicy.shouldDefer(card, WAR)) return false
        if (decisionModel?.shouldDefer(card, WAR) == true) return false
        if (decisionModel?.isDeferredAction(
                club.xiaojiawei.hsscriptcardsdk.bean.PlayAction({}, {}, card), WAR,
            ) == true
        ) return false

        val parsedActionAvailable = runCatching {
            card.action.generatePlayActions(WAR, me).isNotEmpty()
        }.getOrDefault(false)
        val opaqueActionAvailable = decisionModel?.canCreateOpaqueAction(card, WAR) == true
        return parsedActionAvailable || opaqueActionAvailable
    }

    private fun attackAvailableMinions(): Int {
        if (!WAR.isMyTurn || PauseStatus.isPause) return 0
        var dispatched = 0
        val minions = WAR.me.playArea.cards.toList()
        for (minion in minions) {
            if (!WAR.isMyTurn || PauseStatus.isPause) break
            val liveMinion = WAR.me.playArea.cards.firstOrNull { it.entityId == minion.entityId } ?: continue
            if (!liveMinion.canAttack()) continue
            val target = attackTargetFor(liveMinion)
            if (target == null) {
                log.warn {
                    "TURN_END_GUARD_MINION_SKIPPED attacker=${displayName(liveMinion)} " +
                        "entityId=${liveMinion.entityId} reason=NO_LEGAL_TARGET"
                }
                continue
            }
            val accepted = runCatching {
                liveMinion.action.attack(target, isPause = false) != null
            }.getOrElse { error ->
                log.error(error) {
                    "TURN_END_GUARD_MINION_FAILED attacker=${displayName(liveMinion)} " +
                        "target=${displayName(target)}"
                }
                false
            }
            if (accepted) dispatched++
            log.info {
                "TURN_END_GUARD_MINION_ATTACK attacker=${displayName(liveMinion)} " +
                    "target=${displayName(target)} accepted=$accepted"
            }
        }
        return dispatched
    }

    private fun useHeroPowerIfAvailable(): Int {
        if (!WAR.isMyTurn || PauseStatus.isPause) return 0
        val power = WAR.me.playArea.power ?: return 0
        val mana = WAR.me.usableResource
        if (!isHeroPowerPlayable(power.cost, mana, power.canPower())) return 0

        // Hero power is a fallback action.  Never spend the action point on it
        // while a real hand card is currently playable.  This second check is
        // intentionally performed after playAvailableHandCards(): Coin can
        // change the live mana total while the Power.log listener is catching
        // up, and the old implementation could jump straight here with a
        // newly playable two-mana minion still in hand.
        val playableNonCoin = WAR.me.handArea.cards.firstOrNull { card ->
            !card.isCoinCard && isHandCardPlayable(
                cardType = card.cardType,
                cost = card.cost,
                usableMana = mana,
                boardFull = WAR.me.playArea.isFull,
            ) && !CardTimingPolicy.shouldDefer(card, WAR)
        }
        if (playableNonCoin != null) {
            log.info {
                "TURN_END_GUARD_HERO_POWER_DEFERRED " +
                    "power=${displayName(power)} cost=${power.cost} mana=$mana " +
                    "reason=playable-hand-card-remains " +
                    "card=${displayName(playableNonCoin)} cardId=${playableNonCoin.cardId} " +
                    "cardCost=${playableNonCoin.cost}"
            }
            return 0
        }

        val attemptKey = "${WAR.me.turn}:${power.entityId}"
        if (lastHeroPowerAttemptKey != attemptKey) {
            lastHeroPowerAttemptKey = attemptKey
            heroPowerAttemptCount = 0
        }
        if (heroPowerAttemptCount >= HERO_POWER_INPUT_ATTEMPTS_PER_TURN) {
            log.warn {
                "TURN_END_GUARD_HERO_POWER_UNCONFIRMED_SUPPRESSED turn=${WAR.me.turn} " +
                    "power=${displayName(power)} reason=attempt-limit-reached attempts=${heroPowerAttemptCount}"
            }
            return 0
        }
        heroPowerAttemptCount++
        val attemptStartedAt = System.currentTimeMillis()

        log.info {
            "TURN_END_GUARD_HERO_POWER_ATTEMPT power=${displayName(power)} " +
                "cardId=${power.cardId} cost=${power.cost} mana=$mana " +
                "attempt=${heroPowerAttemptCount}/${HERO_POWER_INPUT_ATTEMPTS_PER_TURN}"
        }
        val accepted = runCatching {
            power.action.safePower(isPause = false) != null
        }.getOrElse { error ->
            log.error(error) {
                "TURN_END_GUARD_HERO_POWER_FAILED power=${displayName(power)} cardId=${power.cardId}"
            }
            false
        }
        val confirmed = if (accepted) {
            waitForHeroPowerConfirmation(power.entityId, attemptStartedAt)
        } else {
            false
        }
        if (!confirmed && PauseStatus.isPause) {
            // F2 may arrive after the click but before Power.log is consumed.
            // Do not leave a stale per-turn suppression key that prevents the
            // same action from continuing after the user resumes the script.
            lastHeroPowerAttemptKey = null
            heroPowerAttemptCount = 0
        }
        log.info {
            "TURN_END_GUARD_HERO_POWER_DISPATCH power=${displayName(power)} " +
                "cardId=${power.cardId} cost=${power.cost} manaBefore=$mana " +
                "accepted=$accepted confirmed=$confirmed"
        }
        return if (confirmed) 1 else 0
    }

    internal fun classifyEndTurnButtonColor(samples: List<EndTurnColorSample>): EndTurnButtonColor {
        var green = 0
        var yellow = 0
        for (sample in samples) {
            val hueAndBrightness = Color.RGBtoHSB(
                sample.red.coerceIn(0, 255),
                sample.green.coerceIn(0, 255),
                sample.blue.coerceIn(0, 255),
                null,
            )
            val hue = hueAndBrightness[0]
            val saturation = hueAndBrightness[1]
            val brightness = hueAndBrightness[2]
            if (saturation < 0.18f || brightness < 0.18f) continue
            when {
                hue in 0.20f..0.50f -> green++
                hue in 0.06f..0.20f -> yellow++
            }
        }
        return when {
            green > yellow && green > 0 -> EndTurnButtonColor.GREEN
            yellow > green && yellow > 0 -> EndTurnButtonColor.YELLOW
            else -> EndTurnButtonColor.UNKNOWN
        }
    }

    internal fun allowsEndTurnForColor(color: EndTurnButtonColor): Boolean =
        color == EndTurnButtonColor.GREEN

    internal fun shouldAllowClearStateFallback(
        color: EndTurnButtonColor,
        clearYellowRetries: Int,
    ): Boolean =
        color == EndTurnButtonColor.YELLOW && clearYellowRetries >= MAX_CLEAR_YELLOW_RETRIES

    private fun readEndTurnButtonColor(): EndTurnButtonColor {
        return runCatching {
            val rect = GameUtil.END_TURN_RECT.getRelativeRect()
            val x = rect.x.roundToInt()
            val y = rect.y.roundToInt()
            val width = max(1, rect.width.roundToInt())
            val height = max(1, rect.height.roundToInt())
            val image = screenRobot.createScreenCapture(Rectangle(x, y, width, height))
            val samples = mutableListOf<EndTurnColorSample>()
            for (xRatio in listOf(0.25, 0.5, 0.75)) {
                for (yRatio in listOf(0.25, 0.5, 0.75)) {
                    val pixel = Color(image.getRGB((width * xRatio).roundToInt(), (height * yRatio).roundToInt()))
                    samples += EndTurnColorSample(pixel.red, pixel.green, pixel.blue)
                }
            }
            val color = classifyEndTurnButtonColor(samples)
            log.info {
                "TURN_END_COLOR_SAMPLE color=${color} rect=($x,$y,${width}x${height}) samples=${samples.size}"
            }
            color
        }.getOrElse { error ->
            // Unknown cannot authorize an End Turn click. This keeps a failed
            // screen capture from silently bypassing the visual guardrail.
            log.warn(error) { "TURN_END_COLOR_SAMPLE_FAILED fallback=UNKNOWN" }
            EndTurnButtonColor.UNKNOWN
        }
    }

    private fun waitForHeroPowerConfirmation(entityId: String, startedAt: Long): Boolean {
        val deadline = System.currentTimeMillis() + HERO_POWER_CONFIRMATION_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (PowerActionEvidence.heroPowerConfirmedRecently(entityId, startedAt)) {
                log.info {
                    "TURN_END_GUARD_HERO_POWER_CONFIRMED entityId=$entityId " +
                        "waitedMs=${System.currentTimeMillis() - startedAt}"
                }
                // Power.log and the in-memory model are consumed by separate
                // listener work. Give the model one short scheduling window
                // after authoritative confirmation before checking hero attack.
                SystemUtil.delayShortMedium()
                return true
            }
            if (PauseStatus.isPause || !WAR.isMyTurn) return false
            SystemUtil.delay(HERO_POWER_CONFIRMATION_POLL_MS)
        }
        log.warn {
            "TURN_END_GUARD_HERO_POWER_UNCONFIRMED entityId=$entityId " +
                "waitedMs=$HERO_POWER_CONFIRMATION_WAIT_MS"
        }
        return false
    }

    private fun playAvailableHandCards(): Int {
        var dispatched = 0
        val unconfirmedCardEntityIds = mutableSetOf<String>()
        repeat(HAND_ACTION_MAX_PER_PASS) {
            if (!WAR.isMyTurn || PauseStatus.isPause) return dispatched
            val me = WAR.me
            val playableCards = me.handArea.cards.filter { card ->
                !card.isUncertain && isHandCardPlayable(
                    cardType = card.cardType,
                    cost = card.cost,
                    usableMana = me.usableResource,
                    boardFull = me.playArea.isFull,
                )
            }
            val directCard = playableCards.firstOrNull {
                !it.isCoinCard && !CardTimingPolicy.shouldDefer(it, WAR) &&
                    it.entityId !in unconfirmedCardEntityIds
            }
            val coinCard = playableCards.firstOrNull { it.isCoinCard && it.entityId !in unconfirmedCardEntityIds }
            val card = directCard ?: coinCard?.takeIf { hasCoinPayoff(me) } ?: run {
                if (coinCard != null) {
                    log.info {
                        "TURN_END_GUARD_COIN_DEFERRED " +
                            "card=${displayName(coinCard)} cardId=${coinCard.cardId} " +
                            "mana=${me.usableResource} reason=no-non-coin-hand-card-unlocked-by-coin"
                    }
                }
                return dispatched
            }
            val liveCard = me.handArea.cards.firstOrNull { it.entityId == card.entityId } ?: return dispatched
            log.info {
                "TURN_END_GUARD_CARD_ATTEMPT card=${displayName(liveCard)} " +
                    "cardId=${liveCard.cardId} cost=${liveCard.cost} mana=${me.usableResource} " +
                    "reason=${if (liveCard.isCoinCard) "coin-unlocks-hand-card" else "direct-playable-hand-card"}"
            }
            val accepted = runCatching {
                liveCard.action.autoPower(CARD_DATA_TRIE[liveCard.cardId])
                true
            }.getOrElse { error ->
                log.error(error) {
                    "TURN_END_GUARD_CARD_FAILED card=${displayName(liveCard)} cardId=${liveCard.cardId}"
                }
                false
            }
            log.info {
                "TURN_END_GUARD_CARD_DISPATCH card=${displayName(liveCard)} " +
                    "cardId=${liveCard.cardId} inputSent=$accepted"
            }
            if (!accepted) return dispatched
            val confirmed = waitForHandCardResolution(liveCard.entityId)
            log.info {
                "TURN_END_GUARD_CARD_RESULT card=${displayName(liveCard)} " +
                    "cardId=${liveCard.cardId} inputSent=true confirmed=$confirmed"
            }
            if (!confirmed) {
                unconfirmedCardEntityIds += liveCard.entityId
                log.warn {
                    "TURN_END_GUARD_CARD_UNCONFIRMED card=${displayName(liveCard)} " +
                        "cardId=${liveCard.cardId} reason=entity-remained-in-hand-after-input"
                }
                return dispatched
            }
            dispatched++
        }
        return dispatched
    }

    /**
     * Coin is a resource-conversion action, not a turn-ending action.  Use it
     * only when it immediately makes a currently unplayable non-Coin hand card
     * legal.  In particular, a one-mana hero power is not a valid Coin payoff.
     */
    internal fun hasCoinPayoff(player: Player): Boolean {
        val currentMana = player.usableResource
        val coinMana = currentMana + 1
        return player.handArea.cards.any { card ->
            !card.isCoinCard &&
                !CardTimingPolicy.shouldDefer(card, player.war) &&
                !isHandCardPlayable(
                    cardType = card.cardType,
                    cost = card.cost,
                    usableMana = currentMana,
                    boardFull = player.playArea.isFull,
                ) &&
                isHandCardPlayable(
                    cardType = card.cardType,
                    cost = card.cost,
                    usableMana = coinMana,
                    boardFull = player.playArea.isFull,
                )
        }
    }

    private fun waitForHandCardResolution(entityId: String): Boolean {
        val deadline = System.currentTimeMillis() + HAND_STATE_SETTLE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (PauseStatus.isPause) return false
            if (!WAR.isMyTurn) return true
            if (WAR.me.handArea.cards.none { it.entityId == entityId }) return true
            SystemUtil.delay(HAND_STATE_SETTLE_POLL_MS)
        }
        return !WAR.isMyTurn || WAR.me.handArea.cards.none { it.entityId == entityId }
    }

    internal fun handCardActionConfirmed(entityId: String, remainingHandEntityIds: Set<String>): Boolean =
        entityId !in remainingHandEntityIds

    private fun attackHeroIfAvailable(): Int {
        if (!WAR.isMyTurn || PauseStatus.isPause) return 0
        val hero = WAR.me.playArea.hero ?: return 0
        val target = chooseHeroAttackTarget(
            rivalMinions = WAR.rival.playArea.cards,
            rivalHero = WAR.rival.playArea.hero,
        ) ?: return 0
        val attemptKey = "${WAR.me.turn}:${hero.entityId}:${target.entityId}"
        if (lastHeroAttackAttemptKey == attemptKey) {
            log.warn {
                "TURN_END_GUARD_HERO_UNCONFIRMED_SUPPRESSED turn=${WAR.me.turn} " +
                    "hero=${displayName(hero)} target=${displayName(target)} " +
                    "reason=already-attempted-this-turn"
            }
            return 0
        }
        val weapon = WAR.me.playArea.weapon
        val canAttack = hero.canAttack() || hasWeaponBackedHeroAttack(
            weaponAttack = weapon?.atc ?: 0,
            heroCanAttackIgnoringAttack = hero.canAttack(ignoreAtc = true),
        )
        if (!canAttack) return 0
        lastHeroAttackAttemptKey = attemptKey
        var inputAccepted = false
        val attemptStartedAt = System.currentTimeMillis()
        var confirmed = false
        for (attempt in 0 until HERO_ATTACK_CONFIRMATION_ATTEMPTS) {
            if (attempt > 0) SystemUtil.delayMedium()
            inputAccepted = runCatching {
                if (target.cardType === CardTypeEnum.HERO) {
                    hero.action.attackHero(isPause = false) != null
                } else {
                    hero.action.attack(target, isPause = false) != null
                }
            }.getOrElse { error ->
                log.error(error) { "TURN_END_GUARD_HERO_FAILED hero=${displayName(hero)}" }
                false
            }
            if (!inputAccepted) continue
            SystemUtil.delayMedium()
            if (hero.isExhausted ||
                PowerActionEvidence.heroAttackConfirmedRecently(hero.entityId, attemptStartedAt)
            ) {
                confirmed = true
                log.info {
                    "TURN_END_GUARD_HERO_CONFIRMED hero=${displayName(hero)} " +
                        "attempt=${attempt + 1} exhausted=${hero.isExhausted}"
                }
                break
            }
            if (PowerActionEvidence.heroAttackRejectedRecently(hero.entityId, attemptStartedAt)) {
                log.warn {
                    "TURN_END_GUARD_HERO_REJECTED hero=${displayName(hero)} " +
                        "attempt=${attempt + 1} reason=REQ_ATTACK_GREATER_THAN_0"
                }
                break
            }
        }
        log.info {
            "TURN_END_GUARD_HERO_ATTACK hero=${displayName(hero)} " +
                "target=${displayName(target)} " +
                "targetType=${target.cardType} " +
                "attack=${hero.atc} weapon=${weapon?.let(::displayName) ?: "none"} " +
                "weaponAttack=${weapon?.atc ?: 0} inputAccepted=$inputAccepted confirmed=$confirmed"
        }
        return if (confirmed) 1 else 0
    }

    /**
     * A hero attack must obey the same taunt rule as a minion attack.  The
     * previous implementation always called attackHero(), which bypassed the
     * live taunt target and left the turn-end guard retrying a face attack that
     * Hearthstone correctly rejected.
     */
    internal fun chooseHeroAttackTarget(
        rivalMinions: List<Card>,
        rivalHero: Card?,
    ): Card? =
        rivalMinions.firstOrNull { it.isTaunt && it.canBeAttacked() }
            ?: rivalHero?.takeIf { it.canBeAttacked() }

    private fun attackTargetFor(attacker: Card): Card? {
        val enemyMinion = WAR.rival.playArea.cards.firstOrNull { it.isTaunt && it.canBeAttacked() }
            ?: WAR.rival.playArea.cards.firstOrNull { it.canBeAttacked() }
        if (attacker.isAttackableByRush) return enemyMinion
        return enemyMinion ?: WAR.rival.playArea.hero?.takeIf { it.canBeAttacked() }
    }

    private fun displayName(card: Card): String =
        card.entityName.ifBlank { card.cardId.ifBlank { card.entityId } }
}
