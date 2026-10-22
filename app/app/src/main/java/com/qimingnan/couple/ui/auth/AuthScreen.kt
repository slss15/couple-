package com.qimingnan.couple.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.R
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.BannerKind
import com.qimingnan.couple.ui.components.CoupleTextField
import com.qimingnan.couple.ui.components.LinkButton
import com.qimingnan.couple.ui.components.PrimaryButton
import com.qimingnan.couple.ui.components.StatusBanner

@Composable
fun AuthScreen(onChangeServer: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val t = LocalCoupleTokens.current
    val s by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(t.colors.background).statusBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(t.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        Text(stringResource(R.string.app_name), style = t.typography.display, color = t.colors.primary)
        Text(
            stringResource(if (s.registerMode) R.string.auth_register else R.string.auth_login),
            style = t.typography.headline,
            color = t.colors.onBackground,
        )
        CoupleTextField(s.username, vm::onUsername, stringResource(R.string.auth_username))
        CoupleTextField(s.password, vm::onPassword, stringResource(R.string.auth_password), password = true)
        if (s.registerMode) {
            CoupleTextField(s.displayName, vm::onDisplayName, stringResource(R.string.auth_display_name))
            CoupleTextField(s.inviteCode, vm::onInviteCode, stringResource(R.string.auth_invite_code))
        }
        s.error?.let { StatusBanner(it, BannerKind.Error) }
        PrimaryButton(
            stringResource(if (s.registerMode) R.string.auth_register else R.string.auth_login),
            onClick = vm::submit,
            enabled = s.canSubmit,
            loading = s.loading,
        )
        LinkButton(
            stringResource(if (s.registerMode) R.string.auth_switch_to_login else R.string.auth_switch_to_register),
            vm::toggleMode,
        )
        LinkButton(stringResource(R.string.auth_change_server), onChangeServer)
    }
}
