package club.xiaojiawei.hsscriptstrategysdk.deck

/**
 * Identity of one live strategy turn. Turn numbers alone are not unique:
 * every new game normally starts at turn one, and a strategy can be replaced
 * while the process remains alive.
 */
internal data class MctsTurnSessionKey(
    val localGameId: String,
    val rivalGameId: String,
    val firstPlayerGameId: String,
    val startTime: Long,
    val strategyId: String,
    val turn: Int,
    /** Distinguishes isolated WAR objects when log identity is not available yet. */
    val warIdentity: Int = 0,
)

/**
 * Mutable state owned by one MCTS strategy instance and reset atomically when
 * the live game/strategy/turn identity changes.
 */
internal class MctsTurnSessionState {
    @Volatile
    private var key: MctsTurnSessionKey? = null

    @Volatile
    var cycle: Int = 0
        private set

    @Volatile
    var weaponPlayed: Boolean = false
        private set

    private val suppressedCreatorIds = mutableSetOf<String>()

    @Synchronized
    fun begin(nextKey: MctsTurnSessionKey): Boolean {
        if (key == nextKey) return false
        key = nextKey
        cycle = 0
        weaponPlayed = false
        suppressedCreatorIds.clear()
        return true
    }

    @Synchronized
    fun clear() {
        key = null
        cycle = 0
        weaponPlayed = false
        suppressedCreatorIds.clear()
    }

    @Synchronized
    fun nextCycle(): Int {
        cycle += 1
        return cycle
    }

    @Synchronized
    fun setCycle(value: Int) {
        cycle = value
    }

    @Synchronized
    fun markWeaponPlayed() {
        weaponPlayed = true
    }

    @Synchronized
    fun suppressCreator(entityId: String) {
        if (entityId.isNotBlank()) suppressedCreatorIds += entityId
    }

    @Synchronized
    fun suppressedCreatorIds(): Set<String> = suppressedCreatorIds.toSet()

    @Synchronized
    fun currentKey(): MctsTurnSessionKey? = key
}
