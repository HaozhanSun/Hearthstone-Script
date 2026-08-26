package club.xiaojiawei.hsscriptbase.bean

import kotlin.test.*

/**
 * @author 肖嘉威
 * @date 2026/2/7 21:03
 */
class LikeTrieTest {

    /**
     * 测试优先完全匹配：当同时存在精确key和通配符key时，应优先返回精确匹配
     */
    @Test
    fun testExactMatchPriority() {
        val trie = LikeTrie<String>()

        // 设置通配符模式和精确匹配
        trie["HERO_%"] = "通配符匹配"
        trie["HERO_001"] = "精确匹配"
        trie["HERO_002"] = "精确匹配2"

        // 精确匹配应优先
        assertEquals("精确匹配", trie["HERO_001"], "应优先返回精确匹配")
        assertEquals("精确匹配2", trie["HERO_002"], "应优先返回精确匹配2")

        // 没有精确匹配时才使用通配符
        assertEquals("通配符匹配", trie["HERO_999"], "无精确匹配时应使用通配符")
        assertEquals("通配符匹配", trie["HERO_ABC"], "无精确匹配时应使用通配符")
    }

    /**
     * 测试多个通配符模式
     */
    @Test
    fun testMultipleWildcardPatterns() {
        val trie = LikeTrie<String>()

        trie["A%"] = "A开头"
        trie["AB%"] = "AB开头"
        trie["%Z"] = "Z结尾"

        // AB开头的精确前缀更长，应该匹配
        assertEquals("AB开头", trie["ABC"], "ABC应匹配AB%")
        assertEquals("A开头", trie["AXY"], "AXY应匹配A%")
        assertEquals("Z结尾", trie["XYZ"], "XYZ应匹配%Z")
    }

    /**
     * 测试通配符在中间位置
     */
    @Test
    fun testWildcardInMiddle() {
        val trie = LikeTrie<String>()

        trie["START%END"] = "首尾匹配"
        trie["PRE%SUF"] = "前后缀匹配"

        assertEquals("首尾匹配", trie["STARTEND"], "STARTEND应匹配")
        assertEquals("首尾匹配", trie["START_MIDDLE_END"], "START_MIDDLE_END应匹配")
        assertEquals("前后缀匹配", trie["PRESUF"], "PRESUF应匹配")
        assertEquals("前后缀匹配", trie["PRE123SUF"], "PRE123SUF应匹配")

        assertNull(trie.getNoDefault("STARTXXX"), "STARTXXX不应匹配")
        assertNull(trie.getNoDefault("XXXEND"), "XXXEND不应匹配")
    }

    /**
     * 测试大小写不敏感（默认行为）
     */
    @Test
    fun testCaseInsensitive() {
        val trie = LikeTrie<String>()

        trie["CARD_001"] = "卡牌1"
        trie["Card_002"] = "卡牌2"
        trie["card_%"] = "卡牌通配"

        // 大小写不敏感的精确匹配
        assertEquals("卡牌1", trie["card_001"], "小写应匹配大写key")
        assertEquals("卡牌1", trie["CARD_001"], "大写应匹配大写key")
        assertEquals("卡牌2", trie["CARD_002"], "大写应匹配混合大小写key")

        // 大小写不敏感的通配符匹配
        assertEquals("卡牌通配", trie["CARD_999"], "通配符匹配应大小写不敏感")
    }

    /**
     * 测试大小写敏感模式
     */
    @Test
    fun testCaseSensitive() {
        val trie = LikeTrie<String>(caseSensitive = true)

        trie["CARD_001"] = "大写卡牌"
        trie["card_001"] = "小写卡牌"
        trie["HERO_%"] = "大写通配"
        trie["hero_%"] = "小写通配"

        // 精确匹配区分大小写
        assertEquals("大写卡牌", trie["CARD_001"], "精确匹配应区分大小写")
        assertEquals("小写卡牌", trie["card_001"], "精确匹配应区分大小写")
        assertNull(trie.getNoDefault("Card_001"), "混合大小写应无法匹配")

        // 通配符匹配区分大小写
        assertEquals("大写通配", trie["HERO_999"], "通配符匹配应区分大小写")
        assertEquals("小写通配", trie["hero_999"], "通配符匹配应区分大小写")
        assertNull(trie.getNoDefault("Hero_999"), "混合大小写应无法通配匹配")
    }

    /**
     * 测试大小写敏感模式与默认值
     */
    @Test
    fun testCaseSensitiveWithDefault() {
        val trie = LikeTrie<String>(defaultValue = "默认值", caseSensitive = true)

        trie["KEY"] = "大写值"

        assertEquals("大写值", trie["KEY"], "大写应匹配")
        assertEquals("默认值", trie["key"], "小写应返回默认值")
        assertEquals("默认值", trie["Key"], "混合大小写应返回默认值")
    }

    /**
     * 测试正则特殊字符的转义
     */
    @Test
    fun testSpecialCharacterEscaping() {
        val trie = LikeTrie<String>()

        // 包含正则特殊字符的key
        trie["TEST.%"] = "点号模式"
        trie["[CARD]%"] = "方括号模式"
        trie["(HERO)%"] = "圆括号模式"
        trie["A+B%"] = "加号模式"
        trie["A?B%"] = "问号模式"

        // 应精确匹配特殊字符而不是作为正则
        assertEquals("点号模式", trie["TEST.123"], "点号应被转义")
        assertEquals("方括号模式", trie["[CARD]001"], "方括号应被转义")
        assertEquals("圆括号模式", trie["(HERO)001"], "圆括号应被转义")
        assertEquals("加号模式", trie["A+B123"], "加号应被转义")
        assertEquals("问号模式", trie["A?B123"], "问号应被转义")

        // 不应错误匹配
        assertNull(trie.getNoDefault("TESTX123"), "TESTX123不应匹配TEST.%")
    }

    /**
     * 测试 data() 方法
     */
    @Test
    fun testDataMethod() {
        val trie = LikeTrie<String>()

        trie["KEY1"] = "值1"
        trie["KEY2"] = "值2"
        trie["PATTERN%"] = "通配值"

        val data = trie.data()

        assertEquals(3, data.size, "data()应返回3个元素")

        val keys = data.map { it.key }.toSet()
        assertTrue(keys.contains("KEY1"), "应包含KEY1")
        assertTrue(keys.contains("KEY2"), "应包含KEY2")
        assertTrue(keys.contains("PATTERN%"), "应包含PATTERN%")
    }

    /**
     * 测试 clear() 方法
     */
    @Test
    fun testClearMethod() {
        val trie = LikeTrie<String>()

        trie["KEY1"] = "值1"
        trie["PATTERN%"] = "通配值"

        assertNotNull(trie["KEY1"], "清空前应能获取值")

        trie.clear()

        assertNull(trie.getNoDefault("KEY1"), "清空后应返回null")
        assertNull(trie.getNoDefault("PATTERN123"), "清空后通配符也应返回null")
        assertTrue(trie.data().isEmpty(), "清空后data()应为空")
    }

    /**
     * 测试默认值
     */
    @Test
    fun testDefaultValue() {
        val trie = LikeTrie<String>("默认值")

        trie["KEY1"] = "值1"

        assertEquals("值1", trie["KEY1"], "存在的key应返回实际值")
        assertEquals("默认值", trie["NOT_EXIST"], "不存在的key应返回默认值")
        assertNull(trie.getNoDefault("NOT_EXIST"), "getNoDefault应返回null")
    }

    /**
     * 测试 getOrDefault 方法
     */
    @Test
    fun testGetOrDefault() {
        val trie = LikeTrie<String>()

        trie["KEY1"] = "值1"

        assertEquals("值1", trie.getOrDefault("KEY1", "默认"), "存在的key应返回实际值")
        assertEquals("默认", trie.getOrDefault("NOT_EXIST", "默认"), "不存在的key应返回默认值")
        assertEquals("懒加载默认", trie.getOrDefault("NOT_EXIST") { "懒加载默认" }, "lambda默认值应正常工作")
    }

    /**
     * 测试边界情况：空字符串
     */
    @Test
    fun testEmptyString() {
        val trie = LikeTrie<String>()

        trie[""] = "空字符串"
        trie["%"] = "通配所有"

        assertEquals("空字符串", trie[""], "空字符串应精确匹配")
        assertEquals("通配所有", trie["任意内容"], "任意内容应匹配%")
    }

    /**
     * 测试边界情况：只有通配符
     */
    @Test
    fun testOnlyWildcard() {
        val trie = LikeTrie<String>()

        trie["%"] = "匹配任意"

        assertEquals("匹配任意", trie[""], "空字符串应匹配%")
        assertEquals("匹配任意", trie["a"], "单字符应匹配%")
        assertEquals("匹配任意", trie["any_string"], "任意字符串应匹配%")
    }

    /**
     * 测试边界情况：连续通配符（虽然不常用，但应该正常工作）
     */
    @Test
    fun testConsecutiveWildcards() {
        val trie = LikeTrie<String>()

        // %% 应该等价于 % (匹配任意个字符)
        trie["A%%B"] = "连续通配"

        assertEquals("连续通配", trie["AB"], "AB应匹配A%%B")
        assertEquals("连续通配", trie["AXB"], "AXB应匹配A%%B")
        assertEquals("连续通配", trie["AXXXB"], "AXXXB应匹配A%%B")
    }

    /**
     * 测试值覆盖：相同key设置不同值
     */
    @Test
    fun testValueOverride() {
        val trie = LikeTrie<String>()

        trie["KEY1"] = "值1"
        assertEquals("值1", trie["KEY1"])

        trie["KEY1"] = "新值1"
        assertEquals("新值1", trie["KEY1"], "相同key应覆盖旧值")
    }

}