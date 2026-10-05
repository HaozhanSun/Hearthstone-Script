package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscript.dll.Win32ProcessImagePath
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class WindowMessageClickPolicyTest {
    @Test
    fun `startup owner validation uses actual image path instead of command line`() {
        val configuredImage = "C:\\Users\\tester\\Battle.net\\Battle.net.exe"
        val selfUpdateImage = "C:\\Users\\tester\\Battle.net\\temp_a6289c43bb612c7b933dbbff6b9cfd03.exe"
        val verifiedBattleNet = AuthenticodeEvidence(
            status = "Valid",
            signerSimpleName = "Blizzard Entertainment, Inc.",
            originalFilename = "Battle.net.exe",
            productName = "Battle.net",
        )

        assertTrue(BattleNetOwnerIdentityPolicy.evaluate(configuredImage, selfUpdateImage, verifiedBattleNet).allowed)
        assertTrue(BattleNetOwnerIdentityPolicy.evaluate(configuredImage, configuredImage, verifiedBattleNet).allowed)
        assertTrue(BattleNetOwnerIdentityPolicy.isImageCandidate(configuredImage, selfUpdateImage))
        assertTrue(BattleNetOwnerIdentityPolicy.isImageCandidate(configuredImage, configuredImage))
        assertFalse(BattleNetOwnerIdentityPolicy.isImageCandidate(configuredImage, "C:\\Other\\Battle.net.exe"))
        assertFalse(BattleNetOwnerIdentityPolicy.evaluate(configuredImage, selfUpdateImage, null).allowed)
        assertFalse(
            BattleNetOwnerIdentityPolicy.evaluate(
                configuredImage,
                selfUpdateImage,
                verifiedBattleNet.copy(status = "NotSigned"),
            ).allowed,
        )
        assertFalse(
            BattleNetOwnerIdentityPolicy.evaluate(
                configuredImage,
                selfUpdateImage,
                verifiedBattleNet.copy(signerSimpleName = "Unknown Publisher"),
            ).allowed,
        )
        assertFalse(
            BattleNetOwnerIdentityPolicy.evaluate(
                configuredImage,
                selfUpdateImage,
                verifiedBattleNet.copy(originalFilename = "other.exe"),
            ).allowed,
        )
        assertFalse(
            BattleNetOwnerIdentityPolicy.evaluate(
                configuredImage,
                selfUpdateImage,
                verifiedBattleNet.copy(productName = "Other Product"),
            ).allowed,
        )
        assertFalse(
            BattleNetOwnerIdentityPolicy.evaluate(
                configuredImage,
                "C:\\Other\\temp_a6289c43bb612c7b933dbbff6b9cfd03.exe",
                verifiedBattleNet,
            ).allowed,
        )
        assertFalse(
            BattleNetOwnerIdentityPolicy.evaluate(
                configuredImage,
                "C:\\Users\\tester\\Battle.net\\temp_not-a-hash.exe",
                verifiedBattleNet,
            ).allowed,
        )
    }

    @Test
    fun `Win32 image query reads this process executable and rejects invalid pid`() {
        assumeTrue(System.getProperty("os.name").contains("Windows", ignoreCase = true))

        val imagePath = assertNotNull(Win32ProcessImagePath.query(ProcessHandle.current().pid().toInt()))
        val imageName = Path.of(imagePath).fileName.toString()
        assertTrue(imageName.equals("java.exe", ignoreCase = true) || imageName.equals("javaw.exe", ignoreCase = true))
        assertNull(Win32ProcessImagePath.query(0))
    }

    @Test
    fun `PowerShell Authenticode probe verifies optional signed Battle net fixture`() {
        assumeTrue(System.getProperty("os.name").contains("Windows", ignoreCase = true))
        val fixturePath = System.getenv("HSSCRIPT_TEST_AUTHENTICODE_IMAGE")
        assumeTrue(!fixturePath.isNullOrBlank())

        val evidence = assertNotNull(WindowsAuthenticodeEvidenceProvider.inspect(fixturePath!!))
        assertEquals("Valid", evidence.status)
        assertEquals("Blizzard Entertainment, Inc.", evidence.signerSimpleName)
        assertEquals("Battle.net.exe", evidence.originalFilename)
        assertEquals("Battle.net", evidence.productName)
    }

    @Test
    fun `client point packing preserves x and y for LPARAM`() {
        val packed = WindowMessageClickPolicy.packClientPoint(145, 849)

        assertEquals((849L shl 16) or 145L, packed)
        assertTrue(WindowMessageClickPolicy.isInsideClient(145, 849, 1920, 1080))
    }

    @Test
    fun `negative oversized and out of client coordinates are rejected`() {
        assertNull(WindowMessageClickPolicy.packClientPoint(-1, 20))
        assertNull(WindowMessageClickPolicy.packClientPoint(20, 65_536))
        assertFalse(WindowMessageClickPolicy.isInsideClient(0, 0, 0, 10))
        assertFalse(WindowMessageClickPolicy.isInsideClient(10, 0, 10, 10))
        assertFalse(WindowMessageClickPolicy.isInsideClient(0, 10, 10, 10))
    }
}
