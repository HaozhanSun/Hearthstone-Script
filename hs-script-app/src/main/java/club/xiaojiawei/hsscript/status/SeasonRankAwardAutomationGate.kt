package club.xiaojiawei.hsscript.status

/** The single production gate before any monthly-award recovery action. */
internal object SeasonRankAwardAutomationGate {
    fun allowsRecovery(observation: SeasonRankAwardHumanInterventionPolicy.Observation): Boolean =
        !observation.holdAutomation
}
