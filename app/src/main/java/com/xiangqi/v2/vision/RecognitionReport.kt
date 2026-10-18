package com.xiangqi.v2.vision

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Fen
import java.util.Locale

/**
 * 一次识别的「可读说明」：几行日志 + 哪些格子不可信。
 *
 * 为什么需要它：用户报「相变成炮了」时，看到的只是界面上一个汉字，而同一个现象
 * 至少对应三种完全不同的原因，光看界面分不出来：
 *
 *  1. 模型确实把这一格输出成了别的类别。看摘要里的 FEN 与「最低 conf」就能确认；
 *     到这一步只能靠补样本重训，改代码没用。
 *  2. 同一格上落了两个框，分数高的那个赢了。看告警里的「同格冲突」，
 *     两个分数差多少决定了该改阈值还是该改模型。
 *  3. 那一格其实没检出，界面挂着的是**上一帧**的旧盘面。这时 FEN 与子数会和
 *     屏幕上看到的局面不一致 —— 光看界面永远发现不了，看这一行就一目了然。
 *
 * 另外，[Report.uncertain] 会把「置信度偏低」的格子交给界面高亮出来。
 * 这不是锦上添花：实测把「相」的字形下半遮掉 40%，模型会翻成「红炮」，
 * 置信度从 0.93 掉到 0.49 —— **错得很自信**，用户没有任何办法察觉。
 * 有了这圈标记，同一类错误至少是**看得见**的。
 *
 * 只在**采纳新盘面**时打印（调用方负责），频率跟「对手走一步」一致，
 * 而不是每 700ms 一条。
 */
object RecognitionReport {

    /**
     * 低于这个置信度就点名。
     *
     * 取值依据是两组实测：正常帧全盘最低 0.90 左右；而**降采样混叠**导致的错类
     * 落在 0.49~0.71（「红车→红炮」0.706、「红相→红炮」0.49、「红车→红相」0.49）。
     * 原来定 0.70 时 0.706 那一档恰好漏网 —— 错得不算自信、又不够低，最该被看见的
     * 反而没提示。0.85 能盖住整个混叠区间，同时给正常帧留 0.05 余量。
     */
    const val LOW_CONFIDENCE: Float = 0.85f

    /** 最多点名几格。再多就说明整帧都不对，不必逐个列。 */
    private const val MAX_CALLED_OUT = 4

    /**
     * @param lines 第一行是摘要（总该打），其余是需要留意的告警（可能为空）
     * @param uncertain 置信度偏低的格子，编码为 `row * 9 + col`，供界面高亮
     */
    data class Report(val lines: List<String>, val uncertain: Set<Int>) {
        companion object {
            val EMPTY = Report(emptyList(), emptySet())
        }
    }

    /**
     * @param colOffset 锚定列偏移，用于把候选的原始列号换算回 0..8
     * @param rowOffset 锚定行偏移，同上
     */
    fun build(
        assembly: Assembly,
        candidates: List<CellCandidate>,
        fitX: AxisFit,
        fitY: AxisFit,
        frameWidth: Int,
        frameHeight: Int,
        colOffset: Int,
        rowOffset: Int
    ): Report {
        // 每一格最终胜出的候选（与 BoardAssembler 的冲突消解口径一致：分数最高者）
        val best = HashMap<Int, CellCandidate>()
        for (c in candidates) {
            val row = c.row - rowOffset
            val col = c.col - colOffset
            if (row !in 0 until Board.ROWS || col !in 0 until Board.COLS) continue
            val key = row * Board.COLS + col
            val current = best[key]
            if (current == null || c.detection.score > current.detection.score) best[key] = c
        }

        val board = assembly.board
        var lowest = 1f
        var lowestAt = ""
        val weak = ArrayList<String>()
        val uncertain = LinkedHashSet<Int>()
        for (row in 0 until Board.ROWS) {
            for (col in 0 until Board.COLS) {
                val piece = board[row, col] ?: continue
                val key = row * Board.COLS + col
                val cand = best[key] ?: continue
                val score = cand.detection.score
                if (score < lowest) {
                    lowest = score
                    lowestAt = "($row,$col)${piece.displayName}"
                }
                if (score < LOW_CONFIDENCE) {
                    uncertain.add(key)
                    weak.add(String.format(Locale.US, "($row,$col)%s %.2f", piece.displayName, score))
                }
            }
        }

        val lines = ArrayList<String>(4)
        lines.add(
            String.format(
                Locale.US,
                "识别 %s · %d 子 · 最低 conf %.2f%s · 帧 %dx%d · 格距 %.1fx%.1f · 残差 %.3f/%.3f",
                Fen.boardPart(board),
                assembly.pieceCount,
                lowest,
                if (lowestAt.isEmpty()) "" else " @$lowestAt",
                frameWidth, frameHeight,
                fitX.pitch, fitY.pitch,
                fitX.residual, fitY.residual
            )
        )

        if (weak.isNotEmpty()) {
            lines.add("低置信 ${weak.size} 格：" + weak.take(MAX_CALLED_OUT).joinToString("，"))
            lines.add("⚠ 这几格最可能认错，界面上已用虚线圈出，别按它走")
        }

        // 同格冲突：那一格上本来有两个框，看两个分数差多少
        val conflicts = assembly.conflicts.take(MAX_CALLED_OUT).joinToString("；") { c ->
            val others = c.dropped.joinToString("/") {
                String.format(Locale.US, "%s %.2f", it.piece?.displayName ?: "?", it.score)
            }
            String.format(
                Locale.US,
                "(${c.square.row},${c.square.col}) 取 %s %.2f，丢 %s",
                c.kept.piece?.displayName ?: "?", c.kept.score, others
            )
        }
        if (conflicts.isNotEmpty()) lines.add("同格冲突：$conflicts")

        // 超限丢弃：同一类棋子超过了上限，多出来的按分数丢了
        if (assembly.overLimit.isNotEmpty()) {
            lines.add(
                "超限丢弃 " + assembly.overLimit.take(MAX_CALLED_OUT).joinToString("，") {
                    String.format(Locale.US, "%s %.2f", it.piece?.displayName ?: "?", it.score)
                }
            )
        }
        return Report(lines, uncertain)
    }
}
