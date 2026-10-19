package com.xiangqi.v2.vision

import android.graphics.Bitmap
import android.util.Log
import com.xiangqi.v2.config.RecognitionConfig
import com.xiangqi.v2.infra.LogTags
import com.xiangqi.v2.infra.OnnxModel

/**
 * 从一批候选框里挑「最像棋盘的那一个」。
 *
 * 前提：传进来的框**已经是本项目的格线框**（[BoardBoxSynth.toGridBox] 归一化之后）。
 * 尺寸判据 [BoardBoxSynth.sizePlausible] 的窗口就是按这个前提标定的。
 *
 * 判据顺序是刻意的：**先按尺寸筛，再按置信度取最大**。
 * 反过来（先取最大再校验）会让一个 0.62 的残缺框把 0.31 的正经框顶掉 ——
 * 主检测器在 JJ象棋那张失败图上给的就是这种组合。
 *
 * 尺寸判据用「框宽 / 子宽」而不是绝对像素：同一份代码要同时伺候 1440x3168 与
 * 720x1600，绝对阈值必然要跟着分辨率改。
 */
object BoardPick {

    /**
     * @param boardIndex 棋盘类在**本项目类序**里的下标（[Labels.BOARD]）
     * @param pieceW     棋子宽中位值（帧像素）；<=0 表示没有可比对的棋子，此时跳过尺寸判据
     */
    fun best(
        candidates: List<Detection>,
        boardIndex: Int,
        pieceW: Float,
        pieceH: Float,
        aspectMin: Float,
        aspectMax: Float
    ): Detection? {
        val hasSizeRef = pieceW > 0f && pieceH > 0f
        var bestAny: Detection? = null
        var bestSized: Detection? = null
        for (d in candidates) {
            if (d.classIndex != boardIndex) continue
            val box = d.box
            if (box.width <= 0f || box.height <= 0f) continue
            val aspect = box.width / box.height
            if (aspect < aspectMin || aspect > aspectMax) continue
            if (bestAny == null || d.score > bestAny.score) bestAny = d
            if (hasSizeRef && !BoardBoxSynth.sizePlausible(box, pieceW, pieceH)) continue
            if (bestSized == null || d.score > bestSized.score) bestSized = d
        }
        // 没有棋子尺寸可参照时（残局 3 子、检测大面积失败），只能信形状
        return bestSized ?: if (hasSizeRef) null else bestAny
    }
}

/**
 * 棋盘框的**第二意见** —— 只在主检测器给不出合格框时才跑一次。
 *
 * ## 为什么要有第二个模型
 * JJ象棋深色 3D 皮肤上，主模型的棋盘框置信度天生低（0.21~0.47，常规皮肤 0.83~0.94），
 * 一直贴着门槛抖。75 张真机截图的离线对比：
 *
 * | | 主模型（xqlink_v5） | 兜底（DeepXiangQi detect-ultra） |
 * |---|---|---|
 * | 棋盘框下限 | 0.214，且框本身残缺 | 0.79，框正确 |
 * | 棋子分类 | **强**（浅色皮肤两个"兵"它对） | 弱（把"兵"认成"马"、置信 0.94） |
 *
 * 两者能力错开，所以不整体替换，只借它在**定位**上的长处：框取兜底的，棋子仍取主模型的。
 * 75 张对拍：74 张 FEN 不变、1 张救回、0 张新增错误（`tools/_locator_fen.py` 可复跑）。
 *
 * ## 为什么是「懒」的
 * 只在主模型框不合格时跑（实测约 1/75 的帧），正常帧的耗时、内存、启动时间全不变。
 * 模型本身也是首次真正用到才解压 + 建会话 —— 从不触发失败的用户不会解压那 38MB。
 *
 * ## 为什么不能直接用它的框
 * 它标的 `board` 是**含木纹边框的整块棋盘**，比本项目那个紧贴格线的框大 19%
 * （75 张实测：宽 9.34~9.96 倍子宽、高 10.38~11.63 倍子高，而格线框恒为 8×9 倍）。
 * 直接拿来用会被主模型的尺寸判据拒掉 57/75，勉强放进来又会把裁剪窗口撑宽 19%。
 * 所以先进 [BoardBoxSynth.toGridBox] 归一，再走与主模型完全相同的下游。
 * 归一绕中心缩放，中心不动 —— 锚定结果因此与「主模型自己给出这个框」时完全等价。
 *
 * ## 输入尺寸选 640 而不是 320
 * 320 也测了：置信只掉 0.03，但框更松（同一张图 1621 高 vs 640 时的 1550、真实约 1360），
 * 归一化后横向会被压得更狠。定位任务省这 3/4 算力不值得。
 */
class BoardLocator(
    /** 模型来源。**必须延迟**：解压 38MB + 建会话要几百毫秒，不能放在构造里 */
    private val modelProvider: () -> OnnxModel,
    private val config: RecognitionConfig,
    /** 这个兜底模型自己的类序（见 [ModelLabels]） */
    private val classes: CharArray = ModelLabels.DEEP_XIANGQI,
    private val tag: String = LogTags.RECOGNIZER
) {

    private var model: OnnxModel? = null
    private var resolved = false
    private var failures = 0
    private var disabled = false

    /** 兜底真正给出框的次数（诊断用） */
    @Volatile
    var hits: Int = 0
        private set

    /** 兜底实际跑过的次数（分母） */
    @Volatile
    var attempts: Int = 0
        private set

    private val boardIndex: Int = ModelLabels.boardIndex(classes)

    /**
     * 找棋盘框。返回**帧像素**坐标；找不到、类序对不上、模型不可用时返回 null
     * （调用方继续往下兜：棋子包络反推 → 报「找不到棋盘」）。
     */
    fun locate(frame: Bitmap, pieceW: Float, pieceH: Float): Box? {
        if (disabled || boardIndex < 0) return null
        val active = obtain() ?: return null
        attempts++

        val lb = Letterbox.of(frame.width, frame.height, config.locatorInputSize)
        return try {
            val raw = active.run(DetectorInput.tensor(frame, lb))
            val shape = active.lastOutputShape
            if (shape.isEmpty()) {
                fail("推理已完成但输出形状为空（${raw.size} 个浮点）")
                return null
            }
            val foreign = YoloDecoder.decode(raw, shape, classes.size, config.locatorConfThreshold)
            // 解码后立刻把类别翻成本项目类序：后面所有判据只认一套字母，
            // 否则「同一个 board」在两个模型里是两个不同下标，迟早写错一处。
            // 同时把框归一成「格线框」（它标的是含木纹边框的整块棋盘，大 19%）——
            // 归一之后下游的尺寸判据 / 裁剪窗口 / 格距范围全部按原有标定工作。
            val local = foreign.mapNotNull { d ->
                val i = ModelLabels.toLocal(classes, d.classIndex)
                if (i < 0) null else d.copy(box = BoardBoxSynth.toGridBox(d.box, pieceW, pieceH))
            }
            val picked = BoardPick.best(
                local, boardIndex, pieceW, pieceH,
                config.boardAspectMin, config.boardAspectMax
            ) ?: return null
            val box = lb.toFrame(picked.box)
            hits++
            Log.i(
                tag,
                "棋盘框由兜底模型给出：conf %.3f · %.0fx%.0f · 候选 %d 个"
                    .format(picked.score, box.width, box.height, local.size)
            )
            box
        } catch (e: Exception) {
            // 单帧异常不致命（OOM/尺寸怪），但要能看见
            fail("推理异常 ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun close() {
        runCatching { model?.close() }
        model = null
        resolved = false
    }

    @Synchronized
    private fun obtain(): OnnxModel? {
        if (resolved) return model
        resolved = true
        model = try {
            modelProvider().also { it.load() }
        } catch (e: Exception) {
            // 最常见原因：assets 里没放这个权重（换机器 clone 只补了 4 类二进制）。
            // 一次性关掉，别让每一帧都去尝试解压一个不存在的文件。
            Log.w(tag, "兜底定位模型不可用（${e.javaClass.simpleName}: ${e.message}），棋盘框只剩棋子反推")
            disabled = true
            null
        }
        return model
    }

    private fun fail(reason: String) {
        failures++
        Log.w(tag, "兜底定位失败第 $failures 次：$reason")
        if (failures >= MAX_FAILURES) {
            disabled = true
            Log.w(tag, "兜底定位连续失败 $MAX_FAILURES 次，本次运行不再使用")
        }
    }

    private companion object {
        const val MAX_FAILURES = 3
    }
}
