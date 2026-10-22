package com.qimingnan.couple.ui.pairing

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.R
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.BannerKind
import com.qimingnan.couple.ui.components.CoupleTextField
import com.qimingnan.couple.ui.components.LinkButton
import com.qimingnan.couple.ui.components.PrimaryButton
import com.qimingnan.couple.ui.components.SectionCard
import com.qimingnan.couple.ui.components.SecondaryButton
import com.qimingnan.couple.ui.components.StatusBanner

@Composable
fun PairingScreen(vm: PairingViewModel = hiltViewModel()) {
    val t = LocalCoupleTokens.current
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(t.colors.background).statusBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(t.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        Text(stringResource(R.string.pair_title), style = t.typography.headline, color = t.colors.onBackground)

        if (s.paired) StatusBanner(stringResource(R.string.pair_success), BannerKind.Success)
        s.error?.let { StatusBanner(it, BannerKind.Error) }

        SectionCard(stringResource(R.string.pair_my_code)) {
            val code = s.myCode
            if (code != null) {
                Text(
                    code.code.chunked(3).joinToString(" "),
                    style = t.typography.display,
                    color = if (s.codeExpired) t.colors.onSurfaceVariant else t.colors.primary,
                    modifier = Modifier.semantics { contentDescription = code.code },
                )
                Text(
                    if (s.codeExpired) stringResource(R.string.pair_expired)
                    else stringResource(R.string.pair_expires_in, formatCountdown(s.secondsLeft)),
                    style = t.typography.caption,
                    color = if (s.codeExpired) t.colors.error else t.colors.onSurfaceVariant,
                )
                Text(stringResource(R.string.pair_share_hint), style = t.typography.body, color = t.colors.onSurfaceVariant)
            }
            SecondaryButton(
                stringResource(if (code == null) R.string.pair_generate else R.string.pair_regenerate),
                onClick = vm::generate,
                enabled = !s.generating,
            )
        }

        SectionCard(stringResource(R.string.pair_enter_code)) {
            CoupleTextField(s.input, vm::onInput, stringResource(R.string.pair_enter_code), keyboardType = KeyboardType.NumberPassword)
            PrimaryButton(stringResource(R.string.pair_join), onClick = vm::join, enabled = s.input.length == 6, loading = s.joining)
        }

        LinkButton(stringResource(R.string.me_logout), vm::logout)
    }
}
