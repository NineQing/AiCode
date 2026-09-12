package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续工具调用分组的展开判定。
 *
 * 展开与否 = **上层持久化的手动选择优先**（[AIAgentViewModel.toolExpansionOverrides]，按 [toolGroupKey] 取），
 * 没有手动记录时才回落自动规则（组内还在跑 / 本轮仍在进行且这是最后一个分组）。
 *
 * 重点守两条：①手动选择跨重建稳定（持久化的语义就是每次重建都按同一份覆盖走）；
 * ②自动展开只认「最后一个分组」，新的一轮开始不会把历史分组全部弹开。
 */
class ToolGroupExpansionTest {

    private fun tool(id: String) = AgentUIMessage(id = id, role = MessageRole.TOOL, content = "done")

    private fun assistant(id: String) =
        AgentUIMessage(id = id, role = MessageRole.ASSISTANT, content = "看一下")

    private val groupKey = "toolgroup:t1"

    private fun items(
        messages: List<AgentUIMessage>,
        agentBusy: Boolean = false,
        runningToolIds: Set<String> = emptySet(),
        overrides: Map<String, Boolean> = emptyMap(),
    ) = buildChatItems(messages, agentBusy, runningToolIds, overrides)

    @Test
    fun runningGroup_expandsByDefault() {
        val items = items(listOf(tool("t1"), tool("t2")), runningToolIds = setOf("t1"))
        val header = items.first()
        assertEquals(groupKey, header.key)
        assertTrue(header.groupExpanded)
        // 成员行只在展开时生成：头 + 2 个成员
        assertTrue(items.any { it.key == "t1" })
        assertTrue(items.any { it.key == "t2" })
    }

    @Test
    fun finishedTurn_collapsesByDefault() {
        val items = items(listOf(tool("t1"), tool("t2")))
        assertFalse(items.first().groupExpanded)
        // 收起时不生成成员行
        assertFalse(items.any { it.key == "t1" })
        assertEquals(1, items.size)
    }

    @Test
    fun busyLastGroup_expandsByDefault() {
        val items = items(listOf(tool("t1"), tool("t2")), agentBusy = true)
        assertTrue(items.first().groupExpanded)
    }

    @Test
    fun busyLastGroupWithMultipleMembers_expandsByDefault() {
        // 回归：曾经把「最后一条可分组消息的下标」当成「最后一个分组的下标」，导致多成员的最后一个分组
        // 在轮次进行中反而不自动展开（只有单条工具的分组才碰巧成立）。
        val items = items(listOf(tool("t1"), tool("t2"), tool("t3")), agentBusy = true)
        assertTrue("多成员的最后一个分组也应自动展开", items.first().groupExpanded)
        assertEquals(4, items.size)
    }

    @Test
    fun manualCollapse_winsOverRunning() {
        val items = items(
            listOf(tool("t1"), tool("t2")),
            runningToolIds = setOf("t1"),
            overrides = mapOf(groupKey to false),
        )
        assertFalse("手动收起后，即使组内还在跑也应保持收起", items.first().groupExpanded)
    }

    @Test
    fun manualExpand_survivesRebuild() {
        val messages = listOf(tool("t1"), tool("t2"))
        val expanded = items(messages, overrides = mapOf(groupKey to true))
        assertTrue(expanded.first().groupExpanded)
        // 同一份覆盖重建（列表滚动回收 / 切页返回后重组走的就是这条路径）：状态必须一致
        val rebuilt = items(messages, overrides = mapOf(groupKey to true))
        assertTrue(rebuilt.first().groupExpanded)
        assertEquals(expanded.size, rebuilt.size)
    }

    @Test
    fun manualExpand_winsOverIdleTurn() {
        val items = items(listOf(tool("t1"), tool("t2")), overrides = mapOf(groupKey to true))
        assertTrue("本轮已结束，但用户手动展开过：不应被自动折叠规则改回去", items.first().groupExpanded)
    }

    @Test
    fun onlyLastGroupAutoExpands() {
        // 两批工具调用被一条助手消息隔开：轮次进行中也只弹开最后一个分组
        val messages = listOf(
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t3"), tool("t4"),
        )
        val items = items(messages, agentBusy = true)
        val headers = items.filter { it.key.startsWith("toolgroup:") }
        assertEquals(2, headers.size)
        assertFalse("历史分组不应被新一轮弹开", headers[0].groupExpanded)
        assertTrue(headers[1].groupExpanded)
    }

    @Test
    fun userMessageBreaksGrouping() {
        val messages = listOf(
            tool("t1"),
            AgentUIMessage(id = "u1", role = MessageRole.USER, content = "继续"),
            tool("t2"),
        )
        val items = items(messages)
        val headers = items.filter { it.key.startsWith("toolgroup:") }
        assertEquals(2, headers.size)
        assertEquals("toolgroup:t1", headers[0].key)
        assertEquals("toolgroup:t2", headers[1].key)
    }

    // ---- 成员行缩进判定（isExpandedGroupMember）----

    @Test
    fun membersOfExpandedGroup_areIndented() {
        val items = items(listOf(tool("t1"), tool("t2")), overrides = mapOf(groupKey to true))
        // items = [头, t1, t2]
        assertFalse("分组头自身不缩进", isExpandedGroupMember(items, 0, isToolRow = false))
        assertTrue("展开组的成员应缩进", isExpandedGroupMember(items, 1, isToolRow = true))
        assertTrue(isExpandedGroupMember(items, 2, isToolRow = true))
    }

    @Test
    fun collapsedGroupHasNoMembers() {
        val items = items(listOf(tool("t1"), tool("t2")))
        assertEquals(1, items.size)
        assertFalse(isExpandedGroupMember(items, 0, isToolRow = false))
    }

    @Test
    fun ordinaryToolRowOutsideGroup_isNotIndented() {
        // 两组都被手动展开：中间那条是单成员组，它必须从**自己的**分组头判定，
        // 不能越过助手正文去继承前一个分组的缩进。
        val messages = listOf(
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t9"),
        )
        val items = items(
            messages,
            overrides = mapOf(groupKey to true, "toolgroup:t9" to true),
        )
        val firstMember = items.indexOfFirst { it.key == "t1" }
        val secondHeader = items.indexOfFirst { it.key == "toolgroup:t9" }
        val secondMember = items.indexOfFirst { it.key == "t9" }
        assertTrue("前置条件：两个分组都应展开", firstMember > 0 && secondHeader > firstMember && secondMember > secondHeader)

        assertTrue("第一组与其成员连续，成员应缩进", isExpandedGroupMember(items, firstMember, isToolRow = true))
        assertFalse("分组头自身不缩进", isExpandedGroupMember(items, secondHeader, isToolRow = false))
        assertTrue("第二组自己的成员应缩进", isExpandedGroupMember(items, secondMember, isToolRow = true))
    }

    @Test
    fun nonToolRow_isNeverIndented() {
        val items = items(listOf(tool("t1"), tool("t2")), overrides = mapOf(groupKey to true))
        assertFalse(isExpandedGroupMember(items, 1, isToolRow = false))
    }

    @Test
    fun outOfRangeIndex_isNotIndented() {
        val items = items(listOf(tool("t1"), tool("t2")), overrides = mapOf(groupKey to true))
        assertFalse(isExpandedGroupMember(items, 0, isToolRow = true))
        assertFalse(isExpandedGroupMember(items, items.size, isToolRow = true))
        assertFalse(isExpandedGroupMember(items, -1, isToolRow = true))
    }
}
