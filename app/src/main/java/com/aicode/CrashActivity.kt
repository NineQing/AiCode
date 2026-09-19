package com.aicode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.AIEditorTheme
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Copy
import compose.icons.feathericons.RefreshCw
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏崩溃错误页：全局未捕获异常处理器捕获崩溃后拉起本页面，
 * 展示崩溃详情并支持一键复制，方便用户把错误信息反馈给开发者。
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val threadName = intent.getStringExtra(EXTRA_THREAD_NAME) ?: "unknown"
        val stack = intent.getStringExtra(EXTRA_STACK) ?: ""
        val screen = intent.getStringExtra(EXTRA_SCREEN)
        val mode = intent.getStringExtra(EXTRA_WORKSPACE_MODE)
        val logDir = resolveLogDir()
        val report = buildReport(threadName, stack, screen, mode, logDir)
        setContent {
            AIEditorTheme {
                CrashScreen(
                    report = report,
                    logDir = logDir,
                    onCopy = {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("crash", report))
                        Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
                    },
                    onRestart = {
                        // 用户主动点击重启：清除崩溃标记与计数，允许主进程尝试冷启动
                        AIEditorApp.resetCrashState(applicationContext)
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        )
                        finish()
                    },
                    onExit = {
                        finish()
                        Process.killProcess(Process.myPid())
                    }
                )
            }
        }
        // 注意：不在此处重置 KEY_CRASH_UI_SHOWING！
        // 错误页展示期间保持该标志，直到用户主动点击重启或主进程健康存活，杜绝崩溃循环重入。
    }

    /** 组装可复制的崩溃报告：页面、模式、版本、设备、时间、线程、堆栈、日志位置。 */
    private fun buildReport(threadName: String, stack: String, screen: String?, mode: String?, logDir: String): String {
        val appVersion = runCatching {
            packageManager.getPackageInfo(packageName, 0).let { info ->
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                "${info.versionName ?: "unknown"} ($code)"
            }
        }.getOrDefault("unknown")
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine(getString(R.string.crash_report_title))
            appendLine(getString(R.string.crash_report_screen, screenName(screen)))
            appendLine(getString(R.string.crash_report_mode, modeName(mode)))
            appendLine(getString(R.string.crash_report_time, time))
            appendLine(getString(R.string.crash_report_version, appVersion))
            appendLine(getString(R.string.crash_report_device, device))
            appendLine(getString(R.string.crash_report_thread, threadName))
            appendLine()
            appendLine(stack)
            appendLine()
            appendLine(getString(R.string.crash_report_log, logDir))
        }
    }

    private fun modeName(mode: String?): String = when (mode) {
        "LOCAL_PROOT" -> getString(R.string.crash_mode_local)
        "REMOTE_SSH" -> getString(R.string.crash_mode_remote)
        null -> getString(R.string.crash_mode_unknown)
        else -> mode
    }

    private fun screenName(route: String?): String = when (route) {
        "chat" -> getString(R.string.crash_screen_chat)
        "settings" -> getString(R.string.crash_screen_settings)
        "terminal" -> getString(R.string.crash_screen_terminal)
        "git" -> getString(R.string.crash_screen_git)
        null -> getString(R.string.crash_screen_unknown)
        else -> route
    }

    private fun resolveLogDir(): String =
        runCatching { getExternalFilesDir(null)?.absolutePath }.getOrDefault(null)
            ?.let { "$it/logs/" } ?: "filesDir/logs/"

    companion object {
        const val EXTRA_THREAD_NAME = "thread_name"
        const val EXTRA_STACK = "stack"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_WORKSPACE_MODE = "workspace_mode"
    }
}

@Composable
private fun CrashScreen(
    report: String,
    logDir: String,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    // 与设置页一致的 iOS 分组风格：浅色模式浅灰背景 + 白色卡片，深色沿用主题色。
    val pageBg = MaterialTheme.semanticColors.pageBackground
    val cardBg = MaterialTheme.semanticColors.cardSurface
    val titleColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val logHint = LocalContext.current.getString(R.string.crash_log_hint, logDir)
    // 返回键视为退出应用，避免回到已崩溃的界面
    BackHandler { onExit() }
    Scaffold(containerColor = pageBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(Spacing.xxl))
            Icon(
                imageVector = FeatherIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            Text(
                text = stringResource(R.string.crash_title),
                style = MaterialTheme.typography.titleLarge,
                color = titleColor
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.crash_message),
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryColor,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            // 错误详情卡片：白色圆角卡片，等宽字体可滚动
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                shape = RoundedCornerShape(14.dp),
                color = cardBg,
                shadowElevation = 0.dp
            ) {
                Text(
                    text = report,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.md),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = titleColor
                )
            }
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = logHint,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryColor,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.xl))
            Button(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(FeatherIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.crash_copy))
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            OutlinedButton(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(FeatherIcons.RefreshCw, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.crash_restart))
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            TextButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.crash_exit))
            }
        }
    }
}
