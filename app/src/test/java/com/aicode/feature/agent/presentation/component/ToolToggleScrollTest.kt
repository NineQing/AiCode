package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展开/收起工具行后的视口重定位目标。
 *
 * 守住两条回归：①展开后**绝不被拉到视口顶部**（目标位置不得大于卡片当前偏移，也不得为负）；
 * ②只有底部被悬浮层（输入框）挡住时才滚，且滚的是「最小必要位移」——恰好让底部落在安全区。
 */
class ToolToggleScrollTest {

    /** 视口高 2000px，底部预留输入框 400px → 安全区下沿 1600px。 */
    private val safeBottom = 1600

    @Test
    fun itemFitsAboveSafeArea_staysInPlace() {
        // 卡片在视口中部、底部（300 + 900 = 1200）仍在安全区以上：一动不动
        assertEquals(300, toolToggleScrollTarget(itemOffset = 300, itemSize = 900, safeBottom = safeBottom))
    }

    @Test
    fun itemBottomExactlyAtSafeArea_staysInPlace() {
        assertEquals(300, toolToggleScrollTarget(itemOffset = 300, itemSize = 1300, safeBottom = safeBottom))
    }

    @Test
    fun itemBottomPastSafeArea_scrollsUpByMinimalAmount() {
        // 底部 300 + 1500 = 1800，超出安全区 200 → 上滚 200，底部恰好停在 1600
        val target = toolToggleScrollTarget(itemOffset = 300, itemSize = 1500, safeBottom = safeBottom)
        assertEquals(100, target)
        assertEquals("底部应恰好落在安全区", safeBottom, target + 1500)
    }

    @Test
    fun neverScrollsBelowCurrentOffset() {
        // 核心回归：任何输入下目标都不得大于卡片当前偏移（大于即表示把卡片往上顶、往视口顶推）
        val offsets = listOf(0, 1, 120, 600, 1600)
        val sizes = listOf(0, 40, 800, 1600, 4000)
        for (offset in offsets) {
            for (size in sizes) {
                val target = toolToggleScrollTarget(offset, size, safeBottom)
                assertTrue(
                    "offset=$offset size=$size target=$target 不得大于当前偏移",
                    target <= offset
                )
                assertTrue("目标位置不得为负（负值会把标题推出视口顶）", target >= 0)
            }
        }
    }

    @Test
    fun shrunkItem_staysInPlace() {
        // 收起后卡片还在视口里：不主动滚动（把卡片往上吸会凭空跳动）
        assertEquals(400, toolToggleScrollTarget(itemOffset = 400, itemSize = 120, safeBottom = safeBottom))
    }

    @Test
    fun itemNearTop_doesNotJumpToViewportTop() {
        // 卡片顶部已在视口上方（offset 为负）：目标是 0（最小位移），而不是把它整块拉到顶
        assertEquals(0, toolToggleScrollTarget(itemOffset = -50, itemSize = 900, safeBottom = safeBottom))
    }

    @Test
    fun oversizedItem_clampsToViewportTop() {
        // 卡片比安全区还高：所需上滚量超过自身偏移 → 只能压到 0，此时卡片顶停在视口顶
        assertEquals(0, toolToggleScrollTarget(itemOffset = 200, itemSize = 2000, safeBottom = safeBottom))
    }

    @Test
    fun unmeasuredHeight_staysInPlace() {
        // 高度还没测量出来：不动，避免按 0 高度算出「滚到 safeBottom」这种离谱目标
        assertEquals(300, toolToggleScrollTarget(itemOffset = 300, itemSize = 0, safeBottom = safeBottom))
    }
}
