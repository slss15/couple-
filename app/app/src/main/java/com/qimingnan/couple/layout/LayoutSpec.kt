package com.qimingnan.couple.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 布局 JSON（P5）：zones / order / visible / size(S·M·L)。
 * App 与后端使用同一套规则；服务端校验失败时 App 回退 [LayoutRules.DEFAULT]。
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

    /** 当前可编辑区。聊天/通话模块保留，但入口不进入 P5 编辑器。 */
    val ACTIVE_ZONES: List<String> = listOf("bottomBar", "feedHeader", "taCards", "meSections")

    /** 保留区：顶部栏和 TA 头部仍由锁定项使用，但暂不在 Admin 编辑器中开放。 */
    val RESERVED_ZONES: List<String> = listOf("topBar", "taHeader")

    /** 每个区最多容纳的条目数。 */
    val ZONE_CAPACITY: Map<String, Int> = mapOf(
        "topBar" to 4,
        "bottomBar" to 6,
        "feedHeader" to 6,
        "taHeader" to 4,
        "taCards" to 12,
        "meSections" to 12,
        // 历史布局可能仍带聊天/通话区，保留校验兼容；P5 不再下发或编辑。
        "chatToolbar" to 6,
        "callControls" to 6,
    )

    /** 每个区网格总宽（格数），S=1、M=2、L=4。 */
    val ZONE_GRID_WIDTH: Map<String, Int> = mapOf(
        "topBar" to 8,
        "bottomBar" to 12,
        "feedHeader" to 8,
        "taHeader" to 8,
        "taCards" to 48,
        "meSections" to 48,
        "chatToolbar" to 12,
        "callControls" to 12,
    )

    /** 锁定项：返回、共享指示、隐私开关、“我的”入口不可移动或隐藏。 */
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
                    LayoutItem("tab_feed", 0),
                    LayoutItem("tab_ta", 1),
                    LayoutItem("tab_anniversary", 2),
                    LayoutItem("me_entry", 3),
                    LayoutItem("tab_ledger", 4),
                    LayoutItem("tab_period", 5, visible = false),
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

/** 区查询与安全回退：布局缺失或非法时都使用默认布局。 */
object LayoutResolver {
    fun zone(spec: LayoutSpec, id: String): LayoutZone? = spec.zones.firstOrNull { it.id == id }

    fun visibleItems(spec: LayoutSpec, zoneId: String): List<LayoutItem> =
        zone(spec, zoneId)?.items.orEmpty().filter { it.visible }.sortedBy { it.order }

    fun visibleIds(spec: LayoutSpec, zoneId: String, fallback: List<String>): List<String> {
        val ids = visibleItems(spec, zoneId).map { it.id }
        return ids.ifEmpty { fallback }
    }

    fun isVisible(spec: LayoutSpec, zoneId: String, itemId: String, default: Boolean = true): Boolean {
        val zone = zone(spec, zoneId) ?: return default
        val item = zone.items.firstOrNull { it.id == itemId } ?: return default
        return item.visible
    }

    /**
     * 服务端顺序是基线；用户手动排过序且服务端允许本地覆盖时，本地顺序优先。
     * 服务端未下发的 Tab 永远不显示，避免本地设置把后台已关闭的入口重新打开。
     */
    fun resolveTabOrder(
        spec: LayoutSpec,
        localOrder: List<String>,
        localCustomized: Boolean,
        allIds: List<String>,
        fallback: List<String>,
    ): List<String> {
        val serverOrder = visibleIds(spec, "bottomBar", fallback)
            .filter { it in allIds }
            .distinct()
        val base = serverOrder.ifEmpty { fallback.filter { it in allIds }.distinct() }
        if (!localCustomized) return base
        val local = localOrder.filter { it in base }.distinct()
        return local + base.filterNot { it in local }
    }
}
