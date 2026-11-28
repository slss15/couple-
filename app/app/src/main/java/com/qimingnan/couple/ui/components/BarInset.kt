package com.qimingnan.couple.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底栏浮在内容之上（为了让它后面的内容被真实模糊）之后，可滚动页面末尾要自己留出的高度。
 *
 * 为什么不用 Scaffold 的 innerPadding：那样内容会被顶到栏上面，栏后面永远是纯背景色，
 * 模糊出来就是一整块同色 —— 玻璃看起来就是"假的"。留出高度的是**内容末尾的空白**，
 * 不是视口，所以最后一项仍然能滚到栏上方，而滚动过程中内容会从栏下面穿过去。
 */
val LocalBottomBarInset: androidx.compose.runtime.ProvidableCompositionLocal<Dp> = staticCompositionLocalOf { 0.dp }
