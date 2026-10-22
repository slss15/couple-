package com.qimingnan.couple.backend.common

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val coupleId: String?,
    val createdAt: Long,
)

@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val user: User,
)

@Serializable
data class Couple(
    val id: String,
    val members: List<User>,
    val pairedAt: Long,
    /** Epoch ms (contract: "Times are epoch milliseconds"); null until set. */
    val anniversary: Long?,
)

object Roles {
    const val USER = "user"
    const val ADMIN = "admin"
}
