package com.qimingnan.couple.backend.ta

import com.qimingnan.couple.backend.auth.AuthUser
import com.qimingnan.couple.backend.common.Ids
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.forbidden
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Couples
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.ImportantDays
import com.qimingnan.couple.backend.db.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest

@Serializable
data class ProfileView(
    val bio: String? = null,
    val birthday: String? = null,
    val birthYear: Int? = null,
    val showBirthday: Boolean = true,
)

@Serializable
data class MeView(val displayName: String, val bio: String?, val birthday: String?, val birthYear: Int?, val showBirthday: Boolean)

@Serializable
data class PartnerView(
    val id: String,
    val displayName: String,
    val username: String,
    val avatarUrl: String?,
    val bio: String?,
    /** 对方 showBirthday=false 时为 null（birthdayVisible 提示前端显示「已隐藏」）。 */
    val birthday: String?,
    val birthYear: Int?,
    val birthdayVisible: Boolean,
)

@Serializable
data class DayView(
    val id: String,
    val name: String,
    val month: Int,
    val day: Int,
    val year: Int?,
    val createdBy: String,
)

@Serializable
data class TaSummary(
    val me: MeView,
    val partner: PartnerView,
    val anniversary: Long?,
    val pairedAt: Long,
    val days: List<DayView>,
)

@Serializable
data class UpdateMeRequest(
    val bio: String? = null,
    val birthday: String? = null,
    val birthYear: Int? = null,
    val showBirthday: Boolean? = null,
)

@Serializable
data class UpsertDayRequest(val name: String? = null, val month: Int? = null, val day: Int? = null, val year: Int? = null)

object TaRepo {
    fun user(id: String): ResultRow? = Users.selectAll().where { Users.id eq id }.firstOrNull()

    fun couple(cid: String): ResultRow? =
        Couples.selectAll().where { (Couples.id eq cid) and Couples.deletedAt.isNull() }.firstOrNull()

    fun days(cid: String): List<ResultRow> =
        ImportantDays.selectAll().where { ImportantDays.coupleId eq cid }
            .orderBy(ImportantDays.month to SortOrder.ASC, ImportantDays.day to SortOrder.ASC)
            .toList()

    fun day(id: String): ResultRow? = ImportantDays.selectAll().where { ImportantDays.id eq id }.firstOrNull()

    fun insertDay(id: String, cid: String, name: String, month: Int, day: Int, year: Int?, by: String, now: Long) {
        ImportantDays.insert {
            it[ImportantDays.id] = id
            it[coupleId] = cid
            it[ImportantDays.name] = name
            it[ImportantDays.month] = month.toShort()
            it[ImportantDays.day] = day.toShort()
            it[ImportantDays.year] = year?.toShort()
            it[createdBy] = by
            it[createdAt] = now
        }
    }

    fun updateDay(id: String, name: String?, month: Int?, day: Int?, year: Int?) {
        ImportantDays.update({ ImportantDays.id eq id }) {
            name?.let { v -> it[ImportantDays.name] = v }
            month?.let { v -> it[ImportantDays.month] = v.toShort() }
            day?.let { v -> it[ImportantDays.day] = v.toShort() }
            year?.let { v -> it[ImportantDays.year] = v.toShort() }
        }
    }

    fun deleteDay(id: String) {
        ImportantDays.deleteWhere { ImportantDays.id eq id }
    }

    fun updateMe(userId: String, bio: String?, birthday: String?, birthYear: Int?, showBirthday: Boolean?) {
        Users.update({ Users.id eq userId }) {
            bio?.let { v -> it[this.bio] = v }
            birthday?.let { v -> it[Users.birthday] = v }
            birthYear?.let { v -> it[Users.birthYear] = v.toShort() }
            showBirthday?.let { v -> it[Users.showBirthday] = v }
        }
    }
}

class TaService(private val db: Db, private val config: AppConfig) {

    suspend fun summary(caller: AuthUser): TaSummary {
        val now = config.clock()
        return db.tx {
            val me = TaRepo.user(caller.userId) ?: throw notFound("NOT_PAIRED", "user gone")
            val cid = me[Users.coupleId] ?: throw forbidden("NOT_PAIRED", "You are not paired")
            val couple = TaRepo.couple(cid) ?: throw forbidden("NOT_PAIRED", "You are not paired")
            val partnerId = if (couple[Couples.userA] == caller.userId) couple[Couples.userB] else couple[Couples.userA]
            val partner = TaRepo.user(partnerId) ?: throw notFound("NOT_PAIRED", "partner gone")
            val partnerVisible = partner[Users.showBirthday]
            TaSummary(
                me = MeView(
                    displayName = me[Users.displayName],
                    bio = me[Users.bio],
                    birthday = me[Users.birthday],
                    birthYear = me[Users.birthYear]?.toInt(),
                    showBirthday = me[Users.showBirthday],
                ),
                partner = PartnerView(
                    id = partnerId,
                    displayName = partner[Users.displayName],
                    username = partner[Users.username],
                    avatarUrl = partner[Users.avatarUrl],
                    bio = partner[Users.bio],
                    birthday = if (partnerVisible) partner[Users.birthday] else null,
                    birthYear = if (partnerVisible) partner[Users.birthYear]?.toInt() else null,
                    birthdayVisible = partnerVisible,
                ),
                anniversary = couple[Couples.anniversary],
                pairedAt = couple[Couples.pairedAt],
                days = TaRepo.days(cid).map { it.toDayView() },
            )
        }
    }

    suspend fun updateMe(caller: AuthUser, req: UpdateMeRequest) {
        req.birthday?.let { validateBirthday(it) }
        req.birthYear?.let { if (it !in 1900..2100) throw badRequest("VALIDATION_ERROR", "birthYear must be 1900..2100") }
        req.bio?.let { if (it.length > 200) throw badRequest("VALIDATION_ERROR", "bio too long (max 200)") }
        db.tx {
            val me = TaRepo.user(caller.userId) ?: throw notFound("NOT_PAIRED", "user gone")
            if (me[Users.coupleId] == null) throw forbidden("NOT_PAIRED", "You are not paired")
            TaRepo.updateMe(caller.userId, req.bio, req.birthday, req.birthYear, req.showBirthday)
        }
    }

    suspend fun addDay(caller: AuthUser, req: UpsertDayRequest): DayView {
        val now = config.clock()
        return db.tx {
            val me = TaRepo.user(caller.userId) ?: throw notFound("NOT_PAIRED", "user gone")
            val cid = me[Users.coupleId] ?: throw forbidden("NOT_PAIRED", "You are not paired")
            val name = req.name?.trim() ?: ""
            val month = req.month ?: throw badRequest("VALIDATION_ERROR", "month required")
            val day = req.day ?: throw badRequest("VALIDATION_ERROR", "day required")
            validateName(name); validateMonthDay(month, day)
            val id = Ids.newId()
            TaRepo.insertDay(id, cid, name, month, day, req.year, caller.userId, now)
            TaRepo.day(id)!!.toDayView()
        }
    }

    suspend fun updateDay(caller: AuthUser, dayId: String, req: UpsertDayRequest): DayView {
        req.name?.let { validateName(it.trim()) }
        req.month?.let { m -> validateMonthDay(m, req.day ?: 1) }
        req.day?.let { d -> validateMonthDay(req.month ?: 1, d) }
        return db.tx {
            val me = TaRepo.user(caller.userId) ?: throw notFound("NOT_PAIRED", "user gone")
            val cid = me[Users.coupleId] ?: throw forbidden("NOT_PAIRED", "You are not paired")
            val row = TaRepo.day(dayId) ?: throw notFound("DAY_NOT_FOUND", "no day $dayId")
            if (row[ImportantDays.coupleId] != cid) throw notFound("DAY_NOT_FOUND", "no day $dayId")
            TaRepo.updateDay(dayId, req.name?.trim(), req.month, req.day, req.year)
            TaRepo.day(dayId)!!.toDayView()
        }
    }

    suspend fun deleteDay(caller: AuthUser, dayId: String) {
        db.tx {
            val me = TaRepo.user(caller.userId) ?: throw notFound("NOT_PAIRED", "user gone")
            val cid = me[Users.coupleId] ?: throw forbidden("NOT_PAIRED", "You are not paired")
            val row = TaRepo.day(dayId) ?: throw notFound("DAY_NOT_FOUND", "no day $dayId")
            if (row[ImportantDays.coupleId] != cid) throw notFound("DAY_NOT_FOUND", "no day $dayId")
            TaRepo.deleteDay(dayId)
        }
    }

    private fun validateName(name: String) {
        if (name.isEmpty() || name.length > 50) throw badRequest("VALIDATION_ERROR", "name must be 1..50 chars")
    }

    private fun validateMonthDay(month: Int, day: Int) {
        if (month !in 1..12 || day !in 1..31) throw badRequest("VALIDATION_ERROR", "month 1..12, day 1..31")
    }

    private fun validateBirthday(b: String) {
        val m = Regex("^(\\d{2})-(\\d{2})$").find(b) ?: throw badRequest("VALIDATION_ERROR", "birthday must be MM-DD")
        val (mm, dd) = m.destructured
        validateMonthDay(mm.toInt(), dd.toInt())
    }
}

fun ResultRow.toDayView() = DayView(
    id = this[ImportantDays.id],
    name = this[ImportantDays.name],
    month = this[ImportantDays.month].toInt(),
    day = this[ImportantDays.day].toInt(),
    year = this[ImportantDays.year]?.toInt(),
    createdBy = this[ImportantDays.createdBy],
)
