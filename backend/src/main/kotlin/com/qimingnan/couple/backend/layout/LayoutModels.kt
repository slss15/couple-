package com.qimingnan.couple.backend.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

data class LockedSlot(val zone: String, val order: Int)

object LayoutRules {
    const val SCHEMA_VERSION = 1

    val ACTIVE_ZONES: List<String> = listOf("bottomBar", "feedHeader", "taCards", "meSections")
    val RESERVED_ZONES: List<String> = listOf("topBar", "taHeader")

    val ZONE_CAPACITY: Map<String, Int> = mapOf(
        "topBar" to 4,
        "bottomBar" to 8,
        "feedHeader" to 6,
        "taHeader" to 4,
        "taCards" to 12,
        "meSections" to 12,
        "chatToolbar" to 6,
        "callControls" to 6,
    )

    val ZONE_GRID_WIDTH: Map<String, Int> = mapOf(
        "topBar" to 8,
        "bottomBar" to 16,
        "feedHeader" to 8,
        "taHeader" to 8,
        "taCards" to 48,
        "meSections" to 48,
        "chatToolbar" to 12,
        "callControls" to 12,
    )

    val LOCKED: Map<String, LockedSlot> = mapOf(
        "back" to LockedSlot("topBar", 0),
        "sharing_indicator" to LockedSlot("topBar", 1),
        "privacy_toggle" to LockedSlot("taHeader", 0),
        "me_entry" to LockedSlot("bottomBar", 3),
    )

    val LOCKED_IDS: Set<String> get() = LOCKED.keys

    val ID_PATTERN = Regex("^[a-z][a-z0-9_.:-]{0,63}$")

    val DEFAULT: LayoutSpec = LayoutSpec(
        version = SCHEMA_VERSION,
        zones = listOf(
            LayoutZone(
                "topBar",
                listOf(
                    LayoutItem("back", 0, size = ItemSize.S),
                    LayoutItem("sharing_indicator", 1, size = ItemSize.S),
                ),
            ),
            LayoutZone(
                "bottomBar",
                listOf(
                    LayoutItem("tab_home", 0),
                    LayoutItem("tab_feed", 1),
                    LayoutItem("tab_ledger", 2),
                    LayoutItem("me_entry", 3),
                    LayoutItem("tab_ta", 4, visible = false),
                    LayoutItem("tab_anniversary", 5, visible = false),
                    LayoutItem("tab_period", 6, visible = false),
                ),
            ),
            LayoutZone("feedHeader", listOf(LayoutItem("plugin:anniversary", 0, size = ItemSize.L))),
            LayoutZone("taHeader", listOf(LayoutItem("privacy_toggle", 0, size = ItemSize.S))),
            LayoutZone(
                "taCards",
                listOf(
                    LayoutItem("partner_card", 0),
                    LayoutItem("anniversary", 1),
                    LayoutItem("days", 2),
                ),
            ),
            LayoutZone(
                "meSections",
                listOf(
                    LayoutItem("settings", 0),
                    LayoutItem("profile", 1),
                    LayoutItem("couple", 2),
                    LayoutItem("mqtt", 3),
                    LayoutItem("server", 4),
                    LayoutItem("updates", 5),
                    LayoutItem("chat_entry", 6, visible = false),
                ),
            ),
        ),
    )
}

data class LayoutError(val code: String, val path: String, val message: String)

object LayoutValidator {
    fun validate(spec: LayoutSpec): List<LayoutError> {
        val errors = mutableListOf<LayoutError>()
        if (spec.version != LayoutRules.SCHEMA_VERSION) {
            errors += LayoutError("UNSUPPORTED_VERSION", "$.version", "不支持的布局版本 ${spec.version}")
        }

        val seenZones = mutableSetOf<String>()
        val seenItems = mutableMapOf<String, String>()
        val located = mutableMapOf<String, Pair<String, LayoutItem>>()

        spec.zones.forEachIndexed { zi, zone ->
            val zPath = "$.zones[$zi]"
            val capacity = LayoutRules.ZONE_CAPACITY[zone.id]
            if (capacity == null) errors += LayoutError("UNKNOWN_ZONE", "$zPath.id", "未知区域 ${zone.id}")
            if (!seenZones.add(zone.id)) errors += LayoutError("DUPLICATE_ZONE", "$zPath.id", "区域 ${zone.id} 重复")
            if (capacity != null && zone.items.size > capacity) {
                errors += LayoutError("ZONE_OVER_CAPACITY", "$zPath.items", "区域 ${zone.id} 最多 $capacity 项，当前 ${zone.items.size}")
            }
            val width = LayoutRules.ZONE_GRID_WIDTH[zone.id]
            val used = zone.items.filter { it.visible }.sumOf { it.size.gridSpan }
            if (width != null && used > width) {
                errors += LayoutError("ZONE_OVER_WIDTH", "$zPath.items", "区域 ${zone.id} 网格宽 $width 格，可见项共占 $used 格")
            }
            val orders = mutableSetOf<Int>()
            zone.items.forEachIndexed { ii, item ->
                val iPath = "$zPath.items[$ii]"
                if (!LayoutRules.ID_PATTERN.matches(item.id)) {
                    errors += LayoutError("INVALID_ITEM_ID", "$iPath.id", "非法条目 id：${item.id}")
                }
                if (item.order < 0) errors += LayoutError("INVALID_ORDER", "$iPath.order", "order 不能为负数")
                if (!orders.add(item.order)) errors += LayoutError("DUPLICATE_ORDER", "$iPath.order", "区域 ${zone.id} 内 order ${item.order} 重复")
                val prev = seenItems.put(item.id, iPath)
                if (prev != null) errors += LayoutError("DUPLICATE_ITEM", "$iPath.id", "条目 ${item.id} 重复（另见 $prev）")
                else located[item.id] = zone.id to item
            }
        }

        LayoutRules.LOCKED.forEach { (id, slot) ->
            val found = located[id]
            when {
                found == null -> errors += LayoutError("LOCKED_MISSING", "$", "锁定项 $id 不能删除")
                found.first != slot.zone -> errors += LayoutError("LOCKED_MOVED", "$", "锁定项 $id 必须位于 ${slot.zone}，当前在 ${found.first}")
                found.second.order != slot.order -> errors += LayoutError("LOCKED_MOVED", "$", "锁定项 $id 的 order 必须为 ${slot.order}")
                !found.second.visible -> errors += LayoutError("LOCKED_HIDDEN", "$", "锁定项 $id 不能隐藏")
            }
        }
        return errors
    }
}
