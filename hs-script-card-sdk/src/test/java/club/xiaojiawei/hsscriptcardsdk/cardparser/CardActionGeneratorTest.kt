package club.xiaojiawei.hsscriptcardsdk.cardparser

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.PlayAction
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import net.bytebuddy.implementation.bind.annotation.Argument
import net.bytebuddy.implementation.bind.annotation.This
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class CardActionGeneratorTest {

    class TestPlayActionInterceptor : PlayActionInterceptor {
        var invoked = false

        override fun generatePlayActions(
            @Argument(0) war: War,
            @Argument(1) player: Player,
            @This cardAction: CardAction
        ): List<PlayAction> {
            invoked = true
            return emptyList()
        }
    }

    @Test
    fun testGenerateCardActionWithAdvice() {
        val interceptor = TestPlayActionInterceptor()
        val className = "club.xiaojiawei.hsscript.bean.GeneratedAdviceTestCard${System.nanoTime()}"
        val cardIds = arrayOf("EX1_277")//寒冰箭

        val generatedClass = CardActionGenerator.generateCardActionClass(
            className, cardIds,
            interceptor
        )
        Assertions.assertEquals(className, generatedClass.name) { "生成的代理类名不正确" }
        Assertions.assertNotSame(
            CardActionGenerator::class.java.classLoader,
            generatedClass.classLoader
        ) { "生成的CardAction不应挂在主类加载器上" }

        val instance = generatedClass.getDeclaredConstructor().newInstance()
        Assertions.assertArrayEquals(cardIds, instance.getCardId()) { "生成的CardAction cardIds不相同" }

        val newInstance = instance.createNewInstance()
        Assertions.assertNotEquals(instance.createNewInstance(), newInstance) { "生成的CardAction实例相同" }
        Assertions.assertEquals(instance.createNewInstance()::class.java, generatedClass) { "生成的CardAction类不正确" }

        newInstance.generatePlayActions(War(false), Player(playerId = "1"))
        Assertions.assertTrue(interceptor.invoked) { "生成的CardAction generatePlayActions代理失败" }
    }
}
