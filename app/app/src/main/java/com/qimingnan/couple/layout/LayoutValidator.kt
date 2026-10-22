package com.qimingnan.couple.layout

import com.qimingnan.couple.core.CoupleJson
import kotlinx.serialization.SerializationException

data class LayoutError(val code: String, val path: String, val message: String)

sealed interface LayoutValidation {
    data class Valid(val spec: LayoutSpec) : LayoutValidation
    data class Invalid(val errors: List<LayoutError>) : LayoutValidation
}

/**
 * 布局校验器（P0 stub，规则见 [LayoutRules]）：
 * - schema 版本、区 id、条目 id 格式
 * - 区不重复、条目全局不重复、区内 order 非负且不重复
 * - 区容量上限与网格宽度（S/M/L 占 1/2/4 格）
 * - 锁定项必须存在、可见，且位于固定的区和 order
 * 无效布局由调用方回退到 [LayoutRules.DEFAULT]（安全模式）。
 */
object LayoutValidator {

    fun validateJson(json: String): LayoutValidation {
        val spec = try {
            CoupleJson.decodeFromString(LayoutSpec.serializer(), json)
        } catch (e: SerializationException) {
            return LayoutValidation.Invalid(listOf(LayoutError("INVALID_JSON", "$", e.message ?: "JSON 无法解析")))
        } catch (e: IllegalArgumentException) {
            return LayoutValidation.Invalid(listOf(LayoutError("INVALID_JSON", "$", e.message ?: "JSON 无法解析")))
        }
        return validate(spec)
    }

    fun validate(spec: LayoutSpec): LayoutValidation {
        val errors = mutableListOf<LayoutError>()
        if (spec.version != LayoutRules.SCHEMA_VERSION) {
            errors += LayoutError("UNSUPPORTED_VERSION", "$.version", "不支持的布局版本 ${spec.version}")
        }

        val seenZones = mutableSetOf<String>()
        val seenItems = mutableMapOf<String, String>() // itemId -> path
        val located = mutableMapOf<String, Pair<String, LayoutItem>>() // itemId -> zone,item

        spec.zones.forEachIndexed { zi, zone ->
            val zPath = "$.zones[$zi]"
            val capacity = LayoutRules.ZONE_CAPACITY[zone.id]
            if (capacity == null) {
                errors += LayoutError("UNKNOWN_ZONE", "$zPath.id", "未知区域 ${zone.id}")
            }
            if (!seenZones.add(zone.id)) {
                errors += LayoutError("DUPLICATE_ZONE", "$zPath.id", "区域 ${zone.id} 重复")
            }
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
                if (item.order < 0) {
                    errors += LayoutError("INVALID_ORDER", "$iPath.order", "order 不能为负数")
                }
                if (!orders.add(item.order)) {
                    errors += LayoutError("DUPLICATE_ORDER", "$iPath.order", "区域 ${zone.id} 内 order ${item.order} 重复")
                }
                val prev = seenItems.put(item.id, iPath)
                if (prev != null) {
                    errors += LayoutError("DUPLICATE_ITEM", "$iPath.id", "条目 ${item.id} 重复（另见 $prev）")
                } else {
                    located[item.id] = zone.id to item
                }
            }
        }

        LayoutRules.LOCKED.forEach { (id, slot) ->
            val found = located[id]
            when {
                found == null -> errors += LayoutError("LOCKED_MISSING", "$", "锁定项 $id 不能删除")
                found.first != slot.zone -> errors += LayoutError(
                    "LOCKED_MOVED", "$", "锁定项 $id 必须位于 ${slot.zone}，当前在 ${found.first}",
                )
                found.second.order != slot.order -> errors += LayoutError(
                    "LOCKED_MOVED", "$", "锁定项 $id 的 order 必须为 ${slot.order}",
                )
                !found.second.visible -> errors += LayoutError("LOCKED_HIDDEN", "$", "锁定项 $id 不能隐藏")
            }
        }

        return if (errors.isEmpty()) LayoutValidation.Valid(spec) else LayoutValidation.Invalid(errors)
    }

    /** 校验失败时回退默认布局（安全模式）。 */
    fun orDefault(json: String?): LayoutSpec {
        if (json == null) return LayoutRules.DEFAULT
        return (validateJson(json) as? LayoutValidation.Valid)?.spec ?: LayoutRules.DEFAULT
    }
}
