package com.qimingnan.couple.backend.auth

import com.qimingnan.couple.backend.common.User
import com.qimingnan.couple.backend.db.Users
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll

/** Must be called inside a transaction. */
object UserRepo {
    data class Row(val user: User, val role: String, val passwordHash: String)

    fun toRow(r: ResultRow) = Row(
        user = User(
            id = r[Users.id],
            username = r[Users.username],
            displayName = r[Users.displayName],
            avatarUrl = r[Users.avatarUrl],
            coupleId = r[Users.coupleId],
            createdAt = r[Users.createdAt],
        ),
        role = r[Users.role],
        passwordHash = r[Users.passwordHash],
    )

    fun byId(id: String): Row? = Users.selectAll().where { Users.id eq id }.limit(1).map(::toRow).firstOrNull()

    fun byIdForUpdate(id: String): Row? =
        Users.selectAll().where { Users.id eq id }.forUpdate().map(::toRow).firstOrNull()

    fun byUsername(username: String): Row? =
        Users.selectAll().where { Users.username eq username }.limit(1).map(::toRow).firstOrNull()
}
