package com.xiangqi.v2.vision

import com.xiangqi.v2.core.Board

/**
 * 按「一颗棋子该有多大」过滤检测框 —— 挡住**把整块棋盘当成一颗棋子**的误检。
 *
 * ## 这不是假想问题
 * JJ象棋的比赛皮肤（暗红金边）上，棋盘框自身的置信度只有 0.54，于是模型对同一块
 * 区域**同时**给出了「棋盘」和一个「红车」：尺寸 7.4 格 × 8.2 格、中心正落在棋盘中心，
 * 置信度 0.323（刚好越过 0.25 的门槛）。其它皮肤棋盘框 0.83~0.94，从来不出这个。
 *
 * 它会一路活到装配：吸附到河界中间（列 4、行 5），盘面上凭空多一辆红车。之后每一帧
 * 都和上一帧「对不上」（多一个子），[com.xiangqi.v2.core.rule.StepResolver] 一路拒绝，
 * 用户看到的就是「识别不了」。而真正的 27 颗子全部 0.87 以上，认得干干净净。
 *
 * ## 为什么用尺寸而不是置信度
 * 门槛抬到 0.35 也能挡住这一颗，但真实棋子在残局/模糊帧上确实会掉到 0.3~0.4 ——
 * 那是「该保留的」。尺寸是**几何先验**：中国象棋一颗子的直径就是一个格距，
 * 不管哪个平台、哪套皮肤都不会变成 7 格。用它过滤，低分真子一颗不少，巨型误检一个不留。
 *
 * 一格有多大：棋盘框宽度覆盖 9 列（8 个间距）、高度覆盖 10 行（9 个间距），
 * 所以 `宽/8`、`高/9` 就是格距。这个估算不依赖周期拟合，所以能在拟合**之前**用。
 */
object PieceSizeFilter {

    /**
     * 允许的尺寸范围（单位：格）。
     *
     * 下限 0.45：贴边棋子被裁掉一部分时仍有 0.6 以上，留足余量。
     * 上限 1.8：不同皮肤的棋盘框留白比例不同，`宽/8` 这个估算会差 10%~20%，
     * 但真正的「整盘当一个子」是 7 格量级，离上限还有 4 倍，不会误伤。
     */
    const val MIN_CELLS: Float = 0.45f
    const val MAX_CELLS: Float = 1.8f

    fun keep(pieces: List<Detection>, boardBox: Box): List<Detection> {
        val unitX = boardBox.width / (Board.COLS - 1)
        val unitY = boardBox.height / (Board.ROWS - 1)
        if (unitX <= 0f || unitY <= 0f) return pieces
        return pieces.filter { looksLikePiece(it.box, unitX, unitY) }
    }

    /** 单个框是否像一颗棋子。横纵**分别**判：棋盘的宽高比接近 1，用同一把尺子也行，但分开更稳。 */
    fun looksLikePiece(box: Box, unitX: Float, unitY: Float): Boolean {
        if (box.width <= 0f || box.height <= 0f) return false
        val rx = box.width / unitX
        val ry = box.height / unitY
        return rx >= MIN_CELLS && rx <= MAX_CELLS && ry >= MIN_CELLS && ry <= MAX_CELLS
    }
}
