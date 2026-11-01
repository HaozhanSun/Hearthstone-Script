package club.xiaojiawei.hsscript.status

/** Fast, read-only observations for the normal launcher-to-client startup handoff. */
internal object StartupHandoffProgressPolicy {
    enum class PreOcrAction {
        RUN_OCR,
        DEFER_BATTLENET_LAUNCHER,
        CLASSIFY_HUB_CHOOSER_VISUALLY,
    }

    fun preOcrAction(
        purpose: ScreenRecoveryCapturePurpose,
        currentSessionReady: Boolean,
        battleNetLauncherVisual: Boolean,
        hubModeChooserVisual: Boolean,
        modalVisible: Boolean,
    ): PreOcrAction {
        if (purpose != ScreenRecoveryCapturePurpose.STARTUP_CLIENT_PROBE || currentSessionReady) {
            return PreOcrAction.RUN_OCR
        }
        if (battleNetLauncherVisual) return PreOcrAction.DEFER_BATTLENET_LAUNCHER
        if (hubModeChooserVisual && !modalVisible) return PreOcrAction.CLASSIFY_HUB_CHOOSER_VISUALLY
        return PreOcrAction.RUN_OCR
    }
}
