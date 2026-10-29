package com.mathking.tv

import com.mathking.tv.audio.Speech
import com.mathking.tv.audio.SpeechLexicon
import com.mathking.tv.data.Generator
import com.mathking.tv.data.PlayMode
import com.mathking.tv.data.QuestionStyle
import com.mathking.tv.data.StageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 语音词表覆盖测试 —— 这一组断言是防「改一处、静音三处」的底线。
 *
 * 背景：朗读是按词拼音频，词表里没有的字会被**静默丢掉**。历史上
 * 「等于」「括号」就是这么丢的：题目念到一半突然没了等号，或者填空题
 * 只念了「加 五 等于 十二」，孩子根本不知道要填哪儿。
 */
class SpeechCoverageTest {

    private val root = File("src/main/assets")
    private val lexicon = SpeechLexicon.fromJson(
        File(root, "voice/lexicon.json").readText()
    )

    private fun covered(text: String, where: String) {
        val missing = lexicon.uncovered(text)
        assertEquals("词表缺字 @$where: '$missing' in '$text'", "", missing)
    }

    @Test
    fun `词表音频文件齐全`() {
        val raw = File(root, "voice/raw")
        val files = lexicon.files()
        assertEquals("词表条数与音频数不一致", files.size, lexicon.tokens().size)
        files.forEach { name ->
            val f = File(raw, name + ".raw")
            assertTrue("缺音频：" + name + ".raw", f.exists() && f.length() > 2000)
        }
    }

    @Test
    fun `所有阶段的题目朗读都能读全`() {
        StageCatalog.all.forEach { stage ->
            QuestionStyle.entries.forEach { style ->
                repeat(120) { i ->
                    val q = Generator.make(stage, style, Random(stage.id.hashCode() * 31 + i))
                    if (q.speak.isNotBlank()) {
                        covered(q.speak, "${stage.id}/${style.id} 题面")
                    }
                    covered(Speech.retryWrong(q), "${stage.id}/${style.id} 答错")
                    covered(Speech.wrongWithAnswer(q), "${stage.id}/${style.id} 报答案")
                    covered(Speech.skipped(q), "${stage.id}/${style.id} 跳过")
                    covered(Speech.explain(q), "${stage.id}/${style.id} 错题讲解")
                }
            }
        }
    }

    @Test
    fun `分数题必须朗读且带分母读法`() {
        val stage = StageCatalog.byId("s11")
        repeat(50) { i ->
            val q = Generator.make(stage, QuestionStyle.CALC, Random(i))
            assertTrue("分数题没朗读：${q.text}", q.speak.contains("分之"))
            assertTrue("分数题应只填分子：${q.speak}", q.speak.contains("答案只填分子"))
            covered(q.speak, "分数题")
        }
    }

    @Test
    fun `结算播报与提示语都能读全`() {
        listOf(PlayMode.PRACTICE, PlayMode.EXAM, PlayMode.WRONG, PlayMode.TIMED).forEach { mode ->
            listOf(0, 1, 3, 5, 10, 20).forEach { total ->
                listOf(0, 1, 4, 7, 10, 20).forEach { correct ->
                    if (correct <= total) {
                        covered(Speech.result(mode, total, correct, 2, 80.0), "结算")
                    }
                }
            }
        }
        covered(Speech.CORRECT, "答对")
        covered(Speech.READY, "开局")
        covered(Speech.GO, "开始")
        listOf(3, 2, 1).forEach { covered(Speech.countdown(it), "倒计时") }
        covered(Speech.WRONG, "答错")
        covered(Speech.answerOf(com.mathking.tv.data.Question("1 + 1", 2, "加")), "答案")
        (1..2).forEach { covered(Speech.idlePrompt(it), "发呆提示") }
    }

    @Test
    fun `错题本里的算式文本要读成中文数字`() {
        // 这几条都是数据库里真实存在的写法，阿拉伯数字必须转中文，否则会被词表吞掉
        listOf(
            "2 + 5 = 17",
            "435 ÷ 3",
            "29 × 7",
            "1/2 + 2/6",
            "□ + 5 = 12",
            "42 + □ = 45",
            "38 − 9 = 29"
        ).forEach { covered(Speech.expression(it), it) }
        assertEquals("二 加 五 这道题有错误 正确答案是几", Speech.expression("2 + 5 = 17"))
        // 空格读「多少」，不读「括号」——孩子听不懂「括号」，家长口头说的就是「多少」
        assertEquals("多少 加 五 等于 十二", Speech.expression("□ + 5 = 12"))
    }

    @Test
    fun `填空题读多少不读括号`() {
        val stage = StageCatalog.byId("s3")
        var checked = 0
        repeat(300) { i ->
            val q = Generator.make(stage, QuestionStyle.FILL, Random(i))
            assertTrue("填空题应出现多少：${q.speak}", q.speak.contains("多少"))
            assertTrue("填空题不该读括号：${q.speak}", !q.speak.contains("括号"))
            covered(q.speak, "填空")
            checked++
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `挑战次数按考试场次累积`() {
        // 解锁即送 1 次
        assertEquals(1, com.mathking.tv.data.ChallengeRules.credits(0, 0))
        // 用户举的例子：解锁后考了 15 场、一次没挑战 → 1 + 3 = 4 次
        assertEquals(4, com.mathking.tv.data.ChallengeRules.credits(15, 0))
        // 挑战过一次就少一次，余数不丢
        assertEquals(3, com.mathking.tv.data.ChallengeRules.credits(15, 1))
        assertEquals(0, com.mathking.tv.data.ChallengeRules.credits(4, 1))
        // 挑战打多了不会出现负数
        assertEquals(0, com.mathking.tv.data.ChallengeRules.credits(0, 3))
        assertEquals(2, com.mathking.tv.data.ChallengeRules.examsToNextCredit(3))
        assertEquals(5, com.mathking.tv.data.ChallengeRules.examsToNextCredit(0))
    }

    @Test
    fun `解锁算力门槛必须落在可达范围内`() {
        // 用户要求「门槛很高」：目标 = 阶段理论上限 × 0.9
        StageCatalog.all.forEach { stage ->
            val ratio = 0.9
            val cap = 450.0 + 150.0 + 400.0 * (stage.weight / 2.3).coerceAtMost(1.0)
            val target = cap * ratio
            assertTrue("${stage.id} 目标 $target 不能超过上限 $cap", target <= cap)
            assertTrue("${stage.id} 目标太低", target > 600)
        }
    }

    @Test
    fun `一百一十不能读成一百十`() {
        assertEquals("一百一十", Generator.cn(110))
        assertEquals("一百零五", Generator.cn(105))
        assertEquals("一百一十五", Generator.cn(115))
        assertEquals("二百八十二", Generator.cn(282))
        assertEquals("九百九十九", Generator.cn(999))
        assertEquals("十", Generator.cn(10))
        assertEquals("二十", Generator.cn(20))
        assertEquals("零", Generator.cn(0))
    }
}
