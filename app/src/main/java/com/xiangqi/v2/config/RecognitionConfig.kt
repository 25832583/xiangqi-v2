package com.xiangqi.v2.config

/**
 * 识别链路的全部可调参数 —— 只有 9 个。
 *
 * 对比旧项目的 `RecognitionConfig`（detector / grid / cell / ink / quality / monitor /
 * opening 七组、三十多个字段），这里的每一个都对应新链路里**真实存在**的一步：
 * 一次检测、两次一维拟合、一次吸附。没有"单格分类器裁片边长"这类参数，
 * 因为根本没有逐格分类这一步。
 */
data class RecognitionConfig(
    /** 模型输入边长 */
    val inputSize: Int = 640,
    /** 检测置信度门槛 */
    val confidenceThreshold: Float = 0.25f,
    /**
     * **棋盘框**单独用的置信度门槛，比棋子低得多。
     *
     * JJ象棋「32强战」皮肤实测：棋盘框置信 0.31~0.47（常规皮肤 0.83~0.94），
     * 7 张截图里有 2 张掉到 0.23 附近 —— 用 0.25 当门槛会直接被判「找不到棋盘」，
     * 而框本身是对的。棋盘只有一个、且形状固定，误检代价远小于漏检，
     * 所以这里放宽；放宽后靠 [com.xiangqi.v2.vision.BoardBoxSynth.sizePlausible]
     * 的尺寸一致性把它筛回来（框宽应约等于 8 颗子宽、高约 9 颗子高）。
     */
    val boardConfThreshold: Float = 0.12f,
    /** 同类别 NMS 的 IoU 门槛 */
    val iouThreshold: Float = 0.5f,

    /**
     * 棋盘**兜底定位模型**的输入边长。
     *
     * 只在主模型的棋盘框不合格时才跑，所以这里不为速度牺牲精度：320 也测过，
     * 置信只掉 0.03，但框明显偏高，会撞上尺寸判据的上界而被拒。
     */
    val locatorInputSize: Int = 640,
    /**
     * 兜底模型的解码门槛。
     *
     * 它只用来找棋盘，误检那一类框的代价已经被 [com.xiangqi.v2.vision.BoardPick]
     * 的尺寸判据兜住，所以放到 0.10 尽量不漏 —— 实测这批皮肤上真框在 0.79 以上。
     */
    val locatorConfThreshold: Float = 0.10f,
    /**
     * 少于这么多个棋子就认为这一帧不可用。
     *
     * 不能低于 3：一维格距/相位拟合（[AxisFitter]）至少需要 3 个不共线（不同格点）的点。
     * 中国象棋合法残局可以只剩 2 子（双方将帅），但那种局面下拟合不出来，只能报“子太少”。
     * 默认值取 3 是为了让正常残局（单车/单兵对将等）能继续识别，而不是当成“遮挡”。
     */
    val minPieces: Int = 3,

    /** 格距搜索范围：x 轴以「棋盘框宽 / 列数」为基准 */
    val pitchLoFactorX: Float = 0.95f,
    val pitchHiFactorX: Float = 1.30f,
    /** y 轴以「棋盘框高 / 行数」为基准 */
    val pitchLoFactorY: Float = 0.85f,
    val pitchHiFactorY: Float = 1.25f,
    /** 格距搜索步长（像素） */
    val pitchStep: Float = 0.05f,

    /** 拟合 RMS 残差上限（格）：超过它说明这一帧的几何不可信 */
    val maxFitResidual: Float = 0.20f,
    /** 单框吸附残差上限（格）：超过它说明这个框不在任何交点上 */
    val maxSnapResidual: Float = 0.35f,

    /** ONNX 会话线程数 */
    val sessionThreads: Int = 4,

    /** 棋盘框宽高比允许范围（中国象棋约 9:10） */
    val boardAspectMin: Float = 0.6f,
    val boardAspectMax: Float = 1.6f
)
