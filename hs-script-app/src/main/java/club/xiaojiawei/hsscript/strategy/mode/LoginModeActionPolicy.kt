package club.xiaojiawei.hsscript.strategy.mode

/** Prevent speculative login/reconnect clicks during a safe-native cold start. */
internal object LoginModeActionPolicy {
    fun mayRetryCoordinateAction(
        safeNative: Boolean,
        independentlyVerifiedScreen: Boolean = false,
    ): Boolean = !safeNative || independentlyVerifiedScreen
}
