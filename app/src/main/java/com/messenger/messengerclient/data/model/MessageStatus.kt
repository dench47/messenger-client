package com.messenger.messengerclient.data.model

enum class MessageStatus {
    SENT,       // Отправлено на сервер (одна серая галочка) - по умолчанию
    DELIVERED,  // Доставлено получателю (две серые галочки)
    READ,       // Прочитано (две синие галочки)
    ERROR       // Ошибка отправки (красный восклицательный знак)
}