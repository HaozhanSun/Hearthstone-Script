package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscriptbase.config.log

/**
 * Records the code sources that define the app/plugin ABI at runtime.
 * This is intentionally diagnostic only: it makes a classpath mismatch
 * observable before a strategy starts calling an incompatible constructor.
 */
object RuntimeContractTrace {
    private fun sourceOf(className: String): String {
        return runCatching {
            val clazz = Class.forName(className)
            clazz.protectionDomain?.codeSource?.location?.toExternalForm() ?: "<no-code-source>"
        }.getOrElse { "<unavailable:${it.javaClass.simpleName}:${it.message}>" }
    }

    private fun constructorsOf(className: String): String {
        return runCatching {
            Class.forName(className).declaredConstructors
                .joinToString(";") { constructor ->
                    constructor.parameterTypes.joinToString(",", prefix = "(", postfix = ")") { it.name }
                }
        }.getOrElse { "<unavailable:${it.javaClass.simpleName}:${it.message}>" }
    }

    fun emit() {
        val deploymentId = System.getProperty("hs.script.deployment.id", "unmarked")
        val manifest = System.getProperty("hs.script.deployment.manifest", "unmarked")
        val mctsArg = "club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg"
        log.info {
            "运行时部署合同 pid=${ProcessHandle.current().pid()} deploymentId=$deploymentId " +
                "manifest=$manifest app=${sourceOf("club.xiaojiawei.hsscript.MainKt")} " +
                "cardSdk=${sourceOf(mctsArg)} strategySdk=${sourceOf("club.xiaojiawei.hsscriptstrategysdk.deck.MCTSDeckStrategy")} " +
                "MCTSArgConstructors=${constructorsOf(mctsArg)}"
        }
    }
}
