package com.typeright.app.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import com.typeright.keyboard.rules.FeedbackMode
import com.typeright.keyboard.settings.FeedbackModeResolver
import com.typeright.keyboard.share.ShareCardContent
import com.typeright.keyboard.share.ShareCardRequest
import kotlin.math.ceil

/**
 * Draws the "📸 짤" card (1080×1350, 4:5 for Instagram feed/KakaoTalk): a KakaoTalk-like chat with the user's
 * sentence (error struck through, correction in green), the mode mascot answering with the 훈수 line, and a
 * watermark. Text content and privacy masking come from [ShareCardContent] (unit-tested in :keyboard).
 */
object ShareCardRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1350

    private const val PAD = 72f
    private const val KAKAO_CHAT_BG = 0xFFB2C7D9.toInt()
    private const val KAKAO_YELLOW = 0xFFFEE500.toInt()
    private const val ERROR_RED = 0xFFE53935.toInt()
    private const val FIX_GREEN = 0xFF1B8E3E.toInt()
    private const val INK = 0xFF191919.toInt()
    private const val MUTED = 0xFF5F6368.toInt()

    private data class Palette(val background: Int, val accent: Int, val name: String)

    private fun palette(mode: FeedbackMode) = when (mode) {
        FeedbackMode.SPICY_WIT -> Palette(0xFFFFF1E6.toInt(), 0xFFFF6B35.toInt(), "매운맛 훈수봇")
        FeedbackMode.POLICE -> Palette(0xFFEAF0FF.toInt(), 0xFF1E4FD8.toInt(), "맞춤법 경찰")
        FeedbackMode.GENTLE -> Palette(0xFFEFFAF1.toInt(), 0xFF2E9E5B.toInt(), "상냥한 선생님")
    }

    fun render(req: ShareCardRequest): Bitmap {
        val bubble = ShareCardContent.bubble(req)
        val feedback = ShareCardContent.feedback(req)
        val p = palette(req.mode)

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(p.background)

        // Header
        c.drawText("TypeRight 맞춤법 훈수", PAD, 128f, textPaint(44f, p.accent, bold = true))

        // Chat panel
        val panel = RectF(PAD, 180f, WIDTH - PAD, HEIGHT - 230f)
        c.drawRoundRect(panel, 40f, 40f, fill(KAKAO_CHAT_BG))

        // User bubble (right, yellow): sentence with the error struck through and the correction in green.
        val sentence = SpannableStringBuilder(bubble.text).apply {
            setSpan(StrikethroughSpan(), bubble.strikeStart, bubble.strikeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ERROR_RED), bubble.strikeStart, bubble.strikeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(FIX_GREEN), bubble.fixStart, bubble.fixEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), bubble.fixStart, bubble.fixEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val bubbleInner = (panel.width() * 0.72f).toInt()
        val userLayout = layout(sentence, textPaint(50f, INK), bubbleInner, maxLines = 5)
        val userW = contentWidth(userLayout) + 72f
        val userTop = panel.top + 70f
        val userRect = RectF(panel.right - 44f - userW, userTop, panel.right - 44f, userTop + userLayout.height + 60f)
        c.drawRoundRect(userRect, 34f, 34f, fill(KAKAO_YELLOW))
        drawLayout(c, userLayout, userRect.left + 36f, userRect.top + 30f)

        // Mascot (left) + name + 훈수 bubble (white)
        val mascotR = 62f
        val mascotCx = panel.left + 44f + mascotR
        val mascotCy = userRect.bottom + 90f + mascotR
        c.drawCircle(mascotCx, mascotCy, mascotR, fill(Color.WHITE))
        c.drawCircle(mascotCx, mascotCy, mascotR, stroke(p.accent, 6f))
        val emoji = textPaint(66f, INK).apply { textAlign = Paint.Align.CENTER }
        c.drawText(FeedbackModeResolver.emoji(req.mode), mascotCx, mascotCy + 24f, emoji)

        val nameX = mascotCx + mascotR + 28f
        c.drawText(p.name, nameX, mascotCy - mascotR + 34f, textPaint(36f, MUTED, bold = true))
        val fbInner = (panel.right - 44f - nameX - 72f).toInt()
        val fbLayout = layout(feedback, textPaint(48f, INK, bold = true), fbInner, maxLines = 6)
        val fbTop = mascotCy - mascotR + 56f
        val fbRect = RectF(nameX, fbTop, nameX + contentWidth(fbLayout) + 72f, fbTop + fbLayout.height + 60f)
        c.drawRoundRect(fbRect, 34f, 34f, fill(Color.WHITE))
        drawLayout(c, fbLayout, fbRect.left + 36f, fbRect.top + 30f)

        // Footer: mode + watermark
        val modeLine = "${FeedbackModeResolver.emoji(req.mode)} ${req.mode.label} 모드"
        c.drawText(modeLine, PAD, HEIGHT - 150f, textPaint(40f, p.accent, bold = true))
        c.drawText("TypeRight · 맞춤법 훈수 키보드", PAD, HEIGHT - 90f, textPaint(38f, MUTED))
        return bitmap
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.12f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun contentWidth(layout: StaticLayout): Float =
        ceil((0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f)

    private fun drawLayout(c: Canvas, layout: StaticLayout, x: Float, y: Float) {
        c.save()
        c.translate(x, y)
        layout.draw(c)
        c.restore()
    }
}
