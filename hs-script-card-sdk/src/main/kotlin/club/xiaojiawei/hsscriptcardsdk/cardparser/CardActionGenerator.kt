package club.xiaojiawei.hsscriptcardsdk.cardparser

import club.xiaojiawei.hsscriptcardsdk.CardAction
import net.bytebuddy.ByteBuddy
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.FixedValue
import net.bytebuddy.implementation.MethodDelegation
import net.bytebuddy.matcher.ElementMatchers
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap

/**
 * [club.xiaojiawei.hsscriptcardsdk.CardAction]类生成器
 * @author 肖嘉威
 * @date 2026/2/5
 */
object CardActionGenerator {

    val loadedClassMap = ConcurrentHashMap<String, Class<out CardAction>>()

    fun clear() {
        loadedClassMap.clear()
    }

    fun generateCardActionClass(
        className: String,
        cardIds: Array<String>,
        playActionInterceptor: PlayActionInterceptor? = null
    ): Class<out CardAction> {
        val loadedClass = loadedClassMap[className]
        if (loadedClass != null) return loadedClass
        lateinit var generatedClass: Class<out CardAction>
        lateinit var instanceFactory: () -> CardAction
        val unloaded = ByteBuddy().subclass(CardAction.DefaultCardAction::class.java).name(className)
            .method(ElementMatchers.named("getCardId"))
            .intercept(FixedValue.value(cardIds))
            .let { builder ->
                if (playActionInterceptor != null) {
                    builder.method(ElementMatchers.named("generatePlayActions"))
                        .intercept(MethodDelegation.to(playActionInterceptor))
                } else {
                    builder
                }
            }
            .method(ElementMatchers.named("createNewInstance"))
            .intercept(MethodDelegation.to(CreateNewInstanceInterceptor {
                instanceFactory()
            }))
            .make()
        generatedClass = unloaded.load(
            CardActionGenerator::class.java.classLoader,
            ClassLoadingStrategy.Default.WRAPPER
        ).loaded as Class<out CardAction>
        instanceFactory = createInstanceFactory(generatedClass)
        loadedClassMap[className] = generatedClass
        return generatedClass
    }

    private fun createInstanceFactory(generatedClass: Class<out CardAction>): () -> CardAction {
        val lookup = MethodHandles.lookup()
        val privateLookup = MethodHandles.privateLookupIn(generatedClass, lookup)
        val constructorHandle = privateLookup
            .findConstructor(generatedClass, MethodType.methodType(Void.TYPE))
            .asType(MethodType.methodType(CardAction::class.java))
        return { constructorHandle.invoke() as CardAction }
    }

    class CreateNewInstanceInterceptor(private val supplier: () -> CardAction) {
        fun createNewInstance(): CardAction = supplier()
    }
}
