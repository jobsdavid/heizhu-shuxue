package com.mathking.tv.audio

import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.Question

/**
 * 朗读句子工厂。
 *
 * 所有要念给孩子听的话都在这里拼，UI 只负责「什么时候说」。好处是：
 * 1. 词表覆盖不全的问题只在这一处暴露（单测会扫这个文件的每一个模板）；
 * 2. 语速、语气、数字读法统一，不会这里「八」那里「8」。
 */
object Speech {

    const val CORRECT = "回答正确"
    const val WRONG = "回答错误"
    const val TIMEOUT = "时间到"

    /** 答错时要读的思路提示：优先用题目自带的知识点，没有就用通用鼓励 */
    fun hintOf(q: Question?): String =
        q?.speakHint?.takeIf { it.isNotBlank() } ?: "别着急，再仔细算一遍"

    /**
     * 练习模式答错（停在本题重算）：先认错，再给思路，最后催一把。
     * 通用提示本身就带「再仔细算一遍」，别再拼个「再算一遍」，否则听着像结巴。
     */
    fun retryWrong(q: Question): String {
        val hint = q.speakHint
        return if (hint.isBlank()) "$WRONG，别着急，再仔细算一遍" else "$WRONG，$hint，再算一遍"
    }

    /** 限时/考试之类「不能重算」的模式：直接报正确答案 */
    fun wrongWithAnswer(q: Question): String = "$WRONG，正确答案是${answerOf(q)}"

    fun skipped(q: Question): String = "没关系，${hintOf(q)}"

    fun timedOutWithAnswer(q: Question): String = "$TIMEOUT，正确答案是${answerOf(q)}"

    /** 同一题停留太久，再读一遍题面（第 2 次换句更软的提示） */
    fun idlePrompt(times: Int): String =
        if (times <= 1) "再听一遍" else "慢慢想一想，别着急"

    /** 分数题只填分子，所以答案读「五分之三」；整数读中文数字 */
    fun answerOf(q: Question): String = if (q.answerDen > 0) fraction(q.answer, q.answerDen) else cn(q.answer)

    fun fraction(num: Int, den: Int): String = "${cn(den)}分之${cn(num)}"

    fun cn(n: Int): String = com.mathking.tv.data.Generator.cn(n)

    /** 一局结束的播报：成绩 + 一句针对性的建议 */
    fun result(mode: PlayMode, total: Int, correct: Int, stars: Int, accuracy: Double): String {
        val head = when (mode) {
            PlayMode.EXAM -> "考试结束"
            PlayMode.WRONG -> "错题重练结束"
            else -> "练习结束"
        }
        val advice = when {
            accuracy >= 99.9 -> "全对，太棒了，明天再来一轮"
            accuracy >= 85 -> "很不错，看清题目再算，速度还能更快"
            accuracy >= 60 -> "有几道题要多练，去错题本再练一遍吧"
            mode == PlayMode.EXAM -> "把错题练一遍，下次就能考得更好"
            else -> "慢慢来，先把会做的做对，你已经很努力了"
        }
        val starText = if (stars <= 0) "" else "，得${cn(stars)}颗星"
        return "$head，答对${cn(correct)}道题，一共${cn(total)}道题$starText。$advice"
    }

    /** 错题本里自动讲解一道题：题面 → 答案 → 思路 */
    fun explain(q: Question): String {
        val answer = answerOf(q)
        val hint = q.speakHint.takeIf { it.isNotBlank() }
        // 找错题的题面结尾是「正确答案是几」，讲解里再问一遍会绕，去掉
        val head = q.speak.removeSuffix("正确答案是几").trim()
        return buildString {
            if (head.isNotBlank()) append(head).append("，")
            append("答案是$answer")
            if (hint != null) append("，$hint")
        }
    }

    /**
     * 把算式文本（1/2 + 3/4、8 × 7、□ + 5 = 12）读成人话。
     *
     * 注意必须把**阿拉伯数字换成中文**：词表只认中文数字，
     * 直接拼 "2 加 5" 会把两个数字整段丢掉，念出来只剩「加」，孩子完全听不懂。
     */
    fun expression(expr: String): String {
        // 填空题的等号右边是要读出来的（多少 加 五 等于 十二），
        // 而找错题只读等号左边，再把错答案单独念一遍
        val hasBlank = expr.contains("□") || expr.contains("?")
        val hasEq = expr.contains("=") && !hasBlank
        var s = (if (hasBlank) expr else expr.substringBefore("=")).trim()
            .replace("+", " 加 ")
            .replace("−", " 减 ")
            .replace("-", " 减 ")
            .replace("×", " 乘 ")
            .replace("÷", " 除以 ")
            .replace("□", " 多少 ")
            .replace("?", " 多少 ")
            .replace("=", " 等于 ")
        // 先整体识别分数，再把剩下的纯数字转中文
        s = Regex("(\\d+)/(\\d+)").replace(s) { m ->
            fraction(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        s = Regex("\\d+").replace(s) { m ->
            m.value.toIntOrNull()?.let { cn(it) } ?: m.value
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        return when {
            hasEq -> "$s 这道题有错误 正确答案是几"
            hasBlank -> s          // 「多少 加 五 等于 十二」
            else -> "$s 等于几"
        }
    }

    /** 错题条目只存了 tag，按 tag 反推该念哪句思路 */
    fun hintForTag(tag: String): String = when {
        tag.contains("找错") -> "这道题算错了，重新算一遍，填正确答案"
        tag.contains("两位数乘") -> "先把两位数拆成整十和个位，分别乘，再加起来"
        tag.contains("表内乘") -> "想一想乘法口诀"
        tag.contains("除") -> "除法想乘法，几乘几等于它"
        tag.contains("进位") -> "个位满十，向十位进一"
        tag.contains("退位") -> "个位不够减，向十位借一当十"
        tag.contains("异分母") -> "先通分，变成同分母再算"
        tag.contains("同分母") -> "分母不变，分子相加减"
        tag.contains("填空") && tag.contains("×") -> "倒着想，用除法算"
        tag.contains("填空") -> "倒着想，用减法算"
        else -> ""
    }
}
