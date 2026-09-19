package com.aicode.feature.settings.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Terminal

/**
 * 「Shizuku」二级页：展示 Shizuku 可用状态并提供安装/启动/授权入口。
 *
 * 状态由 [com.aicode.feature.agent.domain.shizuku.ShizukuManager] 统一维护，
 * 从 Shizuku 应用返回时（页面恢复）重新探测。
 */
@Composable
internal fun ShizukuSection(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRefresh: () -> Unit
) {
    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    val action: (() -> Unit)? = when (state) {
        ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING -> onOpenShizuku
        ShizukuState.PERMISSION_DENIED -> onRequestPermission
        ShizukuState.READY -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_category_environment))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Terminal,
                title = stringResource(R.string.settings_shizuku),
                subtitle = stringResource(state.hintRes()),
                onClick = action,
                trailing = {
                    Text(
                        text = stringResource(state.statusRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
        Text(
            text = stringResource(R.string.settings_shizuku_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
        )
    }
}

@StringRes
private fun ShizukuState.statusRes(): Int = when (this) {
    ShizukuState.NOT_INSTALLED -> R.string.settings_shizuku_status_not_installed
    ShizukuState.NOT_RUNNING -> R.string.settings_shizuku_status_not_running
    ShizukuState.PERMISSION_DENIED -> R.string.settings_shizuku_status_denied
    ShizukuState.READY -> R.string.settings_shizuku_status_ready
}

@StringRes
private fun ShizukuState.hintRes(): Int = when (this) {
    ShizukuState.NOT_INSTALLED -> R.string.settings_shizuku_hint_not_installed
    ShizukuState.NOT_RUNNING -> R.string.settings_shizuku_hint_not_running
    ShizukuState.PERMISSION_DENIED -> R.string.settings_shizuku_hint_denied
    ShizukuState.READY -> R.string.settings_shizuku_hint_ready
}
