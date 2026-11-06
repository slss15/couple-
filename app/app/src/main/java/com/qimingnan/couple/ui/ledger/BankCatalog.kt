package com.qimingnan.couple.ui.ledger

import com.qimingnan.couple.R

/**
 * 银行图标：来自 bank-icon-cn（Ping++ 官方银行图标库，SVG 转成的 VectorDrawable，
 * 24×24）。只收了常见的 21 家个人账户银行；深圳发展银行已并入平安，不收；
 * 人民银行、农业发展银行不是个人账户银行，不收。
 *
 * 建账户时：输账户名自动按名字里的银行关键词挑银行（比如输“工商银行工资卡”
 * 就自动认成工商银行），也可以手动从下拉里选；手动选过之后，改名字不再自动改。
 */
data class BankInfo(
    val code: String,
    val name: String,
    val keywords: List<String>,
    val drawableRes: Int,
)

object BankCatalog {
    val BANKS = listOf(
        BankInfo("icbc", "工商银行", listOf("工商银行", "工行", "icbc"), R.drawable.bank_icbc),
        BankInfo("abc", "农业银行", listOf("农业银行", "农行", "abc"), R.drawable.bank_abc),
        BankInfo("boc", "中国银行", listOf("中国银行", "中行", "boc"), R.drawable.bank_boc),
        BankInfo("ccb", "建设银行", listOf("建设银行", "建行", "ccb"), R.drawable.bank_ccb),
        BankInfo("bocm", "交通银行", listOf("交通银行", "交行"), R.drawable.bank_bocm),
        BankInfo("cmb", "招商银行", listOf("招商银行", "招行", "cmb"), R.drawable.bank_cmb),
        BankInfo("spdb", "浦发银行", listOf("浦东发展银行", "浦发银行", "浦发", "浦东发展", "spdb"), R.drawable.bank_spdb),
        BankInfo("citic", "中信银行", listOf("中信银行", "中信"), R.drawable.bank_citic),
        BankInfo("ceb", "光大银行", listOf("光大银行", "光大"), R.drawable.bank_ceb),
        BankInfo("cmbc", "民生银行", listOf("民生银行", "民生"), R.drawable.bank_cmbc),
        BankInfo("cib", "兴业银行", listOf("兴业银行", "兴业"), R.drawable.bank_cib),
        BankInfo("gdb", "广发银行", listOf("广发银行", "广发"), R.drawable.bank_gdb),
        BankInfo("pab", "平安银行", listOf("平安银行", "平安"), R.drawable.bank_pab),
        BankInfo("hxb", "华夏银行", listOf("华夏银行", "华夏"), R.drawable.bank_hxb),
        BankInfo("bob", "北京银行", listOf("北京银行"), R.drawable.bank_bob),
        BankInfo("bos", "上海银行", listOf("上海银行"), R.drawable.bank_bos),
        BankInfo("czb", "浙商银行", listOf("浙商银行", "浙商"), R.drawable.bank_czb),
        BankInfo("psbc", "邮储银行", listOf("邮政储蓄", "中国邮政", "邮储", "邮政"), R.drawable.bank_psbc),
        BankInfo("jlb", "吉林银行", listOf("吉林银行", "吉林"), R.drawable.bank_jlb),
        BankInfo("dlb", "大连银行", listOf("大连银行", "大连"), R.drawable.bank_dlb),
        BankInfo("grcb", "广州农信", listOf("广州市农村信用合作社", "广州农信", "广州农村信用社"), R.drawable.bank_grcb),
    )

    fun byCode(code: String?): BankInfo? = BANKS.firstOrNull { it.code == code }

    /**
     * 按账户名自动认银行：名字里出现哪家银行的关键词就认哪家。
     * 关键词都是全名或常用简称，认不出来返回 null（不断言、不猜）。
     */
    fun detectBank(name: String): BankInfo? {
        val lower = name.lowercase()
        return BANKS.firstOrNull { bank -> bank.keywords.any { kw -> lower.contains(kw.lowercase()) } }
    }
}
