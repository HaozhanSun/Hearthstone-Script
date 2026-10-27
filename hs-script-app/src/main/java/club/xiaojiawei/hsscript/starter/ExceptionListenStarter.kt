package club.xiaojiawei.hsscript.starter

import club.xiaojiawei.hsscript.core.Core
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScreenStateRecovery
import club.xiaojiawei.hsscript.status.ScreenRecoveryCapturePurposePolicy
import club.xiaojiawei.hsscript.status.TaskManager
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.hsscriptbase.config.LISTEN_LOG_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.isFalse
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * @author 肖嘉威
 * @date 2024/9/28 21:58
 */
class ExceptionListenStarter : AbstractStarter() {

    private var errorScheduledFuture: ScheduledFuture<*>? = null
    private var uncertainIdleProbeAttempts = 0
    private var serverUnavailableSince = 0L
    private var serverUnavailableIdentity: IdleRestartGuardPolicy.ScreenIdentity? = null
    private var serverUnavailableLastRestartAt = 0L
    private var serverUnavailableRestartCount = 0
    private var serverUnavailableAlerted = false
    private var unknownScreenSince = 0L
    private var unknownScreenIdentity: IdleRestartGuardPolicy.UnknownScreenIdentity? = null
    private var unknownScreenLastRestartAt = 0L

    init {
        TaskManager.addTask(this)
    }

    private fun closeListener() {
        errorScheduledFuture?.let {
            it.isDone.isFalse {
                it.cancel(true)
                errorScheduledFuture = null
            }
        }
    }

    override fun execStart() {
        closeListener()
        if (System.getProperty("hs.script.e2e") == "true") {
            log.info { "E2E运行：禁用空闲重启器，避免匹配等待阶段重启真实对局" }
            return
        }
        log.info { "开始监听异常情况" }
        Core.lastActiveTime = System.currentTimeMillis()
        uncertainIdleProbeAttempts = 0
        serverUnavailableSince = 0L
        serverUnavailableIdentity = null
        serverUnavailableAlerted = false
        unknownScreenSince = 0L
        unknownScreenIdentity = null
        errorScheduledFuture = LISTEN_LOG_THREAD_POOL.scheduleWithFixedDelay({
            if (PauseStatus.isPause || !WorkTimeListener.working) {
                closeListener()
                return@scheduleWithFixedDelay
            }
            val idleTime = ConfigUtil.getLong(ConfigEnum.IDLE_MAXIMUM_TIME)
            val now = System.currentTimeMillis()
            val idleForMs = now - Core.lastActiveTime
            val shouldProbe = idleForMs > idleTime * 60_000L ||
                idleForMs >= ScreenRecoveryCapturePurposePolicy.IDLE_CLIENT_PROBE_AFTER_MS ||
                serverUnavailableSince > 0L
            val observed = if (shouldProbe) {
                runCatching { ScreenStateRecovery.observeFreshScreenForWatchdog(idleForMs) }
                    .onFailure { error ->
                        log.warn(error) {
                            "IDLE_RESTART_SCREEN_PROBE_FAILED idleForMs=$idleForMs " +
                                "uncertainProbeAttempts=$uncertainIdleProbeAttempts"
                        }
                    }
                    .getOrNull()
            } else {
                null
            }
            val observedScreen = observed?.screen
            if (observedScreen.equals("UNKNOWN", ignoreCase = true)) {
                val currentObservation = observed ?: return@scheduleWithFixedDelay
                val currentIdentity = IdleRestartGuardPolicy.UnknownScreenIdentity(
                    screen = currentObservation.screen,
                    pid = currentObservation.pid,
                    hwnd = currentObservation.hwnd,
                    perceptualFingerprint = currentObservation.perceptualFingerprint,
                )
                val first = unknownScreenIdentity
                val decision = IdleRestartGuardPolicy.evaluateUnknownScreen(
                    first = first,
                    current = currentIdentity,
                    stableForMs = if (first == null) 0L else now - unknownScreenSince,
                    currentSessionReady = currentObservation.currentSessionReady ||
                        ScreenStateRecovery.isCurrentGameSessionReadyForStart(),
                    activeGame = WarEx.inWar,
                    terminal = GameUtil.isTerminalGameState(),
                    noActiveMatchmakingEvidence = currentObservation.noActiveMatchmakingEvidence,
                    nowMs = now,
                    lastRestartAtMs = unknownScreenLastRestartAt,
                )
                when (decision) {
                    IdleRestartGuardPolicy.UnknownScreenDecision.RESET_STABILITY -> {
                        unknownScreenIdentity = currentIdentity
                        unknownScreenSince = now
                        log.warn {
                            "IDLE_UNKNOWN_SCREEN_STABILITY_RESET pid=${currentIdentity.pid} hwnd=${currentIdentity.hwnd} " +
                                "fingerprint=${java.lang.Long.toUnsignedString(currentIdentity.perceptualFingerprint, 16)}"
                        }
                    }
                    IdleRestartGuardPolicy.UnknownScreenDecision.WAIT_FOR_STABILITY -> {
                        if (first == null) {
                            unknownScreenIdentity = currentIdentity
                            unknownScreenSince = now
                        }
                        log.info {
                            "IDLE_UNKNOWN_SCREEN_WAIT stableForMs=${if (first == null) 0L else now - unknownScreenSince} " +
                                "requiredMs=${IdleRestartGuardPolicy.UNKNOWN_SCREEN_STABILITY_MS} " +
                                "pid=${currentIdentity.pid} hwnd=${currentIdentity.hwnd}"
                        }
                    }
                    IdleRestartGuardPolicy.UnknownScreenDecision.RESTART_CLIENT -> {
                        log.error {
                            "IDLE_UNKNOWN_SCREEN_RESTART_AUTHORIZED pid=${currentIdentity.pid} hwnd=${currentIdentity.hwnd} " +
                                "stableForMs=${now - unknownScreenSince} currentSessionReady=false " +
                                "matchmakingEvidence=false action=RESTART_CLIENT_NO_INPUT_NO_PAUSE"
                        }
                        unknownScreenIdentity = null
                        unknownScreenSince = 0L
                        unknownScreenLastRestartAt = now
                        Core.lastActiveTime = now
                        Core.restartFromIdleWatchdog()
                    }
                    IdleRestartGuardPolicy.UnknownScreenDecision.DEFER_ACTIVE_SESSION,
                    IdleRestartGuardPolicy.UnknownScreenDecision.DEFER_ACTIVE_MATCHMAKING,
                    IdleRestartGuardPolicy.UnknownScreenDecision.DEFER_UNVERIFIED_MATCHMAKING -> {
                        unknownScreenIdentity = null
                        unknownScreenSince = 0L
                        log.warn {
                            "IDLE_UNKNOWN_SCREEN_RESTART_DEFERRED reason=${decision.name.lowercase()} " +
                                "currentSessionReady=${currentObservation.currentSessionReady} " +
                                "matchmakingEvidence=${!currentObservation.noActiveMatchmakingEvidence} " +
                                "inWar=${WarEx.inWar} terminal=${GameUtil.isTerminalGameState()}"
                        }
                    }
                }
                return@scheduleWithFixedDelay
            } else if (unknownScreenIdentity != null) {
                unknownScreenIdentity = null
                unknownScreenSince = 0L
            }
            if (observedScreen == "SERVER_UNAVAILABLE") {
                val currentObservation = observed ?: return@scheduleWithFixedDelay
                val currentIdentity = IdleRestartGuardPolicy.ScreenIdentity(
                    screen = currentObservation.screen,
                    pid = currentObservation.pid,
                    hwnd = currentObservation.hwnd,
                )
                if (serverUnavailableIdentity == null ||
                    serverUnavailableIdentity?.let { it.pid != currentIdentity.pid || it.hwnd != currentIdentity.hwnd } == true
                ) {
                    serverUnavailableIdentity = currentIdentity
                    serverUnavailableSince = now
                }
                val sessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart()
                val activeGame = WarEx.inWar
                val terminal = sessionReady && GameUtil.isTerminalGameState()
                val serverDecision = IdleRestartGuardPolicy.evaluateServerUnavailable(
                    first = serverUnavailableIdentity,
                    current = currentIdentity,
                    stableForMs = now - serverUnavailableSince,
                    currentSessionReady = sessionReady,
                    activeGame = activeGame,
                    terminal = terminal,
                    noActiveMatchmakingEvidence = currentObservation.noActiveMatchmakingEvidence,
                    nowMs = now,
                    lastRestartAtMs = serverUnavailableLastRestartAt,
                    restartCount = serverUnavailableRestartCount,
                )
                when (serverDecision) {
                    IdleRestartGuardPolicy.ServerUnavailableDecision.WAIT_FOR_STABILITY -> log.info {
                        "SERVER_UNAVAILABLE_RESTART_WAIT pid=${currentObservation.pid} hwnd=${currentObservation.hwnd} " +
                            "stableForMs=${now - serverUnavailableSince} " +
                            "requiredMs=${IdleRestartGuardPolicy.SERVER_UNAVAILABLE_STABILITY_MS}"
                    }
                    IdleRestartGuardPolicy.ServerUnavailableDecision.DEFER_ACTIVE_SESSION -> {
                        log.warn {
                            "SERVER_UNAVAILABLE_RESTART_DEFERRED reason=current-session-log-ready " +
                                "activeGame=$activeGame terminal=$terminal " +
                                "pid=${currentObservation.pid} hwnd=${currentObservation.hwnd}"
                        }
                        SystemUtil.notice("Hearthstone is showing a server error while a current game session is logged. No restart or input was sent.")
                    }
                    IdleRestartGuardPolicy.ServerUnavailableDecision.DEFER_ACTIVE_MATCHMAKING -> {
                        serverUnavailableIdentity = null
                        serverUnavailableSince = 0L
                        Core.lastActiveTime = now
                    }
                    IdleRestartGuardPolicy.ServerUnavailableDecision.ALERT_OPERATOR -> {
                        if (!serverUnavailableAlerted) {
                            serverUnavailableAlerted = true
                            log.error {
                                "SERVER_UNAVAILABLE_RESTART_BUDGET_EXHAUSTED " +
                                    "attempts=$serverUnavailableRestartCount max=${IdleRestartGuardPolicy.SERVER_UNAVAILABLE_MAX_RESTARTS} " +
                                    "action=NO_INPUT_NO_AUTO_PAUSE_CONTINUE_MONITORING"
                            }
                            SystemUtil.notice(
                                "Hearthstone still shows the Yogg server outage page after " +
                                    "$serverUnavailableRestartCount bounded restart attempts. " +
                                    "No input was sent and automation remains running; monitoring continues.",
                            )
                        }
                    }
                    IdleRestartGuardPolicy.ServerUnavailableDecision.RESTART_CLIENT -> {
                        log.error {
                            "SERVER_UNAVAILABLE_RESTART_AUTHORIZED pid=${currentObservation.pid} hwnd=${currentObservation.hwnd} " +
                                "stableForMs=${now - serverUnavailableSince} currentSessionReady=false " +
                                "matchmaking=false action=${IdleRestartGuardPolicy.SERVER_UNAVAILABLE_RESTART_ACTION}"
                        }
                        serverUnavailableIdentity = null
                        serverUnavailableSince = 0L
                        serverUnavailableLastRestartAt = now
                        serverUnavailableRestartCount++
                        serverUnavailableAlerted = false
                        Core.lastActiveTime = now
                        Core.restartFromIdleWatchdog()
                    }
                }
                return@scheduleWithFixedDelay
            } else if (serverUnavailableIdentity != null) {
                serverUnavailableIdentity = null
                serverUnavailableSince = 0L
                serverUnavailableAlerted = false
            }
            val initialDecision =
                IdleRestartGuardPolicy.evaluate(
                    idleForMs = idleForMs,
                    idleLimitMs = idleTime * 60_000L,
                    observedScreen = observedScreen,
                    uncertainProbeAttempts = uncertainIdleProbeAttempts,
                )
            val decision = if (
                initialDecision == IdleRestartGuardPolicy.Decision.DEFER_UNCERTAIN_SCREEN &&
                observedScreen.equals("HOME", ignoreCase = true)
            ) {
                val revalidatedScreen = runCatching {
                    ScreenStateRecovery.observeFreshScreenForWatchdog(idleForMs)?.screen
                }.onFailure { error ->
                    log.warn(error) {
                        "IDLE_RESTART_REVALIDATION_FAILED idleForMs=$idleForMs " +
                            "uncertainProbeAttempts=$uncertainIdleProbeAttempts"
                    }
                }.getOrNull()
                IdleRestartGuardPolicy.evaluate(
                    idleForMs = idleForMs,
                    idleLimitMs = idleTime * 60_000L,
                    observedScreen = observedScreen,
                    uncertainProbeAttempts = uncertainIdleProbeAttempts,
                    revalidatedScreen = revalidatedScreen,
                )
            } else initialDecision
            when (decision) {
                IdleRestartGuardPolicy.Decision.NOT_IDLE -> {
                    uncertainIdleProbeAttempts = 0
                }
                IdleRestartGuardPolicy.Decision.DEFER_ACTIVE_MATCHMAKING -> {
                    uncertainIdleProbeAttempts = 0
                    Core.lastActiveTime = now
                    log.info {
                        "IDLE_RESTART_DEFERRED reason=active-matchmaking-screen " +
                            "screen=$observedScreen idleForMs=$idleForMs powerLogActivity=not-required"
                    }
                }
                IdleRestartGuardPolicy.Decision.DEFER_UNCERTAIN_SCREEN -> {
                    uncertainIdleProbeAttempts =
                        (uncertainIdleProbeAttempts + 1).coerceAtMost(IdleRestartGuardPolicy.MAX_UNCERTAIN_PROBES)
                    log.warn {
                        "IDLE_RESTART_PROBE_DEFERRED reason=uncertain-screen " +
                            "screen=${observedScreen ?: "UNKNOWN"} " +
                            "attempt=$uncertainIdleProbeAttempts/${IdleRestartGuardPolicy.MAX_UNCERTAIN_PROBES} " +
                            "idleForMs=$idleForMs"
                    }
                }
                IdleRestartGuardPolicy.Decision.ALERT_UNCERTAIN_SCREEN -> {
                    uncertainIdleProbeAttempts = IdleRestartGuardPolicy.MAX_UNCERTAIN_PROBES
                    Core.lastActiveTime = now
                    log.error {
                        "IDLE_RESTART_OPERATOR_ALERT reason=screen-unverified " +
                            "screen=${observedScreen ?: "UNKNOWN"} probes=$uncertainIdleProbeAttempts " +
                            "currentSessionReady=${ScreenStateRecovery.isCurrentGameSessionReadyForStart()} " +
                            "action=NO_INPUT_NO_RESTART_NO_AUTO_PAUSE"
                    }
                    SystemUtil.notice(
                        "Hearthstone is running, but the current game screen/session could not be verified. " +
                            "No input or restart was sent. Check for a reconnect dialog and click Start to retry.",
                    )
                }
                IdleRestartGuardPolicy.Decision.RESTART_UNCERTAIN_SCREEN -> {
                    uncertainIdleProbeAttempts = IdleRestartGuardPolicy.MAX_UNCERTAIN_PROBES
                    val sessionReady = ScreenStateRecovery.isCurrentGameSessionReadyForStart()
                    val activeGame = sessionReady && WarEx.inWar
                    val terminal = sessionReady && GameUtil.isTerminalGameState()
                    if (!IdleRestartGuardPolicy.mayRestartUncertainScreen(activeGame, terminal)) {
                        Core.lastActiveTime = now
                        log.warn {
                            "IDLE_RESTART_RECOVERY_DEFERRED reason=active-game-or-terminal " +
                                "screen=${observedScreen ?: "UNKNOWN"} " +
                                "probes=$uncertainIdleProbeAttempts currentSessionReady=$sessionReady " +
                                "inWar=${WarEx.inWar} " +
                                "terminal=$terminal"
                        }
                        SystemUtil.notice(
                            "A reconnect recovery needs attention, but the current game log still confirms an active or terminal game. " +
                                "No client restart was sent; inspect Hearthstone and click Start if needed.",
                        )
                    } else {
                        log.error {
                            "IDLE_RESTART_RECOVERY_EXHAUSTED reason=screen-unverified " +
                                "screen=${observedScreen ?: "UNKNOWN"} " +
                                "probes=$uncertainIdleProbeAttempts action=RESTART_CLIENT_NO_PAUSE " +
                                "working=${WorkTimeListener.working} paused=${PauseStatus.isPause}"
                        }
                        Core.restartFromIdleWatchdog()
                        Core.lastActiveTime = now
                        uncertainIdleProbeAttempts = 0
                    }
                }
                IdleRestartGuardPolicy.Decision.RESTART -> {
                    log.info {
                        "空闲时间超过${idleTime}分钟，准备重启游戏 " +
                            "screen=${observedScreen ?: "UNKNOWN"} " +
                            "uncertainProbeAttempts=$uncertainIdleProbeAttempts"
                    }
                    Core.restartFromIdleWatchdog()
                    Core.lastActiveTime = now
                    uncertainIdleProbeAttempts = 0
                }
            }
        }, 0, 1, TimeUnit.MINUTES)
    }

    override fun stopAll() {
        super.stopAll()
        closeListener()
    }

}
