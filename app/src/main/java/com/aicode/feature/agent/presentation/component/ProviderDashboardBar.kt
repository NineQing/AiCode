package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.R
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.AdaptiveCardAction
import com.aicode.feature.settings.domain.model.ProviderDashboardState
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp

/**
 * 位于聊天输入框上方的自定义面板栏。
 * 基于 Adaptive Cards 声明式规范，支持任意自定义排版与交互。
 */
@Composable
fun ProviderDashboardBar(
    provider: AIProviderConfig,
    state: ProviderDashboardState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    forceCollapse: Boolean = false,
    onRefreshByButton: () -> Unit = {},
    onExpandedChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    // 弹窗/键盘叠加时同帧收起：用派生状态而不是 LaunchedEffect 异步改 isExpanded，
    // 否则弹窗先出现顶开布局、面板后折叠，中间产生空档闪屏。
    val effectiveExpanded = isExpanded && !forceCollapse
    // 上报展开状态给外层，供叠加面板联动折叠
    LaunchedEffect(effectiveExpanded) { onExpandedChange(effectiveExpanded) }

    val cardBgColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs)
            .clip(RoundedCornerShape(Radius.lg))
            .border(1.dp, borderColor, RoundedCornerShape(Radius.lg))
            // 强制收起时动画时长归零，避免收缩过程露出底部空白
            .animateContentSize(animationSpec = tween(if (forceCollapse) 0 else 220)),
        shape = RoundedCornerShape(Radius.lg),
        color = cardBgColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 10.dp)
        ) {
            when (state) {
                is ProviderDashboardState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                text = stringResource(R.string.dashboard_fetching),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                is ProviderDashboardState.Error -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.xs))
                                .clickable { onRefresh() }
                                .padding(horizontal = Spacing.xs, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.AlertCircle,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.dashboard_fetch_failed_retry),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                is ProviderDashboardState.Success -> {
                    val card = state.result.card
                    val onCardAction: (AdaptiveCardAction) -> Unit = { action ->
                        when (action) {
                            is AdaptiveCardAction.OpenUrl -> {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }.onFailure {
                                    Toast.makeText(context, context.getString(R.string.common_open_link_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                            is AdaptiveCardAction.CopyToClipboard -> {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText(action.title, action.value)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, context.getString(R.string.common_copied_with_title, action.title), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    if (!effectiveExpanded) {
                        // ── 收起状态 ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isExpanded = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(end = Spacing.md)
                            )

                            Box(modifier = Modifier.weight(1f)) {
                                AdaptiveCardView(
                                    card = card,
                                    isExpanded = false,
                                    onAction = onCardAction,
                                    onRefresh = onRefreshByButton
                                )
                            }

                            Spacer(Modifier.width(Spacing.sm))

                            Icon(
                                imageVector = FeatherIcons.ChevronDown,
                                contentDescription = stringResource(R.string.common_expand),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .clickable { isExpanded = true },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // ── 展开状态 ──
                        // 注意：点击展开时若 forceCollapse 仍为 true，effectiveExpanded 仍为 false，
                        // 弹窗消失后会自动恢复展开态（isExpanded 保持 true）。
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            // 顶部提供商名 + 折叠按钮
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isExpanded = false },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = FeatherIcons.ChevronUp,
                                    contentDescription = stringResource(R.string.common_collapse),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .clickable { isExpanded = false },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // 卡片 Body 展开渲染
                            AdaptiveCardView(
                                card = card,
                                isExpanded = true,
                                onAction = onCardAction,
                                onRefresh = onRefreshByButton
                            )
                        }
                    }
                }
                ProviderDashboardState.Idle -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRefresh() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = provider.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.dashboard_tap_to_query),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
