package com.xiangqi.v2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认深度 12 → 18 → 22 的迁移判据。
 *
 * 这几条钉的都是一次性的判断，但错了会**静默**影响每一局的搜索深度：
 * 多覆盖一格 = 用户主动调快的设置被悄悄改回去；少覆盖一格 = 老机器永远停在旧默认值，
 * 改了默认值却看不出任何区别。两种都是"日志里一条都没有"的那种错。
 *
 * 多级迁移还有一个特有的坑：跳版本升级的机器（12 → 22）和正常升级的（18 → 22）
 * 都得覆盖到，只认最近一档会让前一批永远停在最早那个数。
 */
class DepthDefaultTest {

    @Test
    fun `没存过就交给新默认值，不写盘`() {
        assertNull(DepthDefault.migrate(stored = null, migrated = false))
    }

    @Test
    fun `存的正好是最早的默认值 12，一路抬到当前`() {
        assertEquals(DepthDefault.CURRENT, DepthDefault.migrate(DepthDefault.LEGACY, false))
    }

    @Test
    fun `存的正好是上一版默认值 18，也要抬到当前`() {
        assertEquals(DepthDefault.CURRENT, DepthDefault.migrate(DepthDefault.PREVIOUS, false))
    }

    @Test
    fun `用户主动调成 8 图快，不能被改回去`() {
        assertNull(DepthDefault.migrate(stored = 8, migrated = false))
    }

    @Test
    fun `用户主动调到 16 也不能动`() {
        assertNull(DepthDefault.migrate(stored = 16, migrated = false))
    }

    @Test
    fun `已经迁移过就不再迁移，否则用户手动调回去会被每次启动改回来`() {
        assertNull(DepthDefault.migrate(stored = DepthDefault.LEGACY, migrated = true))
        assertNull(DepthDefault.migrate(stored = DepthDefault.PREVIOUS, migrated = true))
    }

    @Test
    fun `新默认值必须落在可调范围内`() {
        assertTrue(DepthDefault.CURRENT in SettingsStore.MIN_DEPTH..SettingsStore.MAX_DEPTH)
        assertTrue(DepthDefault.LEGACY in SettingsStore.MIN_DEPTH..SettingsStore.MAX_DEPTH)
        assertTrue(DepthDefault.PREVIOUS in SettingsStore.MIN_DEPTH..SettingsStore.MAX_DEPTH)
    }

    /** 迁移的目标值不能反过来比上限还高 —— 那种错在测试里看不出来，只会在真机上被 coerce 掉。 */
    @Test
    fun `当前默认值留出了往上调的空间`() {
        assertTrue(DepthDefault.CURRENT < SettingsStore.MAX_DEPTH)
    }
}
