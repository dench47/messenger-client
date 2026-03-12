package com.messenger.messengerclient.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import com.messenger.messengerclient.R
import com.messenger.messengerclient.data.model.Message
import com.messenger.messengerclient.data.model.MessageStatus
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ChatMessageCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_BUBBLE_WIDTH_PERCENT = 0.70f
        private const val MIN_BUBBLE_WIDTH_DP = 48f
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
                "#DCF8C6".toColorInt()
            else
                "#E4E4E4".toColorInt()
            requestLayout()
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

    // Иконки статусов
    private val sentIcon = ContextCompat.getDrawable(context, R.drawable.ic_check_sent)
    private val deliveredIcon = ContextCompat.getDrawable(context, R.drawable.ic_check_delivered)
    private val readIcon = ContextCompat.getDrawable(context, R.drawable.ic_check_read)
    private val errorIcon = ContextCompat.getDrawable(context, R.drawable.ic_error)

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
            messageText = msg.content
            timeText = formatTime(msg.timestamp)
        }
    }

    private fun createLayouts(maxWidth: Int) {
        if (messageText.isEmpty()) return

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

        val maxBubbleWidth = (parentWidth * MAX_BUBBLE_WIDTH_PERCENT).toInt()
        val minBubbleWidth = dpToPx(MIN_BUBBLE_WIDTH_DP).toInt()
        val maxTextWidth = maxBubbleWidth - paddingHorizontal * 2

        createLayouts(maxTextWidth)

        val messageHeight = messageLayout?.height ?: 0

        var maxLineWidth = 0
        var lastLineWidth = 0
        var lineCount = 0
        messageLayout?.let { layout ->
            lineCount = layout.lineCount
            for (i in 0 until lineCount) {
                val lineWidth = layout.getLineWidth(i).toInt()
                if (lineWidth > maxLineWidth) {
                    maxLineWidth = lineWidth
                }
            }
            lastLineWidth = layout.getLineWidth(lineCount - 1).toInt()
        }

        val timeWidth = timePaint.measureText(timeText).toInt()

        // Учитываем иконку статуса только для исходящих
        val iconSize = dpToPx(16f).toInt()
        val iconPadding = dpToPx(4f).toInt()

        val totalWidthForOutgoing = timeWidth + iconSize + iconPadding

        // Проверяем помещается ли время (и иконка) на последней строке
        val spaceNeededOutgoing = lastLineWidth + totalWidthForOutgoing + timePadding
        val spaceNeededIncoming = lastLineWidth + timeWidth + timePadding

        timeOnSameLine = if (isOutgoing) {
            spaceNeededOutgoing <= maxTextWidth
        } else {
            spaceNeededIncoming <= maxTextWidth
        }

        timeLayout = StaticLayout.Builder.obtain(timeText, 0, timeText.length, timePaint, timeWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .setMaxLines(1)
            .build()

        val timeHeight = timeLayout?.height ?: 0

        // 👇 ЛОГИКА ОПРЕДЕЛЕНИЯ ШИРИНЫ ПУЗЫРЯ С ПРОВЕРКОЙ НАЛОЖЕНИЯ
        val bubbleWidth = if (timeOnSameLine) {
            if (lineCount > 1) {
                // Многострочное - проверяем не залезает ли время на текст
                if (isOutgoing) {
                    // Для исходящих проверяем последнюю строку + время/статус
                    val lastLineWithStatus = lastLineWidth + totalWidthForOutgoing + timePadding
                    if (lastLineWithStatus > maxLineWidth) {
                        // Время залезает на текст - расширяем до последней строки + блок времени
                        (lastLineWithStatus + paddingHorizontal * 2)
                            .coerceIn(minBubbleWidth, maxBubbleWidth)
                    } else {
                        // Время помещается внутри - ширина = самая длинная строка
                        (maxLineWidth + paddingHorizontal * 2)
                            .coerceIn(minBubbleWidth, maxBubbleWidth)
                    }
                } else {
                    // Для входящих проверяем последнюю строку + время
                    val lastLineWithTime = lastLineWidth + timeWidth + timePadding
                    if (lastLineWithTime > maxLineWidth) {
                        // Время залезает на текст - расширяем до последней строки + время
                        (lastLineWithTime + paddingHorizontal * 2)
                            .coerceIn(minBubbleWidth, maxBubbleWidth)
                    } else {
                        // Время помещается внутри - ширина = самая длинная строка
                        (maxLineWidth + paddingHorizontal * 2)
                            .coerceIn(minBubbleWidth, maxBubbleWidth)
                    }
                }
            } else {
                // Однострочное - всегда учитываем время
                if (isOutgoing) {
                    (lastLineWidth + totalWidthForOutgoing + timePadding + paddingHorizontal * 2)
                        .coerceIn(minBubbleWidth, maxBubbleWidth)
                } else {
                    (lastLineWidth + timeWidth + timePadding + paddingHorizontal * 2)
                        .coerceIn(minBubbleWidth, maxBubbleWidth)
                }
            }
        } else {
            // Время под текстом: ширина = самая длинная строка
            (maxLineWidth + paddingHorizontal * 2)
                .coerceIn(minBubbleWidth, maxBubbleWidth)
        }

        val bubbleHeight = if (timeOnSameLine) {
            messageHeight + paddingVertical * 2
        } else {
            messageHeight + timeHeight + paddingVertical * 2 + timePadding
        }

        setMeasuredDimension(bubbleWidth, bubbleHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        textX = paddingHorizontal
        textY = paddingVertical

        val timeWidth = timeLayout?.width ?: 0
        val timeHeight = timeLayout?.height ?: 0
        val lineCount = messageLayout?.lineCount ?: 0

        if (timeOnSameLine) {
            val lastLineY = messageLayout?.getLineBottom(messageLayout!!.lineCount - 1) ?: 0
            timeY = paddingVertical + lastLineY - timeHeight

            timeX = if (isOutgoing) {
                // ИСХОДЯЩИЕ: время сразу после текста
                textX + (messageLayout?.getLineWidth(messageLayout!!.lineCount - 1) ?: 0).toInt() + timePadding
            } else {
                // ВХОДЯЩИЕ
                if (lineCount > 1) {
                    // Многострочное - время у правого края пузыря
                    width - paddingHorizontal - timeWidth
                } else {
                    // Однострочное - время сразу после текста
                    textX + (messageLayout?.getLineWidth(messageLayout!!.lineCount - 1) ?: 0).toInt() + timePadding
                }
            }
        } else {
            // Время под текстом - у правого края для всех
            timeX = width - paddingHorizontal - timeWidth
            timeY = height - paddingVertical - timeHeight - marginBottom
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat() - marginBottom)
        val cornerRadius = dpToPx(CORNER_RADIUS_DP)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)

        canvas.withTranslation(textX.toFloat(), textY.toFloat()) {
            messageLayout?.draw(this)
        }

        if (isOutgoing) {
            // СВОИ СООБЩЕНИЯ: время + иконка
            val timeWidth = timeLayout?.width ?: 0
            val iconSize = dpToPx(16f).toInt()
            val iconPadding = dpToPx(4f).toInt()

            // Позиция времени (прижато к правому краю)
            val blockX = width - paddingHorizontal - (timeWidth + iconSize + iconPadding)
            val timeY = this.timeY

            canvas.withTranslation(blockX.toFloat(), timeY.toFloat()) {
                timeLayout?.draw(this)
            }

            // Рисуем иконку статуса
            message?.let { msg ->
                val iconX = blockX + timeWidth + iconPadding
                val iconY = timeY + (timeLayout?.height ?: 0) / 2 - iconSize / 2

                val icon = when (msg.getMessageStatus()) {
                    MessageStatus.SENT -> sentIcon
                    MessageStatus.DELIVERED -> deliveredIcon
                    MessageStatus.READ -> readIcon
                    MessageStatus.ERROR -> errorIcon
                }

                icon?.setBounds(iconX, iconY, iconX + iconSize, iconY + iconSize)
                icon?.draw(canvas)
            }
        } else {
            // ЧУЖИЕ СООБЩЕНИЯ: только время
            canvas.withTranslation(timeX.toFloat(), timeY.toFloat()) {
                timeLayout?.draw(this)
            }
        }
    }

    private fun formatTime(timestamp: String?): String {
        if (timestamp.isNullOrEmpty()) return ""
        return try {
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            val dateTime = LocalDateTime.parse(timestamp, formatter)
            dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) {
            timestamp.take(5)
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}