package com.xiangqi.v2.ui

import com.xiangqi.v2.core.Board

/**
 * 面板小棋盘的**显示方向**：把标准方向的坐标换算成「屏幕上第几行第几列」。
 *
 * 盘面在内部一律是标准方向（[Board] 的 row 0 = 黑方底线，红方在下），因为棋规判断
 * （兵卒未过河不换纵线、相象不过河、仕士不出九宫）全都假定这个方向。
 *
 * 但用户在平台上看棋盘时，**自己永远在下方**。黑方用户看到的画面正好与标准方向
 * 相差 180° —— 面板若照标准方向画，他得在脑子里把整盘棋转半圈，走一格过去是
 * 「往左上还是右下」全反了，比没有小棋盘更容易看错。所以显示要跟着画面那个视角走。
 *
 * 为什么不按「轮到谁走谁在下方」：走子方在「自动」模式下每半手就换一次，
 * 照它翻会让棋盘每一步转 180°，比一直看反着更难受。而**谁在画面的下方，同一盘棋里
 * 是恒定的**（识别层每帧都在判 `redOnTop`），那才是用户真正的视角。
 */
object BoardOrientation {

    /** 标准方向的行号 → 屏幕上的行号。 */
    fun row(row: Int, flipped: Boolean): Int = if (flipped) Board.ROWS - 1 - row else row

    /** 标准方向的列号 → 屏幕上的列号。 */
    fun col(col: Int, flipped: Boolean): Int = if (flipped) Board.COLS - 1 - col else col

    /**
     * 显示格子编号（`row * 9 + col`，界面用它标记低置信格）。
     *
     * 小棋盘是逐格遍历再按坐标画的，所以这条只在别处（日志、调试）需要。
     * 两套编号必须一致，否则「点了哪一格」和「画在哪一格」会错位。
     */
    fun cellKey(key: Int, flipped: Boolean): Int {
        if (!flipped) return key
        val row = key / Board.COLS
        val col = key % Board.COLS
        return row(row, true) * Board.COLS + col(col, true)
    }
}
