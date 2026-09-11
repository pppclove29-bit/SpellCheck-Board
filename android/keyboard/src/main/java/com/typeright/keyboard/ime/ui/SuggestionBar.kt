package com.typeright.keyboard.ime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typeright.keyboard.ime.BarState
import com.typeright.keyboard.ime.BarStatus
import com.typeright.keyboard.ime.ChipUi
import com.typeright.keyboard.ime.FeedbackStyle
import com.typeright.keyboard.ime.FeedbackUi
import com.typeright.keyboard.ime.ImeActions
import com.typeright.keyboard.rules.CorrectionSource
import kotlinx.coroutines.delay

@Composable
fun SuggestionBar(bar: BarState, secure: Boolean, actions: ImeActions) {
    val colors = LocalKeyboardColors.current
    Row(
        Modifier
            .fillMaxSize()
            .background(colors.bar)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (secure) {
            Text("🔒 보안 키패드", color = colors.keyText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "  입력 내용을 읽거나 검사·전송하지 않아요",
                color = colors.hintText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Chip(text = "이전 키보드", onClick = actions::onSwitchToPreviousKeyboard)
            return@Row
        }

        StatusIndicator(bar.status)
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (bar.chips.isEmpty()) {
                Text("TypeRight", color = colors.hintText, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
            }
            for (chip in bar.chips) {
                when (chip) {
                    is ChipUi.CorrectionChip -> {
                        val c = chip.correction
                        Chip(
                            text = "${c.originalWord} → ${c.suggestedWord}",
                            subtitle = (if (c.source == CorrectionSource.AI) "AI · " else "") + c.type.label,
                            emphasized = true,
                            onClick = { actions.onChipClick(chip) },
                            onLongClick = { actions.onChipLongPress(chip) },
                        )
                    }
                    is ChipUi.IgnoreChip -> Chip(text = "무시", subtle = true, onClick = { actions.onChipClick(chip) })
                    is ChipUi.ShortcutChip -> Chip(
                        text = "⚡ ${chip.shortcut.key} → ${chip.shortcut.expansion}",
                        onClick = { actions.onChipClick(chip) },
                    )
                    ChipUi.LoginChip -> Chip(text = "🔑 로그인하면 AI 훈수", onClick = { actions.onChipClick(chip) })
                }
            }
        }
        if (bar.showRecharge) {
            Spacer(Modifier.width(6.dp))
            RechargeButton(emphasized = bar.rechargeEmphasized, onClick = actions::onRechargeClick)
        }
    }
}

/** ⚡충전: fixed at the right end for signed-in non-PRO users; emphasized when today's AI quota is used up. */
@Composable
private fun RechargeButton(emphasized: Boolean, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (emphasized) colors.danger else colors.chip)
            .border(1.dp, if (emphasized) colors.danger else colors.accent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "⚡충전",
            color = if (emphasized) Color.White else colors.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusIndicator(status: BarStatus) {
    if (status == BarStatus.NONE) return
    val colors = LocalKeyboardColors.current
    val dot = when (status) {
        BarStatus.CHECKING -> colors.accent
        BarStatus.OFFLINE -> colors.danger
        else -> colors.hintText
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(4.dp))
        Text(status.label, color = colors.hintText, fontSize = 11.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(
    text: String,
    subtitle: String? = null,
    emphasized: Boolean = false,
    subtle: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalKeyboardColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .clip(shape)
            .background(if (subtle) Color.Transparent else colors.chip)
            .border(1.dp, if (emphasized) colors.accent else colors.chipBorder, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = if (subtle) colors.hintText else colors.chipText,
            fontSize = 14.sp,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        if (subtitle != null) Text(subtitle, color = colors.hintText, fontSize = 9.sp, maxLines = 1)
    }
}

private val BubbleShape = GenericShape { size, _ ->
    // Rounded speech bubble body with a small tail at the bottom-left.
    val r = 14f * (size.height / 40f).coerceAtLeast(0.6f)
    val tailH = size.height * 0.18f
    val bodyBottom = size.height - tailH
    addRoundRect(
        androidx.compose.ui.geometry.RoundRect(
            left = 0f, top = 0f, right = size.width, bottom = bodyBottom,
            radiusX = r, radiusY = r,
        ),
    )
    moveTo(28f, bodyBottom - 1f)
    lineTo(40f, size.height)
    lineTo(54f, bodyBottom - 1f)
    close()
}

/**
 * Mode-specific feedback drawn over the reserved bar slot:
 * spicy_wit → speech bubble (pop animation), police → red banner, gentle → tip card, long-press → reason.
 */
@Composable
fun FeedbackOverlay(feedback: FeedbackUi?, onDismiss: (Long) -> Unit) {
    var shown by remember { mutableStateOf<FeedbackUi?>(null) }
    if (feedback != null) shown = feedback

    LaunchedEffect(feedback?.id) {
        val f = feedback ?: return@LaunchedEffect
        delay(
            when (f.style) {
                FeedbackStyle.BUBBLE -> 4_500L
                FeedbackStyle.POLICE -> 3_500L
                FeedbackStyle.TIP -> 7_000L
                FeedbackStyle.REASON -> 4_000L
                FeedbackStyle.NOTICE -> 5_000L
            },
        )
        onDismiss(f.id)
    }

    AnimatedVisibility(
        visible = feedback != null,
        enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) { -it / 2 } +
            scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.85f) + fadeIn(),
        exit = slideOutVertically { -it / 3 } + fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        val f = shown ?: return@AnimatedVisibility
        val colors = LocalKeyboardColors.current
        val (bg, fg, shape) = when (f.style) {
            FeedbackStyle.BUBBLE -> Triple(colors.bubble, colors.bubbleText, BubbleShape)
            FeedbackStyle.POLICE -> Triple(colors.danger, Color.White, RoundedCornerShape(10.dp))
            FeedbackStyle.TIP -> Triple(colors.tip, colors.keyText, RoundedCornerShape(10.dp))
            FeedbackStyle.REASON -> Triple(colors.chip, colors.keyText, RoundedCornerShape(10.dp))
            FeedbackStyle.NOTICE -> Triple(colors.bubble, colors.bubbleText, RoundedCornerShape(10.dp))
        }
        val prefix = when (f.style) {
            FeedbackStyle.TIP -> "💡 "
            FeedbackStyle.REASON -> "ℹ️ "
            else -> ""
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .clip(shape)
                .background(bg)
                .clickable { onDismiss(f.id) }
                .padding(start = 12.dp, end = 12.dp, top = 3.dp, bottom = if (f.style == FeedbackStyle.BUBBLE) 8.dp else 3.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                prefix + f.text,
                color = fg,
                fontSize = 12.5.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (f.style == FeedbackStyle.POLICE) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
