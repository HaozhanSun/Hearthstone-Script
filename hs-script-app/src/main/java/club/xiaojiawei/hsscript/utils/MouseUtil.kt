package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.bean.isDiscoverCardThread
import club.xiaojiawei.hsscript.bean.single.WarEx
import club.xiaojiawei.hsscript.config.DRIVER_LOCK
import club.xiaojiawei.hsscript.dll.CSystemDll
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.MouseControlModeEnum
import club.xiaojiawei.hsscript.listener.WorkTimeListener
import club.xiaojiawei.hsscript.status.Mode
import club.xiaojiawei.hsscript.status.ActionDispatchGate
import club.xiaojiawei.hsscript.status.PauseStatus
import club.xiaojiawei.hsscript.status.ScriptStatus
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.enums.ModeEnum
import club.xiaojiawei.hsscriptbase.util.RandomUtil
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinUser.SW_RESTORE
import com.sun.jna.platform.win32.Kernel32
import java.awt.Robot
import java.awt.MouseInfo
import java.awt.event.InputEvent
import java.awt.Point
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 鼠标工具类
 * @author 肖嘉威
 * @date 2022/11/24 11:18
 */
object MouseUtil {

    private fun e2eInputEnabled(): Boolean =
        System.getProperty("hs.script.e2e.real-input") == "true" ||
            System.getProperty("hs.script.safe-native") == "true"

    /**
     * Java AWT Robot is useful as a safe fallback, but Hearthstone may ignore
     * its desktop events when the client and JVM have different input
     * integrity/focus state.  The supervised E2E runner can opt into the
     * project's native click path, which is the same path used by normal
     * gameplay, while keeping Robot as the default safe path.
     */
    private fun e2eNativeClickEnabled(): Boolean =
        System.getProperty("hs.script.e2e.native-click") == "true"

    private fun hwndIsValid(hwnd: HWND?): Boolean =
        if (e2eInputEnabled()) hwnd != null else hwnd != null && User32.INSTANCE.IsWindow(hwnd)

    /**
     * AWT Robot crosses into native desktop input.  Creating and using a new
     * Robot for every click made the E2E run vulnerable to a native call that
     * never returned (the last observed process disappearance stopped between
     * CLICK_ATTEMPT and ROBOT_SENT).  Keep one Robot on one dedicated thread,
     * bound the call.  The E2E harness deliberately avoids the native DLL
     * fallback because a native helper failure can terminate the JVM without
     * running any Java shutdown hook.
     */
    private val e2eRobotExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "E2E Robot Input").apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
                log.error(error) { "E2E_INPUT_ROBOT_THREAD_FAILED thread=${thread.name}" }
            }
        }
    }

    private val e2eRobotLock = Any()
    private val e2eRobot: Robot by lazy { Robot() }

    /**
     * Robot sends input to the foreground window.  The script window can stay
     * above Hearthstone while the game is loading, so a successful Robot
     * mousePress is not enough evidence that Hearthstone received the click.
     * Restore/activate the game immediately before each E2E click and keep
     * the result in the trace so focus failures are distinguishable from bad
     * coordinates.
     */
    private fun focusE2EWindow(hwnd: HWND): Boolean = runCatching {
        if (!User32.INSTANCE.IsWindow(hwnd)) {
            log.warn { "E2E_INPUT_ROBOT_FOREGROUND_UNAVAILABLE hwnd=$hwnd reason=invalid-window" }
            return@runCatching false
        }

        User32.INSTANCE.ShowWindow(hwnd, SW_RESTORE)

        // Windows may reject SetForegroundWindow from a worker thread when
        // another application currently owns the foreground lock. Temporarily
        // attach this thread to that foreground thread, request activation,
        // then verify the actual foreground HWND before sending input.
        val foreground = User32.INSTANCE.GetForegroundWindow()
        val currentThread = Kernel32.INSTANCE.GetCurrentThreadId()
        val foregroundThread = if (foreground != null) {
            User32.INSTANCE.GetWindowThreadProcessId(foreground, null)
        } else {
            0
        }
        val attached = foregroundThread != 0 &&
            foregroundThread != currentThread &&
            User32.INSTANCE.AttachThreadInput(
                WinDef.DWORD(currentThread.toLong()),
                WinDef.DWORD(foregroundThread.toLong()),
                true,
            )
        try {
            User32.INSTANCE.BringWindowToTop(hwnd)
            val requested = User32.INSTANCE.SetForegroundWindow(hwnd)
            SystemUtil.delay(35)
            val actual = User32.INSTANCE.GetForegroundWindow()
            val focused = actual != null &&
                Pointer.nativeValue(actual.pointer) == Pointer.nativeValue(hwnd.pointer)
            log.info {
                "E2E_INPUT_ROBOT_FOREGROUND_RESULT hwnd=$hwnd requested=$requested " +
                    "focused=$focused actual=$actual foregroundThread=$foregroundThread attached=$attached"
            }
            focused
        } finally {
            if (attached) {
                User32.INSTANCE.AttachThreadInput(
                    WinDef.DWORD(currentThread.toLong()),
                    WinDef.DWORD(foregroundThread.toLong()),
                    false,
                )
            }
        }
    }.getOrElse { error ->
        log.warn(error) { "E2E_INPUT_ROBOT_FOREGROUND_FAILED hwnd=$hwnd" }
        false
    }

    /**
     * Focus the game before a keyboard fallback. Keyboard events are sent to
     * the real foreground window, so calling Robot.keyPress without this
     * check can silently deliver Return to the script UI or another app.
     */
    internal fun focusWindowForInput(hwnd: HWND?): Boolean =
        hwnd?.let(::focusE2EWindow) ?: false

    /**
     * Send Enter through the same real desktop input path as recovery clicks.
     * AWT Robot can report a successful key event while a full-screen Unity
     * client does not consume it; keep the fallback target explicit and
     * observable for stale result pages.
     */
    internal fun pressEnterForRecovery(): Boolean {
        if (!ActionDispatchGate.allow("recovery-enter")) return false
        if (!e2eInputEnabled()) {
            SystemUtil.sendKey(java.awt.event.KeyEvent.VK_ENTER)
            return true
        }
        val hwnd = ScriptStatus.gameHWND ?: run {
            log.warn { "E2E_RECOVERY_KEY_SKIPPED key=ENTER reason=game-window-missing" }
            return false
        }
        if (!hwndIsValid(hwnd) || !WorkTimeListener.working || PauseStatus.isPause) {
            log.warn {
                "E2E_RECOVERY_KEY_SKIPPED key=ENTER hwnd=$hwnd " +
                    "valid=${hwndIsValid(hwnd)} working=${WorkTimeListener.working} paused=${PauseStatus.isPause}"
            }
            return false
        }
        val lockAcquired = try {
            DRIVER_LOCK.tryLock(3000, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!lockAcquired) {
            log.warn { "E2E_RECOVERY_KEY_SKIPPED key=ENTER reason=input-lock-timeout" }
            return false
        }
        try {
            synchronized(e2eRobotLock) {
                if (!ActionDispatchGate.allow("recovery-enter")) return false
                if (!focusE2EWindow(hwnd)) {
                    log.warn { "E2E_RECOVERY_KEY_SKIPPED key=ENTER hwnd=$hwnd reason=foreground-unconfirmed" }
                    return false
                }
                @Suppress("UNCHECKED_CAST")
                val inputs = WinUser.INPUT().toArray(2) as Array<WinUser.INPUT>
                inputs.forEachIndexed { index, input ->
                    input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
                    input.input.setType(WinUser.KEYBDINPUT::class.java)
                    input.input.ki = WinUser.KEYBDINPUT().apply {
                        wVk = WinDef.WORD(0x0D)
                        wScan = WinDef.WORD(0)
                        dwFlags = WinDef.DWORD(if (index == 0) 0L else 0x0002L)
                        time = WinDef.DWORD(0)
                        dwExtraInfo = com.sun.jna.platform.win32.BaseTSD.ULONG_PTR(0)
                    }
                    input.input.write()
                    input.write()
                }
                val sent = User32.INSTANCE.SendInput(
                    WinDef.DWORD(inputs.size.toLong()),
                    inputs,
                    inputs[0].size(),
                ).toInt()
                val accepted = sent == inputs.size
                log.info { "E2E_RECOVERY_KEY_SENT key=ENTER hwnd=$hwnd accepted=$accepted input=SendInput" }
                return accepted
            }
        } catch (error: Throwable) {
            log.warn(error) { "E2E_RECOVERY_KEY_FAILED key=ENTER hwnd=$hwnd" }
            return false
        } finally {
            DRIVER_LOCK.unlock()
        }
    }

    /**
     * Click a recovery control with a real desktop input event.  Recovery is
     * used when the client has already drifted away from the event-driven
     * state machine, so the normal injected/message path is not a reliable
     * proof that Hearthstone consumed the click.  Keep this method bounded
     * and require the game to be the verified foreground window first.
     */
    internal fun leftButtonClickForRecovery(pos: Point): Boolean {
        if (!ActionDispatchGate.allow("recovery-click")) return false
        val hwnd = ScriptStatus.gameHWND
        if (!e2eInputEnabled() || hwnd == null) {
            leftButtonClick(pos, hwnd)
            return true
        }
        if (!hwndIsValid(hwnd) || !WorkTimeListener.working || PauseStatus.isPause) {
            log.warn {
                "E2E_RECOVERY_CLICK_SKIPPED pos=(${pos.x},${pos.y}) hwnd=$hwnd " +
                    "valid=${hwndIsValid(hwnd)} working=${WorkTimeListener.working} paused=${PauseStatus.isPause}"
            }
            return false
        }
        val lockAcquired = try {
            DRIVER_LOCK.tryLock(3000, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!lockAcquired) {
            log.warn { "E2E_RECOVERY_CLICK_SKIPPED pos=(${pos.x},${pos.y}) reason=input-lock-timeout" }
            return false
        }
        try {
            synchronized(e2eRobotLock) {
                if (!ActionDispatchGate.allow("recovery-click")) return false
                val focused = focusE2EWindow(hwnd)
                if (!focused) {
                    log.warn {
                        "E2E_RECOVERY_CLICK_SKIPPED pos=(${pos.x},${pos.y}) hwnd=$hwnd " +
                            "reason=foreground-unconfirmed"
                    }
                    return false
                }
                // In safe-native mode the client is deliberately borderless
                // and fills the desktop.  GameRect coordinates are therefore
                // already screen coordinates, matching the existing Robot
                // conversion used by this runtime.
                if (!ActionDispatchGate.allow("recovery-click")) return false
                val accepted = sendE2eWindowsClick(Point(pos.x, pos.y))
                log.info {
                    "E2E_RECOVERY_CLICK_SENT pos=(${pos.x},${pos.y}) hwnd=$hwnd " +
                        "accepted=$accepted input=SendInput"
                }
                return accepted
            }
        } catch (error: Throwable) {
            log.warn(error) { "E2E_RECOVERY_CLICK_FAILED pos=(${pos.x},${pos.y}) hwnd=$hwnd" }
            return false
        } finally {
            DRIVER_LOCK.unlock()
        }
    }

    /**
     * The native EVENT path is useful for the normal injected runtime, but it
     * can report success without delivering input when the E2E run deliberately
     * skips injection. Use Java's real Windows input path for that harness and
     * log the client-to-screen conversion so a failed click is diagnosable.
     */
    private fun clickWithE2ERobot(
        pos: Point,
        hwnd: HWND,
        buttonMask: Int = InputEvent.BUTTON1_DOWN_MASK,
    ): Boolean {
        val task: Future<Boolean> = e2eRobotExecutor.submit<Boolean> {
            synchronized(e2eRobotLock) {
                if (!ActionDispatchGate.allow("e2e-click")) return@submit false
                log.info { "E2E_INPUT_ROBOT_BEGIN client=(${pos.x},${pos.y}) hwnd=$hwnd" }
                val foregroundFocused = focusE2EWindow(hwnd)
                if (Thread.currentThread().isInterrupted) {
                    log.info { "E2E_INPUT_ROBOT_CANCELLED_AFTER_FOCUS client=(${pos.x},${pos.y}) hwnd=$hwnd" }
                    return@submit false
                }
                if (!foregroundFocused) {
                    log.warn { "E2E_INPUT_ROBOT_FOREGROUND_UNCONFIRMED hwnd=$hwnd" }
                }
                if (!ActionDispatchGate.allow("e2e-click")) return@submit false
                // The E2E game is deliberately kept borderless/full-screen.
                // Keep the coordinate conversion screen-relative after the
                // foreground request; Hearthstone owns the full client area.
                val windowRect = WinDef.RECT().apply {
                    left = 0
                    top = 0
                    right = java.awt.Toolkit.getDefaultToolkit().screenSize.width
                    bottom = java.awt.Toolkit.getDefaultToolkit().screenSize.height
                }
                val screenPoint = WinDef.POINT().apply {
                    x = windowRect.left + pos.x
                    y = windowRect.top + pos.y
                }
                log.info {
                    "E2E_INPUT_ROBOT_RECT client=(${pos.x},${pos.y}) screen=(${screenPoint.x},${screenPoint.y}) " +
                        "window=(${windowRect.left},${windowRect.top})-(${windowRect.right},${windowRect.bottom})"
                }
                log.info { "E2E_INPUT_ROBOT_FOREGROUND_REQUESTED hwnd=$hwnd confirmed=$foregroundFocused" }
                if (Thread.currentThread().isInterrupted) {
                    log.info { "E2E_INPUT_ROBOT_CANCELLED_BEFORE_MOVE client=(${pos.x},${pos.y}) hwnd=$hwnd" }
                    return@submit false
                }
                val sampleCount = moveRobotAlongCurve(e2eRobot, Point(screenPoint.x, screenPoint.y))
                log.info {
                    "E2E_INPUT_ROBOT_MOVED screen=(${screenPoint.x},${screenPoint.y}) samples=$sampleCount"
                }
                if (Thread.currentThread().isInterrupted) {
                    log.info { "E2E_INPUT_ROBOT_CANCELLED_BEFORE_PRESS client=(${pos.x},${pos.y}) hwnd=$hwnd" }
                    return@submit false
                }
                if (!ActionDispatchGate.allow("e2e-click")) return@submit false
                e2eRobot.apply {
                    mousePress(buttonMask)
                    log.info { "E2E_INPUT_ROBOT_PRESSED" }
                    delay(RandomUtil.getInteractionDelay(35))
                    mouseRelease(buttonMask)
                }
                log.info {
                    "E2E_INPUT_ROBOT_SENT client=(${pos.x},${pos.y}) screen=(${screenPoint.x},${screenPoint.y}) hwnd=$hwnd"
                }
                true
            }
        }

        return try {
            // SetForegroundWindow may take several seconds while Battle.net or
            // a Hearthstone transition owns the foreground lock.  A short
            // timeout permanently disabled the Robot after one transient stall.
            task.get(10000, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            task.cancel(true)
            log.error {
                "E2E_INPUT_ROBOT_TIMEOUT client=(${pos.x},${pos.y}) hwnd=$hwnd; " +
                    "cancelled stale click; next click will retry"
            }
            false
        } catch (error: InterruptedException) {
            // A normal Hearthstone phase transition can interrupt the caller
            // while it is waiting for a click whose target is now stale.  Do
            // not poison the process-wide Robot for the next turn: the input
            // worker remains healthy and future clicks must be allowed to use
            // it again.
            Thread.currentThread().interrupt()
            task.cancel(true)
            log.info {
                "E2E_INPUT_ROBOT_INTERRUPTED client=(${pos.x},${pos.y}) hwnd=$hwnd; " +
                    "preserving Robot for the next phase"
            }
            false
        } catch (error: CancellationException) {
            // The caller may cancel a stale click during a phase transition.
            // Cancellation is not evidence that Robot itself failed.
            log.info {
                "E2E_INPUT_ROBOT_CANCELLED client=(${pos.x},${pos.y}) hwnd=$hwnd; " +
                    "preserving Robot for the next phase"
            }
            false
        } catch (error: ExecutionException) {
            task.cancel(true)
            log.error(error) { "E2E_INPUT_ROBOT_FAILED client=(${pos.x},${pos.y}) hwnd=$hwnd; next click will retry" }
            false
        }
    }

    /**
     * Mulligan cards are selected by a hit-tested overlay. A long curved
     * pointer path is useful for gameplay, but it adds an unnecessary race
     * here: the overlay can repaint while the pointer is travelling and the
     * game can ignore the eventual AWT event. Use a short, exact final move
     * for this phase and record the OS-reported pointer position.
     */
    private fun clickMulliganWithE2ERobot(
        pos: Point,
        hwnd: HWND,
    ): Boolean {
        val task: Future<Boolean> = e2eRobotExecutor.submit<Boolean> {
            synchronized(e2eRobotLock) {
                if (!ActionDispatchGate.allow("mulligan-click")) return@submit false
                log.info { "MULLIGAN_ROBOT_BEGIN target=(${pos.x},${pos.y}) hwnd=$hwnd" }
                val focused = focusE2EWindow(hwnd)
                if (!focused) log.warn { "MULLIGAN_ROBOT_FOREGROUND_UNCONFIRMED hwnd=$hwnd" }
                if (Thread.currentThread().isInterrupted) return@submit false
                if (!ActionDispatchGate.allow("mulligan-click")) return@submit false

                e2eRobot.mouseMove(pos.x, pos.y)
                e2eRobot.waitForIdle()
                e2eRobot.delay(120)
                val actual = MouseInfo.getPointerInfo()?.location
                log.info {
                    "MULLIGAN_ROBOT_POINTER target=(${pos.x},${pos.y}) actual=$actual focused=$focused"
                }
                if (Thread.currentThread().isInterrupted) return@submit false

                // Use the same explicit absolute SendInput sequence as the
                // normal E2E click path.  The mulligan overlay is a separate
                // full-screen hit-test layer; the legacy mouse_event helper
                // can move the cursor successfully while dropping the
                // button transition before Unity sees it.
                if (!ActionDispatchGate.allow("mulligan-click")) return@submit false
                val sent = sendE2eWindowsClick(Point(pos.x, pos.y))
                val after = MouseInfo.getPointerInfo()?.location
                log.info {
                    "MULLIGAN_SENDINPUT_CLICK_SENT target=(${pos.x},${pos.y}) " +
                        "before=$actual after=$after accepted=$sent"
                }
                sent
            }
        }
        return try {
            task.get(5000, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            task.cancel(true)
            log.error(error) { "MULLIGAN_ROBOT_TIMEOUT target=(${pos.x},${pos.y}) hwnd=$hwnd" }
            false
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            task.cancel(true)
            log.warn(error) { "MULLIGAN_ROBOT_INTERRUPTED target=(${pos.x},${pos.y}) hwnd=$hwnd" }
            false
        } catch (error: ExecutionException) {
            log.error(error.cause ?: error) {
                "MULLIGAN_ROBOT_FAILED target=(${pos.x},${pos.y}) hwnd=$hwnd"
            }
            false
        } catch (error: CancellationException) {
            log.warn(error) { "MULLIGAN_ROBOT_CANCELLED target=(${pos.x},${pos.y}) hwnd=$hwnd" }
            false
        }
    }

    /**
     * The mulligan overlay can acknowledge SendInput without consuming it.
     * Use the supervised desktop Robot path for that layer when the E2E
     * harness requests it, so the selection can be verified visually.
     */
    fun leftButtonClickMulligan(pos: Point, hwnd: HWND?): Boolean {
        if (!ActionDispatchGate.allow("mulligan-click")) return false
        if (!e2eInputEnabled() || hwnd == null ||
            System.getProperty("hs.script.e2e.mulligan-robot") != "true"
        ) {
            leftButtonClick(pos, hwnd)
            return true
        }
        val sent = clickMulliganWithE2ERobot(pos, hwnd)
        log.info {
            "MULLIGAN_INPUT_ROBOT_RESULT pos=(${pos.x},${pos.y}) hwnd=$hwnd accepted=$sent"
        }
        if (sent) savePos(pos)
        return sent
    }

    /**
     * The E2E harness deliberately skips DLL injection. In that mode MESSAGE
     * clicks are accepted by the helper but are not consumed by the current
     * Hearthstone UI, so use the real EVENT input path for the harness only.
     */
    private fun effectiveMouseMode(): Int =
        if (e2eNativeClickEnabled()) {
            // Native verification must use the configured helper mode.  The
            // E2E Robot path needs EVENT, but forcing EVENT on the native
            // helper makes MESSAGE-mode clicks no-ops in the Hearthstone hub.
            ConfigExUtil.getMouseControlMode().code
        } else if (System.getProperty("hs.script.e2e.real-input") == "true") {
            MouseControlModeEnum.EVENT.code
        } else {
            ConfigExUtil.getMouseControlMode().code
        }

    var mouseMovePauseStep: Int = ConfigUtil.getInt(ConfigEnum.PAUSE_STEP)

    private val prevPoint = Point(0, 0)

    private fun validatePoint(point: Point?): Boolean =
        point?.let {
            it.x != -1 && it.y != -1
        } == true

    private fun savePos(pos: Point) {
        prevPoint.x = pos.x
        prevPoint.y = pos.y
    }

    /**
     * Build a smooth, non-linear cursor path.  The two control points create
     * the broad curve and the low-frequency lateral term adds subtle jitter
     * without producing sharp, robotic corners.  Every random choice comes
     * from the shared per-run stream.
     */
    private fun buildSmoothMousePath(start: Point, end: Point): List<Point> {
        if (start == end) return listOf(Point(start.x, start.y))

        val dx = (end.x - start.x).toDouble()
        val dy = (end.y - start.y).toDouble()
        val distance = hypot(dx, dy)
        val normalX = -dy / distance
        val normalY = dx / distance
        val sampleSpacing = RandomUtil.getRandom(8, 14)
        val sampleCount = (distance / sampleSpacing).roundToInt().coerceIn(8, 42)
        val maxJitter = max(3, min(24, (distance * 0.12).roundToInt()))
        val control1Offset = RandomUtil.getRandom(-maxJitter, maxJitter)
        val control2Offset = RandomUtil.getRandom(-maxJitter, maxJitter)
        val control1T = RandomUtil.getRandom(28, 42) / 100.0
        val control2T = RandomUtil.getRandom(58, 76) / 100.0
        val phase1 = RandomUtil.getRandom(0.0, 2.0 * PI)
        val phase2 = RandomUtil.getRandom(0.0, 2.0 * PI)
        val localJitter = maxJitter * 0.35

        val control1 = Point(
            (start.x + dx * control1T + normalX * control1Offset).roundToInt(),
            (start.y + dy * control1T + normalY * control1Offset).roundToInt(),
        )
        val control2 = Point(
            (start.x + dx * control2T + normalX * control2Offset).roundToInt(),
            (start.y + dy * control2T + normalY * control2Offset).roundToInt(),
        )

        val path = ArrayList<Point>(sampleCount + 1)
        for (index in 0..sampleCount) {
            if (index == 0) {
                path.add(Point(start.x, start.y))
                continue
            }
            if (index == sampleCount) {
                path.add(Point(end.x, end.y))
                continue
            }

            val rawT = index.toDouble() / sampleCount
            val t = rawT * rawT * (3.0 - 2.0 * rawT)
            val oneMinusT = 1.0 - t
            val bezierX = oneMinusT * oneMinusT * oneMinusT * start.x +
                3.0 * oneMinusT * oneMinusT * t * control1.x +
                3.0 * oneMinusT * t * t * control2.x +
                t * t * t * end.x
            val bezierY = oneMinusT * oneMinusT * oneMinusT * start.y +
                3.0 * oneMinusT * oneMinusT * t * control1.y +
                3.0 * oneMinusT * t * t * control2.y +
                t * t * t * end.y
            val smoothJitter = sin(PI * rawT) * (
                0.55 * sin(2.0 * PI * rawT + phase1) +
                    0.45 * sin(3.0 * PI * rawT + phase2)
                ) * localJitter
            val point = Point(
                (bezierX + normalX * smoothJitter).roundToInt(),
                (bezierY + normalY * smoothJitter).roundToInt(),
            )
            if (path.last() != point) path.add(point)
        }
        return path
    }

    private fun moveNativeAlongCurve(
        start: Point,
        end: Point,
        hwnd: HWND?,
        mouseMode: Int,
    ) {
        val path = buildSmoothMousePath(start, end)
        var previous = path.first()
        path.drop(1).forEach { point ->
            if (previous == point) return@forEach
            if (e2eNativeClickEnabled()) {
                // The E2E runner intentionally uses the standard Win32 input
                // API instead of the project's optional injected DLL.  The
                // latter is disabled in safe-native mode, so its movement
                // routine is a no-op and cannot provide a real cursor path.
                User32.INSTANCE.SetCursorPos(point.x.toLong(), point.y.toLong())
            } else {
                CSystemDll.INSTANCE.simulateHumanMoveMouse(
                    previous.x,
                    previous.y,
                    point.x,
                    point.y,
                    hwnd,
                    mouseMovePauseStep,
                    mouseMode,
                )
            }
            SystemUtil.delay(RandomUtil.getMouseStepDelay())
            previous = point
        }
    }

    /**
     * Send a real Win32 mouse click through SendInput.  Unlike WM_* messages,
     * SendInput is consumed by full-screen Unity clients such as Hearthstone.
     *
     * The previous implementation used SetCursorPos followed immediately by
     * left-down/left-up.  That is enough for the normal board, but the
     * full-screen mulligan overlay can ignore the click because it never sees
     * a mouse-move event over the card.  Emit an explicit absolute move event,
     * let the target process observe that hover, and only then send the button
     * pair.  This mirrors the event sequence produced by a real pointer move
     * and makes the selection visible (red X) before confirmation.
     */
    private fun sendE2eWindowsClick(screenPoint: Point): Boolean {
        if (!ActionDispatchGate.allow("sendinput-click")) return false
        fun configureMouseInput(inputValue: WinUser.INPUT, flags: Int) {
            inputValue.apply {
                type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
                input.setType(WinUser.MOUSEINPUT::class.java)
                input.mi = WinUser.MOUSEINPUT().apply {
                    dx = WinDef.LONG(0)
                    dy = WinDef.LONG(0)
                    mouseData = WinDef.DWORD(0)
                    dwFlags = WinDef.DWORD(flags.toLong())
                    time = WinDef.DWORD(0)
                }
                input.write()
                write()
            }
        }

        fun sendOne(input: WinUser.INPUT): Boolean =
            User32.INSTANCE.SendInput(
                WinDef.DWORD(1),
                arrayOf(input),
                input.size(),
            ).toInt() == 1

        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
        val normalizedX = (screenPoint.x.coerceIn(0, screen.width - 1) * 65535.0 /
            max(1, screen.width - 1)).roundToInt()
        val normalizedY = (screenPoint.y.coerceIn(0, screen.height - 1) * 65535.0 /
            max(1, screen.height - 1)).roundToInt()

        val move = WinUser.INPUT()
        configureMouseInput(move, 0x0001 or 0x8000) // MOUSEEVENTF_MOVE | ABSOLUTE
        move.input.mi.dx = WinDef.LONG(normalizedX.toLong())
        move.input.mi.dy = WinDef.LONG(normalizedY.toLong())
        move.input.write()
        move.write()
        if (!sendOne(move)) return false

        // Give the full-screen overlay one frame to process the hover before
        // the button events arrive.  An interrupted worker must not leave a
        // half-click in flight.
        try {
            Thread.sleep(45)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        if (!ActionDispatchGate.allow("sendinput-click")) return false

        @Suppress("UNCHECKED_CAST")
        val buttons = WinUser.INPUT().toArray(2) as Array<WinUser.INPUT>
        configureMouseInput(buttons[0], 0x0002) // MOUSEEVENTF_LEFTDOWN
        configureMouseInput(buttons[1], 0x0004) // MOUSEEVENTF_LEFTUP
        val sent = User32.INSTANCE.SendInput(
            WinDef.DWORD(buttons.size.toLong()),
            buttons,
            buttons[0].size(),
        ).toInt()
        return sent == buttons.size
    }

    private fun moveRobotAlongCurve(robot: Robot, target: Point): Int {
        val current = MouseInfo.getPointerInfo()?.location ?: Point(target.x, target.y)
        val path = buildSmoothMousePath(current, target)
        path.drop(1).forEach { point ->
            robot.mouseMove(point.x, point.y)
            SystemUtil.delay(RandomUtil.getMouseStepDelay())
        }
        return path.size
    }

    /**
     * 计算斜率
     * @return double 斜率
     */
    private fun calcK(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
    ): Double = (startY - endY).toDouble() / (startX - endX)

    fun leftButtonClick(hwnd: HWND?) {
        leftButtonClick(prevPoint, hwnd)
    }

    fun leftButtonClick(
        pos: Point,
        hwnd: HWND?,
        mouseMode: Int = effectiveMouseMode(),
    ) {
        if (!ActionDispatchGate.allow("left-click")) return
        val environmentValid = validateEnv(hwnd)
        if (e2eInputEnabled()) {
                log.info {
                    "E2E_INPUT_CLICK_ATTEMPT pos=(${pos.x},${pos.y}) hwnd=$hwnd hwndValid=${hwndIsValid(hwnd)} " +
                    "foreground=robot-requested mode=$mouseMode working=${WorkTimeListener.working} " +
                    "mouseEnabled=${ConfigUtil.getBoolean(ConfigEnum.ENABLE_MOUSE)} envValid=$environmentValid"
                }
        }
        if (!environmentValid) {
            if (e2eInputEnabled()) log.warn { "E2E_INPUT_CLICK_SKIPPED envValid=false pos=$pos hwnd=$hwnd" }
            return
        }
        if (validatePoint(pos)) {
            val lockAcquired = if (e2eInputEnabled()) {
                log.info { "E2E_INPUT_LOCK_WAIT pos=(${pos.x},${pos.y})" }
                try {
                    DRIVER_LOCK.tryLock(3000, TimeUnit.MILLISECONDS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    // A phase transition cancels queued E2E clicks so a new
                    // mode can own the driver.  The interruption is an
                    // expected cancellation, not an input failure.
                    log.info { "E2E_INPUT_LOCK_CANCELLED_PHASE_CHANGE pos=(${pos.x},${pos.y})" }
                    false
                }
            } else {
                DRIVER_LOCK.lock()
                true
            }
            if (!lockAcquired) {
                if (e2eInputEnabled()) {
                    if (!WorkTimeListener.working ||
                        Mode.currMode !== ModeEnum.GAMEPLAY ||
                        !WarEx.war.isMyTurn
                    ) {
                        log.info {
                            "E2E_INPUT_LOCK_SKIPPED_PHASE_CHANGED pos=(${pos.x},${pos.y})"
                        }
                    } else {
                        log.warn { "E2E_INPUT_LOCK_TIMEOUT pos=(${pos.x},${pos.y})" }
                    }
                }
                return
            }
            try {
                if (!ActionDispatchGate.allow("left-click")) return
                if (!WorkTimeListener.working && !ScriptStatus.testMode) return

                if (e2eNativeClickEnabled() && hwnd != null) {
                    try {
                        val foregroundFocused = focusE2EWindow(hwnd)
                        log.info {
                            "E2E_INPUT_SENDINPUT_FOREGROUND hwnd=$hwnd confirmed=$foregroundFocused"
                        }
                        if (prevPoint != pos) {
                            moveNativeAlongCurve(prevPoint, pos, hwnd, mouseMode)
                        }
                        val sent = sendE2eWindowsClick(pos)
                        log.info {
                            "E2E_INPUT_SENDINPUT_SENT pos=(${pos.x},${pos.y}) hwnd=$hwnd " +
                                "mode=$mouseMode accepted=$sent"
                        }
                        savePos(pos)
                        return
                    } catch (error: Throwable) {
                        log.error(error) {
                            "E2E_INPUT_NATIVE_FAILED pos=(${pos.x},${pos.y}) hwnd=$hwnd"
                        }
                        return
                    }
                }

                if (e2eInputEnabled() && hwnd != null && clickWithE2ERobot(pos, hwnd)) {
                    savePos(pos)
                    return
                }

                if (e2eInputEnabled()) {
                    // Never call CSystemDll from the E2E harness.  A failure
                    // in that native path can disappear the whole JVM with no
                    // Java exception, which was the signature under test.
                    if (Thread.currentThread().isInterrupted ||
                        Mode.currMode !== ModeEnum.GAMEPLAY ||
                        !WarEx.war.isMyTurn
                    ) {
                        log.info {
                            "E2E_INPUT_SKIPPED_PHASE_CHANGED pos=(${pos.x},${pos.y}) hwnd=$hwnd"
                        }
                    } else {
                        log.error {
                            "E2E_INPUT_FAILED_NO_NATIVE_FALLBACK pos=(${pos.x},${pos.y}) hwnd=$hwnd"
                        }
                    }
                    return
                }

                if (prevPoint != pos) {
                    moveNativeAlongCurve(prevPoint, pos, hwnd, mouseMode)
                }
                CSystemDll.INSTANCE.leftClick(pos.x.toLong(), pos.y.toLong(), hwnd, mouseMode)
                if (e2eInputEnabled()) {
                    log.info { "E2E_INPUT_NATIVE_SENT pos=(${pos.x},${pos.y}) hwnd=$hwnd mode=$mouseMode" }
                }
                savePos(pos)
            } finally {
                DRIVER_LOCK.unlock()
            }
        }
    }

    fun rightButtonClick(hwnd: HWND?) {
        rightButtonClick(prevPoint, hwnd)
    }

    fun rightButtonClick(
        pos: Point,
        hwnd: HWND?,
        mouseMode: Int = effectiveMouseMode(),
    ) {
        if (!ActionDispatchGate.allow("right-click")) return
        if (!validateEnv(hwnd) || Mode.currMode !== ModeEnum.GAMEPLAY) return

        if (validatePoint(pos)) {
            DRIVER_LOCK.lock()
            try {
                if (!ActionDispatchGate.allow("right-click")) return
                if ((!WorkTimeListener.working || Mode.currMode !== ModeEnum.GAMEPLAY) && !ScriptStatus.testMode) return

                if (e2eInputEnabled() && hwnd != null && clickWithE2ERobot(pos, hwnd, InputEvent.BUTTON3_DOWN_MASK)) {
                    savePos(pos)
                    return
                }
                if (e2eInputEnabled()) return

                if (prevPoint != pos) {
                    moveNativeAlongCurve(prevPoint, pos, hwnd, mouseMode)
                }
                CSystemDll.INSTANCE.rightClick(pos.x.toLong(), pos.y.toLong(), hwnd, mouseMode)
                savePos(pos)
            } finally {
                DRIVER_LOCK.unlock()
            }
        }
    }

    fun moveMouseByHuman(
        endPos: Point,
        hwnd: HWND?,
    ) {
        moveMouseByHuman(null, endPos, hwnd)
    }

    private fun validateEnv(hwnd: HWND?): Boolean {
        if (PauseStatus.isPause) return false
        if (ScriptStatus.testMode) return true
//        选择卡牌时间只让特定线程执行
        if (WarEx.war.isChooseCardTime && !Thread.currentThread().isDiscoverCardThread()) return false
        hwnd ?: return false
        return ConfigUtil.getBoolean(ConfigEnum.ENABLE_MOUSE) && WorkTimeListener.working
    }

    /**
     * 鼠标移动
     */
    fun moveMouseByHuman(
        startPos: Point?,
        endPos: Point,
        hwnd: HWND?,
        mouseMode: Int = effectiveMouseMode(),
    ) {
        if (!ActionDispatchGate.allow("mouse-move")) return
        if (!validateEnv(hwnd)) return

        DRIVER_LOCK.lock()
        try {
            if (!ActionDispatchGate.allow("mouse-move")) return
            if (!WorkTimeListener.working && !ScriptStatus.testMode) return

            if (validatePoint(startPos)) {
                val requestedStart = startPos!!
                if (prevPoint != requestedStart) {
                    moveNativeAlongCurve(prevPoint, requestedStart, hwnd, mouseMode)
                }
                if (validatePoint(endPos) && requestedStart != endPos) {
                    SystemUtil.delayShort()
                    moveNativeAlongCurve(requestedStart, endPos, hwnd, mouseMode)
                    savePos(endPos)
                }
            } else if (validatePoint(prevPoint) && prevPoint != endPos) {
                moveNativeAlongCurve(prevPoint, endPos, hwnd, mouseMode)
                savePos(endPos)
            }
        } finally {
            DRIVER_LOCK.unlock()
        }
    }
}
