package com.xiangqi.v2.vision

import com.xiangqi.v2.core.Board
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 用**棋盘框几何**把周期拟合出来的行列索引锚定到棋盘左上角。
 *
 * ## 为什么不再依赖规则搜索
 * [RulesAnchor] 靠「帅在列 4、士象同半区」这类棋规约束去猜整数平移。
 * 在**残局/排局**里这些约束大面积失效：子少、帅可能已经离开中线、(实战中)
 * 将甚至可能漏检——49 张残局截图实测，规则锚定有 11 张整盘错位 1~2 格
 * （错位后整盘行列全错，连将都被挤到棋盘外，表现为「黑将缺失」）。
 *
 * 而棋盘框在同样 49 张上**全部检出、置信 0.97、且只有 1 个**。
 * 框是固定 UI 皮肤，中心恒等于格坐标 (列 4, 行 4.5)，四边留白对称。
 * 相位由周期拟合给出（残差 0.01~0.05 格，足够准），所以整数平移可以**直接解**，
 * 不需要搜索、不需要合法性打分：
 *
 *     colOffset = round((boardCenterX - phaseX) / pitchX - 4.0)
 *     rowOffset = round((boardCenterY - phaseY) / pitchY - 4.5)
 *
 * 49 张残局实测：锚定正确率 49/49（规则锚定 38/49）。
 *
 * ## 前提与回退
 * 要求棋盘框**居中对称**。若某平台的框画得明显偏心，这个锚定会整体偏移半格以上——
 * 所以 [BoardRecognizer] 在无棋盘框时才退回 [RulesAnchor]。
 */
object BoardBoxAnchor {

    /** 棋盘框中心在格坐标里的位置：9 列 0..8 的中位是 4；10 行 0..9 的中位是 4.5。 */
    private const val CENTER_COL = 4.0f
    private const val CENTER_ROW = 4.5f

    /**
     * @param fitX     横向拟合（pitch / phase）
     * @param fitY     纵向拟合
     * @param boardBox 棋盘框（帧像素坐标）
     * @return 锚定偏移；[AnchorResult.score] 是几何偏差（格），正常情况下 <0.5
     */
    fun anchor(fitX: AxisFit, fitY: AxisFit, boardBox: Box): AnchorResult {
        val colRaw = (boardBox.centerX - fitX.phase) / fitX.pitch - CENTER_COL
        val rowRaw = (boardBox.centerY - fitY.phase) / fitY.pitch - CENTER_ROW
        val colOff = colRaw.roundToInt()
        val rowOff = rowRaw.roundToInt()
        // 偏差超过半格说明「框中心 == 格中心」这个假设不成立，留给调用方判断
        val deviation = abs(colRaw - colOff) + abs(rowRaw - rowOff)
        return AnchorResult(colOff, rowOff, deviation, 1)
    }

    /**
     * 几何偏差的容忍上限（两轴之和，单位：格）。
     *
     * 注意它**不是**偏心检测器：round 的性质决定了单轴偏差恒 ≤0.5，所以这个阈值
     * 基本只在「pitch/phase 拟合本身就不可信」（例如子极少、分布极偏）时才可能触发，
     * 触发后退回 [RulesAnchor]。框画得偏心目前无法从框本身判断——JJ象棋的框是固定皮肤、
     * 四边留白对称，因此这里的做法成立；换平台需要重新核对这条假设。
     */
    const val MAX_DEVIATION = 0.5f

    /**
     * 把检出裁到棋盘框内（向外放宽 [padCells] 格，容忍贴边棋子略微出框）。
     *
     * 为什么必须先裁：棋盘上方场景里的**装饰棋子**（帅/車立牌）、下方的**被吃子托盘**
     * 都是合法棋子外观，检测器会稳定检出。它们离棋盘中心动辄 3~6 个格距，
     * 直接参与周期拟合会把 pitch 和 phase 一起带偏，进而整盘错位。
     */
    fun clipToBoard(pieces: List<Detection>, boardBox: Box, padCells: Float = 0.55f): List<Detection> {
        val px = boardBox.width / (Board.COLS - 1)
        val py = boardBox.height / (Board.ROWS - 1)
        val xLo = boardBox.x0 - padCells * px
        val xHi = boardBox.x1 + padCells * px
        val yLo = boardBox.y0 - padCells * py
        val yHi = boardBox.y1 + padCells * py
        return pieces.filter {
            it.box.centerX in xLo..xHi && it.box.centerY in yLo..yHi
        }
    }
}
