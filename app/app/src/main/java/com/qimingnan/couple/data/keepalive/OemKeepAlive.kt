package com.qimingnan.couple.data.keepalive

/**
 * Manufacturer detection and the known deep links to each OEM's autostart / battery pages.
 *
 * Pure data (no android.content.Intent) so the selection is unit-testable without Robolectric. [OemIntentLauncher]
 * turns a [SettingsTarget] into an Intent and tries the candidates in order, each in try/catch, falling back to the
 * app's details page. Component names change between ROM versions, which is why there are several per OEM.
 */
enum class Oem(val label: String) {
    XIAOMI("小米 / Redmi（MIUI · HyperOS）"),
    HUAWEI("华为（EMUI · HarmonyOS）"),
    HONOR("荣耀（MagicOS）"),
    OPPO("OPPO（ColorOS）"),
    ONEPLUS("一加（ColorOS · OxygenOS）"),
    REALME("realme（realme UI · ColorOS）"),
    VIVO("vivo / iQOO（OriginOS · Funtouch）"),
    SAMSUNG("三星（One UI）"),
    OTHER("其他品牌"),
}

/** One candidate settings screen. Either an explicit component or an action (optionally restricted to a package). */
data class SettingsTarget(
    val label: String,
    val packageName: String? = null,
    val className: String? = null,
    val action: String? = null,
    /** String extras; the value placeholder [PKG] is replaced with our package name, [LABEL] with the app label. */
    val extras: Map<String, String> = emptyMap(),
    /** Put `package:<our package>` as the intent data. */
    val packageUri: Boolean = false,
) {
    companion object {
        const val PKG = "\${pkg}"
        const val LABEL = "\${label}"
    }
}

enum class OemPage { AUTOSTART, BATTERY }

object OemKeepAlive {
    fun detect(manufacturer: String?, brand: String?): Oem {
        val m = manufacturer.orEmpty().lowercase()
        val b = brand.orEmpty().lowercase()
        fun any(vararg keys: String) = keys.any { m.contains(it) || b.contains(it) }
        return when {
            any("xiaomi", "redmi", "poco", "blackshark") -> Oem.XIAOMI
            any("honor") -> Oem.HONOR // before huawei: older Honor devices report manufacturer=HUAWEI, brand=HONOR
            any("huawei") -> Oem.HUAWEI
            any("oneplus") -> Oem.ONEPLUS
            any("realme") -> Oem.REALME
            any("oppo") -> Oem.OPPO
            any("vivo", "iqoo") -> Oem.VIVO
            any("samsung") -> Oem.SAMSUNG
            else -> Oem.OTHER
        }
    }

    /** Candidates for [page] on [oem], best first. Empty for [Oem.OTHER] (the caller falls back to app details). */
    fun targets(oem: Oem, page: OemPage): List<SettingsTarget> = when (page) {
        OemPage.AUTOSTART -> autostart(oem)
        OemPage.BATTERY -> battery(oem)
    }

    private fun c(label: String, pkg: String, cls: String, extras: Map<String, String> = emptyMap()) =
        SettingsTarget(label, packageName = pkg, className = cls, extras = extras)

    private val coloros = listOf(
        c("自启动管理", "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        c("自启动管理", "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        c("自启动管理", "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    )

    private fun autostart(oem: Oem): List<SettingsTarget> = when (oem) {
        Oem.XIAOMI -> listOf(
            c("自启动管理", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        Oem.HUAWEI -> listOf(
            c("应用启动管理", "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            c("应用启动管理", "com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            c("受保护应用", "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Oem.HONOR -> listOf(
            c("应用启动管理", "com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            c("应用启动管理", "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            c("应用启动管理", "com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        )
        Oem.OPPO, Oem.REALME -> coloros
        Oem.ONEPLUS -> coloros + c("自启动管理", "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        Oem.VIVO -> listOf(
            c("后台启动管理", "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            c("后台启动管理", "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            c("后台启动管理", "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        Oem.SAMSUNG -> listOf(
            c("自动运行应用", "com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity"),
        )
        Oem.OTHER -> emptyList()
    }

    private fun battery(oem: Oem): List<SettingsTarget> = when (oem) {
        Oem.XIAOMI -> listOf(
            SettingsTarget(
                "省电策略",
                packageName = "com.miui.powerkeeper",
                className = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                action = "miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY",
                extras = mapOf("package_name" to SettingsTarget.PKG, "package_label" to SettingsTarget.LABEL),
            ),
            SettingsTarget(
                "省电策略",
                packageName = "com.miui.powerkeeper",
                action = "miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY",
                extras = mapOf("package_name" to SettingsTarget.PKG, "package_label" to SettingsTarget.LABEL),
            ),
        )
        Oem.HUAWEI -> listOf(
            c("电池 · 应用启动管理", "com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
        )
        Oem.HONOR -> listOf(
            c("电池", "com.hihonor.systemmanager", "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity"),
        )
        Oem.OPPO, Oem.ONEPLUS, Oem.REALME -> listOf(
            c("耗电保护", "com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            c("耗电保护", "com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"),
            c("电池", "com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
        )
        Oem.VIVO -> listOf(
            c("后台高耗电", "com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
            c("后台耗电管理", "com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
        )
        Oem.SAMSUNG -> listOf(
            c("电池", "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            c("电池", "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            c("电池", "com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Oem.OTHER -> emptyList()
    }

    /** Short, OEM-specific hint shown under the buttons. */
    fun hint(oem: Oem): String = when (oem) {
        Oem.XIAOMI -> "打开「自启动」，省电策略选「无限制」；在最近任务里下拉卡片加锁。"
        Oem.HUAWEI, Oem.HONOR -> "应用启动管理里关闭「自动管理」，并打开自启动、关联启动、后台活动三项。"
        Oem.OPPO, Oem.ONEPLUS, Oem.REALME -> "允许自启动；耗电保护里允许后台运行（关闭「后台冻结」「异常耗电自动优化」）。"
        Oem.VIVO -> "允许后台启动，后台高耗电里允许本应用。"
        Oem.SAMSUNG -> "电池 → 后台使用限制：把本应用加入「永不休眠的应用」，并关闭「使未使用的应用进入休眠」。"
        Oem.OTHER -> "在系统设置里允许本应用自启动、后台运行，并关闭电池优化。"
    }
}

/** Commands run through Shizuku (shell uid) to exempt the app from Doze and background-run restrictions. */
object ShizukuKeepAliveCommands {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun commands(packageName: String): List<String> {
        // The package name goes into a shell command line: accept only a real package name.
        require(PACKAGE.matches(packageName)) { "invalid package name" }
        return listOf(
            "dumpsys deviceidle whitelist +$packageName",
            "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow",
        )
    }
}
