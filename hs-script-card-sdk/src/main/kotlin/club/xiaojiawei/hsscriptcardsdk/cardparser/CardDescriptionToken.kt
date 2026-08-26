package club.xiaojiawei.hsscriptcardsdk.cardparser

import kotlinx.serialization.Serializable

/**
 * @author 肖嘉威
 * @date 2026/2/5 14:57
 */

@Serializable
sealed interface Token

@Serializable
sealed interface Quantity : Token

@Serializable
sealed interface Camp : Token

@Serializable
sealed interface Target : Token

@Serializable
sealed interface Segment : Token

@Serializable
sealed interface Area : Token

@Serializable
sealed interface Action : Token

@Serializable
enum class ValueTagEnum {
    ATK, HEALTH, RUSH, FROZEN
}

@Serializable
data class Value(var target: ValueTagEnum, var number: Int = 1) : Token

@Serializable
data object One : Quantity

@Serializable
data object All : Quantity

@Serializable
data object Enemy : Camp

@Serializable
data object Friendly : Camp

@Serializable
data object Minion : Target

@Serializable
data object Hero : Target

@Serializable
data object Role : Target

@Serializable
data object Comma : Segment

@Serializable
data object Period : Segment

@Serializable
data object Play : Area

@Serializable
data object Hand : Area

@Serializable
data object Deck : Area

@Serializable
data object Obtain : Action

@Serializable
data object Damage : Action

@Serializable
data object Heal : Action