package club.xiaojiawei.hsscript.status

/**
 * Runtime switches that keep the normal MESSAGE mouse mode away from the
 * legacy native bridge. MESSAGE mode does not need the interception driver;
 * Java Robot plus the log listeners provide the required game interaction.
 */
object RuntimeSafety {
    val safeNative: Boolean
        get() = System.getProperty("hs.script.e2e") == "true" ||
            System.getProperty("hs.script.safe-native") == "true"
}
