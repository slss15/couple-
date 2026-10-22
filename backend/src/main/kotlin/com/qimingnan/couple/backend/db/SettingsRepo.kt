package com.qimingnan.couple.backend.db

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/** Key/value server settings. Must be called inside a transaction. */
object SettingsRepo {
    fun get(key: String): String? =
        ServerSettings.selectAll().where { ServerSettings.key eq key }.firstOrNull()?.get(ServerSettings.value)

    fun getForUpdate(key: String): String? =
        ServerSettings.selectAll().where { ServerSettings.key eq key }.forUpdate().firstOrNull()?.get(ServerSettings.value)

    /** (value, updatedAt) or null. */
    fun getRow(key: String, forUpdate: Boolean = false): Pair<String, Long>? {
        val q = ServerSettings.selectAll().where { ServerSettings.key eq key }
        if (forUpdate) q.forUpdate()
        return q.firstOrNull()?.let { it[ServerSettings.value] to it[ServerSettings.updatedAt] }
    }

    fun put(key: String, value: String, now: Long) {
        val updated = ServerSettings.update({ ServerSettings.key eq key }) {
            it[ServerSettings.value] = value
            it[updatedAt] = now
        }
        if (updated == 0) {
            ServerSettings.insert {
                it[ServerSettings.key] = key
                it[ServerSettings.value] = value
                it[updatedAt] = now
            }
        }
    }
}
