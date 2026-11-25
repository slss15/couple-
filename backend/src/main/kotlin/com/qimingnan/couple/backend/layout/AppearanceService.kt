package com.qimingnan.couple.backend.layout

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.SettingsRepo
import kotlinx.serialization.SerializationException

/** P5 外观配置：布局与主题默认值存在 server_settings，App 和 Admin 共用同一份视图。 */
class AppearanceService(
    private val db: Db,
    private val config: AppConfig,
) {
    private data class Snapshot(
        val stored: StoredAppearance,
        val version: Long,
        val updatedAt: Long?,
    )

    suspend fun view(): AppAppearance = db.tx { readSnapshot().toView() }

    suspend fun update(req: AppearanceUpdate): AppAppearance {
        val now = config.clock()
        return db.tx {
            val current = readSnapshot(forUpdate = true)
            val next = StoredAppearance(
                layout = req.layout ?: current.stored.layout,
                theme = req.theme ?: current.stored.theme,
            )
            val layoutErrors = LayoutValidator.validate(next.layout)
            if (layoutErrors.isNotEmpty()) {
                throw badRequest("VALIDATION_ERROR", layoutErrors.joinToString("; ") { it.message })
            }
            val themeErrors = AppearanceRules.validate(next.theme)
            if (themeErrors.isNotEmpty()) {
                throw badRequest("VALIDATION_ERROR", themeErrors.joinToString("; "))
            }
            val version = current.version + 1
            SettingsRepo.put(KEY_APPEARANCE, AppJson.encodeToString(StoredAppearance.serializer(), next), now)
            SettingsRepo.put(KEY_VERSION, version.toString(), now)
            Snapshot(next, version, now).toView()
        }
    }

    private fun readSnapshot(forUpdate: Boolean = false): Snapshot {
        val row = SettingsRepo.getRow(KEY_APPEARANCE, forUpdate)
        val stored = row?.let {
            try {
                AppJson.decodeFromString(StoredAppearance.serializer(), it.first)
            } catch (_: SerializationException) {
                StoredAppearance()
            } catch (_: IllegalArgumentException) {
                StoredAppearance()
            }
        } ?: StoredAppearance()
        val version = SettingsRepo.get(KEY_VERSION)?.toLongOrNull() ?: 0L
        return Snapshot(stored, version, row?.second)
    }

    private fun Snapshot.toView() = AppAppearance(
        layout = stored.layout,
        theme = stored.theme,
        catalog = LayoutCatalog.zones,
        themeCatalog = LayoutCatalog.themeCatalog,
        appearanceVersion = version,
        updatedAt = updatedAt,
    )

    companion object {
        const val KEY_APPEARANCE = "app.appearance"
        const val KEY_VERSION = "app.appearance_version"
    }
}
