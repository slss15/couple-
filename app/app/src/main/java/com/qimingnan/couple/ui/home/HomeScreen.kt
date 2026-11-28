package com.qimingnan.couple.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.data.anniversary.AnniversaryCalendar
import com.qimingnan.couple.data.anniversary.AnniversaryEvent
import com.qimingnan.couple.data.anniversary.AnniversaryLogic
import com.qimingnan.couple.data.ledger.LedgerEntryType
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.anniversary.AnniversaryViewModel
import com.qimingnan.couple.ui.components.LedgerEntryRow
import com.qimingnan.couple.ui.ledger.LedgerViewModel
import com.qimingnan.couple.ui.me.MeViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 首页（calm 日常版）：打开 App 的第一屏，本身就是一个底栏 Tab。
 *
 * 一屏只讲三件事：在一起多久、现在能做什么、这个月过得怎样。
 * 视觉参照 docs/ui-demo/calm.html：日落插画大卡 + 快捷入口 + 「记一笔」主按钮 +
 * 纪念日倒数 + 本月收支 + 最近账目。颜色全部走 LocalCoupleTokens，不写死色值。
 */
@Composable
fun HomeScreen(
    anniversaryVm: AnniversaryViewModel,
    ledgerVm: LedgerViewModel,
    onRecord: () -> Unit,
    onOpenTa: () -> Unit,
    onOpenAnniversary: () -> Unit,
    onOpenPeriod: () -> Unit,
    onOpenLedger: () -> Unit,
    onOpenFeed: () -> Unit,
    modifier: Modifier = Modifier,
    meVm: MeViewModel = hiltViewModel(),
) {
    val t = LocalCoupleTokens.current
    // 首页是"打开就放着不动"的页面：today 只在一次组合里求值的话，跨过零点后
    // 日期标题、在一起天数、纪念日倒计时、本月收支会一直停在前一天。
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            val now = LocalDate.now()
            if (now != today) today = now
        }
    }
    val meState by meVm.state.collectAsStateWithLifecycle()
    val events by anniversaryVm.events.collectAsStateWithLifecycle()
    val entries by ledgerVm.entries.collectAsStateWithLifecycle()
    val allAccounts by ledgerVm.accounts.collectAsStateWithLifecycle()
    val totals by ledgerVm.monthTotals.collectAsStateWithLifecycle()
    val accounts = allAccounts.filterNot { it.type.isLoan }
    val accountById = accounts.associateBy { it.id }

    LaunchedEffect(Unit) { meVm.refresh() }

    val partner = meState.partner
    val daysTogether = meState.couple?.let { couple ->
        val start = Instant.ofEpochMilli(couple.pairedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        AnniversaryLogic.daysTogether(start, today).coerceAtLeast(0)
    }
    val liveEvents = events.filter { !it.deleted }
    val nextEvent = liveEvents
        .mapNotNull { event -> AnniversaryLogic.nextOccurrence(event, today)?.let { event to it } }
        .minByOrNull { (_, occurrence) -> occurrence.date }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = t.spacing.lg)
            // 底栏浮在内容上，末尾留出它的高度，否则最后一项被压在栏下面。
            .padding(bottom = com.qimingnan.couple.ui.components.LocalBottomBarInset.current),
    ) {
        HomeTopBar(
            greeting = meState.me?.displayName?.takeIf { it.isNotBlank() }?.let { "早安，" + it } ?: "早安",
            avatarGlyph = meState.me?.displayName?.firstOrNull()?.toString() ?: "我",
            dateLabel = today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)),
        )
        HeroCard(
            days = daysTogether,
            partnerName = partner?.displayName,
            partnerOnline = meState.partnerPresence?.online == true,
        )
        QuickRow(
            onOpenTa = onOpenTa,
            onOpenAnniversary = onOpenAnniversary,
            onOpenPeriod = onOpenPeriod,
        )
        RecordCallToAction(onRecord)
        NextAnniversaryCard(
            event = nextEvent?.first,
            occurrence = nextEvent?.second?.date,
            daysRemaining = nextEvent?.second?.let { AnniversaryLogic.daysUntil(today, it) },
            onOpen = onOpenAnniversary,
        )
        MonthSection(
            netCents = totals.netCents,
            incomeCents = totals.incomeCents,
            expenseCents = totals.expenseCents,
            onOpenLedger = onOpenLedger,
        )
        RecentSection(
            entries = entries,
            accountById = accountById,
            onOpenFeed = onOpenFeed,
            onOpenLedger = onOpenLedger,
        )
        Box(Modifier.height(t.spacing.xxl))
    }
}

@Composable
private fun HomeTopBar(greeting: String, avatarGlyph: String, dateLabel: String) {
    val t = LocalCoupleTokens.current
    Row(
        Modifier.fillMaxWidth().padding(top = t.spacing.md, bottom = t.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                dateLabel,
                style = t.typography.caption.copy(fontSize = 10.sp, letterSpacing = 1.sp),
                color = t.colors.onSurfaceVariant,
            )
            Text(greeting, style = t.typography.headline, color = t.colors.onSurface)
        }
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(t.colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(avatarGlyph, style = t.typography.title, color = t.colors.onPrimaryContainer)
        }
    }
}

/** 日落插画大卡：插画用 Canvas 现画，跟着主题色走，深浅色都成立。 */
@Composable
private fun HeroCard(days: Long?, partnerName: String?, partnerOnline: Boolean) {
    val t = LocalCoupleTokens.current
    Box(Modifier.fillMaxWidth().height(228.dp).clip(t.shapes.large)) {
        SunsetArt(Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, t.colors.heroScrim.copy(alpha = 0.62f)),
                ),
            ),
        )
        Surface(
            // 22% 的压暗在浅色插画上只剩 1.6:1，白字基本糊掉；深色下反而正常。
            // 提到 0.55 让胶囊在任何主题下都是「实心小标签」。
            color = t.colors.heroScrim.copy(alpha = 0.55f),
            contentColor = t.colors.onHero,
            shape = t.shapes.pill,
            modifier = Modifier.align(Alignment.TopEnd).padding(t.spacing.md),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(if (partnerOnline) t.colors.success else t.colors.onHero.copy(alpha = 0.5f)),
                )
                Text(
                    when {
                        partnerName == null -> "还没有配对"
                        partnerOnline -> partnerName + " 在线"
                        else -> partnerName + " 离线"
                    },
                    style = t.typography.caption.copy(fontSize = 10.sp),
                    color = t.colors.onHero,
                )
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(t.spacing.lg)) {
            Text(
                "TOGETHER",
                style = t.typography.caption.copy(fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold),
                color = t.colors.onHero.copy(alpha = 0.75f),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    days?.toString() ?: "—",
                    style = t.typography.display.copy(
                        fontSize = 62.sp,
                        lineHeight = 62.sp,
                        letterSpacing = (-3).sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = t.colors.onHero,
                )
                if (days != null) {
                    Text(
                        "天",
                        style = t.typography.body.copy(fontSize = 13.sp),
                        color = t.colors.onHero.copy(alpha = 0.85f),
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                    )
                }
            }
            Text(
                if (days == null) "配对后开始倒数在一起的日子。" else "今天也在一起。",
                style = t.typography.caption,
                color = t.colors.onHero.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun SunsetArt(modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(t.colors.primaryContainer, t.colors.surfaceContainer)))
        drawCircle(
            color = t.colors.onHero.copy(alpha = 0.78f),
            radius = h * 0.17f,
            center = Offset(w * 0.74f, h * 0.28f),
        )
        val far = Path().apply {
            moveTo(0f, h * 0.66f)
            cubicTo(w * 0.22f, h * 0.54f, w * 0.40f, h * 0.70f, w * 0.62f, h * 0.62f)
            cubicTo(w * 0.80f, h * 0.56f, w * 0.90f, h * 0.66f, w, h * 0.60f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(far, t.colors.secondary.copy(alpha = 0.45f))
        val near = Path().apply {
            moveTo(0f, h * 0.80f)
            cubicTo(w * 0.28f, h * 0.72f, w * 0.52f, h * 0.86f, w * 0.74f, h * 0.78f)
            cubicTo(w * 0.86f, h * 0.74f, w * 0.94f, h * 0.80f, w, h * 0.77f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(near, t.colors.secondary.copy(alpha = 0.85f))

        // 两个人影：一大一小，靠在一起。
        val figure = t.colors.heroScrim.copy(alpha = 0.9f)
        val people = listOf(
            Triple(w * 0.42f, h * 0.69f, h * 0.048f),
            Triple(w * 0.52f, h * 0.675f, h * 0.042f),
        )
        people.forEach { (cx, headY, headR) ->
            drawCircle(figure, radius = headR, center = Offset(cx, headY))
            drawRoundRect(
                color = figure,
                topLeft = Offset(cx - headR * 1.15f, headY + headR * 0.9f),
                size = Size(headR * 2.3f, h * 0.93f - headY),
                cornerRadius = CornerRadius(headR * 1.15f),
            )
        }
    }
}

@Composable
private fun QuickRow(onOpenTa: () -> Unit, onOpenAnniversary: () -> Unit, onOpenPeriod: () -> Unit) {
    val t = LocalCoupleTokens.current
    Row(
        Modifier.fillMaxWidth().padding(top = t.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        QuickEntry("TA", Icons.Filled.Favorite, onOpenTa, Modifier.weight(1f))
        QuickEntry("纪念日", Icons.Filled.DateRange, onOpenAnniversary, Modifier.weight(1f))
        QuickEntry("经期", Icons.Filled.Star, onOpenPeriod, Modifier.weight(1f))
    }
}

@Composable
private fun QuickEntry(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = t.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(t.colors.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = t.colors.primary, modifier = Modifier.size(20.dp))
        }
        Text(label, style = t.typography.caption, color = t.colors.onSurfaceVariant)
    }
}

@Composable
private fun RecordCallToAction(onRecord: () -> Unit) {
    val t = LocalCoupleTokens.current
    Surface(
        color = t.colors.primary,
        contentColor = t.colors.onPrimary,
        shape = t.shapes.large,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onRecord),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(t.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .border(1.dp, t.colors.onPrimary.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("＋", style = t.typography.title, color = t.colors.onPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text("记一笔", style = t.typography.title, color = t.colors.onPrimary)
                Text(
                    "金额 → 分类 → 账户",
                    style = t.typography.caption,
                    color = t.colors.onPrimary.copy(alpha = 0.72f),
                )
            }
            Text("→", style = t.typography.title, color = t.colors.onPrimary.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun NextAnniversaryCard(
    event: AnniversaryEvent?,
    occurrence: LocalDate?,
    daysRemaining: Long?,
    onOpen: () -> Unit,
) {
    val t = LocalCoupleTokens.current
    SectionHeader("下一个纪念日", "全部", onOpen)
    Surface(
        color = t.colors.surfaceContainer,
        contentColor = t.colors.onSurface,
        shape = t.shapes.large,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        if (event == null || occurrence == null || daysRemaining == null) {
            Text(
                "还没有纪念日。加一个，这里就开始倒数。",
                style = t.typography.body,
                color = t.colors.onSurfaceVariant,
                modifier = Modifier.padding(t.spacing.lg),
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(t.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
            ) {
                Column(
                    Modifier.width(46.dp).height(52.dp).clip(t.shapes.medium)
                        .background(t.colors.primaryContainer),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        occurrence.dayOfMonth.toString().padStart(2, '0'),
                        style = t.typography.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Normal),
                        color = t.colors.onPrimaryContainer,
                    )
                    Text(
                        occurrence.monthValue.toString() + "月",
                        style = t.typography.caption.copy(fontSize = 9.sp),
                        color = t.colors.onPrimaryContainer,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(event.title, style = t.typography.title, color = t.colors.onSurface)
                    Text(
                        if (event.calendar == AnniversaryCalendar.LUNAR) {
                            "阴历 " + event.month + "月" + event.day + "日"
                        } else {
                            "阳历 · 每年"
                        },
                        style = t.typography.caption,
                        color = t.colors.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        daysRemaining.toString(),
                        style = t.typography.display.copy(
                            fontSize = 24.sp,
                            lineHeight = 24.sp,
                            letterSpacing = (-1).sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        color = t.colors.primary,
                    )
                    Text(
                        "天",
                        style = t.typography.caption,
                        color = t.colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthSection(netCents: Long, incomeCents: Long, expenseCents: Long, onOpenLedger: () -> Unit) {
    val t = LocalCoupleTokens.current
    SectionHeader("本月", "账本", onOpenLedger)
    Surface(
        color = t.colors.surfaceContainer,
        contentColor = t.colors.onSurface,
        shape = t.shapes.large,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLedger),
    ) {
        Row(Modifier.fillMaxWidth().padding(t.spacing.lg)) {
            MonthCell("结余", money(netCents), Modifier.weight(1.2f), highlight = true)
            MonthCell("收入", money(incomeCents), Modifier.weight(1f))
            MonthCell("支出", money(expenseCents), Modifier.weight(1f))
        }
    }
}

@Composable
private fun MonthCell(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val t = LocalCoupleTokens.current
    Column(modifier) {
        Text(label, style = t.typography.caption, color = t.colors.onSurfaceVariant)
        Text(
            value,
            style = t.typography.display.copy(
                fontSize = if (highlight) 20.sp else 17.sp,
                lineHeight = 24.sp,
                letterSpacing = (-0.5).sp,
                fontWeight = FontWeight.Normal,
            ),
            color = if (highlight) t.colors.primary else t.colors.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun RecentSection(
    entries: List<com.qimingnan.couple.data.ledger.LedgerEntry>,
    accountById: Map<String, com.qimingnan.couple.data.ledger.LedgerAccount>,
    onOpenFeed: () -> Unit,
    onOpenLedger: () -> Unit,
) {
    val t = LocalCoupleTokens.current
    SectionHeader("最近", "动态", onOpenFeed)
    Surface(
        color = t.colors.surfaceContainer,
        contentColor = t.colors.onSurface,
        shape = t.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = t.spacing.lg, vertical = t.spacing.sm)) {
            val recent = remember(entries) { entries.sortedByDescending { it.occurredOn }.take(3) }
            if (recent.isEmpty()) {
                Text(
                    "还没有账目，点「记一笔」记第一笔。",
                    style = t.typography.body,
                    color = t.colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = t.spacing.md),
                )
            }
            recent.forEach { entry ->
                LedgerEntryRow(
                    glyph = when (entry.type) {
                        LedgerEntryType.TRANSFER -> "转"
                        else -> (entry.categoryId ?: "其").firstOrNull()?.toString() ?: "其"
                    },
                    title = when (entry.type) {
                        LedgerEntryType.EXPENSE -> "支出 · " + (entry.categoryId ?: "已删除分类")
                        LedgerEntryType.INCOME -> "收入 · " + (entry.categoryId ?: "已删除分类")
                        LedgerEntryType.TRANSFER -> "转账"
                    },
                    subtitle = when (entry.type) {
                        LedgerEntryType.TRANSFER ->
                            (accountById[entry.fromAccountId]?.name ?: "?") + " → " +
                                (accountById[entry.toAccountId]?.name ?: "?") + " · " + entry.occurredOn
                        else -> (accountById[entry.accountId]?.name ?: "") + " · " + entry.occurredOn
                    },
                    amountText = when (entry.type) {
                        LedgerEntryType.EXPENSE -> "-" + money(entry.amountCents)
                        LedgerEntryType.INCOME -> "+" + money(entry.amountCents)
                        LedgerEntryType.TRANSFER -> money(entry.amountCents)
                    },
                    note = entry.note,
                    onClick = onOpenLedger,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    val t = LocalCoupleTokens.current
    Row(
        Modifier.fillMaxWidth().padding(top = t.spacing.xl, bottom = t.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = t.typography.title, color = t.colors.onSurface, modifier = Modifier.weight(1f))
        Text(
            action,
            style = t.typography.caption,
            color = t.colors.primary,
            textAlign = TextAlign.End,
            modifier = Modifier.clickable(onClick = onAction).padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
        )
    }
}

/** ¥ 千分位金额，分转元。 */
private fun money(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    val yuan = abs / 100
    val fen = (abs % 100).toString().padStart(2, '0')
    val grouped = yuan.toString().reversed().chunked(3).joinToString(",").reversed()
    return sign + "¥" + grouped + "." + fen
}
