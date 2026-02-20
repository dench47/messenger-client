package com.messenger.messengerclient.utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DateUtils {
    fun formatMessageTime(timestamp: String?): String {
        if (timestamp.isNullOrEmpty()) return ""

        return try {
            val messageTime = LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val now = LocalDateTime.now()
            val today = now.toLocalDate()
            val messageDate = messageTime.toLocalDate()

            val daysDiff = java.time.temporal.ChronoUnit.DAYS.between(messageDate, today)

            when {
                daysDiff == 0L ->
                    messageTime.format(DateTimeFormatter.ofPattern("HH:mm"))

                daysDiff == 1L ->
                    "вчера"

                daysDiff <= 7L -> {
                    // День недели: пн, вт, ср, чт, пт, сб, вс
                    val dayOfWeek = when (messageDate.dayOfWeek) {
                        java.time.DayOfWeek.MONDAY -> "пн"
                        java.time.DayOfWeek.TUESDAY -> "вт"
                        java.time.DayOfWeek.WEDNESDAY -> "ср"
                        java.time.DayOfWeek.THURSDAY -> "чт"
                        java.time.DayOfWeek.FRIDAY -> "пт"
                        java.time.DayOfWeek.SATURDAY -> "сб"
                        java.time.DayOfWeek.SUNDAY -> "вс"
                    }
                    dayOfWeek
                }

                else ->
                    messageTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            }
        } catch (e: Exception) {
            timestamp
        }
    }
}