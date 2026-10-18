package com.xiangqi.v2.ui

import com.xiangqi.v2.core.Board
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 显示方向。这些断言看着很"算术"，但每一条都对应一个用户能看见的后果：
 * 算错的第一个表现就是「我的棋子跑到上面去了」，第二个是「走一步画到了反方向」。
 */
class BoardOrientationTest {

    @Test
    fun `不翻时行列原样`() {
        for (row in 0 until Board.ROWS) {
            assertEquals(row, BoardOrientation.row(row, flipped = false))
        }
        for (col in 0 until Board.COLS) {
            assertEquals(col, BoardOrientation.col(col, flipped = false))
        }
    }

    @Test
    fun `翻 180 度：左上角换到右下角`() {
        // 标准方向的 (0,0) 是「黑方底线 + a 线」，翻过来必须落在右下角 (9,8)
        assertEquals(Board.ROWS - 1, BoardOrientation.row(0, flipped = true))
        assertEquals(Board.COLS - 1, BoardOrientation.col(0, flipped = true))
        assertEquals(0, BoardOrientation.row(Board.ROWS - 1, flipped = true))
        assertEquals(0, BoardOrientation.col(Board.COLS - 1, flipped = true))
    }

    @Test
    fun `红方底线翻到最上面 —— 也就是画面里红在上`() {
        // 标准方向红方在下（row 9 是红方底线）。画面上红在上时，红方的子必须画在 row 0。
        assertEquals(0, BoardOrientation.row(9, flipped = true))
        assertEquals(1, BoardOrientation.row(8, flipped = true))
        // 黑方底线（row 0）相应落到最下面
        assertEquals(Board.ROWS - 1, BoardOrientation.row(0, flipped = true))
    }

    @Test
    fun `翻两次回到原位`() {
        for (row in 0 until Board.ROWS) {
            for (col in 0 until Board.COLS) {
                val r1 = BoardOrientation.row(row, flipped = true)
                val c1 = BoardOrientation.col(col, flipped = true)
                assertEquals(row, BoardOrientation.row(r1, flipped = true))
                assertEquals(col, BoardOrientation.col(c1, flipped = true))
            }
        }
    }

    @Test
    fun `翻完仍落在棋盘内 —— 否则会画到 View 外面`() {
        for (row in 0 until Board.ROWS) {
            for (col in 0 until Board.COLS) {
                val r = BoardOrientation.row(row, flipped = true)
                val c = BoardOrientation.col(col, flipped = true)
                assertEquals(true, r in 0 until Board.ROWS)
                assertEquals(true, c in 0 until Board.COLS)
            }
        }
    }

    @Test
    fun `格子编号与行列换算必须一致`() {
        // 低置信格是按 row*9+col 编号传进界面的，两套换算差一格就会「圈画在别的子上」
        for (row in 0 until Board.ROWS) {
            for (col in 0 until Board.COLS) {
                val key = row * Board.COLS + col
                for (flipped in listOf(true, false)) {
                    val expected = BoardOrientation.row(row, flipped) * Board.COLS +
                        BoardOrientation.col(col, flipped)
                    assertEquals(expected, BoardOrientation.cellKey(key, flipped))
                }
            }
        }
    }
}
