package com.xiangqi.v2.pipeline

import com.xiangqi.v2.core.Board
import com.xiangqi.v2.core.Side

/**
 * 走子方推断。
 *
 * 识别只看得到盘面，看不到「现在该谁走」—— FEN 里那一格全靠猜。猜错的表现很隐蔽：
 * 引擎会给出**对方**的最佳着法，看起来完全合理，但用户照着走会立刻发现自己不能那么走。
 *
 * 能利用的事实：画面变了，说明**刚有一方走过**。从"哪些格子空了"就能读出是谁：
 * 棋子离场的格子上的那颗子属于哪一方，走的就是哪一方。所以新盘面的走子方是它的对手。
 *
 * 这条规则不依赖任何棋规搜索，只依赖一个前提：两帧之间的变化确实是"走了一步棋"。
 * 所以变化量超过 [MAX_CHANGED_CELLS] 时一律不推断 —— 那是新开一局、换对手、
 * 或者识别本身出错了，此时保留下来的旧值反而是最不容易误导人的。
 */
object SideInference {

    /** 超过这么多格发生变化，就不是"走一步棋"，而是换了一个局面。 */
    const val MAX_CHANGED_CELLS = 6

    /**
     * @param before 上一个已采纳的盘面（标准方向）；null 表示这是首次识别，无法推断
     * @param after  本帧盘面（标准方向）
     * @param current 当前认定的走子方，推断失败时原样返回
     * @return 本帧应使用的走子方
     */
    fun infer(before: Board?, after: Board, current: Side): Side {
        if (before == null) return current

        val vacated = ArrayList<Side>()
        val occupied = ArrayList<Side>()
        var changed = 0
        for (row in 0 until Board.ROWS) {
            for (col in 0 until Board.COLS) {
                val a = before[row, col]
                val b = after[row, col]
                if (a == b) continue
                changed++
                if (a != null && b == null) vacated.add(a.side)
                if (a == null && b != null) occupied.add(b.side)
            }
        }

        if (changed == 0 || changed > MAX_CHANGED_CELLS) return current

        // 正常走子：1 个格子空了（也可能是 2 个——吃子时目标格子的子被换掉）
        if (vacated.isNotEmpty() && vacated.all { it == vacated[0] }) {
            val moved = vacated[0]
            // 只有"空出来的一方的子确实出现在别处"才敢下这个结论
            if (occupied.isEmpty() || occupied.any { it == moved }) {
                return moved.opponent
            }
        }
        return current
    }
}
