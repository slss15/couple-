package com.qimingnan.couple.data.couple

import com.qimingnan.couple.core.CoupleJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 配对/解绑推送（contract "MQTT" §events）：user/{uid}/events 与 couple/{cid}/events，客户端只订阅。 */
sealed interface PairEvent {
    data class Paired(val coupleId: String) : PairEvent
    data class UnpairRequested(val requestId: String, val by: String) : PairEvent
    data object Unpaired : PairEvent

    companion object {
        fun parse(payload: String): PairEvent? = runCatching {
            val o = CoupleJson.parseToJsonElement(payload) as? JsonObject ?: return null
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            when (s("kind")) {
                "paired" -> s("coupleId")?.let { Paired(it) }
                "unpair_requested" -> {
                    val rid = s("requestId"); val by = s("by")
                    if (rid != null && by != null) UnpairRequested(rid, by) else null
                }
                "unpaired" -> Unpaired
                else -> null
            }
        }.getOrNull()
    }
}

/** 收到的解绑申请（由对方发起），UI 弹出确认框。 */
data class UnpairPrompt(val requestId: String, val by: String)
