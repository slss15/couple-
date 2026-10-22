package com.qimingnan.couple.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeTokensTest {
    @Test fun contrastFormulaSanity() {
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF777777, 0xFF777777), 0.001)
    }

    @Test fun lightAndDarkTextContrastAtLeast45() {
        for ((name, p) in listOf("light" to Palettes.Light, "dark" to Palettes.Dark)) {
            p.textPairs().forEach { (pair, c) ->
                val r = Contrast.ratio(c.first, c.second)
                assertTrue("$name $pair 对比度 ${"%.2f".format(r)} < 4.5", r >= Contrast.MIN_TEXT)
            }
        }
    }

    @Test fun lightAndDarkIconContrastAtLeast3() {
        for ((name, p) in listOf("light" to Palettes.Light, "dark" to Palettes.Dark)) {
            p.iconPairs().forEach { (pair, c) ->
                val r = Contrast.ratio(c.first, c.second)
                assertTrue("$name $pair 对比度 ${"%.2f".format(r)} < 3", r >= Contrast.MIN_ICON)
            }
        }
    }

    @Test fun touchTargetAtLeast48dp() {
        assertTrue(CoupleSpacing().minTouchTarget.value >= 48f)
    }

    /**
     * 简易版“禁止硬编码颜色”规则（plan §7 要求的 Detekt 规则，先用单测兜底）：
     * 除 theme/Palette.kt 外，main 源码不得出现 Color(0x…) / Color.Red 等字面量颜色。
     */
    @Test fun noHardcodedColorsOutsidePalette() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("找不到源码目录（工作目录：${File(".").absolutePath}）")
        val pattern = Regex("""Color\(\s*0x|Color\.(Red|Blue|Green|Black|White|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b|Color\(\s*red\s*=|Color\.parseColor|android\.graphics\.Color""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Palette.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (pattern.containsMatchIn(line)) "${f.path}:${i + 1}: ${line.trim()}" else null } }
            .toList()
        assertTrue("发现硬编码颜色：\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
