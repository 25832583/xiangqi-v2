package com.xiangqi.v2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 引擎版本在两处各有一份，必须一致：
 *  - [EngineConfig.VERSION]：告诉用户「去哪一版拿权重」
 *  - `tools/fetch_assets.py` 的 `PIKAFISH_VERSION`：决定下载哪一版
 *
 * 只改一处是这个项目最容易犯的错，而且**两边各自都正常**：App 老老实实提示
 * 2026-09-06、脚本老老实实下 2026-01-02，编译能过、测试能过，直到用户照着提示
 * 导入权重、引擎拒绝加载才发现。
 *
 * 这条测试的价值不在「验证逻辑」，在于把一处只能靠人记住的约束变成会失败的检查。
 */
class EngineVersionTest {

    @Test
    fun `备料脚本与本机引擎版本一致`() {
        val script = File("../tools/fetch_assets.py")
        assumeTrue("找不到 tools/fetch_assets.py（可能单独跑了模块测试）", script.exists())

        val text = script.readText(Charsets.UTF_8)
        val found = Regex("""PIKAFISH_VERSION\s*=\s*"([^"]+)"""").find(text)
        assertNotNull("脚本里没有 PIKAFISH_VERSION，解析规则要跟着改", found)
        assertEquals(EngineConfig.VERSION, found!!.groupValues[1])
    }
}
