package com.qimingnan.couple.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 布局 JSON（plan §7）：zones / order / visible / size(S·M·L)。
 * App 与后端使用同一套规则（[LayoutRules]），后端校验器应与 [LayoutValidator] 行为一致。
 *
 * 示例：
 * {"version":1,"zones":[{"id":"bottomBar","items":[{"id":"me_entry","order":3,"visible":true,"size":"M"}]}]}
 */
@Serializable
data class LayoutSpec(
    val version: Int = LayoutRules.SCHEMA_VERSION,
    val zones: List<LayoutZone>,
)

@Serializable
data class LayoutZone(
    val id: String,
    val items: List<LayoutItem>,
)

@Serializable
data class LayoutItem(
    val id: String,
    val order: Int,
    val visible: Boolean = true,
    val size: ItemSize = ItemSize.M,
)

@Serializable
enum class ItemSize(val gridSpan: Int) {
    @SerialName("S") S(1),
    @SerialName("M") M(2),
    @SerialName("L") L(4),
}

/** 锁定项：固定所在区与顺序，必须可见，不可被布局编辑器移动或隐藏。 */
data class LockedSlot(val zone: String, val order: Int)

object LayoutRules {
    const val SCHEMA_VERSION = 1

    /** 每个区最多容纳的条目数 */
    val ZONE_CAPACITY: Map<String, Int> = mapOf(
        "topBar" to 4,
        "bottomBar" to 5,
        "feedHeader" to 6,
        "chatToolbar" to 6,
        "callControls" to 6,
        "taHeader" to 4,
        "taCards" to 12,
        "meSections" to 12,
    )

    /** 每个区网格总宽（格数），S=1、M=2、L=4 */
    val ZONE_GRID_WIDTH: Map<String, Int> = mapOf(
        "topBar" to 8,
        "bottomBar" to 10,
        "feedHeader" to 8,
        "chatToolbar" to 12,
        "callControls" to 12,
        "taHeader" to 8,
        "taCards" to 48,
        "meSections" to 48,
    )

    /** plan §7：发送、返回、挂断、共享指示、隐私开关、“我的”入口锁定不可移动。 */
    val LOCKED: Map<String, LockedSlot> = mapOf(
        "send" to LockedSlot("chatToolbar", 0),
        "back" to LockedSlot("topBar", 0),
        "hangup" to LockedSlot("callControls", 0),
        "sharing_indicator" to LockedSlot("topBar", 1),
        "privacy_toggle" to LockedSlot("taHeader", 0),
        "me_entry" to LockedSlot("bottomBar", 3),
    )

    val LOCKED_IDS: Set<String> get() = LOCKED.keys

    val ID_PATTERN = Regex("^[a-z][a-z0-9_.:-]{0,63}$")

    val DEFAULT: LayoutSpec = LayoutSpec(
        version = SCHEMA_VERSION,
        zones = listOf(
            LayoutZone("topBar", listOf(LayoutItem("back", 0, size = ItemSize.S), LayoutItem("sharing_indicator", 1, size = ItemSize.S))),
            LayoutZone(
                "bottomBar",
                listOf(
                    LayoutItem("tab_chat", 0), LayoutItem("tab_feed", 1), LayoutItem("tab_ta", 2), LayoutItem("me_entry", 3),
                ),
            ),
            LayoutZone("chatToolbar", listOf(LayoutItem("send", 0, size = ItemSize.S), LayoutItem("emoji", 1, size = ItemSize.S), LayoutItem("voice", 2, size = ItemSize.S))),
            LayoutZone("callControls", listOf(LayoutItem("hangup", 0, size = ItemSize.M), LayoutItem("mute", 1, size = ItemSize.S))),
            LayoutZone("taHeader", listOf(LayoutItem("privacy_toggle", 0, size = ItemSize.S))),
            LayoutZone("feedHeader", listOf(LayoutItem("plugin:anniversary", 0, size = ItemSize.L))),
        ),
    )
}
