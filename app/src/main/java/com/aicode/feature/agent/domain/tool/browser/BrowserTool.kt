package com.aicode.feature.agent.domain.tool.browser

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class BrowserTool @Inject constructor(
    private val browserManager: BrowserManager
) : AgentTool() {

    private companion object {
        const val TAG = "BrowserTool"
        const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
        const val DEFAULT_BACKBONE_DEPTH = 8
        val VISUAL_ACTIONS = setOf("navigate", "click", "fill", "select", "hover", "press", "scroll", "back", "forward", "reload")
        val READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait")
    }

    override val name = "browser"
    override val description = "控制内置浏览器执行自动化操作。支持后台运行（无需打开面板），但截图需面板可见。" +
        "navigate/click/fill/hover/press/scroll/back/forward/reload 后自动附加截图（面板可见时）。" +
        "evaluate 支持 async/Promise，返回原生 JSON（保留 number/boolean/null 类型）。" +
        "click 使用完整事件链，兼容 React/Vue。fill 使用 native setter + React valueTracker hack。" +
        "selector 支持 text= / text*= / role=button[name=xxx] / xpath= / CSS。每次返回 url+title。" +
        "getBackbone 返回无障碍树（role/name/ref，已过滤 script/style 与不可见元素），ref 可传给 selector 直接操作元素。" +
        "select 选原生下拉，dialog 处理 confirm/prompt 对话框，console 取页面控制台日志。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE)

    private val actionEnum = listOf(
        "navigate", "evaluate", "click", "fill", "select", "hover", "press",
        "getText", "getHtml", "getBackbone", "screenshot", "console",
        "wait", "scroll", "dialog", "back", "forward", "reload"
    )

    private val actionSchema: Map<String, Any> = mapOf(
        "type" to "string",
        "enum" to actionEnum,
        "description" to "navigate=导航URL; evaluate=执行JS(支持Promise,返回原生JSON); click=点击(完整事件链); fill=填充表单(React兼容); select=下拉选择; hover=悬停(展开菜单等); press=按键(Enter/Escape/Tab/方向键等); getText=提取文本; getHtml=提取HTML; getBackbone=无障碍树(role/name/ref,ref可传给selector); screenshot=截图(需面板可见); console=控制台日志; wait=等待条件; scroll=滚动; dialog=处理confirm/prompt对话框; back=后退; forward=前进; reload=刷新"
    )

    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "操作类型，见 enum 列表", true),
        "url" to ToolParameter("url", ParameterType.STRING, "navigate: URL（不带协议自动加 https://）", false),
        "script" to ToolParameter("script", ParameterType.STRING, "evaluate: JS 代码（支持 Promise/async）", false),
        "selector" to ToolParameter("selector", ParameterType.STRING,
            "click/fill/hover/getText/getHtml/scroll/wait: 选择器。支持 ref=e22（getBackbone 返回的引用）/ text=登录 / text*=登录 / role=button[name=\"登录\"] / xpath=//a / CSS", false),
        "value" to ToolParameter("value", ParameterType.STRING, "fill/select: 要填入的值或要选中的 option（value 或可见文本）", false),
        "accept" to ToolParameter("accept", ParameterType.BOOLEAN, "dialog: 是否接受对话框（true=确定，false=取消），默认 true", false),
        "text" to ToolParameter("text", ParameterType.STRING, "dialog: prompt 输入内容（accept=true 时生效）", false),
        "level" to ToolParameter("level", ParameterType.STRING, "console: 日志级别过滤（log/warning/error/debug）", false),
        "clear" to ToolParameter("clear", ParameterType.BOOLEAN, "console: 取完后是否清空日志，默认 false", false),
        "key" to ToolParameter("key", ParameterType.STRING,
            "press: 键名（Enter/Escape/Tab/ArrowUp/ArrowDown/ArrowLeft/ArrowRight/Backspace/Delete/空格/普通字符）", false),
        "condition" to ToolParameter("condition", ParameterType.STRING,
            "wait: 等待条件。text=登录(精确匹配) / text*=登录(包含匹配) / selector=#result(CSS存在) / domStable(DOM稳定)", false),
        "timeout" to ToolParameter("timeout", ParameterType.INTEGER, "wait: 超时毫秒，默认 10000", false),
        "maxDepth" to ToolParameter("maxDepth", ParameterType.INTEGER, "getBackbone: 无障碍树最大深度，默认 8", false)
    )

    override fun toJsonSchema(): Map<String, Any> {
        val intKeys = setOf("timeout", "maxDepth")
        val boolKeys = setOf("accept", "clear")
        val properties = LinkedHashMap<String, Any>()
        properties["action"] = actionSchema
        for ((key, param) in parameters) {
            if (key == "action") continue
            properties[key] = mapOf(
                "type" to when {
                    key in intKeys -> "integer"
                    key in boolKeys -> "boolean"
                    else -> "string"
                },
                "description" to param.description
            )
        }
        return mapOf("type" to "object", "properties" to properties, "required" to listOf("action"))
    }

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
        return if (action in READ_ONLY_ACTIONS) setOf(ToolCapability.NETWORK_READ)
        else setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE)
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少 action 参数", "MISSING_ACTION")

        return try {
            val result = executeAction(action, args)
            if (action in VISUAL_ACTIONS && result is ToolResult.Success) {
                val screenshot = browserManager.screenshotIfVisible()
                if (screenshot != null) {
                    ToolResult.Success(result.data, images = listOf(screenshot))
                } else {
                    result
                }
            } else {
                result
            }
        } catch (e: IllegalStateException) {
            FileLogger.e(TAG, "浏览器操作失败: $action", e)
            ToolResult.Error(e.message ?: "浏览器未初始化", "BROWSER_NOT_READY")
        } catch (e: TimeoutCancellationException) {
            ToolResult.Error("操作超时: $action", "TIMEOUT")
        } catch (e: Exception) {
            FileLogger.e(TAG, "浏览器操作异常: $action", e)
            ToolResult.Error("操作失败: ${e.message}", "BROWSER_ERROR")
        }
    }

    private suspend fun executeAction(action: String, args: Map<String, JsonElement>): ToolResult {
        return when (action) {
            "navigate" -> {
                val url = args["url"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("navigate 需要 url 参数", "MISSING_URL")
                FileLogger.i(TAG, "navigate: $url")
                val finalUrl = browserManager.navigate(url)
                buildOk(action, mapOf(
                    "requestedUrl" to JsonPrimitive(url),
                    "finalUrl" to JsonPrimitive(finalUrl),
                    "title" to JsonPrimitive(browserManager.getTitle())
                ))
            }

            "evaluate" -> {
                val script = args["script"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("evaluate 需要 script 参数", "MISSING_SCRIPT")
                FileLogger.i(TAG, "evaluate: ${script.take(100)}")
                val result = browserManager.evaluateJavaScript(script)
                val value = browserManager.parseEvalResult(result)
                buildOk(action, mapOf("value" to value))
            }

            "click" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("click 需要 selector 参数", "MISSING_SELECTOR")
                FileLogger.i(TAG, "click: $selector")
                val json = browserManager.clickElement(selector)
                buildFromJson(action, json)
            }

            "fill" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("fill 需要 selector 参数", "MISSING_SELECTOR")
                val value = args["value"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("fill 需要 value 参数", "MISSING_VALUE")
                FileLogger.i(TAG, "fill: $selector")
                val json = browserManager.fillElement(selector, value)
                buildFromJson(action, json)
            }

            "hover" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("hover 需要 selector 参数", "MISSING_SELECTOR")
                FileLogger.i(TAG, "hover: $selector")
                val json = browserManager.hoverElement(selector)
                buildFromJson(action, json)
            }

            "press" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("press 需要 key 参数", "MISSING_KEY")
                FileLogger.i(TAG, "press: $key")
                browserManager.pressKey(key)
                buildOk(action, mapOf("key" to JsonPrimitive(key)))
            }

            "getText" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                val text = browserManager.getText(selector)
                val truncated = text.take(50_000)
                val resultText = if (text.length > 50_000) "$truncated\n\n[超长截断...]" else truncated
                buildOk(action, mapOf(
                    "text" to JsonPrimitive(resultText),
                    "selector" to JsonPrimitive(selector ?: "body")
                ))
            }

            "getHtml" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                val html = browserManager.getHtml(selector)
                buildOk(action, mapOf(
                    "html" to JsonPrimitive(html),
                    "selector" to JsonPrimitive(selector ?: "document")
                ))
            }

            "getBackbone" -> {
                val maxDepth = args["maxDepth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_BACKBONE_DEPTH
                FileLogger.i(TAG, "getBackbone: depth=$maxDepth")
                val tree = browserManager.getBackbone(maxDepth)
                buildOk(action, mapOf(
                    "tree" to browserManager.parseEvalResult(tree),
                    "maxDepth" to JsonPrimitive(maxDepth)
                ))
            }

            "screenshot" -> {
                FileLogger.i(TAG, "screenshot")
                val image = browserManager.screenshot()
                if (image != null) {
                    val viewport = browserManager.getViewportInfo()
                    val data = buildData(action, mapOf(
                        "status" to JsonPrimitive("captured"),
                        "viewport" to (browserManager.parseEvalResult(viewport))
                    ))
                    ToolResult.Success(data, images = listOf(image))
                } else {
                    buildError(action, "截图失败：浏览器面板未打开或尺寸为 0。请先打开浏览器面板。", "SCREENSHOT_FAILED")
                }
            }

            "wait" -> {
                val condition = args["condition"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("wait 需要 condition 参数", "MISSING_CONDITION")
                val timeout = args["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: DEFAULT_WAIT_TIMEOUT_MS
                FileLogger.i(TAG, "wait: $condition, timeout=$timeout")
                val met = try {
                    browserManager.wait(condition, timeout)
                } catch (e: TimeoutCancellationException) { false }
                buildOk(action, mapOf(
                    "condition" to JsonPrimitive(condition),
                    "met" to JsonPrimitive(met),
                    "message" to JsonPrimitive(if (met) "条件已满足" else "超时未满足")
                ))
            }

            "select" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("select 需要 selector 参数", "MISSING_SELECTOR")
                val value = args["value"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("select 需要 value 参数", "MISSING_VALUE")
                FileLogger.i(TAG, "select: $selector -> $value")
                val json = browserManager.selectOption(selector, value)
                buildFromJson(action, json)
            }

            "dialog" -> {
                val accept = args["accept"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val text = args["text"]?.jsonPrimitive?.contentOrNull
                FileLogger.i(TAG, "dialog: accept=$accept")
                val detail = browserManager.parseEvalResult(browserManager.resolveDialog(accept, text))
                buildOk(action, mapOf("result" to detail))
            }

            "console" -> {
                val level = args["level"]?.jsonPrimitive?.contentOrNull
                val clear = args["clear"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                FileLogger.i(TAG, "console: level=$level, clear=$clear")
                val json = browserManager.getConsoleLogs(level, clear)
                buildOk(action, mapOf("logs" to browserManager.parseEvalResult(json)))
            }

            "scroll" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                val x = args["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val y = args["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                FileLogger.i(TAG, "scroll: selector=$selector, x=$x, y=$y")
                val json = browserManager.scroll(selector, x, y)
                buildFromJson(action, json)
            }

            "back" -> {
                val success = browserManager.goBack()
                buildResult(success, action, mapOf(
                    "success" to JsonPrimitive(success),
                    "message" to JsonPrimitive(if (success) "已后退" else "无法后退：无历史记录或导航未生效")
                ))
            }

            "forward" -> {
                val success = browserManager.goForward()
                buildResult(success, action, mapOf(
                    "success" to JsonPrimitive(success),
                    "message" to JsonPrimitive(if (success) "已前进" else "无法前进：无历史记录或导航未生效")
                ))
            }

            "reload" -> {
                val url = browserManager.reload()
                buildOk(action, mapOf(
                    "url" to JsonPrimitive(url),
                    "title" to JsonPrimitive(browserManager.getTitle())
                ))
            }

            else -> ToolResult.Error("未知 action: $action", "UNKNOWN_ACTION")
        }
    }

    /** 统一响应封装；存在挂起的 confirm/prompt 时附加 pendingDialog 提示 AI 处理。 */
    private fun envelope(ok: Boolean, action: String, detail: JsonElement): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["ok"] = JsonPrimitive(ok)
        fields["action"] = JsonPrimitive(action)
        fields["url"] = JsonPrimitive(browserManager.getUrl())
        fields["title"] = JsonPrimitive(browserManager.getTitle())
        fields["detail"] = detail
        fields["error"] = JsonNull
        browserManager.pendingDialogInfo()?.let { d ->
            fields["pendingDialog"] = JsonObject(mapOf(
                "type" to JsonPrimitive(d.type),
                "message" to JsonPrimitive(d.message)
            ))
        }
        return JsonObject(fields)
    }

    private fun buildResult(ok: Boolean, action: String, detail: Map<String, JsonElement>): ToolResult {
        return ToolResult.Success(envelope(ok, action, JsonObject(detail)))
    }

    private fun buildData(action: String, detail: Map<String, JsonElement>): JsonObject {
        return envelope(true, action, JsonObject(detail))
    }

    private fun buildOk(action: String, detail: Map<String, JsonElement>): ToolResult {
        return ToolResult.Success(buildData(action, detail))
    }

    private fun buildFromJson(action: String, jsonStr: String): ToolResult {
        val detail = browserManager.parseEvalResult(jsonStr)
        val ok = (detail as? JsonObject)?.get("matched")?.jsonPrimitive?.contentOrNull != "false"
        return ToolResult.Success(envelope(ok, action, detail))
    }

    private fun buildError(action: String, message: String, code: String): ToolResult {
        return ToolResult.Success(JsonObject(mapOf(
            "ok" to JsonPrimitive(false),
            "action" to JsonPrimitive(action),
            "url" to JsonPrimitive(browserManager.getUrl()),
            "title" to JsonPrimitive(browserManager.getTitle()),
            "detail" to JsonObject(emptyMap()),
            "error" to JsonObject(mapOf(
                "code" to JsonPrimitive(code),
                "message" to JsonPrimitive(message)
            ))
        )))
    }
}
