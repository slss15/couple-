package com.qimingnan.couple.backend.chat

import com.qimingnan.couple.backend.auth.AuthUser
import com.qimingnan.couple.backend.auth.authUser
import com.qimingnan.couple.backend.common.Ids
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.conflict
import com.qimingnan.couple.backend.common.forbidden
import com.qimingnan.couple.backend.common.notFound
import com.qimingnan.couple.backend.config.AppConfig
import com.qimingnan.couple.backend.db.Couples
import com.qimingnan.couple.backend.db.Db
import com.qimingnan.couple.backend.db.MediaObjects
import com.qimingnan.couple.backend.db.ScheduledMessages
import com.qimingnan.couple.backend.db.Users
import com.qimingnan.couple.backend.media.MediaRepo
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Year
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** 重复规则（contract "Chat → Scheduled messages"）。kind: ONCE | DAILY | WEEKLY | YEARLY。 */
@Serializable
data class ScheduleRuleDto(
    val kind: String,
    /** ONCE：触发时刻（epoch ms）。 */
    val at: Long? = null,
    /** DAILY / WEEKLY / YEARLY："HH:mm"（规则时区的本地时间）。 */
    val time: String? = null,
    /** WEEKLY：1=周一 … 7=周日（ISO）。 */
    val dayOfWeek: Int? = null,
    /** YEARLY：1..12 / 1..31（2 月 29 日在平年落到 2 月 28 日）。 */
    val month: Int? = null,
    val day: Int? = null,
)

@Serializable
data class ScheduledMessageView(
    val id: String,
    val senderId: String,
    val type: String,
    val text: String,
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rule: ScheduleRuleDto,
    val tz: String,
    /** null = 已结束（单次规则已发送）。 */
    val nextFireAt: Long? = null,
    val paused: Boolean,
    val lastFiredAt: Long? = null,
    val createdAt: Long,
)

@Serializable
data class ScheduledListView(val items: List<ScheduledMessageView>)

@Serializable
data class CreateScheduledRequest(
    val type: String = "text",
    val text: String = "",
    /** image：POST /media 得到的 storageKey。 */
    val mediaId: String? = null,
    /** sticker：GET /chat/stickers/search 的 URL；创建时即下载入库。 */
    val stickerUrl: String? = null,
    val rule: ScheduleRuleDto,
    val tz: String? = null,
)

/** PATCH：只改给出的字段。改 rule/tz 或恢复时重算 nextFireAt。 */
@Serializable
data class PatchScheduledRequest(
    val paused: Boolean? = null,
    val text: String? = null,
    val rule: ScheduleRuleDto? = null,
    val tz: String? = null,
)

/** 纯函数：规则校验与下一次触发时间。 */
object ScheduleMath {
    const val DEFAULT_TZ = "Asia/Shanghai"
    private val TIME_RE = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    fun zone(tz: String?): ZoneId = try {
        ZoneId.of(tz?.trim()?.ifEmpty { null } ?: DEFAULT_TZ)
    } catch (e: Exception) {
        throw badRequest("VALIDATION_ERROR", "unknown tz $tz")
    }

    /** 返回规范化后的规则；非法时抛 400。 */
    fun validate(rule: ScheduleRuleDto): ScheduleRuleDto {
        fun time(): String {
            val t = rule.time?.trim() ?: throw badRequest("VALIDATION_ERROR", "rule.time (HH:mm) required")
            if (!TIME_RE.matches(t)) throw badRequest("VALIDATION_ERROR", "rule.time must be HH:mm")
            return t
        }
        return when (rule.kind.trim().uppercase()) {
            "ONCE" -> ScheduleRuleDto("ONCE", at = rule.at ?: throw badRequest("VALIDATION_ERROR", "rule.at required"))
            "DAILY" -> ScheduleRuleDto("DAILY", time = time())
            "WEEKLY" -> {
                val d = rule.dayOfWeek ?: throw badRequest("VALIDATION_ERROR", "rule.dayOfWeek required")
                if (d !in 1..7) throw badRequest("VALIDATION_ERROR", "rule.dayOfWeek must be 1..7")
                ScheduleRuleDto("WEEKLY", time = time(), dayOfWeek = d)
            }
            "YEARLY" -> {
                val m = rule.month ?: throw badRequest("VALIDATION_ERROR", "rule.month required")
                val d = rule.day ?: throw badRequest("VALIDATION_ERROR", "rule.day required")
                if (m !in 1..12) throw badRequest("VALIDATION_ERROR", "rule.month must be 1..12")
                val max = java.time.Month.of(m).maxLength() // Feb → 29
                if (d !in 1..max) throw badRequest("VALIDATION_ERROR", "rule.day out of range for month $m")
                ScheduleRuleDto("YEARLY", time = time(), month = m, day = d)
            }
            else -> throw badRequest("VALIDATION_ERROR", "rule.kind must be ONCE, DAILY, WEEKLY or YEARLY")
        }
    }

    /** 严格晚于 [afterMs] 的下一次触发（epoch ms）；ONCE 已过则为 null。 */
    fun next(rule: ScheduleRuleDto, zone: ZoneId, afterMs: Long): Long? {
        val after = Instant.ofEpochMilli(afterMs)
        val today = after.atZone(zone).toLocalDate()
        fun at(date: LocalDate): Long {
            val t = LocalTime.parse(rule.time!!)
            return ZonedDateTime.of(date, t, zone).toInstant().toEpochMilli() // DST 缺口自动顺延
        }
        return when (rule.kind) {
            "ONCE" -> rule.at!!.takeIf { it > afterMs }
            "DAILY" -> (0L..2L).map { at(today.plusDays(it)) }.first { it > afterMs }
            "WEEKLY" -> {
                val first = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(rule.dayOfWeek!!)))
                (0L..2L).map { at(first.plusWeeks(it)) }.first { it > afterMs }
            }
            "YEARLY" -> (0..8).map { off ->
                val y = today.year + off
                val d = if (rule.month == 2 && rule.day == 29 && !Year.isLeap(y.toLong())) 28 else rule.day!!
                at(LocalDate.of(y, rule.month!!, d))
            }.first { it > afterMs }
            else -> null
        }
    }
}

/**
 * 定时消息：规则 CRUD（只有发送者本人可见、可管理）+ 30 s tick。
 * tick 用 `UPDATE … WHERE next_fire_at = 旧值` 认领每一行，认领与插入消息在同一事务里，多实例 / 重启都只发一次；
 * 停机错过的触发只补发一次，然后从“现在”往后排下一次。
 */
class ScheduledService(
    private val db: Db,
    private val config: AppConfig,
    private val chat: ChatService,
    private val media: com.qimingnan.couple.backend.media.MediaService?,
    private val stickers: StickerService?,
) {
    private val log = LoggerFactory.getLogger(ScheduledService::class.java)

    suspend fun list(caller: AuthUser): ScheduledListView = db.tx {
        ScheduledListView(
            ScheduledMessages.selectAll().where { ScheduledMessages.senderId eq caller.userId }
                .orderBy(ScheduledMessages.createdAt to SortOrder.DESC).map(::toView),
        )
    }

    suspend fun create(caller: AuthUser, req: CreateScheduledRequest): ScheduledMessageView {
        val coupleId = db.tx { requirePairedCouple(caller.userId) }
        val rule = ScheduleMath.validate(req.rule)
        val zone = ScheduleMath.zone(req.tz)
        val now = config.clock()
        val next = ScheduleMath.next(rule, zone, now)
            ?: throw badRequest("SCHEDULE_IN_PAST", "rule.at must be in the future")
        val type = req.type.trim().ifEmpty { "text" }
        val text = if (type == "text") req.text.trim() else ""
        val id = Ids.newId()
        // sticker：创建时下载入库（网络 I/O 在事务外），触发时不再依赖第三方。
        val stickerMedia: ChatMedia? = when (type) {
            "text" -> {
                if (text.isEmpty() || text.length > 4000) throw badRequest("VALIDATION_ERROR", "text must be 1..4000 chars")
                null
            }
            "image" -> {
                if (req.mediaId.isNullOrBlank()) throw badRequest("VALIDATION_ERROR", "image messages need mediaId")
                null
            }
            "sticker" -> {
                val url = req.stickerUrl?.trim().orEmpty()
                if (url.isEmpty() || url.length > 2048) throw badRequest("VALIDATION_ERROR", "sticker messages need stickerUrl")
                val svc = stickers ?: throw badRequest("VALIDATION_ERROR", "stickers are not available")
                val ms = media ?: throw badRequest("VALIDATION_ERROR", "stickers are not available")
                val fetched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { svc.fetch(url) }
                val v = ms.ingestSticker(coupleId, fetched.mimeType, fetched.bytes)
                ChatMedia(v.storageKey, v.width, v.height)
            }
            else -> throw badRequest("VALIDATION_ERROR", "type must be text, image or sticker")
        }
        return db.tx {
            val cid = requirePairedCouple(caller.userId)
            val count = ScheduledMessages.selectAll().where {
                (ScheduledMessages.senderId eq caller.userId) and ScheduledMessages.nextFireAt.isNotNull()
            }.count()
            if (count >= MAX_ACTIVE_RULES) throw conflict("SCHEDULE_LIMIT", "at most $MAX_ACTIVE_RULES active scheduled messages")
            val attached = when (type) {
                "image" -> {
                    val m = MediaRepo.byKey(req.mediaId!!) ?: throw badRequest("VALIDATION_ERROR", "unknown media ${req.mediaId}")
                    if (m.coupleId != cid || m.purpose != "post") throw badRequest("VALIDATION_ERROR", "media belongs to another couple")
                    if (m.usedByPost != null) throw conflict("MEDIA_ALREADY_USED", "media already used")
                    ChatMedia(m.storageKey, m.width, m.height)
                }
                "sticker" -> stickerMedia
                else -> null
            }
            ScheduledMessages.insert {
                it[ScheduledMessages.id] = id
                it[ScheduledMessages.coupleId] = cid
                it[senderId] = caller.userId
                it[msgType] = type
                it[ScheduledMessages.text] = text
                it[mediaKey] = attached?.key
                it[mediaWidth] = attached?.width
                it[mediaHeight] = attached?.height
                writeRule(it, rule)
                it[tz] = zone.id
                it[nextFireAt] = next
                it[paused] = false
                it[lastFiredAt] = null
                it[createdAt] = now
            }
            // 规则持有媒体（used_by_post = 规则 id），孤儿清理不会删掉它。
            attached?.let { MediaRepo.markUsed(it.key, id) }
            toView(row(id)!!)
        }
    }

    suspend fun patch(caller: AuthUser, id: String, req: PatchScheduledRequest): ScheduledMessageView {
        val now = config.clock()
        return db.tx {
            val r = owned(caller, id)
            val rule = req.rule?.let { ScheduleMath.validate(it) } ?: ruleOf(r)
            val zone = ScheduleMath.zone(req.tz ?: r[ScheduledMessages.tz])
            val finished = r[ScheduledMessages.nextFireAt] == null
            val paused = req.paused ?: r[ScheduledMessages.paused]
            val newText = req.text?.trim()
            if (newText != null) {
                if (r[ScheduledMessages.msgType] != "text") throw badRequest("VALIDATION_ERROR", "only text rules have editable text")
                if (newText.isEmpty() || newText.length > 4000) throw badRequest("VALIDATION_ERROR", "text must be 1..4000 chars")
            }
            val ruleChanged = req.rule != null || req.tz != null
            if (finished && ruleChanged && r[ScheduledMessages.msgType] != "text") {
                // 已发送的单次图片 / 表情包，媒体已交给那条消息，不能再复用
                throw conflict("SCHEDULE_FINISHED", "this one-time message was already sent")
            }
            val resumed = r[ScheduledMessages.paused] && !paused
            if (finished && !ruleChanged && (resumed || req.paused == false)) {
                throw conflict("SCHEDULE_FINISHED", "this one-time message was already sent; edit its time to reuse it")
            }
            val next = if (ruleChanged || resumed) {
                ScheduleMath.next(rule, zone, now) ?: throw badRequest("SCHEDULE_IN_PAST", "rule.at must be in the future")
            } else {
                r[ScheduledMessages.nextFireAt]
            }
            ScheduledMessages.update({ ScheduledMessages.id eq id }) {
                if (newText != null) it[text] = newText
                writeRule(it, rule)
                it[tz] = zone.id
                it[ScheduledMessages.paused] = paused
                it[nextFireAt] = next
            }
            toView(row(id)!!)
        }
    }

    suspend fun delete(caller: AuthUser, id: String) {
        val freed = db.tx {
            val r = owned(caller, id)
            ScheduledMessages.deleteWhere { ScheduledMessages.id eq id }
            // 只删规则自己持有的媒体；已交给消息的（单次规则已发送）不动。
            r[ScheduledMessages.mediaKey]?.takeIf { k -> MediaRepo.byKey(k)?.usedByPost == id }
        }
        freed?.let { k -> runCatching { media?.deleteObject(k) } }
    }

    /** 处理到期规则，返回本次发出的消息数。 */
    suspend fun tick(now: Long = config.clock()): Int {
        val due = db.tx {
            ScheduledMessages.selectAll().where {
                (ScheduledMessages.paused eq false) and ScheduledMessages.nextFireAt.isNotNull() and
                    (ScheduledMessages.nextFireAt lessEq now)
            }.orderBy(ScheduledMessages.nextFireAt to SortOrder.ASC).limit(TICK_BATCH).toList()
        }
        var sent = 0
        for (r in due) {
            val ok = runCatching { fire(r, now) }.onFailure {
                log.warn("scheduled message {} failed: {}", r[ScheduledMessages.id], it.toString())
            }.getOrDefault(false)
            if (ok) sent++
        }
        return sent
    }

    private suspend fun fire(r: ResultRow, now: Long): Boolean {
        val id = r[ScheduledMessages.id]
        val old = r[ScheduledMessages.nextFireAt] ?: return false
        val rule = ruleOf(r)
        val once = rule.kind == "ONCE"
        // 错过的触发只补一次：下一次从 max(now, old) 往后算，不会把停机期间的每一次都补发。
        val next = if (once) null else ScheduleMath.next(rule, ScheduleMath.zone(r[ScheduledMessages.tz]), maxOf(now, old))
        val type = r[ScheduledMessages.msgType]
        val key = r[ScheduledMessages.mediaKey]
        // 单次规则把媒体直接交给消息；重复规则每次复制一份（每条消息独占自己的媒体，撤回只删自己那份）。
        var copied: String? = null
        val attached: ChatMedia? = when {
            key == null -> null
            once -> ChatMedia(key, r[ScheduledMessages.mediaWidth], r[ScheduledMessages.mediaHeight])
            else -> media?.duplicate(key)?.let { copied = it.storageKey; ChatMedia(it.storageKey, it.width, it.height) }
        }
        val mediaMissing = type != "text" && attached == null
        val msgId = Ids.newId()
        val result = db.tx {
            val claimed = ScheduledMessages.update({
                (ScheduledMessages.id eq id) and (ScheduledMessages.nextFireAt eq old) and (ScheduledMessages.paused eq false)
            }) {
                it[nextFireAt] = next
                it[lastFiredAt] = now
            }
            if (claimed != 1) return@tx null // 别的 tick / 实例已认领，或期间被暂停 / 修改
            val cid = r[ScheduledMessages.coupleId]
            val sender = r[ScheduledMessages.senderId]
            val active = Couples.selectAll().where { (Couples.id eq cid) and Couples.deletedAt.isNull() }.any() &&
                Users.selectAll().where { (Users.id eq sender) and (Users.coupleId eq cid) }.any()
            if (!active || mediaMissing) return@tx null // 认领照常推进，避免每 30 s 重试
            chat.insertServerMessage(msgId, cid, sender, type, r[ScheduledMessages.text], attached, now)
        }
        if (result == null) {
            copied?.let { k -> runCatching { media?.deleteObject(k) } }
            if (mediaMissing) log.warn("scheduled message {} skipped: media {} missing", id, key)
            return false
        }
        chat.publishMessage(result.coupleId, result)
        return true
    }

    /** 解绑时调用（事务内）：删除该 couple 的所有规则，释放规则持有的媒体交给孤儿清理。 */
    object Unpair {
        fun deleteForCouple(coupleId: String) {
            val ids = ScheduledMessages.selectAll().where { ScheduledMessages.coupleId eq coupleId }.map { it[ScheduledMessages.id] }
            if (ids.isEmpty()) return
            MediaObjects.update({ MediaObjects.usedByPost inList ids }) { it[usedByPost] = null }
            ScheduledMessages.deleteWhere { ScheduledMessages.coupleId eq coupleId }
        }
    }

    private fun row(id: String): ResultRow? = ScheduledMessages.selectAll().where { ScheduledMessages.id eq id }.firstOrNull()

    private fun owned(caller: AuthUser, id: String): ResultRow {
        val r = row(id) ?: throw notFound("SCHEDULE_NOT_FOUND", "no scheduled message $id")
        if (r[ScheduledMessages.senderId] != caller.userId) throw notFound("SCHEDULE_NOT_FOUND", "no scheduled message $id")
        return r
    }

    private fun requirePairedCouple(userId: String): String {
        val me = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: throw forbidden("NOT_PAIRED", "You are not paired")
        val cid = me[Users.coupleId] ?: throw forbidden("NOT_PAIRED", "You are not paired")
        Couples.selectAll().where { (Couples.id eq cid) and Couples.deletedAt.isNull() }.firstOrNull()
            ?: throw forbidden("NOT_PAIRED", "You are not paired")
        return cid
    }

    private fun writeRule(it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, rule: ScheduleRuleDto) {
        it[ScheduledMessages.ruleKind] = rule.kind
        it[ScheduledMessages.atMs] = rule.at
        it[ScheduledMessages.fireTime] = rule.time
        it[ScheduledMessages.fireDow] = rule.dayOfWeek
        it[ScheduledMessages.fireMonth] = rule.month
        it[ScheduledMessages.fireDay] = rule.day
    }

    private fun ruleOf(r: ResultRow) = ScheduleRuleDto(
        kind = r[ScheduledMessages.ruleKind],
        at = r[ScheduledMessages.atMs],
        time = r[ScheduledMessages.fireTime],
        dayOfWeek = r[ScheduledMessages.fireDow],
        month = r[ScheduledMessages.fireMonth],
        day = r[ScheduledMessages.fireDay],
    )

    private fun toView(r: ResultRow) = ScheduledMessageView(
        id = r[ScheduledMessages.id],
        senderId = r[ScheduledMessages.senderId],
        type = r[ScheduledMessages.msgType],
        text = r[ScheduledMessages.text],
        mediaId = r[ScheduledMessages.mediaKey],
        width = r[ScheduledMessages.mediaWidth],
        height = r[ScheduledMessages.mediaHeight],
        rule = ruleOf(r),
        tz = r[ScheduledMessages.tz],
        nextFireAt = r[ScheduledMessages.nextFireAt],
        paused = r[ScheduledMessages.paused],
        lastFiredAt = r[ScheduledMessages.lastFiredAt],
        createdAt = r[ScheduledMessages.createdAt],
    )

    companion object {
        const val MAX_ACTIVE_RULES = 50L
        const val TICK_BATCH = 100
        const val TICK_MS = 30_000L
    }
}

/** 必须挂在 authenticate(JWT_AUTH) 里。 */
fun Route.scheduledRoutes(service: ScheduledService) {
    get("/chat/scheduled") { call.respond(service.list(call.authUser())) }
    post("/chat/scheduled") { call.respond(service.create(call.authUser(), call.receive<CreateScheduledRequest>())) }
    patch("/chat/scheduled/{id}") {
        call.respond(service.patch(call.authUser(), call.parameters["id"]!!, call.receive<PatchScheduledRequest>()))
    }
    delete("/chat/scheduled/{id}") {
        service.delete(call.authUser(), call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
}
