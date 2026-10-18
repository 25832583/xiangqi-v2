package com.xiangqi.v2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import com.xiangqi.v2.MainActivity
import com.xiangqi.v2.R
import com.xiangqi.v2.XiangqiApp
import com.xiangqi.v2.di.AppGraph
import com.xiangqi.v2.infra.LogTags
import com.xiangqi.v2.pipeline.BoardWatcher
import com.xiangqi.v2.pipeline.WatchPhase
import com.xiangqi.v2.pipeline.WatchSnapshot
import com.xiangqi.v2.ui.OverlayViews
import com.xiangqi.v2.ui.PanelView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 悬浮窗前台服务：**只做接线**。
 *
 * 三类职责，仅此而已：
 *  1. 前台服务生命周期（通知、窗口增删）；
 *  2. 把 [BoardWatcher] 的状态推到界面；
 *  3. 把用户操作翻译成 watcher / settings 的调用。
 *
 * 识别与分析在 [com.xiangqi.v2.pipeline.AnalysisPipeline]，循环在 [BoardWatcher]，
 * 窗口排版在 [PanelView]。这样「换掉识别模型」和「改界面」是两件互不相干的事。
 */
class OverlayService : Service(), OverlayViews.Host {

    private lateinit var graph: AppGraph
    private lateinit var wm: WindowManager
    private lateinit var watcher: BoardWatcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bubble: LinearLayout? = null
    private var panel: PanelView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var renderJob: Job? = null

    /** 面板收起前是否是「因为要重新识别」而收起的 —— 有结论后要自动展开回去。 */
    private var reopenAfterRefresh = false

    override fun onCreate() {
        super.onCreate()
        graph = XiangqiApp.graphOf(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 没有悬浮窗权限时 addView 会抛异常，服务会变成"活着但什么都看不见"的幽灵进程。
        // 这种情况直接退出，让用户看到主界面的提示，而不是在通知栏里找个不存在的窗口。
        if (!Settings.canDrawOverlays(this)) {
            Log.w(LogTags.PANEL, "没有悬浮窗权限，服务退出")
            Toast.makeText(this, "缺少悬浮窗权限，请回主界面重新授权", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        watcher = BoardWatcher(graph.frameSource(), graph.pipeline, graph.settings)

        startForegroundCompat()
        showBubble()
        renderJob = scope.launch {
            watcher.state.collect { render(it) }
        }
        // 模型预热放到后台：解压 28MB + 建会话要 1~3 秒，别卡住悬浮球出现
        scope.launch(Dispatchers.IO) { graph.pipeline.loadModels() }
        watcher.start(scope)
        running = true
        Log.i(LogTags.PANEL, "悬浮服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        watcher.stop()
        renderJob?.cancel()
        scope.cancel()
        removeBubble()
        Log.i(LogTags.PANEL, "悬浮服务结束")
        super.onDestroy()
    }

    // ---- 窗口 ----

    private fun showBubble() {
        if (bubble != null) return
        val view = OverlayViews.bubble(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 记住的位置要夹回屏幕内：换了分辨率、转屏、或者上次拖到了边缘外，
            // 直接套用会导致悬浮球出现在屏幕外，用户以为服务没起来
            val savedX = graph.settings.bubbleX
            val savedY = graph.settings.bubbleY
            x = if (savedX < 0 || savedX > screenWidth() - dp(48)) dp(12) else savedX
            y = if (savedY < 0 || savedY > screenHeight() - dp(48)) screenHeight() - dp(180) else savedY
        }
        attachDrag(view, params)
        runCatching { wm.addView(view, params) }
            .onFailure { Log.w(LogTags.PANEL, "悬浮球添加失败", it) }
        bubble = view
        bubbleParams = params
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
        hidePanel()
    }

    /**
     * 拖动 + 点击。
     *
     * 不能直接用 `setOnClickListener`：窗口里的 View 收不到点击判定（事件被拖动逻辑
     * 自己消费了），而且用户拖动时手指必然会离开原位，靠"有没有移动过"来区分更可靠。
     * 阈值取系统 touchSlop —— 手抖几个像素不该被算成拖动。
     */
    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        graph.settings.bubbleX = params.x
                        graph.settings.bubbleY = params.y
                        // 拖动时面板跟着走：否则球移开了，面板还停在原地
                        if (panel != null) positionPanel()
                    } else {
                        togglePanel()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun togglePanel() {
        if (panel == null) showPanel() else hidePanel()
    }

    private fun showPanel() {
        if (panel != null) return
        val view = PanelView(this, this)
        val width = dp(300)
        // 先量一遍拿到高度，才能决定往上开还是往下开
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        panel = view
        panelParams = params
        positionPanel()
        runCatching { wm.addView(view, params) }
            .onFailure {
                Log.w(LogTags.PANEL, "面板添加失败", it)
                panel = null
                panelParams = null
                return
            }
        // 面板会盖住棋盘，展开期间必须停掉盯盘 —— 否则识别到的是自己的面板
        watcher.setUiPaused(true)
        render(watcher.state.value)
    }

    private fun positionPanel() {
        val view = panel ?: return
        val params = panelParams ?: return
        val bubbleView = bubble
        val bubbleWidth = bubbleView?.width?.takeIf { it > 0 } ?: dp(96)
        val bubbleHeight = bubbleView?.height?.takeIf { it > 0 } ?: dp(44)
        val anchorX = bubbleParams?.x ?: dp(12)
        val anchorY = bubbleParams?.y ?: (screenHeight() - dp(180))
        val panelWidth = params.width
        val panelHeight = view.measuredHeight.takeIf { it > 0 } ?: dp(360)

        params.x = (anchorX + bubbleWidth - panelWidth).coerceIn(0, (screenWidth() - panelWidth).coerceAtLeast(0))
        val above = anchorY - panelHeight - dp(8)
        params.y = if (above >= 0) above else (anchorY + bubbleHeight + dp(8))
        panel?.let { if (it.isAttachedToWindow) runCatching { wm.updateViewLayout(it, params) } }
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
        watcher.setUiPaused(false)
    }

    // ---- 状态渲染 ----

    private fun render(snapshot: WatchSnapshot) {
        bubble?.let { OverlayViews.tintBubble(it, snapshot.phase) }
        panel?.let {
            it.render(
                snapshot = snapshot,
                sideMode = watcher.sideMode,
                paused = watcher.paused,
                depth = graph.settings.depth,
                intervalMs = graph.settings.scanIntervalMs
            )
        }
        updateNotification(snapshot)
        if (reopenAfterRefresh && snapshot.phase != WatchPhase.CAPTURING &&
            snapshot.phase != WatchPhase.ANALYZING && snapshot.phase != WatchPhase.WAITING_STABLE
        ) {
            reopenAfterRefresh = false
            showPanel()
        }
    }

    // ---- 面板回调 ----

    override fun onRefresh() {
        // 面板先收起来：它挡着棋盘，不收起的话"重新识别"识别到的还是面板
        reopenAfterRefresh = true
        hidePanel()
        scope.launch {
            delay(150) // 等系统真的把面板从画面上撤掉，否则这一帧还带着它
            watcher.refreshOnce()
            if (reopenAfterRefresh) {
                reopenAfterRefresh = false
                showPanel()
            }
        }
    }

    override fun onTogglePause() {
        val paused = watcher.togglePause()
        render(watcher.state.value)
        toast(if (paused) "已暂停" else "已继续")
    }

    override fun onCycleSide() {
        val mode = watcher.cycleSideMode()
        toast("走子方：${mode.label}")
    }

    override fun onDepthDelta(delta: Int) {
        graph.settings.depth = graph.settings.depth + delta
        // 深度是每次 analyze 的入参，改完不用重建引擎
        render(watcher.state.value)
        toast("搜索深度 ${graph.settings.depth}")
    }

    override fun onIntervalDelta(delta: Int) {
        graph.settings.scanIntervalMs = graph.settings.scanIntervalMs + delta * 200
        render(watcher.state.value)
        toast("盯盘间隔 ${graph.settings.scanIntervalMs}ms")
    }

    /**
     * 收起 / 展开小棋盘。
     *
     * 展开后要**重新摆一次位置**：面板高度变了，而窗口是 WRAP_CONTENT 高度、
     * 位置由我们自己算 —— 不重摆就会出现"面板往上长，顶到屏幕外"或者
     * "收起后下面空一大块"。
     */
    override fun onToggleBoard() {
        val visible = !graph.settings.showBoard
        graph.settings.showBoard = visible
        val view = panel ?: return
        view.setBoardVisible(visible)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(panelParams?.width ?: dp(300), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        positionPanel()
        render(watcher.state.value)
        toast(if (visible) "显示棋盘" else "收起棋盘")
    }

    override fun onMinimize() = hidePanel()

    override fun onStop() {
        stopSelf()
    }

    // ---- 通知 ----

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "悬浮识别", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "象棋助手悬浮窗运行状态"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val notification = buildNotification("正在盯盘")
        // 用两参版本，让系统读取 manifest 里声明的 foregroundServiceType=specialUse。
        // 三参版本要显式传类型，而那个常量在 API 34 才定义，29~33 上传进去反而会抛异常。
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("象棋助手 v2")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_xiangqi)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_xiangqi),
                    "停止",
                    stop
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    /** 通知文案跟状态走 —— 用户切到别的应用后，这是唯一能看到"还在不在干活"的地方。 */
    private fun updateNotification(snapshot: WatchSnapshot) {
        val text = when (snapshot.phase) {
            WatchPhase.READY -> {
                val move = snapshot.analysis?.bestMoveUci ?: "—"
                "${snapshot.side.displayName}走 $move（深度 ${snapshot.analysis?.depth ?: 0}）"
            }
            WatchPhase.ANALYZING -> "分析中…"
            WatchPhase.CAPTURING -> "抓屏中…"
            WatchPhase.WAITING_STABLE -> "等待画面稳定…"
            WatchPhase.PROBLEM -> snapshot.message
            WatchPhase.IDLE -> if (watcher.paused) "已暂停" else "待开始"
        }
        runCatching {
            (getSystemService(NotificationManager::class.java))
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    // ---- 小工具 ----

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    @Suppress("DEPRECATION")
    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    companion object {
        private const val CHANNEL_ID = "xiangqi_overlay"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.xiangqi.v2.action.STOP"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
