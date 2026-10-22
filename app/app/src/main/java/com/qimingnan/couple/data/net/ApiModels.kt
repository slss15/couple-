package com.qimingnan.couple.data.net

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val coupleId: String? = null,
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
data class RegisterRequest(
    val username: String,
    val password: String,
    val displayName: String,
    val deviceId: String,
    val deviceName: String,
    val inviteCode: String,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class RefreshRequest(val refreshToken: String, val deviceId: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class PairCode(val code: String, val expiresAt: Long)

@Serializable
data class JoinRequest(val code: String)

@Serializable
data class Couple(
    val id: String,
    val members: List<User>,
    val pairedAt: Long,
    val anniversary: Long? = null,
)

@Serializable
data class UnpairRequestResponse(val requestId: String)

@Serializable
data class UnpairConfirmRequest(val requestId: String? = null)

@Serializable
data class ErrorBody(val code: String, val message: String = "")
