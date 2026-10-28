package com.mathking.tv.data

import kotlin.random.Random

/**
 * 出题器。按 StageKind 分流，再按 QuestionStyle 变形（找错 / 填空）。
 *
 * 三条硬约束：
 * 1. 结果永远是非负整数（分数题例外：答案是分子，分母由题目固定）；
 * 2. 同一个阶段内，进位/退位、数域都由 StageConfig 决定，不在出题时随机放宽；
 * 3. 分数题的答案只填分子，分母写成固定值（如 = ?/5），这样数字键盘就能答。
 */
object Generator {

    /** 变形方式 */
    private enum class Shape { CALC, FIND, FILL }

    fun make(
        stage: StageConfig,
        style: QuestionStyle = QuestionStyle.CALC,
        rnd: Random = Random.Default
    ): Question {
        val base = when (stage.kind) {
            StageKind.ADD_SUB -> addSub(stage, rnd)
            StageKind.MUL -> mul(stage, rnd)
            StageKind.DIV -> div(stage, rnd)
            StageKind.MUL_DIV -> if (rnd.nextBoolean()) mul(stage, rnd) else div(stage, rnd)
            StageKind.FOUR_OPS -> fourOps(stage, rnd)
            StageKind.FRACTION_SAME -> fractionSame(stage, rnd)
            StageKind.FRACTION_DIFF -> fractionDiff(stage, rnd)
        }
        val shape = when (style) {
            QuestionStyle.CALC -> Shape.CALC
            QuestionStyle.FIND -> Shape.FIND
            QuestionStyle.FILL -> Shape.FILL
            // 混合：计算为主，找错和填空各占一成多
            QuestionStyle.MIX -> when (rnd.nextInt(10)) {
                0, 1 -> Shape.FIND
                2 -> Shape.FILL
                else -> Shape.CALC
            }
        }
        // 分数题没法填空题（要固定分母），退化成计算
        val actual = if (stage.fraction && shape == Shape.FILL) Shape.CALC else shape
        return when (actual) {
            Shape.CALC -> base
            Shape.FIND -> toFindError(base, rnd)
            Shape.FILL -> fill(stage, rnd)
        }
    }

    // ------------------------------------------------------------ 变形：找错

    /** 把一道正常题改造成「找错题」：等号后面故意填一个错答案 */
    private fun toFindError(q: Question, rnd: Random): Question {
        val wrong = if (q.answerDen > 0) {
            // 分数题：只在分子上做手脚，别把分母改掉（那样错得太离谱）
            val pool = listOf(q.answer + 1, q.answer - 1, q.answer + 2)
                .filter { it > 0 && it < q.answerDen && it != q.answer }
            if (pool.isEmpty()) q.answer + 1 else pool.random(rnd)
        } else {
            wrongAnswer(q.answer, rnd)
        }
        val wrongText = if (q.answerDen > 0) "$wrong/${q.answerDen}" else wrong.toString()
        val stem = q.speak.removeSuffix("等于几").trim()
        return q.copy(
            text = "${q.text} = $wrongText",
            tag = "找错·${q.tag}",
            speak = if (q.speak.isBlank()) "" else "$stem 等于 ${cn(wrong)} 这道题有错误 正确答案是几",
            findError = true,
            hint = "这道题有错误，重新算一遍，填正确答案"
        )
    }

    private fun wrongAnswer(correct: Int, rnd: Random): Int {
        val pool = mutableListOf<Int>()
        pool.add(correct + 1)
        pool.add(correct + 2)
        if (correct > 1) pool.add(correct - 1)
        pool.add(correct + 10)
        if (correct > 10) pool.add(correct - 10)
        val s = correct.toString()
        if (s.length == 2 && s[0] != s[1] && s[1] != '0') {
            s.reversed().toIntOrNull()?.let { pool.add(it) }
        }
        pool.removeAll { it == correct || it < 0 }
        return pool.random(rnd)
    }

    // ------------------------------------------------------------ 变形：填空

    /**
     * 填空：把算式里的一个数挖掉，孩子填这个数（小学低年级叫「求括号里的数」）。
     * 题目本体先走正常出题器，保证和该阶段的其他题一样遵守范围 / 进退位规则，
     * 再把其中一个数换成方框——否则填空题会绕过 s2 的「必须进退位」这类约束。
     */
    private fun fill(stage: StageConfig, rnd: Random): Question {
        val base = when (stage.kind) {
            StageKind.ADD_SUB -> addSub(stage, rnd)
            StageKind.MUL -> mul(stage, rnd)
            StageKind.DIV -> div(stage, rnd)
            StageKind.MUL_DIV -> if (rnd.nextBoolean()) mul(stage, rnd) else div(stage, rnd)
            StageKind.FOUR_OPS -> fourOps(stage, rnd)
            else -> addSub(stage, rnd)
        }
        val parts = base.text.split(" ")
        if (parts.size < 3) return base
        val a = parts[0].toIntOrNull() ?: return base
        val opSym = parts[1]
        val b = parts[2].toIntOrNull() ?: return base
        val c = base.answer
        val op = stage.ops.firstOrNull { it.symbol == opSym } ?: Op.ADD

        return if (rnd.nextBoolean()) {
            Question(
                text = "□ $opSym $b = $c",
                answer = a,
                tag = "填空·$opSym",
                speak = "括号里填几：括号 ${op.speak} ${cn(b)} 等于 ${cn(c)}",
                hint = "倒着想：${cn(c)} ${if (op == Op.ADD) "减" else if (op == Op.MUL) "除以" else "减"} ${cn(b)}"
            )
        } else {
            Question(
                text = "$a $opSym □ = $c",
                answer = b,
                tag = "填空·$opSym",
                speak = "括号里填几：${cn(a)} ${op.speak} 括号 等于 ${cn(c)}",
                hint = if (op == Op.SUB) "倒着想：${cn(a)} 减 ${cn(c)}"
                else if (op == Op.DIV) "倒着想：${cn(a)} 除以 ${cn(c)}"
                else "倒着想：${cn(c)} ${if (op == Op.ADD) "减" else "除以"} ${cn(a)}"
            )
        }
    }

    /** 生成 a op b = c 的三元组，供填空复用 */
    private fun triple(stage: StageConfig, op: Op, rnd: Random): Triple<Int, Int, Int> {
        return when (op) {
            Op.ADD -> {
                val a = rnd.nextInt(1, stage.maxValue)
                val b = rnd.nextInt(1, stage.maxValue - a + 1)
                Triple(a, b, a + b)
            }
            Op.SUB -> {
                val a = rnd.nextInt(2, stage.maxValue + 1)
                val b = rnd.nextInt(1, a)
                Triple(a, b, a - b)
            }
            Op.MUL -> {
                val a = rnd.nextInt(2, 10)
                val b = rnd.nextInt(2, 10)
                Triple(a, b, a * b)
            }
            Op.DIV -> {
                val b = rnd.nextInt(2, 10)
                val q = rnd.nextInt(2, 10)
                Triple(b * q, b, q)
            }
        }
    }

    // ------------------------------------------------------------ 加减（一体）

    private fun addSub(stage: StageConfig, rnd: Random): Question {
        val plus = if (stage.ops.size == 1) stage.ops[0] == Op.ADD
        else if (stage.ops.contains(Op.ADD) && stage.ops.contains(Op.SUB)) rnd.nextBoolean()
        else stage.ops.contains(Op.ADD)
        return if (plus) addQ(stage, rnd) else subQ(stage, rnd)
    }

    private fun addQ(stage: StageConfig, rnd: Random): Question {
        val max = stage.maxValue
        val min = stage.minValue.coerceAtLeast(1)
        // 50/100 以内这种范围，至少要有一个加数是两位数，否则净出 6+2 这种，练不到重点
        val aMin = if (max >= 30) maxOf(min, 10) else min
        val bMin = if (max >= 30 && rnd.nextBoolean()) 10 else min
        var a = aMin
        var b = bMin
        var tries = 0
        while (tries++ < 400) {
            // a 最多取到 max-1；剩下的空间要够放 b，否则 nextInt 的 bound 会小于 origin 直接崩
            if (aMin >= max) break
            a = rnd.nextInt(aMin, max)
            val room = max - a
            if (room < 1) continue
            val bLo = if (bMin in 1..room) bMin else min
            if (bLo > room) continue
            b = rnd.nextInt(bLo, room + 1)
            val carry = (a % 10) + (b % 10) >= 10
            val ok = when (stage.carry) {
                CarryRule.ANY -> true
                CarryRule.REQUIRE -> carry
                CarryRule.AVOID -> !carry
            }
            if (ok && a + b <= max) break
        }
        val carry = (a % 10) + (b % 10) >= 10
        return Question(
            text = "$a + $b",
            answer = a + b,
            tag = if (carry) "进位加" else "不进位加",
            speak = "${cn(a)} 加 ${cn(b)} 等于几",
            hint = if (carry) "个位满十，记得向十位进一" else ""
        )
    }

    private fun subQ(stage: StageConfig, rnd: Random): Question {
        val max = stage.maxValue
        val min = stage.minValue.coerceAtLeast(1)
        // 同理：50 以内/100 以内要出两位数的被减数
        val lo = if (max >= 30) maxOf(min + 1, 10) else min + 1
        var a = max
        var b = 1
        var tries = 0
        while (tries++ < 400) {
            if (lo > max) break
            a = rnd.nextInt(lo, max + 1)
            if (a <= min) continue
            b = rnd.nextInt(min, a)
            val borrow = (a % 10) < (b % 10)
            val ok = when (stage.carry) {
                CarryRule.ANY -> true
                CarryRule.REQUIRE -> borrow
                CarryRule.AVOID -> !borrow
            }
            if (ok) break
        }
        val borrow = (a % 10) < (b % 10)
        return Question(
            text = "$a − $b",
            answer = a - b,
            tag = if (borrow) "退位减" else "不退位减",
            speak = "${cn(a)} 减 ${cn(b)} 等于几",
            hint = if (borrow) "个位不够减，向十位借一当十" else ""
        )
    }

    // ------------------------------------------------------------ 乘法

    private fun mul(stage: StageConfig, rnd: Random): Question {
        // 表内乘法（maxValue <= 9）vs 两位数乘一位数
        return if (stage.maxValue <= 9) {
            val a = rnd.nextInt(2, 10)
            val b = rnd.nextInt(2, 10)
            Question(
                text = "$a × $b",
                answer = a * b,
                tag = "表内乘",
                speak = "${cn(a)} 乘 ${cn(b)} 等于几",
                hint = "想乘法口诀"
            )
        } else {
            val a = rnd.nextInt(10, stage.maxValue + 1)
            val b = rnd.nextInt(2, 10)
            val tens = a / 10 * 10
            val ones = a % 10
            Question(
                text = "$a × $b",
                answer = a * b,
                tag = "两位数乘一位数",
                speak = "${cn(a)} 乘 ${cn(b)} 等于几",
                hint = if (ones == 0) "先算 ${tens} × ${b}"
                else "先算 ${tens} × ${b} = ${tens * b}，再算 ${ones} × ${b} = ${ones * b}"
            )
        }
    }

    // ------------------------------------------------------------ 除法

    private fun div(stage: StageConfig, rnd: Random): Question {
        return if (stage.maxValue <= 9) {
            val b = rnd.nextInt(2, 10)
            val q = rnd.nextInt(2, 10)
            Question(
                text = "${b * q} ÷ $b",
                answer = q,
                tag = "表内除",
                speak = "${cn(b * q)} 除以 ${cn(b)} 等于几",
                hint = "想：几乘 ${cn(b)} 等于 ${cn(b * q)}"
            )
        } else {
            // 三位数 ÷ 一位数，整除
            val b = rnd.nextInt(2, 10)
            val qLow = (stage.minValue + b - 1) / b
            val qHigh = stage.maxValue / b
            val q = rnd.nextInt(qLow.coerceAtLeast(2), qHigh + 1)
            Question(
                text = "${b * q} ÷ $b",
                answer = q,
                tag = "三位数除一位数",
                speak = "${cn(b * q)} 除以 ${cn(b)} 等于几",
                hint = "从最高位开始除，除到哪一位，商就写在哪一位上"
            )
        }
    }

    // ------------------------------------------------------------ 四则混合

    private fun fourOps(stage: StageConfig, rnd: Random): Question {
        return when (stage.ops[rnd.nextInt(stage.ops.size)]) {
            Op.ADD -> addQ(stage, rnd)
            Op.SUB -> subQ(stage, rnd)
            Op.MUL -> {
                val a = rnd.nextInt(2, 10)
                val b = rnd.nextInt(2, 10)
                Question("$a × $b", a * b, "表内乘", speak = "${cn(a)} 乘 ${cn(b)} 等于几", hint = "先背口诀")
            }
            Op.DIV -> {
                val b = rnd.nextInt(2, 10)
                val q = rnd.nextInt(2, 10)
                Question(
                    "${b * q} ÷ $b", q, "表内除",
                    speak = "${cn(b * q)} 除以 ${cn(b)} 等于几",
                    hint = "想：几乘 ${cn(b)} 等于 ${cn(b * q)}"
                )
            }
        }
    }

    // ------------------------------------------------------------ 分数：同分母

    /**
     * 同分母分数加减：分母不变，分子相加减。
     * 结果保证是最简真分数（分子 < 分母），答案填分子，分母由题面给出。
     */
    private fun fractionSame(stage: StageConfig, rnd: Random): Question {
        val d = rnd.nextInt(3, stage.maxDen + 1)
        return if (rnd.nextBoolean()) {
            val n1 = rnd.nextInt(1, d - 1)
            val n2 = rnd.nextInt(1, d - n1)
            Question(
                text = "$n1/$d + $n2/$d",
                answer = n1 + n2,
                tag = "同分母加",
                answerText = "${n1 + n2}/$d",
                // 分数不朗读（电视语音包是按整数念的，念分数反而听不懂）
                speak = "",
                hint = "分母不变，分子相加：$n1 + $n2 = ?",
                answerDen = d
            )
        } else {
            val n1 = rnd.nextInt(2, d)
            val n2 = rnd.nextInt(1, n1)
            Question(
                text = "$n1/$d − $n2/$d",
                answer = n1 - n2,
                tag = "同分母减",
                answerText = "${n1 - n2}/$d",
                speak = "",
                hint = "分母不变，分子相减：$n1 − $n2 = ?",
                answerDen = d
            )
        }
    }

    // ------------------------------------------------------------ 分数：异分母

    /**
     * 异分母分数加减，但**只出一个分母是另一个倍数**的题，
     * 这样通分后的公分母就是较大那个分母，孩子只需要算分子：
     *   1/2 + 1/4  →  2/4 + 1/4 = 3/4，答案填 3，分母 4 由题面给出。
     * 这样既练了通分，又不需要在电视上用数字键盘输入分数。
     */
    private fun fractionDiff(stage: StageConfig, rnd: Random): Question {
        val small = rnd.nextInt(2, stage.maxDen / 2 + 1)
        val k = if (stage.maxDen / small >= 3 && rnd.nextBoolean()) 3 else 2
        val big = small * k
        if (big > stage.maxDen) return fractionSame(stage, rnd)

        val n1 = rnd.nextInt(1, small)          // 1..small-1，真分数
        val n1Big = n1 * k                       // 通分后的分子
        val plus = rnd.nextBoolean()
        return if (plus) {
            val maxN2 = big - n1Big - 1
            if (maxN2 < 1) return fractionSame(stage, rnd)
            val n2 = rnd.nextInt(1, maxN2 + 1)
            Question(
                text = "$n1/$small + $n2/$big",
                answer = n1Big + n2,
                tag = "异分母加",
                answerText = "${n1Big + n2}/$big",
                speak = "",
                hint = "把 $n1/$small 通分成 $n1Big/$big，再算分子 $n1Big + $n2",
                answerDen = big
            )
        } else {
            val minN2 = 1
            val maxN2 = n1Big - 1
            if (maxN2 < minN2) return fractionSame(stage, rnd)
            val n2 = rnd.nextInt(minN2, maxN2 + 1)
            Question(
                text = "$n1/$small − $n2/$big",
                answer = n1Big - n2,
                tag = "异分母减",
                answerText = "${n1Big - n2}/$big",
                speak = "",
                hint = "把 $n1/$small 通分成 $n1Big/$big，再算分子 $n1Big − $n2",
                answerDen = big
            )
        }
    }

    // ------------------------------------------------------------ 朗读用中文数字

    fun cn(n: Int): String {
        val digits = "零一二三四五六七八九"
        if (n < 0) return n.toString()
        if (n == 0) return "零"
        if (n < 10) return digits[n].toString()
        if (n < 20) return "十" + if (n % 10 == 0) "" else digits[n % 10].toString()
        if (n < 100) {
            val t = n / 10
            val o = n % 10
            return digits[t].toString() + "十" + if (o == 0) "" else digits[o].toString()
        }
        if (n < 1000) {
            val h = n / 100
            val rest = n % 100
            return digits[h].toString() + "百" + if (rest == 0) "" else (if (rest < 10) "零" else "") + cn(rest)
        }
        return n.toString()
    }
}
