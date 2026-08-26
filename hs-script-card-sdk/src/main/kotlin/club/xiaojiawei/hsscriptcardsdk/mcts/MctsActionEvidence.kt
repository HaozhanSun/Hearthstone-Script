package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.Action
import club.xiaojiawei.hsscriptcardsdk.bean.War
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived execution evidence shared by the live executor and MCTS.
 *
 * A mouse event being sent is not proof that Hearthstone accepted the action.
 * When an action throws, or the live model does not change during the bounded
 * confirmation window, MCTS must not select the same entity again in the same
 * turn.  The evidence is intentionally scoped to one player turn and expires
 * quickly so it cannot become permanent strategy state.
 */
object MctsActionEvidence {

    private data class RejectedAction(val turn: Int, val expiresAt: Long)

    private val rejected = ConcurrentHashMap<String, RejectedAction>()

    fun recordUnconfirmed(action: Action, war: War, now: Long = System.currentTimeMillis()) {
        actionKey(action)?.let { key ->
            rejected[key] = RejectedAction(war.me.turn, now + EVIDENCE_TTL_MS)
        }
    }

    fun isRejected(action: Action, war: War, now: Long = System.currentTimeMillis()): Boolean {
        val key = actionKey(action) ?: return false
        val evidence = rejected[key] ?: return false
        if (evidence.turn != war.me.turn || evidence.expiresAt < now) {
            rejected.remove(key, evidence)
            return false
        }
        return true
    }

    fun clearForTests() = rejected.clear()

    private fun actionKey(action: Action): String? {
        val creator = action.creator ?: return null
        if (creator.entityId.isBlank()) return null
        return "${action::class.qualifiedName}:${creator.entityId}"
    }

    private const val EVIDENCE_TTL_MS = 8_000L
}
