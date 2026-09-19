package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.SegmentedTabs
import com.aicode.feature.settings.data.local.ProviderPreset
import com.aicode.feature.settings.data.local.ProviderPresetLibrary
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

/**
 * 新增提供商底部弹层：顶部「付费 / 免费」分段切换。
 * - 付费 tab：第一项「自定义提供商」，下方是内置官方 provider 列表（来自 api.official.json）。
 * - 免费 tab：占位（预留免费模型服务，暂不开放）。
 * 选择自定义时回调 [onSelectCustom]；选择官方预设时回调 [onSelectOfficial]。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ProviderPresetSheet(
    onDismiss: () -> Unit,
    onSelectCustom: () -> Unit,
    onSelectOfficial: (ProviderPreset) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0: 需配置, 1: 开箱即用
    val officialPresets by produceState<List<ProviderPreset>?>(initialValue = null) {
        value = ProviderPresetLibrary.loadOfficial(context)
    }
    // 固定内容高度，避免左右 tab 内容高度不一致导致切换时 sheet 整体跳动。
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val sheetContentHeight = minOf(640.dp, screenHeight * 0.75f)
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
                .height(sheetContentHeight)
        ) {
            Text(
                text = stringResource(R.string.provider_add_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            SegmentedTabs(
                selected = selectedTab,
                labels = listOf(
                    stringResource(R.string.provider_add_tab_configured),
                    stringResource(R.string.provider_add_tab_instant)
                ),
                onSelect = { selectedTab = it },
                modifier = Modifier.padding(horizontal = Spacing.lg)
            )
            Spacer(Modifier.height(Spacing.md))

            if (selectedTab == 0) {
                // ── 需配置：自定义 + 内置官方（同一列表，同一样式）──
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs)
                ) {
                    // 自定义提供商：作为列表第一项，样式与内置提供商一致。
                    item(key = "__custom__") {
                        Surface(
                            onClick = onSelectCustom,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ModelLogoIcon(
                                    modelName = "openai",
                                    size = 36.dp
                                )
                                Spacer(Modifier.width(Spacing.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.provider_custom),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    McpPill(
                                        text = "openai",
                                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }

                    when {
                        officialPresets == null -> item(key = "__loading__") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.xl),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.height(Spacing.sm))
                                    Text(
                                        text = stringResource(R.string.provider_preset_loading),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        officialPresets.orEmpty().isEmpty() -> item(key = "__empty__") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.xl),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.provider_preset_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        else -> items(officialPresets.orEmpty(), key = { it.id }) { preset ->
                            Surface(
                                onClick = { onSelectOfficial(preset) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ModelLogoIcon(
                                        modelName = preset.name,
                                        size = 36.dp
                                    )
                                    Spacer(Modifier.width(Spacing.md))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = preset.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        McpPill(
                                            text = preset.type.lowercase(),
                                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ── 开箱即用：占位 ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = FeatherIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.provider_add_instant_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}