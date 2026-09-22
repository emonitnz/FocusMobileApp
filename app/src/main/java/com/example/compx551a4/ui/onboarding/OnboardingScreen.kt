package com.example.compx551a4.ui.onboarding

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.example.compx551a4.R
import com.example.compx551a4.data.UiPreferences
import com.example.compx551a4.ui.common.SectionCard
import com.example.compx551a4.ui.common.ScreenPadding
import com.example.compx551a4.ui.container
import com.example.compx551a4.ui.theme.COMPX551A4Theme
import com.example.compx551a4.ui.theme.FocusTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// [D] Permission flow: BLE, POST_NOTIFICATIONS, DND access, listener access, battery. Known drop-off point.
//
// DESIGN.md §5 calls notification-listener access a known drop-off point, and the reason is
// structural: three of the five grants here are "special access" settings that cannot be requested
// with a dialog. Android drops the user into a list of every app on the device and returns no
// result. So this screen (a) explains what to tap before sending them there, and (b) re-reads every
// grant on resume, because there is no callback to wait for.

/** Indirection so the ViewModel can be unit-tested without a Context. */
fun interface PermissionReader {
    fun read(): List<PermissionStep>
}

data class OnboardingUiState(
    val steps: List<PermissionStep> = emptyList(),
    val saving: Boolean = false,
) {
    val grantedCount: Int get() = steps.count { it.granted }
    val allRequiredGranted: Boolean get() = steps.none { it.required && !it.granted }
}

class OnboardingViewModel(
    private val reader: PermissionReader,
    private val uiPreferences: UiPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Called on every resume. The settings-based grants change while this screen is stopped, so
     * this — not a result callback — is what keeps the checkmarks honest.
     */
    fun refresh() {
        _uiState.value = _uiState.value.copy(steps = reader.read())
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(saving = true)
            uiPreferences.setOnboardingComplete(true)
            onDone()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                OnboardingViewModel(
                    reader = { PermissionChecks.readAll(app) },
                    uiPreferences = container().uiPreferences,
                )
            }
        }
    }
}

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Results are ignored on purpose: a "denied" result and a silently-dismissed dialog are
    // indistinguishable here, so both paths just re-read the real state.
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    val openSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refresh() }

    OnboardingContent(
        state = state,
        modifier = modifier,
        onGrant = { step ->
            val runtime = PermissionChecks.runtimePermissionsFor(step.id)
            when {
                runtime.isNotEmpty() -> requestPermissions.launch(runtime)
                else -> PermissionChecks.settingsIntentFor(context, step.id)
                    ?.let { openSettings.launch(it) }
            }
        },
        onFinish = { viewModel.finish(onFinished) },
    )
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onGrant: (PermissionStep) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.onboarding_progress, state.grantedCount, state.steps.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        state.steps.forEach { step ->
            PermissionCard(step = step, onGrant = { onGrant(step) })
        }

        if (!state.allRequiredGranted) {
            Text(
                stringResource(R.string.onboarding_required_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.onboarding_all_set),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = onFinish,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    // Leaving without the required grants is allowed — DESIGN.md §3's fail-open
                    // applies to the setup flow too. Better a working app that delivers everything
                    // than a dead end on step three.
                    if (state.allRequiredGranted) R.string.onboarding_continue
                    else R.string.onboarding_skip_all,
                ),
            )
        }
    }
}

@Composable
private fun PermissionCard(
    step: PermissionStep,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appName = stringResource(R.string.app_name)
    SectionCard(
        title = stringResource(step.titleRes),
        modifier = modifier,
        trailing = {
            if (step.granted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = FocusTheme.colors.contactGood,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.perm_granted),
                        style = MaterialTheme.typography.labelMedium,
                        color = FocusTheme.colors.contactGood,
                    )
                }
            } else {
                Text(
                    stringResource(if (step.required) R.string.perm_required else R.string.perm_optional),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (step.required) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Text(
            stringResource(step.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (step.id == PermissionStepId.BLUETOOTH && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Text(
                stringResource(R.string.perm_bluetooth_body_legacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The hint only earns its space while the grant is still outstanding.
        step.hintRes?.takeIf { !step.granted }?.let { hint ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp).clearAndSetSemantics {},
                    )
                    Text(
                        stringResource(hint, appName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        if (!step.granted) {
            if (step.isRuntimePermission) {
                Button(onClick = onGrant) { Text(stringResource(R.string.perm_action_grant)) }
            } else {
                OutlinedButton(onClick = onGrant) { Text(stringResource(R.string.perm_action_open)) }
            }
        } else if (!step.isRuntimePermission) {
            // Special access can be revoked in Settings, so the way back stays visible.
            TextButton(onClick = onGrant, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(R.string.perm_action_open))
            }
        }
    }
}

private val PermissionStep.titleRes: Int
    get() = when (id) {
        PermissionStepId.NOTIFICATIONS -> R.string.perm_notifications_title
        PermissionStepId.BLUETOOTH -> R.string.perm_bluetooth_title
        PermissionStepId.LISTENER -> R.string.perm_listener_title
        PermissionStepId.DND -> R.string.perm_dnd_title
        PermissionStepId.BATTERY -> R.string.perm_battery_title
    }

private val PermissionStep.bodyRes: Int
    get() = when (id) {
        PermissionStepId.NOTIFICATIONS -> R.string.perm_notifications_body
        PermissionStepId.BLUETOOTH -> R.string.perm_bluetooth_body
        PermissionStepId.LISTENER -> R.string.perm_listener_body
        PermissionStepId.DND -> R.string.perm_dnd_body
        PermissionStepId.BATTERY -> R.string.perm_battery_body
    }

private val PermissionStep.hintRes: Int?
    get() = when (id) {
        PermissionStepId.LISTENER -> R.string.perm_listener_hint
        PermissionStepId.DND -> R.string.perm_dnd_hint
        else -> null
    }

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun OnboardingPreview() {
    COMPX551A4Theme {
        OnboardingContent(
            state = OnboardingUiState(
                steps = listOf(
                    PermissionStep(PermissionStepId.NOTIFICATIONS, granted = true),
                    PermissionStep(PermissionStepId.BLUETOOTH, granted = true),
                    PermissionStep(PermissionStepId.LISTENER, granted = false),
                    PermissionStep(PermissionStepId.DND, granted = false),
                    PermissionStep(PermissionStepId.BATTERY, granted = false),
                ),
            ),
            onGrant = {},
            onFinish = {},
        )
    }
}
