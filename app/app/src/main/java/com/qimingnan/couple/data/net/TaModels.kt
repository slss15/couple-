package com.qimingnan.couple.data.net

import kotlinx.serialization.Serializable

/** contract "TA" 的模型。 */

@Serializable
data class TaMeView(
    val displayName: String,
    val bio: String? = null,
    val birthday: String? = null,
    val birthYear: Int? = null,
    val showBirthday: Boolean = true,
)

@Serializable
data class TaPartnerView(
    val id: String,
    val displayName: String,
    val username: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val birthday: String? = null,
    val birthYear: Int? = null,
    val birthdayVisible: Boolean = true,
)

@Serializable
data class ImportantDay(
    val id: String,
    val name: String,
    val month: Int,
    val day: Int,
    val year: Int? = null,
    val createdBy: String = "",
)

@Serializable
data class TaSummary(
    val me: TaMeView,
    val partner: TaPartnerView,
    val anniversary: Long? = null,
    val pairedAt: Long = 0,
    val days: List<ImportantDay> = emptyList(),
)

@Serializable
data class UpdateMeRequest(
    val bio: String? = null,
    val birthday: String? = null,
    val birthYear: Int? = null,
    val showBirthday: Boolean? = null,
)

@Serializable
data class UpsertDayRequest(
    val name: String? = null,
    val month: Int? = null,
    val day: Int? = null,
    val year: Int? = null,
)
