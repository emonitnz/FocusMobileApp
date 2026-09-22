package com.example.compx551a4.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.compx551a4.R
import com.example.compx551a4.core.FocusEngine
import com.example.compx551a4.core.FocusThresholds
import com.example.compx551a4.core.NotificationPolicy
import com.example.compx551a4.core.PolicySettings
import com.example.compx551a4.data.HighDemandTask
import com.example.compx551a4.data.SessionStore
import com.example.compx551a4.data.UiPrefs
import com.example.compx551a4.data.UiPreferences
import com.example.compx551a4.ui.common.ScreenPadding
import com.example.compx551a4.ui.common.SectionCard
import com.example.compx551a4.ui.common.formatDateTime
import com.example.compx551a4.ui.common.formatDecimal
import com.example.compx551a4.ui.container
import com.example.compx551a4.ui.theme.COMPX551A4Theme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// [D] Exit timeout, max hold, allowlist editing, developer options.

data class SettingsUiState(
    val thresholds: FocusThresholds? = null,
    val policy: PolicySettings = PolicySettings(),
    val uiPrefs: UiPrefs = UiPrefs(),
)

class SettingsViewModel(
    private val focusEngine: FocusEngine,
    private val notificationPolicy: NotificationPolicy,
    private val uiPreferences: UiPreferences,
    private val sessionStore: SessionStore,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        focusEngine.thresholds,
        notificationPolicy.settings,
        uiPreferences.prefs,
    ) { thresholds, policy, prefs ->
        SettingsUiState(thresholds, policy, prefs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /**
     * Dwell times are part of the fitted threshold set, so editing one rewrites the whole record.
     * Before calibration there is nothing to edit — the sliders are hidden rather than writing
     * placeholder values the user would mistake for their own.
     */
    private fun editThresholds(edit: (FocusThresholds) -> FocusThresholds) {
        val current = uiState.value.thresholds ?: return
        viewModelScope.launch { focusEngine.updateThresholds(edit(current)) }
    }

    fun setExitDwell(seconds: Int) = editThresholds { it.copy(exitDwellSec = seconds) }

    fun setEntryDwell(seconds: Int) = editThresholds { it.copy(entryDwellSec = seconds) }

    fun updatePolicy(policy: PolicySettings) {
        viewModelScope.launch { notificationPolicy.update(policy) }
    }

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch { uiPreferences.setDemoMode(enabled) }
    }

    fun setRawLogging(enabled: Boolean) {
        viewModelScope.launch { uiPreferences.setRawLogging(enabled) }
    }

    fun setHighDemandTask(task: HighDemandTask) {
        viewModelScope.launch { uiPreferences.setHighDemandTask(task) }
    }

    fun setFastCalibration(enabled: Boolean) {
        viewModelScope.launch { uiPreferences.setFastCalibration(enabled) }
    }

    /** Developer escape hatch so the rest of the app can be exercised without a 9-minute detour. */
    fun loadPlaceholderThresholds() {
        viewModelScope.launch {
            focusEngine.updateThresholds(
                FocusThresholds.PLACEHOLDER.copy(fittedAtMs = System.currentTimeMillis()),
            )
        }
    }

    fun clearHistory() {
        viewModelScope.launch { sessionStore.clear() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = container()
                SettingsViewModel(c.focusEngine, c.notificationPolicy, c.uiPreferences, c.sessionStore)
            }
        }
    }
}

@Composable
fun SettingsScreen(
    onCalibrate: () -> Unit,
    onReRunOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onExitDwell = viewModel::setExitDwell,
        onEntryDwell = viewModel::setEntryDwell,
        onPolicy = viewModel::updatePolicy,
        onDemoMode = viewModel::setDemoMode,
        onRawLogging = viewModel::setRawLogging,
        onFastCalibration = viewModel::setFastCalibration,
        onHighTask = viewModel::setHighDemandTask,
        onLoadPlaceholder = viewModel::loadPlaceholderThresholds,
        onClearHistory = viewModel::clearHistory,
        onCalibrate = onCalibrate,
        onReRunOnboarding = onReRunOnboarding,
        modifier = modifier,
    )
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onExitDwell: (Int) -> Unit,
    onEntryDwell: (Int) -> Unit,
    onPolicy: (PolicySettings) -> Unit,
    onDemoMode: (Boolean) -> Unit,
    onRawLogging: (Boolean) -> Unit,
    onFastCalibration: (Boolean) -> Unit,
    onHighTask: (HighDemandTask) -> Unit,
    onLoadPlaceholder: () -> Unit,
    onClearHistory: () -> Unit,
    onCalibrate: () -> Unit,
    onReRunOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        val thresholds = state.thresholds
        if (thresholds != null) {
            SectionCard(title = stringResource(R.string.settings_focus)) {
                SliderRow(
                    label = stringResource(R.string.settings_exit_timeout),
                    body = stringResource(R.string.settings_exit_timeout_body),
                    value = thresholds.exitDwellSec,
                    range = 10..300,
                    steps = 28,
                    valueLabel = stringResource(R.string.settings_seconds, thresholds.exitDwellSec),
                    onChange = onExitDwell,
                )
                SliderRow(
                    label = stringResource(R.string.settings_entry_dwell),
                    body = stringResource(R.string.settings_entry_dwell_body),
                    value = thresholds.entryDwellSec,
                    range = 3..60,
                    steps = 18,
                    valueLabel = stringResource(R.string.settings_seconds, thresholds.entryDwellSec),
                    onChange = onEntryDwell,
                )
            }
        }

        SectionCard(title = stringResource(R.string.settings_notifications)) {
            SliderRow(
                label = stringResource(R.string.settings_max_hold),
                body = stringResource(R.string.settings_max_hold_body),
                value = state.policy.maxHoldMinutes,
                range = 5..120,
                steps = 22,
                valueLabel = stringResource(R.string.settings_minutes, state.policy.maxHoldMinutes),
                onChange = { onPolicy(state.policy.copy(maxHoldMinutes = it)) },
            )

            Text(
                stringResource(R.string.settings_breakthrough),
                style = MaterialTheme.typography.titleSmall,
            )
            ToggleRow(R.string.settings_allow_calls, state.policy.allowCalls) {
                onPolicy(state.policy.copy(allowCalls = it))
            }
            ToggleRow(R.string.settings_allow_alarms, state.policy.allowAlarms) {
                onPolicy(state.policy.copy(allowAlarms = it))
            }
            ToggleRow(R.string.settings_allow_repeat, state.policy.allowRepeatCallers) {
                onPolicy(state.policy.copy(allowRepeatCallers = it))
            }
            ToggleRow(R.string.settings_allow_starred, state.policy.allowStarredContacts) {
                onPolicy(state.policy.copy(allowStarredContacts = it))
            }
            ToggleRow(R.string.settings_allow_2fa, state.policy.allowTwoFactorCodes) {
                onPolicy(state.policy.copy(allowTwoFactorCodes = it))
            }
        }

        SectionCard(title = stringResource(R.string.settings_calibration)) {
            Text(
                if (thresholds == null || thresholds.fittedAtMs == 0L) {
                    stringResource(R.string.settings_calibration_never)
                } else {
                    stringResource(
                        R.string.settings_calibration_done,
                        formatDateTime(thresholds.fittedAtMs),
                        formatDecimal(thresholds.auc),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCalibrate) {
                Text(stringResource(R.string.settings_recalibrate))
            }
        }

        SectionCard(title = stringResource(R.string.settings_developer)) {
            // Disabled rather than hidden: it marks the swap point in AppContainer, and a switch
            // that silently does nothing is worse than one that says why it cannot.
            ToggleRow(
                labelRes = R.string.settings_demo_mode,
                checked = true,
                bodyRes = R.string.settings_demo_mode_body,
                enabled = false,
                onChange = onDemoMode,
            )
            ToggleRow(
                labelRes = R.string.settings_fast_calibration,
                checked = state.uiPrefs.fastCalibration,
                bodyRes = R.string.settings_fast_calibration_body,
                onChange = onFastCalibration,
            )
            ToggleRow(
                labelRes = R.string.settings_raw_logging,
                checked = state.uiPrefs.rawLoggingEnabled,
                bodyRes = R.string.settings_raw_logging_body,
                onChange = onRawLogging,
            )

            Text(stringResource(R.string.settings_high_task), style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.uiPrefs.highDemandTask == HighDemandTask.N_BACK,
                    onClick = { onHighTask(HighDemandTask.N_BACK) },
                    label = { Text(stringResource(R.string.settings_task_nback)) },
                )
                FilterChip(
                    selected = state.uiPrefs.highDemandTask == HighDemandTask.SERIAL_SUBTRACTION,
                    onClick = { onHighTask(HighDemandTask.SERIAL_SUBTRACTION) },
                    label = { Text(stringResource(R.string.settings_task_subtraction)) },
                )
            }

            Text(
                stringResource(R.string.settings_load_placeholder_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onLoadPlaceholder) {
                Text(stringResource(R.string.settings_load_placeholder))
            }
            TextButton(onClick = onClearHistory) {
                Text(stringResource(R.string.settings_clear_history))
            }
            TextButton(onClick = onReRunOnboarding) {
                Text(stringResource(R.string.settings_permissions))
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    body: String,
    value: Int,
    range: IntRange,
    steps: Int,
    valueLabel: String,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
        )
    }
}

@Composable
private fun ToggleRow(
    labelRes: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) = ToggleRow(labelRes, checked, null, true, onChange)

@Composable
private fun ToggleRow(
    labelRes: Int,
    checked: Boolean,
    bodyRes: Int?,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
            if (bodyRes != null) {
                Text(
                    stringResource(bodyRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Preview(showBackground = true, heightDp = 1800)
@Composable
private fun SettingsPreview() {
    COMPX551A4Theme {
        SettingsContent(
            state = SettingsUiState(thresholds = FocusThresholds.PLACEHOLDER.copy(auc = 0.78)),
            onExitDwell = {}, onEntryDwell = {}, onPolicy = {}, onDemoMode = {}, onRawLogging = {},
            onFastCalibration = {}, onHighTask = {}, onLoadPlaceholder = {}, onClearHistory = {},
            onCalibrate = {}, onReRunOnboarding = {},
        )
    }
}
