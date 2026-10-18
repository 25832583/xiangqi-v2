package com.xiangqi.v2

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.xiangqi.v2.config.SettingsStore
import com.xiangqi.v2.core.Fen
import com.xiangqi.v2.core.Side
import com.xiangqi.v2.di.AppGraph
import com.xiangqi.v2.pipeline.AnalysisOutcome
import com.xiangqi.v2.pipeline.GateMode
import com.xiangqi.v2.service.CaptureAccessibilityService
import com.xiangqi.v2.service.OverlayService
import com.xiangqi.v2.ui.MoveFormatter
import com.xiangqi.v2.vision.RecognitionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：**授权与参数**，不做识别显示。
 *
 * 识别结果在悬浮窗里看，这里是"把路铺好"的地方：
 *  1. 三项授权（无障碍 / 悬浮窗 / 通知）—— 都是系统设置，只能引导用户去开；
 *  2. 引擎参数（深度 / 线程 / 思考时间）；
 *  3. 本地图片分析 —— 不打开游戏也能验证「识别 + 引擎」整条链路。
 *     这个入口很重要：真机出问题时第一步就是拿一张截图在这里跑，
 *     能立刻区分是「识别不行」还是「抓屏不行」。
 */
class MainActivity : Activity() {

    private lateinit var graph: AppGraph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var accessibilityStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var startButton: Button
    private lateinit var imageResult: TextView
    private lateinit var preview: ImageView

    private var analysisJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = XiangqiApp.graphOf(this)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- 界面 ----

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        root.addView(
            TextView(this).apply {
                text = "象棋助手 v2"
                setTextColor(TEXT)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        root.addView(
            TextView(this).apply {
                text = "YOLOv8 + ONNX Runtime 识别，皮卡鱼分析。识别结果在悬浮窗里看。"
                setTextColor(DIM)
                textSize = 12f
                setPadding(0, dp(6), 0, dp(16))
            }
        )

        root.addView(permissionCard())
        root.addView(runCard())
        root.addView(tuningCard())
        root.addView(imageCard())

        root.addView(
            TextView(this).apply {
                text = "模型 detector_xqlink_v5（49 张真实残局逐颗 100%）\n引擎 皮卡鱼 2026-01-02 arm64 + 配套 NNUE 权重"
                setTextColor(0xFF606060.toInt())
                textSize = 10f
                setPadding(0, dp(16), 0, 0)
            }
        )

        return ScrollView(this).apply { addView(root) }
    }

    private fun permissionCard(): View {
        val body = cardBody()
        appendTitle(body, "第一步 · 三项授权")
        accessibilityStatus = appendStatusRow(body, "无障碍服务（抓屏）") { openAccessibilitySettings() }
        overlayStatus = appendStatusRow(body, "悬浮窗（显示分析）") { openOverlaySettings() }
        notificationStatus = appendStatusRow(body, "通知（前台服务必需）") { requestNotification() }
        return wrapCard(body)
    }

    private fun runCard(): View {
        val body = cardBody()
        appendTitle(body, "第二步 · 开始盯盘")
        startButton = Button(this).apply {
            text = "开始悬浮分析"
            setBackgroundColor(ACCENT)
            setTextColor(0xFF1A1A1A.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f
            setOnClickListener { toggleOverlay() }
        }
        body.addView(startButton, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) })
        body.addView(
            TextView(this).apply {
                text = "开启后先把悬浮球拖到棋盘外（比如屏幕底边），再切到 JJ象棋。\n" +
                    "面板展开时会自动暂停盯盘（它会挡住棋盘），收起后自动恢复。"
                setTextColor(DIM)
                textSize = 11f
                setPadding(0, dp(10), 0, 0)
            }
        )
        return wrapCard(body)
    }

    private fun tuningCard(): View {
        val body = cardBody()
        appendTitle(body, "引擎参数（悬浮面板里也能临时调）")
        val s = graph.settings

        appendSlider(
            body, "搜索深度", SettingsStore.MIN_DEPTH, SettingsStore.MAX_DEPTH, 1,
            get = { s.depth },
            set = { s.depth = it }
        )
        appendSlider(
            body, "搜索线程", 1, s.maxThreads(), 1,
            get = { s.threads },
            set = {
                s.threads = it
                // Threads 只在 UCI 握手时生效，缓存里的引擎已经对不上新参数了
                graph.pipeline.releaseEngine()
            }
        )
        appendSlider(
            body, "思考上限", SettingsStore.MIN_MOVE_TIME, SettingsStore.MAX_MOVE_TIME, 100,
            get = { s.moveTimeMs },
            set = { s.moveTimeMs = it },
            unit = "ms"
        )
        appendGateSwitch(body, get = { s.gateMode }, set = { s.gateMode = it })
        body.addView(
            TextView(this).apply {
                text = "深度是「上限」：算到就返回，复杂局面由「思考上限」兜底，所以调高不会更卡。" +
                    "线程数超过手机大核数反而互相抢核（实测同深度下更多线程更慢）。"
                setTextColor(0xFF606060.toInt())
                textSize = 10f
                setPadding(0, dp(6), 0, 0)
            }
        )
        return wrapCard(body)
    }

    private fun imageCard(): View {
        val body = cardBody()
        appendTitle(body, "本地图片分析（不开游戏也能验证链路）")
        body.addView(
            Button(this).apply {
                text = "选一张截图"
                setBackgroundColor(CARD_ALT)
                setTextColor(TEXT)
                setOnClickListener { pickImage() }
            },
            LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(8) }
        )
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        body.addView(preview, LinearLayout.LayoutParams(MATCH, dp(220)))
        imageResult = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, 0)
            text = "选图后会显示 FEN 与推荐着法。\n用的是同一套模型和同一个引擎。"
        }
        body.addView(imageResult)
        return wrapCard(body)
    }

    // ---- 视图小工具 ----

    private fun cardBody(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun wrapCard(body: LinearLayout): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(12))
        addView(body)
    }

    private fun appendTitle(parent: LinearLayout, text: String) {
        parent.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(ACCENT)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
    }

    private fun appendStatusRow(parent: LinearLayout, title: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val label = TextView(this).apply {
            text = title
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(this).apply {
            text = "检测中…"
            textSize = 12f
        }
        val action = TextView(this).apply {
            text = "去设置"
            setTextColor(ACCENT)
            textSize = 12f
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { onClick() }
        }
        row.addView(label)
        row.addView(value)
        row.addView(action)
        parent.addView(row)
        return value
    }

    private fun appendSlider(
        parent: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        step: Int,
        get: () -> Int,
        set: (Int) -> Unit,
        unit: String = ""
    ) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val label = TextView(this).apply {
            text = "$title  ${get()}$unit"
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 13f
        }
        val steps = ((max - min) / step).coerceAtLeast(1)
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = ((get() - min) / step).coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val value = min + progress * step
                    label.text = "$title  $value$unit"
                    set(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        wrap.addView(label)
        wrap.addView(bar)
        parent.addView(wrap)
    }

    /**
     * 「多帧确认 / 单帧即采」的切换行。
     *
     * 用「标签 + 切换」而不是 Switch 控件：取值是枚举，将来可能不止两项，
     * 循环式的写法不用跟着改布局。同时把两种模式的代价明文写出来 ——
     * 这一项是手感与稳妥的取舍，不给代价说明等于让用户瞎猜。
     */
    private fun appendGateSwitch(parent: LinearLayout, get: () -> GateMode, set: (GateMode) -> Unit) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun refresh(mode: GateMode) {
            label.text = "采纳方式  ${mode.label}"
        }
        refresh(get())
        row.addView(label)
        row.addView(
            TextView(this).apply {
                text = "切换"
                setTextColor(ACCENT)
                textSize = 12f
                setPadding(dp(12), dp(4), dp(4), dp(4))
                setOnClickListener {
                    val next = get().next()
                    set(next)
                    refresh(next)
                }
            }
        )
        wrap.addView(row)
        wrap.addView(
            TextView(this).apply {
                text = "多帧确认：连续两帧识别结果相同才采纳，慢一个扫描间隔，挡得住走子动画的中间态。\n" +
                    "单帧即采：这一帧合法、且能由上一帧走一步得到就采纳，快一帧；中间态偶尔会闪出一个错着法。"
                setTextColor(0xFF606060.toInt())
                textSize = 10f
                setPadding(0, dp(4), 0, 0)
            }
        )
        parent.addView(wrap)
    }

    // ---- 授权 ----

    private fun refreshStatus() {
        setStatus(accessibilityStatus, isAccessibilityEnabled())
        setStatus(overlayStatus, Settings.canDrawOverlays(this))
        setStatus(notificationStatus, hasNotificationPermission())
        startButton.text = if (OverlayService.running) "停止悬浮分析" else "开始悬浮分析"
    }

    private fun setStatus(view: TextView, ok: Boolean) {
        view.text = if (ok) "已开启" else "未开启"
        view.setTextColor(if (ok) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, CaptureAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }.let { splitter ->
            while (splitter.hasNext()) {
                val parsed = ComponentName.unflattenFromString(splitter.next()) ?: continue
                if (parsed == expected) return true
            }
        }
        return false
    }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast("打不开无障碍设置，请手动到系统设置里找") }
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            toast("已授权")
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }.onFailure { toast("打不开悬浮窗设置，请手动到系统设置里找") }
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        } else {
            toast("通知权限已允许")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    // ---- 启动/停止 ----

    private fun toggleOverlay() {
        if (OverlayService.running) {
            OverlayService.stop(this)
            startButton.text = "开始悬浮分析"
            return
        }
        if (!isAccessibilityEnabled()) {
            toast("请先开启无障碍服务")
            openAccessibilitySettings()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            openOverlaySettings()
            return
        }
        if (!hasNotificationPermission()) {
            requestNotification()
            return
        }
        runCatching { OverlayService.start(this) }
            .onFailure { toast("启动失败：${it.message}") }
        startButton.text = "正在启动…"
        scope.launch {
            delay(900)
            refreshStatus()
            if (!OverlayService.running) toast("服务没起来，请确认通知权限没被拒")
        }
    }

    // ---- 本地图片分析 ----

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching { startActivityForResult(intent, REQ_IMAGE) }
            .onFailure { toast("打不开文件选择器") }
    }

    @Deprecated("ActivityResult API 要多引一个依赖；这里只有一处选图，旧接口足够且零依赖")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        analyzeImage(uri)
    }

    private fun analyzeImage(uri: Uri) {
        analysisJob?.cancel()
        imageResult.text = "读取图片…"
        analysisJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap == null) {
                imageResult.text = "读不到这张图（可能是云盘文件或格式不支持）"
                return@launch
            }
            preview.setImageBitmap(bitmap)
            preview.visibility = View.VISIBLE
            imageResult.text = "识别中…"

            when (val recognition = graph.pipeline.recognize(bitmap)) {
                is RecognitionOutcome.Success -> {
                    val assembly = recognition.assembly
                    val header = buildString {
                        append("识别 ").append(assembly.pieceCount).append(" 子")
                        append("｜方向 ").append(if (assembly.redOnTop) "红在上" else "红在下")
                        append("｜残差 ").append("%.2f".format(assembly.maxResidual)).append(" 格")
                        append("｜耗时 ").append(recognition.elapsedMs).append("ms")
                        if (assembly.overLimit.isNotEmpty()) append("｜超限丢弃 ").append(assembly.overLimit.size)
                        if (assembly.notes.isNotEmpty()) append("\n注意: ").append(assembly.notes.joinToString("；"))
                    }
                    val fen = Fen.encode(assembly.board, Side.RED)
                    imageResult.text = "$header\n\n$fen\n\n分析中（引擎首次启动要几秒）…"
                    when (val outcome = graph.pipeline.analyze(assembly.board, Side.RED, graph.settings.engineRevision)) {
                        is AnalysisOutcome.Success ->
                            imageResult.text = "$header\n\n$fen\n\n" +
                                MoveFormatter.detailText(outcome.fen, outcome.analysis)
                        else ->
                            imageResult.text = "$header\n\n$fen\n\n${outcome.displayMessage}"
                    }
                }

                is RecognitionOutcome.NoBoard ->
                    imageResult.text = "找不到棋盘（候选 ${recognition.boardCandidates} 个）。请用完整包含九宫的截图。"

                is RecognitionOutcome.TooFewPieces ->
                    imageResult.text = "只认出 ${recognition.count} 颗子，可能截图裁剪过度或有遮挡。"

                is RecognitionOutcome.PoorGeometry ->
                    imageResult.text = "格线拟合失败：x 残差 %.2f / y 残差 %.2f 格，格距 %.1f / %.1f px。"
                        .format(recognition.residualX, recognition.residualY, recognition.pitchX, recognition.pitchY)
            }
        }
    }

    // ---- 小工具 ----

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_IMAGE = 1001
        const val REQ_NOTIFICATION = 1002

        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        const val BG = 0xFF121212.toInt()
        const val CARD = 0xFF1E1E1E.toInt()
        const val CARD_ALT = 0xFF333333.toInt()
        const val ACCENT = 0xFFFFD24A.toInt()
        const val TEXT = 0xFFF2F2F2.toInt()
        const val DIM = 0xFF9E9E9E.toInt()
    }
}
