package club.xiaojiawei.hsscriptcardsdk.cardparser

import club.xiaojiawei.hsscriptcardsdk.bean.DBCard
import club.xiaojiawei.hsscriptcardsdk.enums.CardActionEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum

/**
 * 仅对可明确识别的即时效果生成拦截器，避免将召唤、变形、随机分配、被动效果误判为伤害。
 */
object CardDescriptionParser {

    private enum class ParsedEffectType {
        DAMAGE,
        HEAL,
        HEALTH_BUFF,
        ATK_BUFF,
        ATTRIBUTE_BUFF,
        FREEZE,
    }

    private enum class ParsedSide {
        MY,
        RIVAL,
    }

    private enum class ParsedScope {
        SINGLE,
        ALL,
    }

    private data class ParsedIntent(
        val effectType: ParsedEffectType,
        val side: ParsedSide,
        val scope: ParsedScope,
        val targetType: DirectedTargetType,
        val amount: Int = 0,
        val attackAmount: Int = 0,
        val healthAmount: Int = 0,
        val noPointOnly: Boolean = false,
    )

    private data class ParsedTarget(
        val side: ParsedSide,
        val scope: ParsedScope,
        val targetType: DirectedTargetType,
    )

    private val htmlRegex = Regex("<.*?>")
    private val noteRegex = Regex("""[（(][^）)]*[）)]""")
    private val whitespaceRegex = Regex("""\s+""")
    private val splitRegex = Regex("""[。；]""")
    private val damageRegex = Regex("""造成[$#]?(\d+)点伤害""")
    private val healRegex = Regex("""恢复[$#]?(\d+)点生命值""")
    private val attackBuffRegex = Regex("""获得\+(\d+)攻击力""")
    private val healthBuffRegex = Regex("""获得\+?[$#]?(\d+)生命值""")
    private val attributeBuffRegex = Regex("""\+(\d+)/\+(\d+)""")
    private val freezeRegex = Regex("""冻结""")

    private val supportedPrefixes = listOf("战吼：", "连击：", "抉择：", "法术迸发：", "塑造法术：", "进击：")
    private val blockedPrefixes = listOf("亡语：", "激励：", "过量治疗：", "奥秘：")
    private val blockedKeywords = listOf(
        "随机",
        "召唤",
        "变形",
        "发现",
        "复生后",
        "在你的回合结束时",
        "在你的回合开始时",
        "在本回合结束时",
        "每当",
        "当你",
        "当本随从",
        "你的英雄技能",
        "光环",
        "互换",
        "交换",
        "复制",
    )

    private val explicitTargets = listOf(
        "所有敌方随从" to ParsedTarget(ParsedSide.RIVAL, ParsedScope.ALL, DirectedTargetType.MINION),
        "所有友方随从" to ParsedTarget(ParsedSide.MY, ParsedScope.ALL, DirectedTargetType.MINION),
        "所有敌方角色" to ParsedTarget(ParsedSide.RIVAL, ParsedScope.ALL, DirectedTargetType.ROLE),
        "所有友方角色" to ParsedTarget(ParsedSide.MY, ParsedScope.ALL, DirectedTargetType.ROLE),
        "所有敌人" to ParsedTarget(ParsedSide.RIVAL, ParsedScope.ALL, DirectedTargetType.ROLE),
        "敌方英雄" to ParsedTarget(ParsedSide.RIVAL, ParsedScope.SINGLE, DirectedTargetType.HERO),
        "你的英雄" to ParsedTarget(ParsedSide.MY, ParsedScope.SINGLE, DirectedTargetType.HERO),
        "我方英雄" to ParsedTarget(ParsedSide.MY, ParsedScope.SINGLE, DirectedTargetType.HERO),
        "一个友方随从" to ParsedTarget(ParsedSide.MY, ParsedScope.SINGLE, DirectedTargetType.MINION),
        "一个敌方随从" to ParsedTarget(ParsedSide.RIVAL, ParsedScope.SINGLE, DirectedTargetType.MINION),
        "一个友方角色" to ParsedTarget(ParsedSide.MY, ParsedScope.SINGLE, DirectedTargetType.ROLE),
        "一个敌方角色" to ParsedTarget(ParsedSide.RIVAL, ParsedScope.SINGLE, DirectedTargetType.ROLE),
        "一个敌人" to ParsedTarget(ParsedSide.RIVAL, ParsedScope.SINGLE, DirectedTargetType.ROLE),
        "自身" to ParsedTarget(ParsedSide.MY, ParsedScope.SINGLE, DirectedTargetType.MINION),
        "本随从" to ParsedTarget(ParsedSide.MY, ParsedScope.SINGLE, DirectedTargetType.MINION),
    )

    fun parseAsPlayActionInterceptor(dbCard: DBCard): PlayActionInterceptor? {
        val cardTypeName = dbCard.type ?: return null
        val cardType = runCatching { CardTypeEnum.valueOf(cardTypeName) }.getOrNull() ?: return null
        val intent = parseIntent(dbCard.text) ?: return null
        if (intent.noPointOnly) {
            return createNoPointPlayActionInterceptor(cardType)
        }
        return buildPlayActionInterceptor(cardType, intent)
    }

    fun parseAsCardActionEnum(dbCard: DBCard): List<CardActionEnum> {
        val intent = parseIntent(dbCard.text) ?: return listOf(CardActionEnum.NO_POINT)
        if (intent.noPointOnly) {
            return listOf(CardActionEnum.NO_POINT)
        }
        if (intent.scope === ParsedScope.ALL) {
            return listOf(CardActionEnum.NO_POINT)
        }
        val action = when (intent.side) {
            ParsedSide.MY -> when (intent.targetType) {
                DirectedTargetType.MINION -> CardActionEnum.POINT_MY_MINION
                DirectedTargetType.HERO -> CardActionEnum.POINT_MY_HERO
                DirectedTargetType.ROLE -> CardActionEnum.POINT_MY
            }

            ParsedSide.RIVAL -> when (intent.targetType) {
                DirectedTargetType.MINION -> CardActionEnum.POINT_RIVAL_MINION
                DirectedTargetType.HERO -> CardActionEnum.POINT_RIVAL_HERO
                DirectedTargetType.ROLE -> CardActionEnum.POINT_RIVAL
            }
        }
        return listOf(action)
    }

    private fun parseIntent(text: String): ParsedIntent? {
        return extractCandidateClauses(text)
            .asSequence()
            .mapNotNull(::parseClause)
            .firstOrNull()
    }

    private fun extractCandidateClauses(text: String): List<String> {
        val normalized = text
            .replace(htmlRegex, "")
            .replace(noteRegex, "")
            .replace("\n", "")
            .replace("\"", "")
            .replace("“", "")
            .replace("”", "")
            .replace(whitespaceRegex, "")
            .trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        return normalized.split(splitRegex)
            .mapNotNull { rawSegment ->
                val segment = rawSegment.trim()
                if (segment.isBlank()) {
                    return@mapNotNull null
                }
                if (blockedPrefixes.any(segment::contains) && supportedPrefixes.none(segment::contains)) {
                    return@mapNotNull null
                }
                val supportedPrefix = supportedPrefixes.firstOrNull(segment::contains)
                if (supportedPrefix != null) {
                    return@mapNotNull segment.substringAfter(supportedPrefix).trim().ifBlank { null }
                }
                segment
            }
    }

    private fun parseClause(clause: String): ParsedIntent? {
        if (isRandomDistributedDamage(clause)) {
            return ParsedIntent(
                effectType = ParsedEffectType.DAMAGE,
                side = ParsedSide.RIVAL,
                scope = ParsedScope.ALL,
                targetType = inferRandomDistributedTargetType(clause),
                noPointOnly = true,
            )
        }
        if (blockedKeywords.any(clause::contains)) {
            return null
        }
        val target = resolveTarget(clause) ?: return null
        attributeBuffRegex.find(clause)?.let { match ->
            return ParsedIntent(
                effectType = ParsedEffectType.ATTRIBUTE_BUFF,
                side = target.side,
                scope = target.scope,
                targetType = target.targetType,
                attackAmount = match.groupValues[1].toInt(),
                healthAmount = match.groupValues[2].toInt(),
            )
        }
        damageRegex.find(clause)?.let { match ->
            return ParsedIntent(
                effectType = ParsedEffectType.DAMAGE,
                side = target.side,
                scope = target.scope,
                targetType = target.targetType,
                amount = match.groupValues[1].toInt(),
            )
        }
        healRegex.find(clause)?.let { match ->
            return ParsedIntent(
                effectType = ParsedEffectType.HEAL,
                side = target.side,
                scope = target.scope,
                targetType = target.targetType,
                amount = match.groupValues[1].toInt(),
            )
        }
        healthBuffRegex.find(clause)?.let { match ->
            return ParsedIntent(
                effectType = ParsedEffectType.HEALTH_BUFF,
                side = target.side,
                scope = target.scope,
                targetType = target.targetType,
                amount = match.groupValues[1].toInt(),
            )
        }
        attackBuffRegex.find(clause)?.let { match ->
            return ParsedIntent(
                effectType = ParsedEffectType.ATK_BUFF,
                side = target.side,
                scope = target.scope,
                targetType = target.targetType,
                amount = match.groupValues[1].toInt(),
            )
        }
        if (freezeRegex.containsMatchIn(clause)) {
            return ParsedIntent(
                effectType = ParsedEffectType.FREEZE,
                side = target.side,
                scope = target.scope,
                targetType = target.targetType,
            )
        }
        return null
    }

    private fun isRandomDistributedDamage(clause: String): Boolean {
        return clause.contains("造成") &&
                clause.contains("随机分配到所有") &&
                (clause.contains("敌人身上") || clause.contains("敌方随从身上") || clause.contains("敌方角色身上"))
    }

    private fun inferRandomDistributedTargetType(clause: String): DirectedTargetType {
        return when {
            clause.contains("敌方随从") -> DirectedTargetType.MINION
            clause.contains("敌方角色") -> DirectedTargetType.ROLE
            else -> DirectedTargetType.ROLE
        }
    }

    private fun resolveTarget(clause: String): ParsedTarget? {
        if (clause.contains("每个英雄") || clause.contains("双方英雄") || clause.contains("所有角色") || clause.contains(
                "所有随从"
            )
        ) {
            return null
        }
        explicitTargets.firstOrNull { (keyword, _) -> clause.contains(keyword) }?.let { return it.second }
        return when {
            clause.contains("友方") -> ParsedTarget(ParsedSide.MY, ParsedScope.SINGLE, inferTargetType(clause))
            clause.contains("敌方") -> ParsedTarget(ParsedSide.RIVAL, ParsedScope.SINGLE, inferTargetType(clause))
            clause.contains("一个随从") -> ParsedTarget(
                guessDefaultSide(clause),
                ParsedScope.SINGLE,
                DirectedTargetType.MINION
            )

            clause.contains("一个角色") -> ParsedTarget(
                guessDefaultSide(clause),
                ParsedScope.SINGLE,
                DirectedTargetType.ROLE
            )

            clause.contains("造成") -> ParsedTarget(ParsedSide.RIVAL, ParsedScope.SINGLE, DirectedTargetType.ROLE)
            clause.contains("恢复") -> ParsedTarget(ParsedSide.MY, ParsedScope.SINGLE, DirectedTargetType.ROLE)
            clause.contains("获得") -> ParsedTarget(ParsedSide.MY, ParsedScope.SINGLE, DirectedTargetType.MINION)
            else -> null
        }
    }

    private fun inferTargetType(clause: String): DirectedTargetType {
        return when {
            clause.contains("英雄") -> DirectedTargetType.HERO
            clause.contains("角色") || clause.contains("敌人") -> DirectedTargetType.ROLE
            else -> DirectedTargetType.MINION
        }
    }

    private fun guessDefaultSide(clause: String): ParsedSide {
        return when {
            clause.contains("造成") -> ParsedSide.RIVAL
            clause.contains("恢复") -> ParsedSide.MY
            clause.contains("获得") -> ParsedSide.MY
            else -> ParsedSide.RIVAL
        }
    }

    private fun buildPlayActionInterceptor(cardType: CardTypeEnum, intent: ParsedIntent): PlayActionInterceptor? {
        return when (intent.scope) {
            ParsedScope.SINGLE -> buildSinglePlayActionInterceptor(cardType, intent)
            ParsedScope.ALL -> buildAllPlayActionInterceptor(cardType, intent)
        }
    }

    private fun buildSinglePlayActionInterceptor(cardType: CardTypeEnum, intent: ParsedIntent): PlayActionInterceptor? {
        return when (intent.effectType) {
            ParsedEffectType.DAMAGE -> createDirectedInterceptor(
                cardType,
                intent.side === ParsedSide.MY,
                intent.targetType,
                intent.amount,
                ParsedEffectType.DAMAGE
            )

            ParsedEffectType.HEAL -> createDirectedInterceptor(
                cardType,
                intent.side === ParsedSide.MY,
                intent.targetType,
                intent.amount,
                ParsedEffectType.HEAL
            )

            ParsedEffectType.HEALTH_BUFF -> createDirectedInterceptor(
                cardType,
                intent.side === ParsedSide.MY,
                intent.targetType,
                intent.amount,
                ParsedEffectType.HEALTH_BUFF
            )

            ParsedEffectType.ATK_BUFF -> createDirectedInterceptor(
                cardType,
                intent.side === ParsedSide.MY,
                intent.targetType,
                intent.amount,
                ParsedEffectType.ATK_BUFF
            )

            ParsedEffectType.ATTRIBUTE_BUFF -> createAttributeDirectedInterceptor(
                cardType,
                intent.side === ParsedSide.MY,
                intent.targetType,
                intent.attackAmount,
                intent.healthAmount
            )

            ParsedEffectType.FREEZE -> createFreezeDirectedInterceptor(
                cardType,
                intent.side === ParsedSide.MY,
                intent.targetType,
            )
        }
    }

    private fun buildAllPlayActionInterceptor(cardType: CardTypeEnum, intent: ParsedIntent): PlayActionInterceptor? {
        return when (intent.side) {
            ParsedSide.MY -> when (intent.effectType) {
                ParsedEffectType.DAMAGE -> createFriendlyAllDirectedInterceptor(
                    cardType,
                    intent.targetType,
                    intent.amount,
                    ParsedEffectType.DAMAGE
                )

                ParsedEffectType.HEAL -> createFriendlyAllDirectedInterceptor(
                    cardType,
                    intent.targetType,
                    intent.amount,
                    ParsedEffectType.HEAL
                )

                ParsedEffectType.HEALTH_BUFF -> createFriendlyAllDirectedInterceptor(
                    cardType,
                    intent.targetType,
                    intent.amount,
                    ParsedEffectType.HEALTH_BUFF
                )

                ParsedEffectType.ATK_BUFF -> createFriendlyAllDirectedInterceptor(
                    cardType,
                    intent.targetType,
                    intent.amount,
                    ParsedEffectType.ATK_BUFF
                )

                ParsedEffectType.ATTRIBUTE_BUFF -> createAllAttributeInterceptor(
                    cardType,
                    true,
                    intent.targetType,
                    intent.attackAmount,
                    intent.healthAmount
                )

                ParsedEffectType.FREEZE -> createAllFreezeInterceptor(cardType, true, intent.targetType)
            }

            ParsedSide.RIVAL -> when (intent.effectType) {
                ParsedEffectType.DAMAGE -> createEnemyAllDirectedInterceptor(
                    cardType,
                    intent.targetType,
                    intent.amount,
                    ParsedEffectType.DAMAGE
                )

                ParsedEffectType.HEAL -> createEnemyAllDirectedInterceptor(
                    cardType,
                    intent.targetType,
                    intent.amount,
                    ParsedEffectType.HEAL
                )

                ParsedEffectType.HEALTH_BUFF -> createEnemyAllDirectedInterceptor(
                    cardType,
                    intent.targetType,
                    intent.amount,
                    ParsedEffectType.HEALTH_BUFF
                )

                ParsedEffectType.ATK_BUFF -> createEnemyAllDirectedInterceptor(
                    cardType,
                    intent.targetType,
                    intent.amount,
                    ParsedEffectType.ATK_BUFF
                )

                ParsedEffectType.ATTRIBUTE_BUFF -> createAllAttributeInterceptor(
                    cardType,
                    false,
                    intent.targetType,
                    intent.attackAmount,
                    intent.healthAmount
                )

                ParsedEffectType.FREEZE -> createAllFreezeInterceptor(cardType, false, intent.targetType)
            }
        }
    }

    private fun createEnemyAllDirectedInterceptor(
        cardType: CardTypeEnum,
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        return if (cardType === CardTypeEnum.SPELL) {
            createSpellAllRivalInterceptor(target, amount, effectType)
        } else {
            createMinionAllRivalInterceptor(target, amount, effectType)
        }
    }

    private fun createDirectedInterceptor(
        cardType: CardTypeEnum,
        isFriendly: Boolean,
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        return if (cardType === CardTypeEnum.SPELL) {
            if (isFriendly) {
                createSpellPointMyInterceptor(target, amount, effectType)
            } else {
                createSpellPointRivalInterceptor(target, amount, effectType)
            }
        } else {
            if (isFriendly) {
                createMinionPointMyInterceptor(target, amount, effectType)
            } else {
                createMinionPointRivalInterceptor(target, amount, effectType)
            }
        }
    }

    private fun createFriendlyAllDirectedInterceptor(
        cardType: CardTypeEnum,
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        return if (cardType === CardTypeEnum.SPELL) {
            createSpellAllMyInterceptor(target, amount, effectType)
        } else {
            createMinionAllMyInterceptor(target, amount, effectType)
        }
    }

    private fun createAttributeDirectedInterceptor(
        cardType: CardTypeEnum,
        isFriendly: Boolean,
        target: DirectedTargetType,
        attackAmount: Int,
        healthAmount: Int,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(isFriendly, target)
        return if (cardType === CardTypeEnum.SPELL) {
            if (isFriendly) {
                SpellPointMyAttributeBuffPlayActionInterceptor(attackAmount, healthAmount, targetDomain)
            } else {
                SpellPointRivalAttributeBuffPlayActionInterceptor(attackAmount, healthAmount, targetDomain)
            }
        } else {
            if (isFriendly) {
                MinionPointMyAttributeBuffPlayActionInterceptor(attackAmount, healthAmount, targetDomain)
            } else {
                MinionPointRivalAttributeBuffPlayActionInterceptor(attackAmount, healthAmount, targetDomain)
            }
        }
    }

    private fun createAllAttributeInterceptor(
        cardType: CardTypeEnum,
        isFriendly: Boolean,
        target: DirectedTargetType,
        attackAmount: Int,
        healthAmount: Int,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(isFriendly, target)
        return if (cardType === CardTypeEnum.SPELL) {
            if (isFriendly) {
                SpellAllMyAttributeBuffPlayActionInterceptor(attackAmount, healthAmount, targetDomain)
            } else {
                SpellAllRivalAttributeBuffPlayActionInterceptor(attackAmount, healthAmount, targetDomain)
            }
        } else {
            if (isFriendly) {
                MinionAllMyAttributeBuffPlayActionInterceptor(attackAmount, healthAmount, targetDomain)
            } else {
                MinionAllRivalAttributeBuffPlayActionInterceptor(attackAmount, healthAmount, targetDomain)
            }
        }
    }

    private fun createFreezeDirectedInterceptor(
        cardType: CardTypeEnum,
        isFriendly: Boolean,
        target: DirectedTargetType,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(isFriendly, target)
        return if (cardType === CardTypeEnum.SPELL) {
            if (isFriendly) {
                SpellPointMyFreezePlayActionInterceptor(targetDomain)
            } else {
                SpellPointRivalFreezePlayActionInterceptor(targetDomain)
            }
        } else {
            if (isFriendly) {
                MinionPointMyFreezePlayActionInterceptor(targetDomain)
            } else {
                MinionPointRivalFreezePlayActionInterceptor(targetDomain)
            }
        }
    }

    private fun createAllFreezeInterceptor(
        cardType: CardTypeEnum,
        isFriendly: Boolean,
        target: DirectedTargetType,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(isFriendly, target)
        return if (cardType === CardTypeEnum.SPELL) {
            if (isFriendly) {
                SpellAllMyFreezePlayActionInterceptor(targetDomain)
            } else {
                SpellAllRivalFreezePlayActionInterceptor(targetDomain)
            }
        } else {
            if (isFriendly) {
                MinionAllMyFreezePlayActionInterceptor(targetDomain)
            } else {
                MinionAllRivalFreezePlayActionInterceptor(targetDomain)
            }
        }
    }

    private fun createSpellPointRivalInterceptor(
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(false, target)
        return when (effectType) {
            ParsedEffectType.DAMAGE -> SpellPointRivalDamagePlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEAL -> SpellPointRivalHealPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEALTH_BUFF -> SpellPointRivalHealthBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATK_BUFF -> SpellPointRivalAtkBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATTRIBUTE_BUFF -> error("组合属性增益需走专用分支")
            ParsedEffectType.FREEZE -> SpellPointRivalFreezePlayActionInterceptor(targetDomain)
        }
    }

    private fun createSpellPointMyInterceptor(
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(true, target)
        return when (effectType) {
            ParsedEffectType.DAMAGE -> SpellPointMyDamagePlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEAL -> SpellPointMyHealPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEALTH_BUFF -> SpellPointMyHealthBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATK_BUFF -> SpellPointMyAtkBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATTRIBUTE_BUFF -> error("组合属性增益需走专用分支")
            ParsedEffectType.FREEZE -> SpellPointMyFreezePlayActionInterceptor(targetDomain)
        }
    }

    private fun createSpellAllRivalInterceptor(
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(false, target)
        return when (effectType) {
            ParsedEffectType.DAMAGE -> SpellAllRivalDamagePlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEAL -> SpellAllRivalHealPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEALTH_BUFF -> SpellAllRivalHealthBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATK_BUFF -> SpellAllRivalAtkBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATTRIBUTE_BUFF -> error("组合属性增益需走专用分支")
            ParsedEffectType.FREEZE -> SpellAllRivalFreezePlayActionInterceptor(targetDomain)
        }
    }

    private fun createSpellAllMyInterceptor(
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(true, target)
        return when (effectType) {
            ParsedEffectType.DAMAGE -> SpellAllMyDamagePlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEAL -> SpellAllMyHealPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEALTH_BUFF -> SpellAllMyHealthBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATK_BUFF -> SpellAllMyAtkBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATTRIBUTE_BUFF -> error("组合属性增益需走专用分支")
            ParsedEffectType.FREEZE -> SpellAllMyFreezePlayActionInterceptor(targetDomain)
        }
    }

    private fun createMinionPointRivalInterceptor(
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(false, target)
        return when (effectType) {
            ParsedEffectType.DAMAGE -> MinionPointRivalDamagePlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEAL -> MinionPointRivalHealPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEALTH_BUFF -> MinionPointRivalHealthBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATK_BUFF -> MinionPointRivalAtkBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATTRIBUTE_BUFF -> error("组合属性增益需走专用分支")
            ParsedEffectType.FREEZE -> MinionPointRivalFreezePlayActionInterceptor(targetDomain)
        }
    }

    private fun createMinionPointMyInterceptor(
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(true, target)
        return when (effectType) {
            ParsedEffectType.DAMAGE -> MinionPointMyDamagePlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEAL -> MinionPointMyHealPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEALTH_BUFF -> MinionPointMyHealthBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATK_BUFF -> MinionPointMyAtkBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATTRIBUTE_BUFF -> error("组合属性增益需走专用分支")
            ParsedEffectType.FREEZE -> MinionPointMyFreezePlayActionInterceptor(targetDomain)
        }
    }

    private fun createMinionAllRivalInterceptor(
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(false, target)
        return when (effectType) {
            ParsedEffectType.DAMAGE -> MinionAllRivalDamagePlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEAL -> MinionAllRivalHealPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEALTH_BUFF -> MinionAllRivalHealthBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATK_BUFF -> MinionAllRivalAtkBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATTRIBUTE_BUFF -> error("组合属性增益需走专用分支")
            ParsedEffectType.FREEZE -> MinionAllRivalFreezePlayActionInterceptor(targetDomain)
        }
    }

    private fun createMinionAllMyInterceptor(
        target: DirectedTargetType,
        amount: Int,
        effectType: ParsedEffectType,
    ): PlayActionInterceptor {
        val targetDomain = resolveTargetDomain(true, target)
        return when (effectType) {
            ParsedEffectType.DAMAGE -> MinionAllMyDamagePlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEAL -> MinionAllMyHealPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.HEALTH_BUFF -> MinionAllMyHealthBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATK_BUFF -> MinionAllMyAtkBuffPlayActionInterceptor(amount, targetDomain)
            ParsedEffectType.ATTRIBUTE_BUFF -> error("组合属性增益需走专用分支")
            ParsedEffectType.FREEZE -> MinionAllMyFreezePlayActionInterceptor(targetDomain)
        }
    }

    private fun resolveTargetDomain(isFriendly: Boolean, target: DirectedTargetType): TargetDomain {
        return when {
            isFriendly && target === DirectedTargetType.MINION -> MyMinionTargetDomain
            isFriendly && target === DirectedTargetType.HERO -> MyHeroTargetDomain
            isFriendly -> MyRoleTargetDomain
            !isFriendly && target === DirectedTargetType.MINION -> RivalMinionTargetDomain
            !isFriendly && target === DirectedTargetType.HERO -> RivalHeroTargetDomain
            else -> RivalRoleTargetDomain
        }
    }
}
