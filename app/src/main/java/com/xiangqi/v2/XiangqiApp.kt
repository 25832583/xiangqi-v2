package com.xiangqi.v2

import android.app.Application
import android.content.Context
import com.xiangqi.v2.di.AppGraph

/**
 * 应用入口：只负责持有 [AppGraph]。
 *
 * 放在 Application 而不是 Service 里，是因为模型会话与引擎进程是**应用级**资源：
 * 悬浮服务被重启（改设置、系统回收、用户手动停再启）时不该重新付一次冷启动代价。
 */
class XiangqiApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    companion object {
        fun graphOf(context: Context): AppGraph =
            (context.applicationContext as XiangqiApp).graph
    }
}
