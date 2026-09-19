package club.xiaojiawei.hsscript.status

import club.xiaojiawei.hsscript.enums.ConfigEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PrivacyProcessAuditTest {
    @Test
    fun `audit is opt in and scoped to Blizzard allowlist`() {
        assertEquals("false", ConfigEnum.PROCESS_PRIVACY_AUDIT.defaultValue)
        assertTrue(PrivacyProcessAudit.isAllowedImageNameForTest("Hearthstone.exe"))
        assertTrue(PrivacyProcessAudit.isAllowedImageNameForTest("Battle.net.exe"))
        assertTrue(PrivacyProcessAudit.isAllowedImageNameForTest("Agent.exe"))
        assertFalse(PrivacyProcessAudit.isAllowedImageNameForTest("notepad.exe"))
        assertFalse(PrivacyProcessAudit.isAllowedImageNameForTest("chrome.exe"))
    }

    @Test
    fun `audit token sanitization does not emit sensitive argument names`() {
        val sanitized = PrivacyProcessAudit.safeTokenForTest("--password=my-secret email=user@example.com")
        assertFalse(sanitized.contains("password", ignoreCase = true))
        assertFalse(sanitized.contains("email", ignoreCase = true))
        assertTrue(sanitized.contains("redacted"))
    }

    @Test
    fun `audit uses stable hashes rather than raw paths or values`() {
        val first = PrivacyProcessAudit.digestForTest("D:/Hearthstone/Hearthstone.exe")
        val second = PrivacyProcessAudit.digestForTest("D:/Hearthstone/Hearthstone.exe")
        val different = PrivacyProcessAudit.digestForTest("C:/Other/Hearthstone.exe")
        assertEquals(64, first.length)
        assertEquals(first, second)
        assertNotEquals(first, different)
        assertFalse(first.contains("Hearthstone", ignoreCase = true))
    }
}
