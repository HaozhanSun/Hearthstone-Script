package club.xiaojiawei.hsscriptbase.bean

import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.withNotNull

/**
 * # Trie树
 * 支持 % 通配符匹配任意个字符
 * 匹配优先级：完全匹配 > 通配符匹配
 * @param caseSensitive 是否大小写敏感，默认为 false（不敏感）
 * @author 肖嘉威
 * @date 2024/11/12 9:32
 */
class LikeTrie<V>(
    private var defaultValue: V? = null,
    private val caseSensitive: Boolean = false
) {

    private val root = TrieNode<V>()

    /**
     * 根据大小写敏感设置规范化 key
     */
    private fun normalizeKey(key: String): String = if (caseSensitive) key else key.lowercase()

    class TrieNode<V> {
        val children = mutableMapOf<Char, TrieNode<V>>()
        val regPattern = mutableListOf<Reg<V>>()
        var key: String? = null
        var value: V? = null
    }

    class Entry<V>(
        var key: String,
        var value: V,
    )

    /**
     * 正则模式类，预编译正则表达式以提高性能
     */
    class Reg<V>(val pattern: String, val originalKey: String, val value: V, val caseSensitive: Boolean) {
        val regex: Regex by lazy {
            if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
        }
    }

    /**
     * 设置元素
     * @param key 键，通配符%表示任意个字符
     * @param value 值
     */
    operator fun set(key: String, value: V) {
        var currentNode = root
        val normalizedKey = normalizeKey(key)
        val charKey = normalizedKey.toCharArray()
        var hasWildcard = false

        for (c in charKey) {
            currentNode = currentNode.children.getOrPut(c) { TrieNode() }
            if (c == '%') {
                // 遇到通配符，添加到 regPattern 并标记
                currentNode.regPattern.add(Reg(likeToRegex(normalizedKey), key, value, caseSensitive))
                hasWildcard = true
                break
            }
        }

        // 只有非通配符的 key 才设置 value 和 key
        if (!hasWildcard) {
            currentNode.value = value
            currentNode.key = key
        }
    }

    /**
     * 获取元素集合
     */
    fun data(): MutableList<Entry<V>> {
        val data = mutableListOf<Entry<V>>()
        collectData(root, data)
        return data
    }

    private fun collectData(node: TrieNode<V>, data: MutableList<Entry<V>>) {
        // 收集精确匹配的值
        withNotNull(node.key, node.value) { k, v ->
            data.add(Entry(k, v))
        }
        // 收集通配符模式的值
        for (reg in node.regPattern) {
            data.add(Entry(reg.originalKey, reg.value))
        }
        for (entry in node.children) {
            collectData(entry.value, data)
        }
    }

    /**
     * 清空元素
     */
    fun clear() {
        root.children.clear()
        root.key = null
        root.value = null
        root.regPattern.clear()
    }

    /**
     * 获取元素
     * @param key 键
     * @param defaultValue 默认值，找不到指定元素时返回此值
     */
    fun getOrDefault(key: String, defaultValue: V): V {
        return getHelp(key) ?: defaultValue
    }

    /**
     * 获取元素
     * @param key 键
     * @param defaultValue 默认值，找不到指定元素时返回此值
     */
    fun getOrDefault(key: String, defaultValueExp: () -> V): V {
        return getHelp(key) ?: defaultValueExp()
    }

    fun getNoDefault(key: String): V? = getHelp(key)

    /**
     * 获取元素
     * @param key 键
     */
    operator fun get(key: String): V? = getHelp(key) ?: this.defaultValue

    /**
     * 核心获取逻辑：优先完全匹配，找不到时使用通配符匹配
     */
    private fun getHelp(key: String): V? {
        val normalizedKey = normalizeKey(key)

        // 第一优先级：尝试完全匹配
        val exactMatch = getExact(root, normalizedKey, 0)
        if (exactMatch != null) {
            return exactMatch
        }

        // 第二优先级：尝试通配符匹配
        return getByWildcard(normalizedKey)
    }

    /**
     * 精确匹配：只匹配不含通配符的 key
     */
    private fun getExact(node: TrieNode<V>, str: String, index: Int): V? {
        if (index == str.length) {
            return node.value
        }

        val char = str[index]
        return node.children[char]?.let { childNode ->
            getExact(childNode, str, index + 1)
        }
    }

    /**
     * 通配符匹配：沿着输入字符串路径搜索，优先匹配最长前缀的通配符
     */
    private fun getByWildcard(str: String): V? {
        return findWildcardMatch(root, str, 0)
    }

    /**
     * 沿着输入字符串路径递归查找通配符模式
     * 先递归到最深处，再回溯检查通配符，确保最长前缀优先
     */
    private fun findWildcardMatch(node: TrieNode<V>, str: String, index: Int): V? {
        // 1. 先尝试继续沿路径深入（更长前缀优先）
        if (index < str.length) {
            val char = str[index]
            node.children[char]?.let { childNode ->
                val result = findWildcardMatch(childNode, str, index + 1)
                if (result != null) {
                    return result
                }
            }
        }

        // 2. 深入失败后，检查当前节点的 '%' 子节点的 regPattern
        node.children['%']?.let { wildcardNode ->
            for (reg in wildcardNode.regPattern) {
                if (reg.regex.matches(str)) {
                    return reg.value
                }
            }
        }

        return null
    }

    /**
     * 将 LIKE 模式转换为正则表达式
     * 对特殊字符进行转义，% 转换为 .*
     */
    private fun likeToRegex(pattern: String): String {
        val escaped = StringBuilder()
        for (c in pattern) {
            when (c) {
                '%' -> escaped.append(".*")
                '.', '[', ']', '(', ')', '{', '}', '|', '^', '$', '+', '?', '\\' -> {
                    escaped.append('\\').append(c)
                }

                else -> escaped.append(c)
            }
        }
        return escaped.toString()
    }
}