package com.aicode.feature.agent.domain.tool.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

data class BrowserState(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val attached: Boolean = false
)

data class BrowserDialog(
    val type: String,
    val message: String,
    val defaultValue: String?
)

data class BrowserConsoleEntry(
    val level: String,
    val message: String,
    val line: Int,
    val source: String
)

@Singleton
class BrowserManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    private companion object {
        const val TAG = "BrowserManager"
        const val NAVIGATE_TIMEOUT_MS = 30_000L
        const val EVAL_TIMEOUT_MS = 30_000L
        const val WAIT_POLL_INTERVAL_MS = 500L
        const val SCREENSHOT_QUALITY = 80
        const val MAX_CONTENT_CHARS = 100_000
        const val MAX_BACKBONE_NODES = 600
        const val DOM_STABLE_QUIET_MS = 500L
        const val DOM_STABLE_POLL_MS = 200L
        const val GO_URL_TIMEOUT_MS = 3_000L
        const val GO_POLL_MS = 100L
        const val GO_LOAD_TIMEOUT_MS = 10_000L
        const val HEADLESS_WIDTH_DP = 412
        const val HEADLESS_HEIGHT_DP = 915
        const val MAX_CONSOLE_LOGS = 200
        const val DIALOG_TIMEOUT_MS = 30_000L
    }

    private var webView: WebView? = null
    private var loadDeferred: CompletableDeferred<Result<String>>? = null
    private val pendingJsCalls = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val consoleLogs = ArrayDeque<BrowserConsoleEntry>()
    @Volatile private var pendingDialog: BrowserDialog? = null
    private var pendingDialogResult: JsResult? = null
    private var dialogTimeout: Runnable? = null

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    /** 选择器辅助函数，prepend 到所有需要选择器的 JS 中。支持 ref= / text= / text*= / role= / xpath= / CSS。 */
    private val selectorHelper = """
        function __resolveSelector(selector){
            if(selector.startsWith('ref=')){
                var id=selector.substring(4);
                var reg=window.__bicodeRefs||{};
                var el=reg[id];
                return (el && el.isConnected) ? el : null;
            }
            if(selector.startsWith('text=')){
                var t=selector.substring(5);
                var els=document.querySelectorAll('*');
                for(var i=els.length-1;i>=0;i--){
                    var own=els[i].childNodes;
                    for(var j=0;j<own.length;j++){
                        if(own[j].nodeType===3&&own[j].textContent.trim()===t)return els[i];
                    }
                }
                for(var i=0;i<els.length;i++){
                    if(els[i].textContent.trim()===t)return els[i];
                }
                return null;
            }
            if(selector.startsWith('text*=')){
                var t=selector.substring(6);
                var els=document.querySelectorAll('*');
                var best=null,bestLen=Infinity;
                for(var i=0;i<els.length;i++){
                    var tag=els[i].tagName;
                    if(tag==='HTML'||tag==='BODY'||tag==='HEAD'||tag==='SCRIPT'||tag==='STYLE')continue;
                    var text=els[i].textContent||'';
                    if(text.includes(t)&&text.length<bestLen){
                        best=els[i];bestLen=text.length;
                    }
                }
                return best;
            }
            if(selector.startsWith('role=')){
                var m=selector.match(/^role=(\w+)(?:\[name="(.+)"\])?${'$'}/);
                if(!m)return null;
                var role=m[1],name=m[2];
                var els=document.querySelectorAll('[role="'+role+'"]');
                if(name){
                    for(var i=0;i<els.length;i++){
                        if(els[i].textContent.includes(name))return els[i];
                    }
                    return null;
                }
                return els[0]||null;
            }
            if(selector.startsWith('xpath=')){
                var x=selector.substring(6);
                return document.evaluate(x,document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue;
            }
            return document.querySelector(selector);
        }
    """.trimIndent()

    inner class BrowserJsBridge {
        @JavascriptInterface
        fun resolve(callId: String, result: String) {
            pendingJsCalls[callId]?.complete(result)
            pendingJsCalls.remove(callId)
        }

        @JavascriptInterface
        fun reject(callId: String, error: String) {
            pendingJsCalls[callId]?.completeExceptionally(RuntimeException(error))
            pendingJsCalls.remove(callId)
        }
    }

    private val webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            _state.update { it.copy(loading = true, url = url.orEmpty(), title = "", error = null) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            _state.update { it.copy(loading = false, url = url.orEmpty()) }
            val finish = {
                loadDeferred?.complete(Result.success(url.orEmpty()))
                loadDeferred = null
            }
            if (view == null) {
                finish()
            } else {
                view.evaluateJavascript("(function(){return document.title})()") { result ->
                    val title = result?.trim()?.trim('"') ?: ""
                    _state.update { it.copy(title = title) }
                    finish()
                }
            }
        }

        override fun onReceivedError(
            view: WebView?, request: WebResourceRequest?, error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                val msg = error?.description?.toString() ?: "未知错误"
                _state.update { it.copy(loading = false, error = msg) }
                loadDeferred?.complete(Result.failure(RuntimeException(msg)))
                loadDeferred = null
            }
        }
    }

    private val webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            val level = when (msg.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "error"
                ConsoleMessage.MessageLevel.WARNING -> "warning"
                ConsoleMessage.MessageLevel.DEBUG -> "debug"
                else -> "log"
            }
            if (consoleLogs.size >= MAX_CONSOLE_LOGS) consoleLogs.removeFirst()
            consoleLogs.addLast(BrowserConsoleEntry(level, msg.message(), msg.lineNumber(), msg.sourceId()))
            return true
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            if (result == null) return false
            pendingDialog = BrowserDialog("alert", message.orEmpty(), null)
            pendingDialogResult = null
            result.confirm()
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            if (result == null) return false
            holdDialog("confirm", message.orEmpty(), null, result)
            return true
        }

        override fun onJsPrompt(
            view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
        ): Boolean {
            if (result == null) return false
            holdDialog("prompt", message.orEmpty(), defaultValue, result)
            return true
        }
    }

    /** confirm/prompt 挂起等待 dialog action；超时未处理则自动取消，避免页面 JS 永久阻塞。 */
    private fun holdDialog(type: String, message: String, defaultValue: String?, result: JsResult) {
        dialogTimeout?.let { mainHandler.removeCallbacks(it) }
        pendingDialog = BrowserDialog(type, message, defaultValue)
        pendingDialogResult = result
        val timeout = Runnable {
            if (pendingDialogResult === result) {
                pendingDialogResult = null
                pendingDialog = null
                result.cancel()
            }
        }
        dialogTimeout = timeout
        mainHandler.postDelayed(timeout, DIALOG_TIMEOUT_MS)
    }

    private fun configureWebView(wv: WebView) {
        wv.webViewClient = webViewClient
        wv.webChromeClient = webChromeClient
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.addJavascriptInterface(BrowserJsBridge(), "__browserBridge__")
    }

    fun getOrCreateWebView(context: Context): WebView {
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val wv = WebView(context)
        configureWebView(wv)
        webView = wv
        _state.update { it.copy(attached = true, url = wv.url.orEmpty()) }
        FileLogger.i(TAG, "WebView created (UI)")
        return wv
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(appContext)
        configureWebView(wv)
        val density = appContext.resources.displayMetrics.density
        val width = (HEADLESS_WIDTH_DP * density).toInt()
        val height = (HEADLESS_HEIGHT_DP * density).toInt()
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, width, height)
        webView = wv
        _state.update { it.copy(attached = true) }
        FileLogger.i(TAG, "Headless WebView created")
        return wv
    }

    fun detachFromViewHierarchy() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        _state.update { it.copy(attached = false) }
    }

    fun destroy() {
        webView?.apply {
            stopLoading()
            removeJavascriptInterface("__browserBridge__")
            destroy()
        }
        webView = null
        loadDeferred?.cancel()
        loadDeferred = null
        pendingJsCalls.values.forEach { it.cancel() }
        pendingJsCalls.clear()
        dialogTimeout?.let { mainHandler.removeCallbacks(it) }
        dialogTimeout = null
        pendingDialogResult?.cancel()
        pendingDialogResult = null
        pendingDialog = null
        consoleLogs.clear()
        _state.update { it.copy(attached = false) }
    }

    fun isVisible(): Boolean {
        val wv = webView ?: return false
        return wv.parent != null && wv.width > 0 && wv.height > 0
    }

    suspend fun navigate(url: String): String = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val finalUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val deferred = CompletableDeferred<Result<String>>()
        loadDeferred = deferred
        wv.loadUrl(finalUrl)
        withTimeout(NAVIGATE_TIMEOUT_MS) { deferred.await() }.getOrThrow()
    }

    suspend fun evaluateJavaScript(script: String): String? = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val callId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingJsCalls[callId] = deferred

        val wrapped = """
            (function(){
                var callId = ${jsStringLiteral(callId)};
                new Promise(function(resolve, reject){
                    try {
                        var result = eval(${jsStringLiteral(script)});
                        if (result && typeof result.then === 'function') {
                            result.then(resolve).catch(reject);
                        } else {
                            resolve(result);
                        }
                    } catch(e) { reject(e); }
                }).then(function(r){
                    __browserBridge__.resolve(callId, JSON.stringify(r));
                }).catch(function(e){
                    __browserBridge__.reject(callId, (e && e.message) ? e.message : String(e));
                });
            })();
        """.trimIndent()

        wv.evaluateJavascript(wrapped) { }
        try {
            withTimeout(EVAL_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            pendingJsCalls.remove(callId)
            throw e
        }
    }

    /** click：使用 Promise 桥等 300ms 检测 SPA 导航，返回 {matched,tag,text,href,navigatedTo}。 */
    suspend fun clickElement(selector: String): String = withContext(Dispatchers.Main) {
        val script = """
            $selectorHelper
            (function(sel){
                var el = __resolveSelector(sel);
                if(!el) return {matched:false};
                var before = location.href;
                var events = ['mouseover','mousedown','mouseup','click','mouseout'];
                for(var i=0;i<events.length;i++){
                    el.dispatchEvent(new MouseEvent(events[i],{
                        view: window, bubbles: true, cancelable: true, buttons: 1
                    }));
                }
                return new Promise(function(resolve){
                    setTimeout(function(){
                        resolve({
                            matched: true,
                            tag: el.tagName ? el.tagName.toLowerCase() : null,
                            text: (el.textContent || '').trim().substring(0, 100),
                            href: el.href || null,
                            navigatedTo: location.href !== before ? location.href : null
                        });
                    }, 300);
                });
            })(${jsStringLiteral(selector)})
        """.trimIndent()
        evaluateJavaScript(script) ?: "{\"matched\":false}"
    }

    /** fill：返回 JSON 字符串 {matched, tag, type, value}。 */
    suspend fun fillElement(selector: String, value: String): String = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val js = """
            $selectorHelper
            (function(sel, val){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false});
                if(el.tagName === 'SELECT'){
                    var opt = null;
                    for(var i=0;i<el.options.length;i++){
                        var o = el.options[i];
                        if(o.value === val || (o.textContent||'').trim() === val){ opt = o; break; }
                    }
                    if(!opt) return JSON.stringify({matched:false, reason:'option-not-found'});
                    el.value = opt.value;
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                    return JSON.stringify({matched:true, tag:'select', value:el.value});
                }
                var setter = Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                ) && Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                ).set || (Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                ) && Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                ).set);
                if(setter){ setter.call(el, val); }
                else { el.value = val; }
                if(el._valueTracker){ el._valueTracker.setValue(''); }
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return JSON.stringify({
                    matched: true,
                    tag: el.tagName ? el.tagName.toLowerCase() : null,
                    type: el.type || null,
                    value: el.value
                });
            })(${jsStringLiteral(selector)}, ${jsStringLiteral(value)})
        """.trimIndent()
        val result = wv.evaluateJavascriptSync(js)
        result ?: "{}"
    }

    /** select：按 value 或可见文本选中 option，返回 JSON 字符串 {matched, tag, value, text}。 */
    suspend fun selectOption(selector: String, value: String): String = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val js = """
            $selectorHelper
            (function(sel, val){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false, reason:'not-found'});
                if(el.tagName !== 'SELECT') return JSON.stringify({matched:false, reason:'not-select'});
                var opt = null;
                for(var i=0;i<el.options.length;i++){
                    var o = el.options[i];
                    if(o.value === val || (o.textContent||'').trim() === val){ opt = o; break; }
                }
                if(!opt) return JSON.stringify({matched:false, reason:'option-not-found'});
                el.value = opt.value;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return JSON.stringify({
                    matched: true,
                    tag: 'select',
                    value: el.value,
                    text: (opt.textContent||'').trim()
                });
            })(${jsStringLiteral(selector)}, ${jsStringLiteral(value)})
        """.trimIndent()
        wv.evaluateJavascriptSync(js) ?: "{}"
    }

    /** 当前挂起的 JS 对话框（confirm/prompt），供工具层提示 AI。 */
    fun pendingDialogInfo(): BrowserDialog? = pendingDialog

    /** dialog：处理挂起的 confirm/prompt。accept=true 确定（prompt 用 text），false 取消。 */
    suspend fun resolveDialog(accept: Boolean, text: String?): String = withContext(Dispatchers.Main) {
        val result = pendingDialogResult
        val dialog = pendingDialog
        if (result == null || dialog == null) {
            return@withContext """{"handled":false,"reason":"no-dialog"}"""
        }
        dialogTimeout?.let { mainHandler.removeCallbacks(it) }
        dialogTimeout = null
        pendingDialogResult = null
        pendingDialog = null
        try {
            if (accept) {
                if (dialog.type == "prompt" && result is JsPromptResult) {
                    result.confirm(text ?: dialog.defaultValue ?: "")
                } else {
                    result.confirm()
                }
            } else {
                result.cancel()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "resolveDialog failed", e)
        }
        """{"handled":true,"type":"${dialog.type}","accepted":$accept}"""
    }

    /** console：返回有界控制台日志，可按 level 过滤，可选清空。 */
    suspend fun getConsoleLogs(level: String?, clear: Boolean): String = withContext(Dispatchers.Main) {
        val filtered = if (level.isNullOrBlank()) consoleLogs.toList()
            else consoleLogs.filter { it.level.equals(level, ignoreCase = true) }
        val logs = filtered.joinToString(",") { e ->
            JsonObject(mapOf(
                "level" to JsonPrimitive(e.level),
                "message" to JsonPrimitive(e.message),
                "line" to JsonPrimitive(e.line),
                "source" to JsonPrimitive(e.source)
            )).toString()
        }
        if (clear) consoleLogs.clear()
        """{"count":${filtered.size},"logs":[$logs]}"""
    }

    /** hover：返回 JSON 字符串 {matched, tag, text}。 */
    suspend fun hoverElement(selector: String): String = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val js = """
            $selectorHelper
            (function(sel){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false});
                var events = ['mouseenter','mouseover','mousemove'];
                for(var i=0;i<events.length;i++){
                    el.dispatchEvent(new MouseEvent(events[i],{
                        view: window, bubbles: true, cancelable: true
                    }));
                }
                return JSON.stringify({
                    matched: true,
                    tag: el.tagName ? el.tagName.toLowerCase() : null,
                    text: (el.textContent || '').trim().substring(0, 100)
                });
            })(${jsStringLiteral(selector)})
        """.trimIndent()
        val result = wv.evaluateJavascriptSync(js)
        result ?: "{}"
    }

    /** press：派发键盘事件。key 支持 Enter/Escape/Tab/ArrowUp/ArrowDown/ArrowLeft/ArrowRight/Backspace/Delete/空格/普通字符。 */
    suspend fun pressKey(key: String): Boolean = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val js = """
            (function(key){
                var keyMap = {
                    'Enter': {key:'Enter',code:'Enter',keyCode:13},
                    'Escape': {key:'Escape',code:'Escape',keyCode:27},
                    'Tab': {key:'Tab',code:'Tab',keyCode:9},
                    'ArrowUp': {key:'ArrowUp',code:'ArrowUp',keyCode:38},
                    'ArrowDown': {key:'ArrowDown',code:'ArrowDown',keyCode:40},
                    'ArrowLeft': {key:'ArrowLeft',code:'ArrowLeft',keyCode:37},
                    'ArrowRight': {key:'ArrowRight',code:'ArrowRight',keyCode:39},
                    'Backspace': {key:'Backspace',code:'Backspace',keyCode:8},
                    'Delete': {key:'Delete',code:'Delete',keyCode:46},
                    ' ': {key:' ',code:'Space',keyCode:32}
                };
                var k = keyMap[key] || {key:key,code:key,keyCode:key.charCodeAt(0)};
                var target = document.activeElement || document.body;
                ['keydown','keypress','keyup'].forEach(function(type){
                    target.dispatchEvent(new KeyboardEvent(type, {
                        key: k.key, code: k.code, keyCode: k.keyCode,
                        bubbles: true, cancelable: true
                    }));
                });
                return true;
            })(${jsStringLiteral(key)})
        """.trimIndent()
        val result = wv.evaluateJavascriptSync(js)
        result == "true"
    }

    suspend fun getText(selector: String? = null): String = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val js = if (selector != null) {
            """
            $selectorHelper
            (function(){
                var el=__resolveSelector(${jsStringLiteral(selector)});
                if(!el) return '';
                var t=el.innerText;
                if(t&&t.trim())return t;
                var clone=el.cloneNode(true);
                var junk=clone.querySelectorAll('script,style,noscript,template');
                for(var i=0;i<junk.length;i++){junk[i].parentNode.removeChild(junk[i]);}
                return clone.textContent||'';
            })()
            """.trimIndent()
        } else {
            "(function(){return document.body?document.body.innerText:''})()"
        }
        val result = wv.evaluateJavascriptSync(js)
        unescapeJsString(result).takeIf { it.isNotBlank() } ?: ""
    }

    suspend fun getHtml(selector: String? = null): String = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val js = if (selector != null) {
            "$selectorHelper\n(function(){var el=__resolveSelector(${jsStringLiteral(selector)});return el?el.outerHTML:''})()"
        } else {
            "(function(){return document.documentElement?document.documentElement.outerHTML:''})()"
        }
        val result = wv.evaluateJavascriptSync(js)
        val raw = unescapeJsString(result)
        if (raw.length > MAX_CONTENT_CHARS) raw.take(MAX_CONTENT_CHARS) + "\n\n[网页内容超长，已截断...]" else raw
    }

    suspend fun getBackbone(maxDepth: Int = 8): String = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val js = """
            (function(maxDepth, maxNodes){
                var SKIP = {SCRIPT:1, STYLE:1, NOSCRIPT:1, TEMPLATE:1, LINK:1, META:1, HEAD:1};
                var ROLE_BY_TAG = {
                    A:'link', BUTTON:'button', TEXTAREA:'textbox', SELECT:'combobox', OPTION:'option',
                    IMG:'img', H1:'heading', H2:'heading', H3:'heading', H4:'heading', H5:'heading', H6:'heading',
                    UL:'list', OL:'list', LI:'listitem', DL:'list', DT:'term', DD:'definition',
                    TABLE:'table', TR:'row', TD:'cell', TH:'columnheader',
                    THEAD:'rowgroup', TBODY:'rowgroup', TFOOT:'rowgroup',
                    NAV:'navigation', HEADER:'banner', FOOTER:'contentinfo', ASIDE:'complementary',
                    MAIN:'main', FORM:'form', ARTICLE:'article', SECTION:'generic', DIALOG:'dialog',
                    SUMMARY:'button', DETAILS:'group', FIGURE:'figure', FIGCAPTION:'caption',
                    PROGRESS:'progressbar', P:'paragraph', BLOCKQUOTE:'blockquote',
                    PRE:'code', CODE:'code', HR:'separator', IFRAME:'iframe',
                    VIDEO:'video', AUDIO:'audio', OUTPUT:'status', TIME:'time'
                };
                var INPUT_ROLE = {
                    text:'textbox', search:'searchbox', email:'textbox', tel:'textbox', url:'textbox',
                    password:'textbox', number:'spinbutton', checkbox:'checkbox', radio:'radio',
                    range:'slider', button:'button', submit:'button', reset:'button', file:'button', color:'button'
                };
                var CONTENT_NAME = {
                    link:1, button:1, heading:1, cell:1, columnheader:1, rowheader:1, listitem:1,
                    paragraph:1, menuitem:1, tab:1, option:1, term:1, definition:1, caption:1
                };
                var INTERACTIVE = {
                    link:1, button:1, textbox:1, searchbox:1, checkbox:1, radio:1, combobox:1,
                    slider:1, spinbutton:1, menuitem:1, tab:1, option:1, switch:1
                };
                var refs = {};
                var refCount = 0;
                var count = 0;
                var budgetHit = false;

                function norm(s){ return (s || '').replace(/\s+/g, ' ').trim(); }
                function roleOf(el){
                    var explicit = el.getAttribute('role');
                    if (explicit) { var r = explicit.trim().split(/\s+/)[0]; if (r) return r; }
                    var tag = el.tagName;
                    if (tag === 'INPUT') return INPUT_ROLE[(el.type || 'text').toLowerCase()] || 'textbox';
                    return ROLE_BY_TAG[tag] || 'generic';
                }
                function isVisible(el){
                    if (el.hidden) return false;
                    if (el.getAttribute('aria-hidden') === 'true') return false;
                    if (el.style && (el.style.display === 'none' || el.style.visibility === 'hidden')) return false;
                    if (typeof el.checkVisibility === 'function') {
                        try { return el.checkVisibility({ checkVisibilityCSS: true, checkOpacity: false }); } catch (e) {}
                    }
                    var cs = getComputedStyle(el);
                    return cs.display !== 'none' && cs.visibility !== 'hidden';
                }
                function accessibleName(el, role){
                    var lb = el.getAttribute('aria-labelledby');
                    if (lb) {
                        var parts = [];
                        lb.trim().split(/\s+/).forEach(function(id){
                            var r = document.getElementById(id);
                            if (r) parts.push(r.textContent || '');
                        });
                        var byLabel = norm(parts.join(' '));
                        if (byLabel) return byLabel;
                    }
                    var al = el.getAttribute('aria-label');
                    if (al && norm(al)) return norm(al);
                    var tag = el.tagName;
                    if (tag === 'IMG') { var alt = el.getAttribute('alt'); if (alt && norm(alt)) return norm(alt); }
                    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
                        var ph = el.getAttribute('placeholder');
                        if (ph && norm(ph)) return norm(ph);
                        if (el.id) {
                            var lab = document.querySelector('label[for="' + el.id + '"]');
                            if (lab && norm(lab.textContent)) return norm(lab.textContent);
                        }
                        if (el.value != null && norm(String(el.value))) return norm(String(el.value));
                    }
                    if (CONTENT_NAME[role]) { var t = norm(el.textContent); if (t) return t; }
                    var ti = el.getAttribute('title');
                    if (ti && norm(ti)) return norm(ti);
                    if (el.childElementCount === 0) { var own = norm(el.textContent); if (own) return own; }
                    return '';
                }
                function walk(el, depth){
                    if (!el || !el.tagName) return [];
                    var tag = el.tagName;
                    if (SKIP[tag]) return [];
                    if (!isVisible(el)) return [];
                    var role = roleOf(el);
                    if (depth > maxDepth) {
                        return [{ role: role, truncated: true, childCount: el.children.length }];
                    }
                    if (count >= maxNodes) { budgetHit = true; return []; }
                    var name = accessibleName(el, role);
                    var kids = [];
                    for (var i = 0; i < el.children.length; i++) {
                        var sub = walk(el.children[i], depth + 1);
                        for (var k = 0; k < sub.length; k++) kids.push(sub[k]);
                    }
                    if (role === 'generic' && !name) return kids;
                    count++;
                    var node = { role: role };
                    if (name) node.name = name.substring(0, 200);
                    if (role === 'generic' && el.childElementCount === 0) node.role = 'text';
                    if (role === 'heading') { var lv = parseInt(tag.charAt(1), 10); if (lv) node.level = lv; }
                    if (INTERACTIVE[role]) {
                        refCount++;
                        var ref = 'e' + refCount;
                        refs[ref] = el;
                        node.ref = ref;
                    }
                    if (el.getAttribute) {
                        var href = el.getAttribute('href');
                        if (href && (role === 'link' || role === 'menuitem' || role === 'tab')) node.url = href;
                    }
                    if ((tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT')
                        && el.value != null && String(el.value)) node.value = String(el.value).substring(0, 200);
                    if (tag === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) node.checked = !!el.checked;
                    else if (el.getAttribute('aria-checked')) node.checked = el.getAttribute('aria-checked') === 'true';
                    if (el.getAttribute('aria-expanded')) node.expanded = el.getAttribute('aria-expanded') === 'true';
                    if (el.disabled || el.getAttribute('aria-disabled') === 'true') node.disabled = true;
                    if (kids.length) node.children = kids;
                    return [node];
                }
                window.__bicodeRefs = refs;
                var out = { role: 'document' };
                var top = walk(document.body, 0);
                if (top.length) out.children = top;
                if (budgetHit) out.budgetExceeded = true;
                return JSON.stringify(out);
            })($maxDepth, $MAX_BACKBONE_NODES)
        """.trimIndent()
        val result = wv.evaluateJavascriptSync(js)
        unescapeJsString(result)
    }

    /** 统一等待：condition 支持 text=xxx / text*=xxx / selector=CSS / domStable。 */
    suspend fun wait(condition: String, timeoutMs: Long = 10_000): Boolean = withContext(Dispatchers.Main) {
        when {
            condition == "domStable" -> waitForDomStable(timeoutMs)
            condition.startsWith("text=") -> {
                val text = condition.substring(5)
                waitForCondition("var els=document.querySelectorAll('*');for(var i=0;i<els.length;i++){if(els[i].textContent.trim()===${jsStringLiteral(text)})return true;}return false;", timeoutMs)
            }
            condition.startsWith("text*=") -> {
                val text = condition.substring(6)
                waitForCondition("return document.body&&document.body.innerText.includes(${jsStringLiteral(text)});", timeoutMs)
            }
            condition.startsWith("selector=") -> {
                val sel = condition.substring(9)
                waitForCondition("$selectorHelper\nreturn !!__resolveSelector(${jsStringLiteral(sel)});", timeoutMs)
            }
            else -> {
                waitForCondition("$selectorHelper\nreturn !!__resolveSelector(${jsStringLiteral(condition)});", timeoutMs)
            }
        }
    }

    private suspend fun waitForCondition(checkJs: String, timeoutMs: Long): Boolean {
        val wv = ensureWebView()
        val deadline = System.currentTimeMillis() + timeoutMs
        val wrapped = "(function(){try{$checkJs}catch(e){return false}})()"
        while (System.currentTimeMillis() < deadline) {
            val result = wv.evaluateJavascriptSync(wrapped)
            if (result == "true") return true
            delay(WAIT_POLL_INTERVAL_MS)
        }
        return false
    }

    suspend fun waitForDomStable(timeoutMs: Long = 5_000): Boolean = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val sigJs = "(function(){var b=document.body||document.documentElement;" +
            "return b?b.getElementsByTagName('*').length+':'+b.innerHTML.length:'0:0'})()"
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val sig = wv.evaluateJavascriptSync(sigJs)?.trim('"') ?: ""
            val now = System.currentTimeMillis()
            if (sig.isNotEmpty() && sig == last) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= DOM_STABLE_QUIET_MS) return@withContext true
            } else {
                last = sig
                stableSince = 0L
            }
            delay(DOM_STABLE_POLL_MS)
        }
        false
    }

    /** scroll：返回 JSON 字符串 {from, to, atTop, atBottom}。 */
    suspend fun scroll(selector: String? = null, x: Int? = null, y: Int? = null): String =
        withContext(Dispatchers.Main) {
            val wv = ensureWebView()
            val beforeJs = "(function(){return JSON.stringify({y:window.scrollY||0,x:window.scrollX||0})})()"
            val beforeResult = wv.evaluateJavascriptSync(beforeJs)
            val before = unescapeJsString(beforeResult)

            val actionJs = when {
                selector != null -> """
                    $selectorHelper
                    (function(){var el=__resolveSelector(${jsStringLiteral(selector)});
                    if(!el) return false;
                    el.scrollIntoView({behavior:'smooth',block:'center'});
                    return true;})()
                """.trimIndent()
                x != null || y != null -> "(function(){window.scrollBy(${x ?: 0}, ${y ?: 0});return true;})()"
                else -> "(function(){window.scrollTo(0,document.body?document.body.scrollHeight:0);return true;})()"
            }
            wv.evaluateJavascriptSync(actionJs)

            delay(300)
            val afterJs = "(function(){return JSON.stringify({y:window.scrollY||0,x:window.scrollX||0,atTop:(window.scrollY||0)===0,atBottom:(window.innerHeight+(window.scrollY||0))>=(document.body?document.body.scrollHeight:0)})})()"
            val afterResult = wv.evaluateJavascriptSync(afterJs)
            val after = unescapeJsString(afterResult)

            """{"from":$before,"to":$after}"""
        }

    suspend fun screenshot(): AgentImage? = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext null
        if (wv.width == 0 || wv.height == 0) return@withContext null
        val bitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        wv.draw(canvas)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, baos)
        bitmap.recycle()
        AgentImage(mimeType = "image/jpeg", base64Data = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
    }

    suspend fun screenshotIfVisible(): AgentImage? = if (isVisible()) screenshot() else null

    /** 获取视口信息 JSON 字符串。 */
    suspend fun getViewportInfo(): String = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val js = "(function(){return JSON.stringify({w:window.innerWidth,h:window.innerHeight,dpr:window.devicePixelRatio||1,scrollY:window.scrollY||0,scrollHeight:document.body?document.body.scrollHeight:0})})()"
        unescapeJsString(wv.evaluateJavascriptSync(js))
    }

    suspend fun goBack(): Boolean = navigateHistory({ it.canGoBack() }) { it.goBack() }

    suspend fun goForward(): Boolean = navigateHistory({ it.canGoForward() }) { it.goForward() }

    /**
     * 后退/前进：执行后确认 url 真的变化了再报成功。同文档导航（pushState）不触发
     * onPageFinished，因此先轮询 url，再按需等待加载完成。
     */
    private suspend fun navigateHistory(
        canGo: (WebView) -> Boolean,
        action: (WebView) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        if (!canGo(wv)) return@withContext false
        val before = wv.url.orEmpty()
        val deferred = CompletableDeferred<Result<String>>()
        loadDeferred = deferred
        action(wv)

        val urlDeadline = System.currentTimeMillis() + GO_URL_TIMEOUT_MS
        var after = before
        while (after == before && System.currentTimeMillis() < urlDeadline) {
            delay(GO_POLL_MS)
            after = wv.url.orEmpty()
        }
        if (after == before) {
            val href = currentHref()
            if (href.isNotEmpty() && href != before) after = href
        }
        if (after == before) {
            if (loadDeferred === deferred) loadDeferred = null
            return@withContext false
        }
        if (_state.value.loading) {
            try {
                withTimeout(GO_LOAD_TIMEOUT_MS) { deferred.await() }
            } catch (e: Exception) {
                // 同文档导航不会触发 onPageFinished，超时后直接继续
            }
        }
        if (loadDeferred === deferred) loadDeferred = null
        syncTitle()
        true
    }

    private suspend fun currentHref(): String {
        val wv = webView ?: return ""
        return unescapeJsString(wv.evaluateJavascriptSync("(function(){return location.href})()"))
    }

    private suspend fun syncTitle() {
        val wv = webView ?: return
        val raw = wv.evaluateJavascriptSync("(function(){return document.title})()")
        val title = raw?.trim()?.trim('"') ?: ""
        _state.update { it.copy(title = title) }
    }

    suspend fun reload(): String = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val deferred = CompletableDeferred<Result<String>>()
        loadDeferred = deferred
        wv.reload()
        withTimeout(NAVIGATE_TIMEOUT_MS) { deferred.await() }.getOrThrow()
    }

    fun getUrl(): String = webView?.url ?: _state.value.url
    fun getTitle(): String = _state.value.title

    /** 解析 evaluate 的 JSON 字符串为原生 JsonElement。 */
    fun parseEvalResult(raw: String?): JsonElement {
        if (raw.isNullOrBlank()) return JsonNull
        return try {
            val element = Json.parseToJsonElement(raw)
            if (element is kotlinx.serialization.json.JsonPrimitive && element.isString) {
                try { Json.parseToJsonElement(element.content) } catch (e: Exception) { element }
            } else {
                element
            }
        } catch (e: Exception) {
            kotlinx.serialization.json.JsonPrimitive(raw)
        }
    }

    private suspend fun WebView.evaluateJavascriptSync(script: String): String? {
        val deferred = CompletableDeferred<String?>()
        evaluateJavascript(script) { result -> deferred.complete(result) }
        return deferred.await()
    }

    private fun jsStringLiteral(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("'")
        return sb.toString()
    }

    private fun unescapeJsString(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var result = s.trim()
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        return result
            .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
            .replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")
    }
}
