package com.messenger.messengerclient.ui

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.messenger.messengerclient.data.model.Message
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ChatMessageCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_BUBBLE_WIDTH_PERCENT = 0.70f
        private const val MIN_BUBBLE_WIDTH_DP = 48f   // 48dp = ~96px (как в Telegram)
        private const val CORNER_RADIUS_DP = 16f
    }


    var message: Message? = null
        set(value) {
            field = value
            updateContent()
            requestLayout()
            invalidate()
        }

    var isOutgoing: Boolean = true
        set(value) {
            field = value
            backgroundPaint.color = if (value)
                Color.parseColor("#DCF8C6")
            else
                Color.parseColor("#E4E4E4")
            requestLayout()  // 👈 ЭТО ВАЖНО!

            invalidate()
        }

    private var messageText: CharSequence = ""
    private var timeText: String = ""

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpToPx(16f)
        color = Color.BLACK
    }

    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpToPx(11f)
        color = Color.GRAY
        alpha = 180
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var messageLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null

    private var textX = 0
    private var textY = 0
    private var timeX = 0
    private var timeY = 0

    private val paddingHorizontal = dpToPx(12f).toInt()
    private val paddingVertical = dpToPx(8f).toInt()
    private val timePadding = dpToPx(4f).toInt()
    private val marginBottom = dpToPx(2f).toInt()

    private var timeOnSameLine = false

    init {
        setWillNotDraw(false)
    }

    private fun updateContent() {
        message?.let { msg ->
            messageText = msg.content ?: ""
            timeText = formatTime(msg.timestamp)
        }
    }

    private fun createLayouts(maxWidth: Int) {
        if (messageText.isEmpty()) return

        // Принудительно измеряем реальную ширину текста
        val textWidth = textPaint.measureText(messageText.toString()).toInt()
        val effectiveWidth = if (textWidth < maxWidth) textWidth else maxWidth

        messageLayout = StaticLayout.Builder.obtain(
            messageText,
            0,
            messageText.length,
            textPaint,
            effectiveWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()


    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)

        // Максимальная ширина пузыря
        val maxBubbleWidth = (parentWidth * MAX_BUBBLE_WIDTH_PERCENT).toInt()
        val minBubbleWidth = dpToPx(MIN_BUBBLE_WIDTH_DP).toInt()
        val maxTextWidth = maxBubbleWidth - paddingHorizontal * 2

        // Сначала создаём ТОЛЬКО текст
        createLayouts(maxTextWidth)

        val messageHeight = messageLayout?.height ?: 0

        // Находим максимальную ширину строки
        var maxLineWidth = 0
        messageLayout?.let { layout ->
            for (i in 0 until layout.lineCount) {
                val lineWidth = layout.getLineWidth(i).toInt()
                if (lineWidth > maxLineWidth) {
                    maxLineWidth = lineWidth
                }
            }
        }

        val lastLineWidth = messageLayout?.let {
            it.getLineWidth(it.lineCount - 1).toInt()
        } ?: 0

        // Измеряем время
        val timeWidth = timePaint.measureText(timeText).toInt()

        // Проверяем, помещается ли время на последней строке
        val spaceNeeded = lastLineWidth + timeWidth + timePadding
        timeOnSameLine = spaceNeeded <= maxTextWidth


        // 👇 СОЗДАЁМ timeLayout С ПРАВИЛЬНОЙ ШИРИНОЙ
        timeLayout = StaticLayout.Builder.obtain(timeText, 0, timeText.length, timePaint, timeWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .setMaxLines(1)
            .build()

        val timeHeight = timeLayout?.height ?: 0

        // Ширина пузыря
        val bubbleWidth = if (timeOnSameLine) {
            (maxOf(maxLineWidth, spaceNeeded) + paddingHorizontal * 2)
                .coerceIn(minBubbleWidth, maxBubbleWidth)
        } else {
            (maxLineWidth + paddingHorizontal * 2)
                .coerceIn(minBubbleWidth, maxBubbleWidth)
        }

        // Высота пузыря
        val bubbleHeight = if (timeOnSameLine) {
            messageHeight + paddingVertical * 2
        } else {
            messageHeight + timeHeight + paddingVertical * 2 + timePadding
        }

        setMeasuredDimension(bubbleWidth, bubbleHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        // Текст всегда слева
        textX = paddingHorizontal
        textY = paddingVertical

        val timeWidth = timeLayout?.width ?: 0
        val timeHeight = timeLayout?.height ?: 0

        if (timeOnSameLine) {
            // 👇 Время на той же строке - прижимаем к ПРАВОМУ краю
            timeX = width - paddingHorizontal - timeWidth
            // Y - на уровне последней строки
            val lastLineY = messageLayout?.getLineBottom(messageLayout!!.lineCount - 1) ?: 0
            timeY = paddingVertical + lastLineY - timeHeight
        } else {
            // 👇 Время под текстом - тоже справа
            timeX = width - paddingHorizontal - timeWidth
            timeY = height - paddingVertical - timeHeight - marginBottom
        }

        Log.d("ChatMessageCell", "time position: ($timeX, $timeY), onSameLine: $timeOnSameLine")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat() - marginBottom)
        val cornerRadius = dpToPx(CORNER_RADIUS_DP)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)

        canvas.save()
        canvas.translate(textX.toFloat(), textY.toFloat())
        messageLayout?.draw(canvas)
        canvas.restore()

        canvas.save()
        canvas.translate(timeX.toFloat(), timeY.toFloat())
        timeLayout?.draw(canvas)
        canvas.restore()
    }

    private fun formatTime(timestamp: String?): String {
        if (timestamp.isNullOrEmpty()) return ""
        return try {
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            val dateTime = LocalDateTime.parse(timestamp, formatter)
            dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            timestamp.take(5)
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}