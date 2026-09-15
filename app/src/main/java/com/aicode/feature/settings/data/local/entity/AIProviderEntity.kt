package com.aicode.feature.settings.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_providers")
data class AIProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    /** 明文 Room，与 git token 同口径；后续统一加密时一并处理。 */
    val apiKey: String,
    /** 多 Key 模式开关；关闭时只用 [apiKey]。 */
    val multiKeyEnabled: Boolean = false,
    /** 多 Key 候选列表，以换行分隔持久化（同 [models]）。 */
    val apiKeys: String = "",
    /** 多 Key 取用策略：SEQUENTIAL / ROUND_ROBIN。 */
    val keyRotationStrategy: String = "SEQUENTIAL",
    /** 已废弃：「连续失败达阈值才切」的旧阈值，保留列以兼容旧数据库（SQLite 3.18 无 DROP COLUMN）。 */
    val keyFailoverThreshold: Int = 2,
    /** 被切走的 Key 冷却分钟数；0 表示不冷却。 */
    val keyCooldownMinutes: Int = 5,
    /** 命中即触发多 Key 自动切换的 HTTP 状态码，以逗号分隔持久化；空串表示用默认值。 */
    val keySwitchStatusCodes: String = "",
    val baseUrl: String,
    val defaultModel: String,
    /** 可用模型列表，以换行分隔持久化。 */
    val models: String = "",
    /** 当前选中模型；为空时回退到 defaultModel。 */
    val selectedModel: String = "",
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Anthropic 显式缓存断点（cache_control）。仅 ANTHROPIC 类型使用，默认开启。 */
    val anthropicCacheBreakpoints: Boolean = true,
    /** Chat Completion 路径发送 prompt_cache_key（shard 路由）。仅 OPENAI 类型使用，默认关闭（官方 API 不接受该字段）。 */
    val openaiChatCacheKey: Boolean = false,
    /** 套餐余量脚本路径。 */
    val balanceScriptPath: String = "",
    /** 套餐余量自动刷新间隔（分钟）。默认 5 分钟。 */
    val balanceRefreshInterval: Int = 5,
    /** 自定义请求头 User-Agent；留空使用默认。 */
    val userAgent: String = "",
    /** 提供商列表排序序号，越小越靠前；新建时分配 max+1。 */
    val sortOrder: Int = 0,
    /** 单独为该提供商配置代理（关闭时跟随全局代理设置）。 */
    val proxyEnabled: Boolean = false,
    val proxyType: String = "HTTP",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = ""
)
