package com.qimingnan.couple.core

import kotlinx.serialization.json.Json

/** 全局共享的 Json 配置：宽松忽略未知字段，便于后端向前兼容。 */
val CoupleJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}
