package club.xiaojiawei.hsscript.status.surrender

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class MulliganRankDispatchBarrierTest {
    @AfterEach
    fun reset() = MulliganRankDispatchBarrier.resetForTest()

    @Test
    fun `only exact eligible current-game rank releases the barrier`() {
        for (rank in listOf(5, 10)) {
            val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
            assertEquals(MulliganRankDispatchBarrier.State.PENDING, MulliganRankDispatchBarrier.currentState())
            assertTrue(MulliganRankDispatchBarrier.authorizeEligibleRank(ticket, rank))
            assertEquals(MulliganRankDispatchBarrier.State.ELIGIBLE, MulliganRankDispatchBarrier.currentState())
        }
    }

    @Test
    fun `stale ticket and non-target numeric rank cannot authorize continuation`() {
        val oldTicket = MulliganRankDispatchBarrier.beginCurrentGame()
        val currentTicket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertFalse(MulliganRankDispatchBarrier.authorizeEligibleRank(oldTicket, 10))
        assertFalse(MulliganRankDispatchBarrier.authorizeEligibleRank(currentTicket, 8))
        assertEquals(MulliganRankDispatchBarrier.State.PENDING, MulliganRankDispatchBarrier.currentState())
        assertNotNull(MulliganRankDispatchBarrier.requireSurrender(currentTicket))
        assertEquals(MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED, MulliganRankDispatchBarrier.currentState())
    }

    @Test
    fun `authoritative terminal result takes priority over pending rank preflight`() {
        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        assertTrue(MulliganRankDispatchBarrier.completeTerminalWithoutSurrender(ticket))
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertFalse(MulliganRankDispatchBarrier.authorizeEligibleRank(ticket, 10))
    }

    @Test
    fun `authoritative terminal state supersedes a required but not yet accepted surrender`() {
        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        val capability = MulliganRankDispatchBarrier.requireSurrender(ticket)
        assertNotNull(capability)
        assertEquals(MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED, MulliganRankDispatchBarrier.currentState())

        assertTrue(MulliganRankDispatchBarrier.completeTerminalWithoutSurrender(ticket))
        assertEquals(MulliganRankDispatchBarrier.State.IDLE, MulliganRankDispatchBarrier.currentState())
        assertFalse(MulliganRankDispatchBarrier.isSurrenderCapabilityValid(capability))
    }

    @Test
    fun `mandatory surrender capability is single-use and game-scoped`() {
        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        val capability = MulliganRankDispatchBarrier.requireSurrender(ticket)
        assertNotNull(capability)
        assertTrue(MulliganRankDispatchBarrier.isSurrenderCapabilityValid(capability))
        assertTrue(MulliganRankDispatchBarrier.consumeSurrenderCapability(capability))
        assertFalse(MulliganRankDispatchBarrier.consumeSurrenderCapability(capability))

        MulliganRankDispatchBarrier.beginCurrentGame()
        assertFalse(MulliganRankDispatchBarrier.isSurrenderCapabilityValid(capability))
        assertNull(MulliganRankDispatchBarrier.requireSurrender(ticket))
    }

    @Test
    fun `concurrent eligibility and surrender decisions cannot both open the barrier`() {
        val ticket = MulliganRankDispatchBarrier.beginCurrentGame()
        val start = CountDownLatch(1)
        val eligibleWon = AtomicBoolean(false)
        val surrenderWon = AtomicBoolean(false)
        val eligible = Thread {
            start.await()
            eligibleWon.set(MulliganRankDispatchBarrier.authorizeEligibleRank(ticket, 10))
        }
        val surrender = Thread {
            start.await()
            surrenderWon.set(MulliganRankDispatchBarrier.requireSurrender(ticket) != null)
        }
        eligible.start()
        surrender.start()
        start.countDown()
        eligible.join()
        surrender.join()

        assertTrue(eligibleWon.get() xor surrenderWon.get())
        assertEquals(
            if (eligibleWon.get()) MulliganRankDispatchBarrier.State.ELIGIBLE
            else MulliganRankDispatchBarrier.State.SURRENDER_REQUIRED,
            MulliganRankDispatchBarrier.currentState(),
        )
    }
}
