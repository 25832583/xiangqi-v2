package com.xiangqi.v2

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.xiangqi.v2.infra.WeightsRules
import com.xiangqi.v2.pipeline.AnalysisOutcome
import com.xiangqi.v2.pipeline.GateMode
import com.xiangqi.v2.service.CaptureAccessibilityService
import com.xiangqi.v2.service.OverlayService
import com.xiangqi.v2.ui.CutRectDrawable
import com.xiangqi.v2.ui.Hud
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
 *
 * 2026-10-21：界面重做成 HUD 风（[Hud]），同时删掉了全部说明性文案。
 * 原来的小字解释承担的是"作者怕用户不懂"的功能，而这块界面只有四件事要做；
 * 说明越多越像文档，越不像工具。真需要解释的地方改成「点了就知道」——
 * 未授权时点启动会直接跳系统设置。
 */
class MainActivity : Activity() {

    private lateinit var graph: AppGraph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var accessibilityStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var weightsStatus: TextView
    private lateinit var weightsChip: TextView
    private lateinit var startButton: TextView
    private lateinit var imageResult: TextView
    private lateinit var preview: ImageView

    private var analysisJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = XiangqiApp.graphOf(this)
        @Suppress("DEPRECATION")
        window.statusBarColor = Hud.BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = Hud.BG
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
            setBackgroundColor(Hud.BG)
            setPadding(dp(16), dp(28), dp(16), dp(36))
        }

        root.addView(masthead())
        missingAssetsBlock()?.let { root.addView(it) }
        root.addView(permissionBlock())
        root.addView(runBlock())
        root.addView(tuningBlock())
        root.addView(imageBlock())

        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Hud.BG)
            addView(root)
        }
    }

    /** 抬头：左侧一道锋线，主名 + 一行窄体编号。 */
    private fun masthead(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(22))
        }
        row.addView(
            View(this).apply {
                setBackgroundColor(Hud.ACCENT)
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(42)).apply { rightMargin = dp(12) }
            }
        )
        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(this@MainActivity).apply {
                        text = "象棋助手"
                        setTextColor(Hud.TXT)
                        textSize = 26f
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.05f
                    }
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "TACTICAL  ASSIST  /  " + BuildConfig.VERSION_NAME
                        setTextColor(Hud.ACCENT)
                        textSize = 9f
                        typeface = Hud.narrow(true)
                        letterSpacing = 0.22f
                        setPadding(0, dp(5), 0, 0)
                    }
                )
            }
        )
        return row
    }

    private fun sectionWrap(code: String, title: String, build: (LinearLayout) -> Unit): View {
        val box = Hud.section(this, code, title)
        build(box)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(box)
        }
    }

    /**
     * 资产自检。只在真缺文件时出现 —— 不缺就完全不占版面。
     *
     * 为什么值得单独占一块：缺模型的默认表现是「点了没反应」。识别每帧抛的异常
     * 被上层吞成一句通用失败文案，看不出是文件没下载。而这份仓库的体积与许可
     * 决定了模型不能随源码一起给，这种缺法是常态（每个新 clone 都是），
     * 所以要在最显眼的位置直接说清楚，而不是等人去翻日志。
     */
    private fun missingAssetsBlock(): View? {
        val missing = graph.missingAssets()
        if (missing.isEmpty()) return null

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = CutRectDrawable(
                fill = Hud.SURFACE,
                stroke = Hud.ERR,
                strokePx = dpf(1f),
                cutPx = dpf(16f),
                codes = CutRectDrawable.TR or CutRectDrawable.BR
            )
            setPadding(dp(14), dp(13), dp(14), dp(14))
            addView(alarmHeader())
            addView(
                Hud.tagText(this@MainActivity, missing.joinToString("   /   "), Hud.ERR, 12f)
                    .apply { setPadding(0, dp(8), 0, 0) }
            )
            addView(
                Hud.tagText(
                    this@MainActivity,
                    "工程根目录运行  python tools/fetch_assets.py",
                    Hud.TXT_DIM,
                    12f
                ).apply { setPadding(0, dp(6), 0, 0) }
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(box)
        }
    }

    /** 与 [Hud.sectionHeader] 同构，只把标记色换成告警红。 */
    private fun alarmHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(4))
        addView(
            TextView(this@MainActivity).apply {
                text = "!!"
                setTextColor(Hud.ERR)
                textSize = 10f
                typeface = Hud.narrow(true)
                letterSpacing = 0.2f
            }
        )
        addView(
            View(this@MainActivity).apply {
                setBackgroundColor(Hud.ERR)
                layoutParams = LinearLayout.LayoutParams(dp(2), dp(11)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            }
        )
        addView(
            TextView(this@MainActivity).apply {
                text = "资产缺失"
                setTextColor(Hud.TXT)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
            }
        )
        addView(
            View(this@MainActivity).apply {
                setBackgroundColor(Hud.LINE)
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply {
                    marginStart = dp(10)
                }
            }
        )
    }

    private fun permissionBlock(): View = sectionWrap("01", "权限") { body ->
        accessibilityStatus = permissionRow(body, "无障碍抓屏", true) { openAccessibilitySettings() }
        overlayStatus = permissionRow(body, "悬浮窗", false) { openOverlaySettings() }
        notificationStatus = permissionRow(body, "通知", false) { requestNotification() }
    }

    private fun runBlock(): View = sectionWrap("02", "运行") { body ->
        startButton = Hud.primaryButton(this, "开始悬浮分析") { toggleOverlay() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(6) }
        }
        body.addView(startButton)
    }

    private fun tuningBlock(): View = sectionWrap("03", "引擎") { body ->
        val s = graph.settings
        weightsRow(body)
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
            unit = " ms"
        )
        appendGateRow(body, get = { s.gateMode }, set = { s.gateMode = it })
    }

    private fun imageBlock(): View = sectionWrap("04", "单图分析") { body ->
        Hud.ghostButton(this, "选择截图") { pickImage() }.let {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(6) }
            body.addView(it)
        }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        body.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        imageResult = TextView(this).apply {
            setTextColor(Hud.TXT)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, 0)
            text = ""
        }
        body.addView(imageResult)
    }

    // ---- 视图小工具 ----

    /** 一行授权项：名称铺满、右侧状态、尾随箭头。整行可点，不另设按钮。 */
    private fun permissionRow(
        parent: LinearLayout,
        title: String,
        first: Boolean,
        onClick: () -> Unit
    ): TextView {
        if (!first) parent.addView(Hud.divider(this))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                text = title
                setTextColor(Hud.TXT)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val value = TextView(this).apply {
            text = "▌ --"
            textSize = 11f
            typeface = Hud.narrow(true)
            letterSpacing = 0.08f
            setTextColor(Hud.TXT_MUTE)
        }
        row.addView(value)
        row.addView(
            TextView(this).apply {
                text = "›"
                setTextColor(Hud.TXT_MUTE)
                textSize = 15f
                setPadding(dp(10), 0, 0, 0)
            }
        )
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
        parent.addView(Hud.divider(this))
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            Hud.tagText(this, title, Hud.TXT_DIM, 12f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val value = TextView(this).apply {
            text = "${get()}$unit"
            setTextColor(Hud.ACCENT)
            textSize = 14f
            typeface = Hud.narrow(true)
            letterSpacing = 0.08f
        }
        head.addView(value)

        val steps = ((max - min) / step).coerceAtLeast(1)
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = ((get() - min) / step).coerceIn(0, steps)
            progressTintList = ColorStateList.valueOf(Hud.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Hud.LINE_HI)
            splitTrack = false
            // 方块滑块替代系统圆球：圆球在这套硬边界面里是唯一一处"柔软"的东西
            thumb = CutRectDrawable(
                fill = Hud.ACCENT,
                cutPx = dpf(3f),
                codes = CutRectDrawable.BL,
                intrinsic = dp(11)
            )
            setPadding(0, dp(2), 0, dp(2))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val newValue = min + progress * step
                    value.text = "$newValue$unit"
                    set(newValue)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        wrap.addView(head)
        wrap.addView(bar)
        parent.addView(wrap)
    }

    /**
     * 「多帧确认 / 单帧即采」的切换行。
     *
     * 用「标签 + 切换」而不是 Switch 控件：取值是枚举，将来可能不止两项，
     * 循环式的写法不用跟着改布局。
     */
    private fun appendGateRow(parent: LinearLayout, get: () -> GateMode, set: (GateMode) -> Unit) {
        parent.addView(Hud.divider(this))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(15), 0, dp(2))
        }
        val label = Hud.tagText(this, "", Hud.TXT_DIM, 12f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun refresh(mode: GateMode) {
            label.text = "采纳方式   ${mode.label}"
        }
        refresh(get())
        row.addView(label)
        row.addView(
            Hud.chipButton(this, "切换") {
                val next = get().next()
                set(next)
                refresh(next)
            }
        )
        parent.addView(row)
    }

    // ---- NNUE 权重 ----

    /**
     * 「引擎」分区的第一行：权重状态 + 导入。
     *
     * 为什么单独占一行而不是塞进资产自检块：可分发包里**本来就没有**这个文件，
     * 自检块是「出故障了」的位置，而这里描述的是「正常状态下的可选补齐」。
     * 两者混在一起，会让每个分发包用户一打开就看到一个红框。
     */
    private fun weightsRow(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(12))
        }
        row.addView(
            Hud.tagText(this, "NNUE 权重", Hud.TXT, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
        )
        weightsStatus = Hud.tagText(this, "", Hud.TXT_MUTE, 11f)
        row.addView(weightsStatus)
        weightsChip = Hud.chipButton(this, "导入") { pickWeights() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        }
        row.addView(weightsChip)
        parent.addView(row)
    }

    private fun refreshWeights() {
        val ready = graph.weightsReady()
        // 包里带来的那份还没解压时体积是 0，这时说「包内」比说「0.0MB」诚实
        val localBytes = graph.weights.sizeBytes()
        weightsStatus.text = when {
            localBytes > 0 -> "▌ " + WeightsRules.mb(localBytes)
            ready -> "▌ 包内"
            else -> "▌ 未导入"
        }
        weightsStatus.setTextColor(if (ready) Hud.OK else Hud.WARN)
        weightsChip.text = if (ready) "替换" else "导入"
    }

    private fun pickWeights() {
        // 没有权重时先讲清楚去哪拿：直接弹文件选择器，用户选到的多半是张截图
        if (!graph.weightsReady()) {
            AlertDialog.Builder(this)
                .setTitle("导入 NNUE 权重")
                .setMessage(
                    "需要 pikafish.nnue（约 53MB），可从皮卡鱼官方 release 的 Android 包中解出。\n\n" +
                        "不导入也能用，但引擎以无权重模式运行，棋力明显下降。"
                )
                .setPositiveButton("选择文件") { _, _ -> openWeightsPicker() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        openWeightsPicker()
    }

    private fun openWeightsPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // `.nnue` 没有注册的 MIME，限定成某个类型会让选择器直接筛空
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_WEIGHTS) }
            .onFailure { toast("打不开文件选择器") }
    }

    private fun importWeights(uri: Uri) {
        weightsStatus.text = "▌ 导入中"
        weightsStatus.setTextColor(Hud.ACCENT)
        weightsChip.isEnabled = false
        scope.launch {
            val problem = withContext(Dispatchers.IO) { graph.weights.importFrom(uri) }
            weightsChip.isEnabled = true
            if (problem != null) {
                toast(problem)
            } else {
                // 引擎可能已经以「无权重」起过了，进程里的 EvalFile 不会自己重读
                graph.pipeline.releaseEngine()
                toast("权重已导入")
            }
            refreshWeights()
        }
    }

    // ---- 授权 ----

    private fun refreshStatus() {
        setStatus(accessibilityStatus, isAccessibilityEnabled())
        setStatus(overlayStatus, Settings.canDrawOverlays(this))
        setStatus(notificationStatus, hasNotificationPermission())
        refreshWeights()
        startButton.text = if (OverlayService.running) "停止悬浮分析" else "开始悬浮分析"
    }

    private fun setStatus(view: TextView, ok: Boolean) {
        view.text = if (ok) "▌ 已开启" else "▌ 未开启"
        view.setTextColor(if (ok) Hud.OK else Hud.WARN)
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
        // 缺模型时启动服务只会让每一帧都失败，界面却毫无反应。
        // 在这里挡一次，把原因说到脸上。
        val missing = graph.missingAssets()
        if (missing.isNotEmpty()) {
            toast("缺 ${missing.size} 个模型文件，见页首提示")
            return
        }
        // 权重缺失不拦，但要说一声：无权重模式下引擎棋力掉一大截，
        // 不说的话用户第一反应是「这识别/引擎不行」而不是「我少导了个文件」
        if (!graph.weightsReady()) toast("未导入 NNUE 权重，引擎以无权重模式运行")
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

    @Deprecated("ActivityResult API 要多引一个依赖；这里只有两处选择器，旧接口足够且零依赖")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_IMAGE -> analyzeImage(uri)
            REQ_WEIGHTS -> importWeights(uri)
        }
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
                    imageResult.text = "只认出 ${recognition.count} 颗子，至少需要 3 颗棋子才能确定棋盘格线。"

                is RecognitionOutcome.PoorGeometry ->
                    imageResult.text = "格线拟合失败：x 残差 %.2f / y 残差 %.2f 格，格距 %.1f / %.1f px。"
                        .format(recognition.residualX, recognition.residualY, recognition.pitchX, recognition.pitchY)
            }
        }
    }

    // ---- 小工具 ----

    private fun dp(value: Int): Int = Hud.dp(this, value.toFloat())

    private fun dpf(value: Float): Float = Hud.dpf(this, value)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_IMAGE = 1001
        const val REQ_NOTIFICATION = 1002
        const val REQ_WEIGHTS = 1003
    }
}
