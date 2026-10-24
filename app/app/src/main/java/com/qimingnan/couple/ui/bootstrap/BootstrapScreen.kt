package com.qimingnan.couple.ui.bootstrap

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.R
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.BannerKind
import com.qimingnan.couple.ui.components.CoupleTextField
import com.qimingnan.couple.ui.components.LinkButton
import com.qimingnan.couple.ui.components.PrimaryButton
import com.qimingnan.couple.ui.components.SecondaryButton
import com.qimingnan.couple.ui.components.StatusBanner

/**
 * @param initialUri 通过 couple://bootstrap 深链拉起时带入的链接
 * @param onBack 从“我的 → 重新扫码”进入时可以返回
 */
@Composable
fun BootstrapScreen(
    initialUri: String?,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    vm: BootstrapViewModel = hiltViewModel(),
) {
    val t = LocalCoupleTokens.current
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var scanSession by remember { mutableIntStateOf(0) }

    LaunchedEffect(initialUri) { if (!initialUri.isNullOrBlank()) vm.inspectUri(initialUri) }
    LaunchedEffect(state) { if (state is BootstrapUiState.Success) onDone() }

    Column(
        Modifier.fillMaxSize().background(t.colors.background).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(t.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        if (onBack != null) LinkButton(stringResource(R.string.back), onBack)
        Text(stringResource(R.string.bootstrap_title), style = t.typography.headline, color = t.colors.onBackground)
        Text(stringResource(R.string.bootstrap_subtitle), style = t.typography.body, color = t.colors.onSurfaceVariant)

        StatusArea(state)

        (state as? BootstrapUiState.Confirm)?.let { c ->
            BootstrapConfirmCard(c.preview, onConfirm = vm::confirm, onCancel = { vm.cancelConfirm(); scanSession++ })
            return@Column
        }

        TabRow(selectedTabIndex = tab, containerColor = t.colors.background, contentColor = t.colors.primary) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.bootstrap_tab_scan)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.bootstrap_tab_manual)) })
        }
        if (tab == 0) {
            ScanTab(
                active = !vm.busy,
                sessionKey = scanSession,
                onResult = { vm.inspectUri(it) },
            )
            if (state is BootstrapUiState.Failed || state is BootstrapUiState.RolledBack) {
                SecondaryButton(stringResource(R.string.retry), onClick = { vm.reset(); scanSession++ })
            }
        } else {
            ManualTab(busy = vm.busy, onUri = vm::inspectUri, onManual = vm::inspectManual)
        }
    }
}

@Composable
private fun StatusArea(state: BootstrapUiState) {
    when (state) {
        BootstrapUiState.Idle, is BootstrapUiState.Confirm -> Unit
        BootstrapUiState.Inspecting -> StatusBanner(stringResource(R.string.bootstrap_inspecting), BannerKind.Info)
        BootstrapUiState.Verifying -> StatusBanner(stringResource(R.string.bootstrap_verifying), BannerKind.Info)
        is BootstrapUiState.HealthChecking -> StatusBanner(
            stringResource(R.string.bootstrap_checking, state.secondsLeft) + (state.lastError?.let { "\n$it" } ?: ""),
            BannerKind.Info,
        )
        BootstrapUiState.Success -> StatusBanner(stringResource(R.string.bootstrap_success), BannerKind.Success)
        is BootstrapUiState.RolledBack -> StatusBanner(stringResource(R.string.bootstrap_rolled_back) + "\n" + state.reason, BannerKind.Warning)
        is BootstrapUiState.Failed -> StatusBanner(stringResource(R.string.bootstrap_failed, state.reason), BannerKind.Error)
    }
}

@Composable
private fun ScanTab(active: Boolean, sessionKey: Int, onResult: (String) -> Unit) {
    val t = LocalCoupleTokens.current
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    if (!granted) {
        Text(stringResource(R.string.bootstrap_camera_permission), style = t.typography.body, color = t.colors.onSurfaceVariant)
        PrimaryButton(stringResource(R.string.bootstrap_grant_permission), onClick = { launcher.launch(Manifest.permission.CAMERA) })
        return
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(t.shapes.large).background(t.colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            androidx.compose.runtime.key(sessionKey) {
                QrScanner(onResult = onResult, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ManualTab(busy: Boolean, onUri: (String) -> Unit, onManual: (String, String) -> Unit) {
    val t = LocalCoupleTokens.current
    var uri by rememberSaveable { mutableStateOf("") }
    var base by rememberSaveable { mutableStateOf("") }
    var mqtt by rememberSaveable { mutableStateOf("") }

    CoupleTextField(uri, { uri = it }, stringResource(R.string.bootstrap_paste_uri), singleLine = false, keyboardType = KeyboardType.Uri)
    PrimaryButton(stringResource(R.string.bootstrap_apply_uri), onClick = { onUri(uri) }, enabled = uri.isNotBlank(), loading = busy)

    Text(stringResource(R.string.bootstrap_manual_debug), style = t.typography.title, color = t.colors.onBackground)
    CoupleTextField(base, { base = it }, stringResource(R.string.bootstrap_base_url), keyboardType = KeyboardType.Uri)
    CoupleTextField(mqtt, { mqtt = it }, stringResource(R.string.bootstrap_mqtt_url), keyboardType = KeyboardType.Uri)
    SecondaryButton(
        stringResource(R.string.bootstrap_apply_manual),
        onClick = { onManual(base, mqtt) },
        enabled = !busy && base.isNotBlank() && mqtt.isNotBlank(),
    )
}
