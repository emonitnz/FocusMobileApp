package com.example.compx551a4.ui

import com.example.compx551a4.core.DeviceState
import com.example.compx551a4.core.DeviceStatus
import com.example.compx551a4.core.FlushReason
import com.example.compx551a4.core.FocusDecision
import com.example.compx551a4.core.FocusState
import com.example.compx551a4.core.FocusThresholds
import com.example.compx551a4.core.ServiceController
import com.example.compx551a4.core.Suppressor
import com.example.compx551a4.core.DeferredNotification
import com.example.compx551a4.ui.home.HomeViewModel
import com.example.compx551a4.ui.home.StartBlocker
import com.example.compx551a4.ui.model.LiveStatus
import com.example.compx551a4.ui.onboarding.PermissionStep
import com.example.compx551a4.ui.onboarding.PermissionStepId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs entirely against fakes: no headset, no service, no Android. That this is possible is the
 * point of the contract boundary — if these tests ever need a Context, something has leaked into
 * the ViewModel that should not be there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val live = MutableStateFlow(LiveStatus())
    private val decisions = MutableSharedFlow<FocusDecision>(extraBufferCapacity = 512)

    private class FakeController : ServiceController {
        private val _running = MutableStateFlow(false)
        override val running: StateFlow<Boolean> = _running.asStateFlow()
        var startCalls = 0
        override fun start() { startCalls++; _running.value = true }
        override fun stop() { _running.value = false }
    }

    private class FakeSuppressor : Suppressor {
        private val _deferred = MutableStateFlow<List<DeferredNotification>>(emptyList())
        override val deferred: StateFlow<List<DeferredNotification>> = _deferred.asStateFlow()
        var flushes = mutableListOf<FlushReason>()
        override suspend fun apply(state: FocusState) = Unit
        override suspend fun flush(reason: FlushReason) { flushes += reason }
    }

    private val controller = FakeController()
    private val suppressor = FakeSuppressor()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(allPermissionsGranted: Boolean = true) = HomeViewModel(
        serviceController = controller,
        suppressor = suppressor,
        liveStatus = live,
        decisions = decisions,
        permissionReader = {
            PermissionStepId.entries.map { PermissionStep(it, granted = allPermissionsGranted) }
        },
        ticker = emptyFlow(),
    )

    @Test
    fun `missing permissions block the switch even once calibrated`() = runTest(dispatcher) {
        val vm = viewModel(allPermissionsGranted = false)
        live.value = LiveStatus(thresholds = FocusThresholds.PLACEHOLDER)

        assertEquals(StartBlocker.PERMISSIONS_MISSING, vm.uiState.value.blocker)
        assertFalse(vm.uiState.value.canToggle)
    }

    @Test
    fun `an uncalibrated user is told to calibrate, not that permissions are wrong`() =
        runTest(dispatcher) {
            val vm = viewModel()
            live.value = LiveStatus(thresholds = null)

            assertEquals(StartBlocker.NOT_CALIBRATED, vm.uiState.value.blocker)
        }

    @Test
    fun `the switch unblocks once calibrated and permitted`() = runTest(dispatcher) {
        val vm = viewModel()
        live.value = LiveStatus(thresholds = FocusThresholds.PLACEHOLDER)

        assertNull(vm.uiState.value.blocker)
        assertTrue(vm.uiState.value.canToggle)
    }

    /** A running service must stay switchable off, whatever went wrong since it started. */
    @Test
    fun `a running service can always be turned off`() = runTest(dispatcher) {
        val vm = viewModel(allPermissionsGranted = false)
        live.value = LiveStatus(thresholds = null, serviceRunning = true)

        assertTrue(vm.uiState.value.canToggle)
    }

    @Test
    fun `the chart keeps a bounded window of the most recent decisions`() = runTest(dispatcher) {
        val vm = viewModel()
        repeat(HomeViewModel.CHART_WINDOW + 40) { i ->
            decisions.emit(FocusDecision(i.toLong(), FocusState.FOCUSED, 1.0, i.toDouble(), true))
        }

        val points = vm.uiState.value.points
        assertEquals(HomeViewModel.CHART_WINDOW, points.size)
        assertEquals((HomeViewModel.CHART_WINDOW + 39).toDouble(), points.last().normalizedZ, 0.0)
    }

    @Test
    fun `rejected epochs reach the chart so they can be drawn as gaps`() = runTest(dispatcher) {
        val vm = viewModel()
        decisions.emit(FocusDecision(0L, FocusState.UNKNOWN, 1.0, 0.0, accepted = false))

        assertFalse(vm.uiState.value.points.single().accepted)
    }

    @Test
    fun `releasing early flushes as a manual release`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.releaseNow()

        assertEquals(listOf(FlushReason.MANUAL), suppressor.flushes)
    }

    @Test
    fun `poor contact is surfaced separately from being disconnected`() = runTest(dispatcher) {
        val vm = viewModel()
        live.value = LiveStatus(device = DeviceStatus(DeviceState.POOR_CONTACT))

        assertTrue(vm.uiState.value.contactPoor)
    }
}
