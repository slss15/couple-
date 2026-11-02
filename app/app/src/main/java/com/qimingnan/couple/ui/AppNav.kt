package com.qimingnan.couple.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.qimingnan.couple.R
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.update.UpdateDialogHost
import com.qimingnan.couple.ui.auth.AuthScreen
import com.qimingnan.couple.ui.bootstrap.BootstrapScreen
import com.qimingnan.couple.ui.components.BannerKind
import com.qimingnan.couple.ui.components.FullScreenLoading
import com.qimingnan.couple.ui.components.PrimaryButton
import com.qimingnan.couple.ui.components.StatusBanner
import com.qimingnan.couple.ui.keepalive.KeepAliveScreen
import com.qimingnan.couple.ui.main.MainShell
import com.qimingnan.couple.ui.pairing.PairingScreen

object Routes {
    const val LOADING = "loading"
    const val BOOTSTRAP = "bootstrap"
    const val AUTH = "auth"
    const val PAIRING = "pairing"
    const val MAIN = "main"
    const val ERROR = "error"
    /** 从“我的 / 登录页”进入的重新扫码，可返回 */
    const val RESCAN = "rescan"
    /** 我的 → 后台保活（只在主界面内可达，返回主界面） */
    const val KEEPALIVE = "keepalive"
}

private fun RootDestination.route(): String = when (this) {
    RootDestination.Loading -> Routes.LOADING
    RootDestination.Bootstrap -> Routes.BOOTSTRAP
    RootDestination.Auth -> Routes.AUTH
    RootDestination.Pairing -> Routes.PAIRING
    RootDestination.Main -> Routes.MAIN
    is RootDestination.Error -> Routes.ERROR
}

@Composable
fun CoupleRoot(deepLinkUri: String?, onDeepLinkConsumed: () -> Unit, vm: RootViewModel = hiltViewModel()) {
    val t = LocalCoupleTokens.current
    val nav = rememberNavController()
    val dest by vm.destination.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    LaunchedEffect(dest) {
        val route = dest.route()
        val current = nav.currentDestination?.route
        val stay = current == Routes.RESCAN || (current == Routes.KEEPALIVE && route == Routes.MAIN)
        if (current != route && !stay) nav.navigateRoot(route)
    }
    // 深链（系统相机扫码打开）总是进入可返回的扫码页
    LaunchedEffect(deepLinkUri) {
        if (deepLinkUri != null && dest !is RootDestination.Bootstrap) nav.navigate(Routes.RESCAN) { launchSingleTop = true }
    }

    Scaffold(
        containerColor = t.colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(nav, startDestination = Routes.LOADING, modifier = Modifier.padding(padding)) {
            composable(Routes.LOADING) { FullScreenLoading() }
            composable(Routes.BOOTSTRAP) {
                BootstrapScreen(initialUri = deepLinkUri, onDone = onDeepLinkConsumed, onBack = null)
            }
            composable(Routes.RESCAN) {
                BootstrapScreen(
                    initialUri = deepLinkUri,
                    onDone = { onDeepLinkConsumed(); nav.navigateRoot(vm.destination.value.route()) },
                    onBack = { onDeepLinkConsumed(); nav.navigateRoot(vm.destination.value.route()) },
                )
            }
            composable(Routes.AUTH) { AuthScreen(onChangeServer = { nav.navigate(Routes.RESCAN) }) }
            composable(Routes.PAIRING) { PairingScreen() }
            composable(Routes.MAIN) {
                MainShell(
                    onRescan = { nav.navigate(Routes.RESCAN) },
                    onOpenKeepAlive = { nav.navigate(Routes.KEEPALIVE) { launchSingleTop = true } },
                )
            }
            composable(Routes.KEEPALIVE) { KeepAliveScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ERROR) {
                Column(
                    Modifier.fillMaxSize().background(t.colors.background).padding(t.spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(t.spacing.lg, Alignment.CenterVertically),
                ) {
                    StatusBanner((dest as? RootDestination.Error)?.message ?: "", BannerKind.Error)
                    PrimaryButton(stringResource(R.string.retry), onClick = vm::retry)
                }
            }
        }
        // App self-update prompt (blocking when forced), above every screen.
        UpdateDialogHost()
        // 对方发起的解绑申请（MQTT unpair_requested 推送）。
        val unpair by vm.unpairPrompt.collectAsStateWithLifecycle()
        unpair?.let { p ->
            AlertDialog(
                onDismissRequest = vm::dismissUnpairPrompt,
                title = { Text(stringResource(R.string.unpair_prompt_title)) },
                text = { Text(stringResource(R.string.unpair_prompt_body)) },
                confirmButton = {
                    TextButton(onClick = { vm.confirmUnpair(p.requestId) }) {
                        Text(stringResource(R.string.me_unpair_confirm), color = t.colors.error)
                    }
                },
                dismissButton = { TextButton(onClick = vm::dismissUnpairPrompt) { Text(stringResource(R.string.unpair_prompt_later)) } },
            )
        }
    }
}

private fun NavHostController.navigateRoot(route: String) {
    navigate(route) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}
