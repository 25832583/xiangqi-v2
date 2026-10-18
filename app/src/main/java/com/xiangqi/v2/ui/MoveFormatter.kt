package com.xiangqi.v2.ui

import com.xiangqi.v2.core.ChineseNotation
import com.xiangqi.v2.engine.EngineAnalysis
import java.util.Locale

/**
 * 走法结果的文字呈现。
 *
 * 单独抽出来是因为「显示成什么」和「算出来是什么」是两件事：悬浮窗要一行紧凑的
 * 「红方走: 炮二平五 +1.23」，主界面图片分析要多行详情。以后改文案、加本地化，
 * 都只动这里。
 */
object MoveFormatter {

    /** 引擎「无着可走」时的两种 UCI 写法：`bestmove (none)` / `bestmove 0000`。 */
    private const val NO_MOVE_UCI = "(none)"
    private const val ZERO_UCI = "0000"

    /** 一行推荐着法（中文记谱）。转不出来时退化成 UCI 串，再退化成破折号。 */
    fun moveText(fen: String, analysis: EngineAnalysis?): String {
        if (analysis == null || !analysis.hasMove) return "—"
        val uci = analysis.bestMoveUci
        if (uci == NO_MOVE_UCI || uci == ZERO_UCI) return "无着可走"
        return runCatching { ChineseNotation.render(fen, uci) }.getOrElse { uci }
    }

    /** 评分：`+1.23` / `-0.45` / `杀 3 步`；没有分数时给空串，不要显示成 0.00。 */
    fun scoreText(analysis: EngineAnalysis?): String {
        if (analysis == null) return ""
        analysis.mateIn?.let { return "杀 ${Math.abs(it)} 步" }
        val cp = analysis.scoreCp ?: return ""
        val sign = if (cp >= 0) "+" else ""
        return sign + String.format(Locale.US, "%.2f", cp / 100.0)
    }

    /** 主变：引擎自己认为的后续几步。中文记谱要逐个推演盘面，这里直接用 UCI，短而准。 */
    fun pvText(analysis: EngineAnalysis?, maxPlies: Int = 4): String {
        val pv = analysis?.pv ?: return ""
        if (pv.isEmpty()) return ""
        return pv.take(maxPlies).joinToString(" ")
    }

    /** 主界面图片分析用的多行详情。 */
    fun detailText(fen: String, analysis: EngineAnalysis): String = buildString {
        append("推荐: ").append(moveText(fen, analysis))
        if (analysis.hasMove) append("  (").append(analysis.bestMoveUci).append(')')
        appendLine()
        val score = scoreText(analysis)
        append("评分: ").append(score.ifEmpty { "—" })
        append("    深度: ").append(analysis.depth)
        val pv = pvText(analysis, 6)
        if (pv.isNotEmpty()) {
            appendLine()
            append("主变: ").append(pv)
        }
    }
}
