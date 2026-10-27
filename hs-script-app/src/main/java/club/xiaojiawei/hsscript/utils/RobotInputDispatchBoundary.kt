package club.xiaojiawei.hsscript.utils

/** The last authorization boundary shared by the real Robot press and offline dispatch replay. */
internal object RobotInputDispatchBoundary {
    fun dispatchIfAuthorized(
        action: String,
        authorize: (String) -> Boolean,
        sendInput: () -> Unit,
    ): Boolean {
        if (!authorize(action)) return false
        sendInput()
        return true
    }
}
