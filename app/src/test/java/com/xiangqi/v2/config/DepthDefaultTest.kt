package com.xiangqi.v2.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认深度从 12 提到 18 的迁移判据。
 *
 * 这几条钉的都是一次性的判断，但错了会**静默**影响每一局的搜索深度：
 * 多覆盖一格 = 用户主动调快的设置被悄悄改回去；少覆盖一格 = 老机器永远停在 12，
 * 改了默认值却看不出任何区别。两种都是"日志里一条都没有"的那种错。
 */
class DepthDefaultTest {

    @Test
    fun `没存过就交给新默认值，不写盘`() {
        assertNull(DepthDefault.migrate(stored = null, migrated = false))
    }

    @Test
    fun `存的正好是旧默认值 12 才抬到 18`() {
        assertEquals(DepthDefault.CURRENT, DepthDefault.migrate(DepthDefault.LEGACY, false))
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
    fun `已经迁移过就不再迁移，否则用户手动调回 12 会被每次启动改回 18`() {
        assertNull(DepthDefault.migrate(stored = DepthDefault.LEGACY, migrated = true))
    }

    @Test
    fun `新默认值必须落在可调范围内`() {
        assertTrue(DepthDefault.CURRENT in SettingsStore.MIN_DEPTH..SettingsStore.MAX_DEPTH)
        assertTrue(DepthDefault.LEGACY in SettingsStore.MIN_DEPTH..SettingsStore.MAX_DEPTH)
    }
}
