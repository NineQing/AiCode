package com.aicode.feature.settings.data.repository

import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 多 Key 轮换器：会话粘性、立即切换、已试 Key 排除与冷却终止。
 */
class ProviderKeyRotatorTest {

    private fun config(
        id: String = "p1",
        keys: List<String> = listOf("key-a", "key-b", "key-c"),
        strategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
        cooldownMinutes: Int = 5
    ) = AIProviderConfig(
        id = id,
        name = "p",
        type = ProviderType.OPENAI,
        apiKey = "",
        multiKeyEnabled = true,
        apiKeys = keys,
        keyRotationStrategy = strategy,
        keyCooldownMinutes = cooldownMinutes,
        baseUrl = "https://example.com",
        defaultModel = "m"
    )

    @Test
    fun activeKey_sticks_to_same_key_within_session() {
        val rotator = ProviderKeyRotator()
        val c = config(strategy = KeyRotationStrategy.ROUND_ROBIN)
        val first = rotator.activeKey(c, "s1")
        assertEquals(first, rotator.activeKey(c, "s1"))
        assertEquals(first, rotator.activeKey(c, "s1"))
    }

    @Test
    fun activeKey_round_robin_rotates_across_sessions() {
        val rotator = ProviderKeyRotator()
        val c = config(strategy = KeyRotationStrategy.ROUND_ROBIN)
        val keys = listOf(
            rotator.activeKey(c, "s1"),
            rotator.activeKey(c, "s2"),
            rotator.activeKey(c, "s3")
        )
        assertEquals(setOf("key-a", "key-b", "key-c"), keys.toSet())
    }

    @Test
    fun activeKey_sequential_always_starts_from_first() {
        val rotator = ProviderKeyRotator()
        val c = config()
        assertEquals("key-a", rotator.activeKey(c, "s1"))
        assertEquals("key-a", rotator.activeKey(c, "s2"))
    }

    @Test
    fun single_key_returns_it_without_rotation() {
        val rotator = ProviderKeyRotator()
        val c = config(keys = listOf("only"))
        assertEquals("only", rotator.activeKey(c, "s1"))
    }

    @Test
    fun reportFailure_switches_immediately() {
        val rotator = ProviderKeyRotator()
        val c = config()
        val active = rotator.activeKey(c, "s1")
        assertEquals("key-a", active)

        val switched = rotator.reportFailure(c.id, "s1", active!!, setOf(active))
        assertEquals("key-b", switched?.newKey)
        assertEquals(2, switched?.newIndex)
        assertEquals(3, switched?.total)
    }

    @Test
    fun reportFailure_excludes_tried_keys_and_returns_null_when_exhausted() {
        val rotator = ProviderKeyRotator()
        val c = config()
        rotator.activeKey(c, "s1")
        assertNull(rotator.reportFailure(c.id, "s1", "key-c", setOf("key-a", "key-b", "key-c")))
    }

    @Test
    fun reportFailure_without_prior_activeKey_returns_null() {
        val rotator = ProviderKeyRotator()
        assertNull(rotator.reportFailure("p1", "s1", "key-a", setOf("key-a")))
    }

    @Test
    fun reportFailure_single_key_returns_null() {
        val rotator = ProviderKeyRotator()
        val c = config(keys = listOf("only"))
        rotator.activeKey(c, "s1")
        assertNull(rotator.reportFailure(c.id, "s1", "only", setOf("only")))
    }

    @Test
    fun switched_away_key_is_not_reselected_while_cooling() {
        val rotator = ProviderKeyRotator()
        val c = config(keys = listOf("key-a", "key-b"))
        rotator.activeKey(c, "s1")
        rotator.reportFailure(c.id, "s1", "key-a", setOf("key-a"))
        // 新会话只能拿到未冷却的 key-b
        assertEquals("key-b", rotator.activeKey(c, "s2"))
    }

    @Test
    fun reportFailure_without_alternative_does_not_cool_down() {
        val rotator = ProviderKeyRotator()
        val c = config(keys = listOf("only"))
        assertEquals("only", rotator.activeKey(c, "s1"))
        assertNull(rotator.reportFailure(c.id, "s1", "only", setOf("only")))
        // 没有可切换候选时不应把唯一的 Key 打入冷却，否则会锁死一段时间
        assertEquals("only", rotator.activeKey(c, "s1"))
    }

    @Test
    fun reportFailure_last_candidate_is_not_cooled() {
        val rotator = ProviderKeyRotator()
        val c = config(keys = listOf("key-a", "key-b"))
        rotator.activeKey(c, "s1")
        rotator.reportFailure(c.id, "s1", "key-a", setOf("key-a")) // key-a 冷却，切到 key-b
        rotator.reportFailure(c.id, "s1", "key-b", setOf("key-a", "key-b")) // 无候选，不冷却 key-b
        assertEquals("key-b", rotator.activeKey(c, "s1"))
    }

    @Test
    fun cooldown_zero_allows_immediate_reuse() {
        val rotator = ProviderKeyRotator()
        val c = config(keys = listOf("key-a", "key-b"), cooldownMinutes = 0)
        rotator.activeKey(c, "s1")
        rotator.reportFailure(c.id, "s1", "key-a", setOf("key-a"))
        assertEquals("key-a", rotator.activeKey(c, "s2"))
    }

    @Test
    fun currentKey_reflects_last_selected() {
        val rotator = ProviderKeyRotator()
        val c = config()
        rotator.activeKey(c, "s1")
        assertEquals("key-a", rotator.currentKey(c))
    }
}
