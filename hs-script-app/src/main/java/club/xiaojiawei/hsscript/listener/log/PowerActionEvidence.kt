package club.xiaojiawei.hsscript.listener.log

import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only evidence bridge from Power.log to live action guards.
 *
 * Windows accepting a mouse event is not proof that Hearthstone accepted the
 * action. The game emits an ATTACK block for an accepted hero attack, or an
 * option error such as REQ_ATTACK_GREATER_THAN_0 when the current hero
 * attack is actually zero.
 */
object PowerActionEvidence {

    private enum class HeroAttackStatus {
        REJECTED_NO_ATTACK,
        CONFIRMED,
    }

    private data class Evidence(
        val status: HeroAttackStatus,
        val observedAt: Long,
    )

    private data class HeroPowerEvidence(
        val observedAt: Long,
    )

    private val heroAttackEvidence = ConcurrentHashMap<String, Evidence>()
    private val heroPowerEvidence = ConcurrentHashMap<String, HeroPowerEvidence>()

    /** Called for every raw Power.log line before normal relevance filtering. */
    fun observeLine(line: String) {
        val now = System.currentTimeMillis()

        if (line.contains("DebugPrintOptions()") &&
            line.contains("mainEntity=[") &&
            line.contains("error=REQ_ATTACK_GREATER_THAN_0")
        ) {
            entityIdFrom(line, "mainEntity=")?.let { entityId ->
                heroAttackEvidence[entityId] = Evidence(HeroAttackStatus.REJECTED_NO_ATTACK, now)
            }
        }

        if (line.contains("BlockType=ATTACK") && line.contains("Entity=[")) {
            entityIdFrom(line, "Entity=")?.let { entityId ->
                if (line.contains("cardId=HERO_")) {
                    heroAttackEvidence[entityId] = Evidence(HeroAttackStatus.CONFIRMED, now)
                }
            }
        }

        if (line.contains("BlockType=POWER") &&
            line.contains("Entity=[") &&
            line.contains("cardId=HERO_")
        ) {
            entityIdFrom(line, "Entity=")?.let { entityId ->
                heroPowerEvidence[entityId] = HeroPowerEvidence(now)
            }
        }
    }

    fun heroAttackRejectedRecently(entityId: String, since: Long): Boolean =
        heroAttackEvidence[entityId]?.let {
            it.status == HeroAttackStatus.REJECTED_NO_ATTACK && it.observedAt >= since
        } == true

    fun heroAttackConfirmedRecently(entityId: String, since: Long): Boolean =
        heroAttackEvidence[entityId]?.let {
            it.status == HeroAttackStatus.CONFIRMED && it.observedAt >= since
        } == true

    fun heroPowerConfirmedRecently(entityId: String, since: Long): Boolean =
        heroPowerEvidence[entityId]?.observedAt?.let { it >= since } == true

    private fun entityIdFrom(line: String, marker: String): String? {
        val start = line.indexOf(marker).takeIf { it >= 0 } ?: return null
        val entityStart = line.indexOf("id=", start).takeIf { it >= 0 }?.plus(3) ?: return null
        val entityEnd = line.indexOf(' ', entityStart).takeIf { it >= 0 } ?: return null
        return line.substring(entityStart, entityEnd).trim().ifBlank { null }
    }
}
