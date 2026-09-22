package com.example.compx551a4.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.example.compx551a4.R
import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.core.DeviceState
import com.example.compx551a4.core.DeviceStatus
import com.example.compx551a4.core.FlushReason
import com.example.compx551a4.core.FocusDecision
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.ServiceController
import com.example.compx551a4.core.Suppressor
import com.example.compx551a4.ui.common.ContactQualityMeter
import com.example.compx551a4.ui.common.FocusIndexChart
import com.example.compx551a4.ui.common.FocusPoint
import com.example.compx551a4.ui.common.NoticeCard
import com.example.compx551a4.ui.common.ScreenPadding
import com.example.compx551a4.ui.common.SectionCard
import com.example.compx551a4.ui.common.StatusPill
import com.example.compx551a4.ui.common.formatDuration
import com.example.compx551a4.ui.container
import com.example.compx551a4.ui.model.LiveStatus
import com.example.compx551a4.ui.model.liveStatusFlow
import com.example.compx551a4.ui.onboarding.PermissionChecks
import com.example.compx551a4.ui.onboarding.PermissionReader
import com.example.compx551a4.ui.theme.COMPX551A4Theme
import com.example.compx551a4.ui.theme.FocusTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// [D] Master switch, live focus state, connection and contact quality.

/** Why the master switch is unavailable. Null means it is usable. */
enum class StartBlocker { NOT_CALIBRATED, PERMISSIONS_MISSING }

data class HomeUiState(
    val serviceRunning: Boolean = false,
    val blocker: StartBlocker? = null,
    val focusState: FocusState = FocusState.UNKNOWN,
    val stateForMs: Long = 0L,
    val device: DeviceStatus = DeviceStatus.DISCONNECTED,
    val points: List<FocusPoint> = emptyList(),
    val tHighZ: Double? = null,
    val tLowZ: Double? = null,
    val deferred: List<DeferredNotification> = emptyList(),
    val recalibrationSuggested: Boolean = false,
) {
    val calibrated: Boolean get() = tHighZ != null
    val canToggle: Boolean get() = serviceRunning || blocker == null
    val contactPoor: Boolean get() = device.state == DeviceState.POOR_CONTACT
}

class HomeViewModel(
    private val serviceController: ServiceController,
    private val suppressor: Suppressor,
    liveStatus: Flow<LiveStatus>,
    decisions: Flow<FocusDecision>,
    private val permissionReader: PermissionReader,
    /**
     * Drives the "focused for 4m 12s" readout. Injected rather than looped inline so tests can
     * supply an empty flow — an unbounded `delay` loop inside a ViewModel makes every test that
     * touches it hang on the virtual clock.
     */
    ticker: Flow<Unit> = secondTicker(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var stateSinceMs: Long = clock()
    private var permissionsOk: Boolean = true

    init {
        viewModelScope.launch {
            liveStatus.collect { status ->
                if (status.focusState != _uiState.value.focusState) stateSinceMs = clock()
                _uiState.value = _uiState.value.copy(
                    serviceRunning = status.serviceRunning,
                    focusState = status.focusState,
                    device = status.device,
                    deferred = status.deferred,
                    tHighZ = status.thresholds?.tHighZ,
                    tLowZ = status.thresholds?.tLowZ,
                    recalibrationSuggested = status.recalibrationSuggested,
                    blocker = blockerFor(status.calibrated),
                )
            }
        }
        viewModelScope.launch {
            decisions.collect { decision ->
                val point = FocusPoint(decision.normalizedZ, decision.accepted)
                val points = (_uiState.value.points + point).takeLast(CHART_WINDOW)
                _uiState.value = _uiState.value.copy(points = points)
            }
        }
        viewModelScope.launch {
            ticker.collect {
                _uiState.value = _uiState.value.copy(stateForMs = clock() - stateSinceMs)
            }
        }
        refreshPermissions()
    }

    /** Called on resume — special access can be revoked in Settings while this screen is stopped. */
    fun refreshPermissions() {
        permissionsOk = permissionReader.read().none { it.required && !it.granted }
        _uiState.value = _uiState.value.copy(blocker = blockerFor(_uiState.value.calibrated))
    }

    private fun blockerFor(calibrated: Boolean): StartBlocker? = when {
        !permissionsOk -> StartBlocker.PERMISSIONS_MISSING
        !calibrated -> StartBlocker.NOT_CALIBRATED
        else -> null
    }

    fun setRunning(running: Boolean) {
        if (running) serviceController.start() else serviceController.stop()
    }

    fun releaseNow() {
        viewModelScope.launch { suppressor.flush(FlushReason.MANUAL) }
    }

    companion object {
        /** Focus state changes far less often than the elapsed label has to be redrawn. */
        fun secondTicker(): Flow<Unit> = flow {
            while (true) {
                delay(1_000)
                emit(Unit)
            }
        }

        /** Two minutes of 1 Hz decisions — long enough to show a dwell period, short enough to read. */
        const val CHART_WINDOW = 120

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                val c = container()
                HomeViewModel(
                    serviceController = c.serviceController,
                    suppressor = c.suppressor,
                    liveStatus = liveStatusFlow(c.eegSource, c.focusEngine, c.suppressor, c.serviceController),
                    decisions = c.focusEngine.decisions,
                    permissionReader = { PermissionChecks.readAll(app) },
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    onCalibrate: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HomeContent(
        state = state,
        onToggle = viewModel::setRunning,
        onRelease = viewModel::releaseNow,
        onCalibrate = onCalibrate,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onToggle: (Boolean) -> Unit,
    onRelease: () -> Unit,
    onCalibrate: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall)

        when (state.blocker) {
            StartBlocker.NOT_CALIBRATED -> NoticeCard(
                text = stringResource(R.string.home_needs_calibration),
                icon = Icons.Default.Info,
                actionLabel = stringResource(R.string.home_action_calibrate),
                onAction = onCalibrate,
            )

            StartBlocker.PERMISSIONS_MISSING -> NoticeCard(
                text = stringResource(R.string.home_needs_permissions),
                icon = Icons.Default.Warning,
                actionLabel = stringResource(R.string.home_action_fix),
                onAction = onOpenSettings,
            )

            null -> if (state.recalibrationSuggested) {
                NoticeCard(
                    text = stringResource(R.string.home_recalibrate_suggested),
                    icon = Icons.Default.Info,
                    actionLabel = stringResource(R.string.home_action_calibrate),
                    onAction = onCalibrate,
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.home_master_switch),
            subtitle = stringResource(
                if (state.serviceRunning) R.string.home_master_switch_on
                else R.string.home_master_switch_off,
            ),
            trailing = {
                Switch(
                    checked = state.serviceRunning,
                    onCheckedChange = onToggle,
                    enabled = state.canToggle,
                )
            },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    text = stringResource(state.focusState.labelRes),
                    icon = state.focusState.icon,
                    color = state.focusState.color,
                )
                if (state.serviceRunning && state.focusState != FocusState.UNKNOWN) {
                    Text(
                        stringResource(R.string.home_state_for, formatDuration(state.stateForMs)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard(
            title = stringResource(R.string.home_signal_title),
            subtitle = stringResource(R.string.home_signal_subtitle),
        ) {
            if (state.points.size < 2) {
                Text(
                    stringResource(R.string.home_signal_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FocusIndexChart(
                    points = state.points,
                    tHighZ = state.tHighZ,
                    tLowZ = state.tLowZ,
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.home_device_title),
            subtitle = state.device.deviceName,
            trailing = {
                StatusPill(
                    text = stringResource(state.device.state.labelRes),
                    icon = if (state.device.usable) Icons.Default.CheckCircle else Icons.Default.Warning,
                    color = if (state.device.usable) FocusTheme.colors.contactGood else FocusTheme.colors.unknown,
                )
            },
        ) {
            ContactQualityMeter(state.device.contact)
            state.device.batteryPct?.let {
                Text(
                    stringResource(R.string.home_device_battery, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.contactPoor) {
                Text(
                    stringResource(R.string.home_contact_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(
            title = stringResource(R.string.home_deferred_title),
            trailing = {
                if (state.deferred.isNotEmpty()) {
                    StatusPill(
                        text = stringResource(R.string.home_deferred_count, state.deferred.size),
                        icon = Icons.Default.Notifications,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        ) {
            if (state.deferred.isEmpty()) {
                Text(
                    stringResource(R.string.home_deferred_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // The queue is summarised, never previewed in full: reading the messages here would
                // be the same interruption the app exists to prevent.
                state.deferred.groupBy { it.appLabel }.forEach { (app, items) ->
                    Text(
                        "$app · ${items.size}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = onRelease, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_deferred_release))
                }
            }
        }
    }
}

private val FocusState.labelRes: Int
    get() = when (this) {
        FocusState.FOCUSED -> R.string.home_state_focused
        FocusState.NOT_FOCUSED -> R.string.home_state_not_focused
        FocusState.UNKNOWN -> R.string.home_state_unknown
    }

private val FocusState.icon
    @Composable get() = when (this) {
        FocusState.FOCUSED -> Icons.Default.CheckCircle
        FocusState.NOT_FOCUSED -> Icons.Default.Notifications
        FocusState.UNKNOWN -> Icons.Default.Info
    }

private val FocusState.color
    @Composable get() = when (this) {
        FocusState.FOCUSED -> FocusTheme.colors.focused
        FocusState.NOT_FOCUSED -> FocusTheme.colors.idle
        FocusState.UNKNOWN -> FocusTheme.colors.unknown
    }

private val DeviceState.labelRes: Int
    get() = when (this) {
        DeviceState.DISCONNECTED -> R.string.home_device_disconnected
        DeviceState.SCANNING -> R.string.home_device_scanning
        DeviceState.CONNECTING -> R.string.home_device_connecting
        DeviceState.CONNECTED -> R.string.home_device_connected
        DeviceState.POOR_CONTACT -> R.string.home_device_poor_contact
    }

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun HomeFocusedPreview() {
    COMPX551A4Theme {
        HomeContent(
            state = com.example.compx551a4.ui.preview.PreviewData.homeFocused,
            onToggle = {},
            onRelease = {},
            onCalibrate = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun HomeUncalibratedPreview() {
    COMPX551A4Theme {
        HomeContent(
            state = com.example.compx551a4.ui.preview.PreviewData.homeUncalibrated,
            onToggle = {},
            onRelease = {},
            onCalibrate = {},
            onOpenSettings = {},
        )
    }
}
