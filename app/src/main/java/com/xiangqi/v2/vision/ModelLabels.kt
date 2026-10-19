package com.xiangqi.v2.vision

/**
 * 外部模型的**类别顺序**映射。
 *
 * 换权重时最容易踩的坑不是算子不兼容，而是「同样是 15 类、同样有 board，
 * 但顺序不同」—— 直接套用 [Labels] 会得到一个不报错、只是全错的结果：
 * 类序错位后每个子都成了别的子，而置信度依然是 0.9+。
 *
 * 每个外部模型在这里登记一行「它的类序翻译成本项目字母」的表，
 * 索引即模型输出通道下标。新增模型只需加一个 [CharArray]，不需要改解码器。
 */
object ModelLabels {

    /**
     * `DuyLeTran/DeepXiangQi` 的 `detect-ultra` / `detect-aux`（15 类）。
     *
     * 它的顺序是「黑方按字母 a,b,c,k,n,p,r，红方同样，最后 board」，
     * 与本项目 `n,b,a,k,r,c,p / R,N,A,K,B,C,P / 0` 完全不同。
     * 这张表与 `tools/_oss_compare.py` 里的 `REMAP` 互为镜像，改一处必须改另一处。
     */
    val DEEP_XIANGQI: CharArray = charArrayOf(
        'a', 'b', 'c', 'k', 'n', 'p', 'r',
        'A', 'B', 'C', 'K', 'N', 'P', 'R',
        '0'
    )

    /**
     * 棋盘类在这个模型的通道下标。
     *
     * 找不到时返回 -1 —— 调用方据此判定「这个模型的类序表和它本身对不上」，
     * 直接放弃兜底，而不是拿 0 号通道（那是黑士）当棋盘。
     */
    fun boardIndex(classes: CharArray): Int = classes.indexOf(Labels.ALL[Labels.BOARD])

    /** 把外部模型的通道下标翻成本项目 [Detection.classIndex]；落不下返回 -1。 */
    fun toLocal(classes: CharArray, foreignIndex: Int): Int {
        if (foreignIndex !in classes.indices) return -1
        return Labels.ALL.indexOf(classes[foreignIndex])
    }
}
