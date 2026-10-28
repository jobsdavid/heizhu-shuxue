package com.mathking.tv.data

import kotlin.random.Random

/**
 * 出题器：按阶段生成题目，并给出知识点标签（用于统计）和朗读文本（给 TTS）。
 */
object Generator {

    fun make(
        stage: StageConfig,
        style: QuestionStyle = QuestionStyle.CALC,
        rnd: Random = Random.Default
    ): Question {
        val base = if (stage.fraction) fraction(stage, rnd) else integer(stage, rnd)
        val wantFind = when (style) {
            QuestionStyle.CALC -> false
            QuestionStyle.FIND -> true
            QuestionStyle.MIX -> rnd.nextInt(100) < 30
        }
        return if (wantFind) toFindError(base, rnd) else base
    }

    /** 把一道正常题改造成「找错题」：等号后面故意填一个错答案 */
    private fun toFindError(q: Question, rnd: Random): Question {
        val wrong = wrongAnswer(q.answer, rnd)
        val stem = q.speak.removeSuffix("等于几").trim()
        return q.copy(
            text = "${q.text} = $wrong",
            tag = "找错·${q.tag}",
            speak = "$stem 等于 ${cn(wrong)} 这道题有错误 正确答案是几",
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

    // ---------------------------------------------------------------- 整数题

    private fun integer(stage: StageConfig, rnd: Random): Question {
        val op = stage.ops[rnd.nextInt(stage.ops.size)]
        return when (op) {
            Op.ADD -> add(stage.maxValue, rnd)
            Op.SUB -> sub(stage.maxValue, rnd)
            Op.MUL -> mul(rnd)
            Op.DIV -> div(rnd)
        }
    }

    private fun add(max: Int, rnd: Random): Question {
        val a = rnd.nextInt(1, max)            // 1..max-1
        val b = rnd.nextInt(1, max - a + 1)    // 1..max-a
        val carry = (a % 10) + (b % 10) >= 10
        val tag = if (carry) "进位加" else "不进位加"
        val hint = if (carry) "个位满十，记得进一" else ""
        return Question(
            text = "$a + $b",
            answer = a + b,
            tag = tag,
            speak = "${cn(a)} 加 ${cn(b)} 等于几",
            hint = hint
        )
    }

    private fun sub(max: Int, rnd: Random): Question {
        val a = rnd.nextInt(2, max + 1)        // 2..max
        val b = rnd.nextInt(1, a)              // 1..a-1
        val borrow = (a % 10) < (b % 10)
        val tag = if (borrow) "退位减" else "不退位减"
        val hint = if (borrow) "个位不够减，向十位借一" else ""
        return Question(
            text = "$a − $b",
            answer = a - b,
            tag = tag,
            speak = "${cn(a)} 减 ${cn(b)} 等于几",
            hint = hint
        )
    }

    private fun mul(rnd: Random): Question {
        val a = rnd.nextInt(2, 10)
        val b = rnd.nextInt(2, 10)
        return Question(
            text = "$a × $b",
            answer = a * b,
            tag = "表内乘",
            speak = "${cn(a)} 乘 ${cn(b)} 等于几",
            hint = "想乘法口诀"
        )
    }

    private fun div(rnd: Random): Question {
        val b = rnd.nextInt(2, 10)
        val q = rnd.nextInt(2, 10)
        return Question(
            text = "${b * q} ÷ $b",
            answer = q,
            tag = "表内除",
            speak = "${cn(b * q)} 除以 ${cn(b)} 等于几",
            hint = "想：几乘 ${cn(b)} 等于 ${cn(b * q)}"
        )
    }

    // ---------------------------------------------------------------- 分数题

    /** 同分母分数加减：分母不变，只算分子，答案就是一个整数（分子）。 */
    private fun fraction(stage: StageConfig, rnd: Random): Question {
        val d = rnd.nextInt(3, stage.maxValue + 1)
        val plus = rnd.nextBoolean()
        return if (plus) {
            val n1 = rnd.nextInt(1, d - 1)              // 1..d-2
            val n2 = rnd.nextInt(1, d - n1)             // 1..d-1-n1，保证和仍为真分数
            Question(
                text = "$n1/$d + $n2/$d",
                answer = n1 + n2,
                tag = "同分母加",
                answerText = "${n1 + n2}/$d",
                speak = "${cn(d)}分之${cn(n1)} 加 ${cn(d)}分之${cn(n2)} 等于几",
                hint = "分母不变，分子相加"
            )
        } else {
            val n1 = rnd.nextInt(2, d)                  // 2..d-1
            val n2 = rnd.nextInt(1, n1)                 // 1..n1-1
            Question(
                text = "$n1/$d − $n2/$d",
                answer = n1 - n2,
                tag = "同分母减",
                answerText = "${n1 - n2}/$d",
                speak = "${cn(d)}分之${cn(n1)} 减 ${cn(d)}分之${cn(n2)} 等于几",
                hint = "分母不变，分子相减"
            )
        }
    }

    // ---------------------------------------------------------------- 朗读用中文数字

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
        return n.toString()
    }
}
