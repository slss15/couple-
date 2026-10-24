package com.qimingnan.couple.keepalive

import com.qimingnan.couple.data.keepalive.Oem
import com.qimingnan.couple.data.keepalive.OemKeepAlive
import com.qimingnan.couple.data.keepalive.OemPage
import com.qimingnan.couple.data.keepalive.SettingsTarget
import com.qimingnan.couple.data.keepalive.ShizukuKeepAliveCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OemKeepAliveTest {
    @Test fun detectsManufacturers() {
        val cases = mapOf(
            ("Xiaomi" to "Redmi") to Oem.XIAOMI,
            ("Xiaomi" to "POCO") to Oem.XIAOMI,
            ("HUAWEI" to "HUAWEI") to Oem.HUAWEI,
            ("HUAWEI" to "HONOR") to Oem.HONOR, // pre-split Honor devices
            ("HONOR" to "HONOR") to Oem.HONOR,
            ("OPPO" to "OPPO") to Oem.OPPO,
            ("OnePlus" to "OnePlus") to Oem.ONEPLUS,
            ("realme" to "realme") to Oem.REALME,
            ("vivo" to "vivo") to Oem.VIVO,
            ("vivo" to "iQOO") to Oem.VIVO,
            ("samsung" to "samsung") to Oem.SAMSUNG,
            ("Google" to "google") to Oem.OTHER,
            (null to null) to Oem.OTHER,
        )
        cases.forEach { (mb, expected) -> assertEquals("$mb", expected, OemKeepAlive.detect(mb.first, mb.second)) }
    }

    @Test fun knownAutostartComponentsComeFirst() {
        assertEquals(
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
            OemKeepAlive.targets(Oem.XIAOMI, OemPage.AUTOSTART).first().className,
        )
        assertEquals("com.huawei.systemmanager", OemKeepAlive.targets(Oem.HUAWEI, OemPage.AUTOSTART).first().packageName)
        assertEquals("com.hihonor.systemmanager", OemKeepAlive.targets(Oem.HONOR, OemPage.AUTOSTART).first().packageName)
        assertEquals("com.coloros.safecenter", OemKeepAlive.targets(Oem.OPPO, OemPage.AUTOSTART).first().packageName)
        assertEquals("com.coloros.safecenter", OemKeepAlive.targets(Oem.REALME, OemPage.AUTOSTART).first().packageName)
        assertTrue(OemKeepAlive.targets(Oem.ONEPLUS, OemPage.AUTOSTART).any { it.packageName == "com.oneplus.security" })
        assertEquals("com.vivo.permissionmanager", OemKeepAlive.targets(Oem.VIVO, OemPage.AUTOSTART).first().packageName)
        assertTrue(OemKeepAlive.targets(Oem.SAMSUNG, OemPage.BATTERY).first().packageName!!.startsWith("com.samsung.android"))
    }

    @Test fun xiaomiBatteryPassesOurPackage() {
        val t = OemKeepAlive.targets(Oem.XIAOMI, OemPage.BATTERY).first()
        assertEquals("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY", t.action)
        assertEquals(SettingsTarget.PKG, t.extras["package_name"])
    }

    @Test fun everyKnownOemHasBothPagesAndOtherFallsBack() {
        Oem.entries.filter { it != Oem.OTHER }.forEach { oem ->
            OemPage.entries.forEach { page ->
                val targets = OemKeepAlive.targets(oem, page)
                assertTrue("$oem $page", targets.isNotEmpty())
                targets.forEach { assertTrue("$oem $page needs a package", !it.packageName.isNullOrBlank()) }
            }
            assertTrue(OemKeepAlive.hint(oem).isNotBlank())
        }
        OemPage.entries.forEach { assertTrue(OemKeepAlive.targets(Oem.OTHER, it).isEmpty()) } // -> app details
    }

    @Test fun shizukuCommands() {
        assertEquals(
            listOf(
                "dumpsys deviceidle whitelist +com.qimingnan.couple.debug",
                "cmd appops set com.qimingnan.couple.debug RUN_ANY_IN_BACKGROUND allow",
            ),
            ShizukuKeepAliveCommands.commands("com.qimingnan.couple.debug"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun shizukuRejectsShellInjection() {
        ShizukuKeepAliveCommands.commands("com.x; reboot")
    }
}
