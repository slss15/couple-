package com.qimingnan.couple.backend.audit

import com.qimingnan.couple.backend.common.AppJson
import com.qimingnan.couple.backend.common.Ids
import com.qimingnan.couple.backend.db.AuditLog
import com.qimingnan.couple.backend.db.Db
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

@Serializable
data class AuditEntry(
    val id: String,
    val actorId: String?,
    val action: String,
    val target: String?,
    val meta: JsonElement?,
    val ts: Long,
)

/** Audit action names. Every auth / pair / admin action writes one entry. */
object AuditActions {
    const val REGISTER = "auth.register"
    const val REGISTER_FAILED = "auth.register_failed"
    const val LOGIN = "auth.login"
    const val LOGIN_FAILED = "auth.login_failed"
    const val REFRESH = "auth.refresh"
    const val REFRESH_FAILED = "auth.refresh_failed"
    const val REFRESH_REUSE = "auth.refresh_reuse_detected"
    const val LOGOUT = "auth.logout"
    const val PAIR_CODE = "pair.code"
    const val PAIR_JOIN = "pair.join"
    const val PAIR_JOIN_FAILED = "pair.join_failed"
    const val UNPAIR_REQUEST = "couple.unpair_request"
    const val UNPAIR_CONFIRM = "couple.unpair_confirm"
    const val UNPAIR_CONFIRM_FAILED = "couple.unpair_confirm_failed"
    const val ADMIN_CONFIG_VIEW = "admin.config.view"
    const val ADMIN_CONFIG_UPDATE = "admin.config.update"
    const val ADMIN_BOOTSTRAP = "admin.bootstrap.issue"
    const val ADMIN_AUDIT_VIEW = "admin.audit.view"
    const val ADMIN_DENIED = "admin.denied"
    const val MQTT_AUTH_DENIED = "mqtt.auth_denied"
    const val BOOTSTRAP_KEY_GENERATED = "bootstrap.key_generated"
    const val RELEASE_MIRRORED = "release.mirrored"
    const val RELEASE_REJECTED = "release.rejected"
    const val RELEASE_CERT_PINNED = "release.cert_pinned"
    const val RELEASE_SYNC_FAILED = "release.sync_failed"
    const val ADMIN_RELEASE_LIST = "admin.release.list"
    const val ADMIN_RELEASE_SYNC = "admin.release.sync"
    const val ADMIN_RELEASE_UPDATE = "admin.release.update"
    const val ADMIN_RELEASE_SETTINGS_VIEW = "admin.release.settings.view"
    const val ADMIN_RELEASE_SETTINGS_UPDATE = "admin.release.settings.update"
    const val ADMIN_SERVER_UPDATE_STATUS = "admin.server.update_status"
    const val UPDATER_STATUS = "updater.status"
}

class AuditService(private val db: Db, private val clock: () -> Long) {

    suspend fun log(actorId: String?, action: String, target: String? = null, meta: Map<String, Any?> = emptyMap()) {
        val metaJson = if (meta.isEmpty()) null else AppJson.encodeToString(JsonObject.serializer(), toJson(meta))
        db.tx {
            AuditLog.insert {
                it[id] = Ids.newId()
                it[AuditLog.actorId] = actorId
                it[AuditLog.action] = action
                it[AuditLog.target] = target?.take(128)
                it[AuditLog.meta] = metaJson
                it[ts] = clock()
            }
        }
    }

    suspend fun list(limit: Int, before: Long?): List<AuditEntry> = db.tx {
        val q = AuditLog.selectAll()
        if (before != null) q.andWhere { AuditLog.ts less before }
        q.orderBy(AuditLog.ts to SortOrder.DESC, AuditLog.id to SortOrder.DESC)
            .limit(limit)
            .map {
                AuditEntry(
                    id = it[AuditLog.id],
                    actorId = it[AuditLog.actorId],
                    action = it[AuditLog.action],
                    target = it[AuditLog.target],
                    meta = it[AuditLog.meta]?.let { m -> AppJson.parseToJsonElement(m) },
                    ts = it[AuditLog.ts],
                )
            }
    }

    private fun toJson(map: Map<String, Any?>): JsonObject = JsonObject(map.mapValues { (_, v) ->
        when (v) {
            null -> JsonNull
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is JsonElement -> v
            else -> JsonPrimitive(v.toString())
        }
    })
}

