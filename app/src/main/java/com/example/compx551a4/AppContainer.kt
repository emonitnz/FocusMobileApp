package com.example.compx551a4

import android.app.Application
import android.content.Context
import com.example.compx551a4.core.EegSource
import com.example.compx551a4.core.FocusEngine
import com.example.compx551a4.core.NotificationPolicy
import com.example.compx551a4.core.ServiceController
import com.example.compx551a4.core.SessionRecorder
import com.example.compx551a4.core.Suppressor
import com.example.compx551a4.data.NotificationSettings
import com.example.compx551a4.data.SessionStore
import com.example.compx551a4.data.ThresholdStore
import com.example.compx551a4.data.UiPreferences
import com.example.compx551a4.device.FakeEegSource
import com.example.compx551a4.device.ForegroundEegSource
import com.example.compx551a4.device.OscEegSource
import com.example.compx551a4.device.MuseEegSource
import com.example.compx551a4.device.SwitchableEegSource
import com.example.compx551a4.notification.CatchUpNotifier
import com.example.compx551a4.notification.DeferralQueue
import com.example.compx551a4.notification.DndController
import com.example.compx551a4.notification.NotificationController
import com.example.compx551a4.signal.FocusPipeline
import com.example.compx551a4.service.FocusService
import com.example.compx551a4.service.AndroidServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

// [SHARED] Application class + manual DI wiring the four modules together.
//
// Manual construction rather than a DI library: five singletons do not justify the build cost, and
// a container everyone can read in one screen is worth more to a four-person team than annotations.

class FocusApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {

    /**
     * Outlives every ViewModel: the decision loop must keep running while no screen is bound, which
     * is the entire point of the foreground service.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Workload D --------------------------------------------------------
    val uiPreferences = UiPreferences(context)
    val sessionStore = SessionStore(context, applicationScope)
    val sessionRecorder: SessionRecorder = sessionStore

    // --- Workload A ----------------------------------------------------------
    // Wrapped so MainActivity can keep the headset connected whenever the app is open, and
    // switchable to the synthetic source by the "Demo mode" developer option in Settings.
    // OscEegSource (Mind Monitor) is a further drop-in alternative for development.
    val eegSource = ForegroundEegSource(
        SwitchableEegSource(
            real = MuseEegSource(context),
            fake = FakeEegSource(),
            demoMode = uiPreferences.prefs.map { it.demoMode },
            scope = applicationScope,
        )
    )

    val serviceController: ServiceController = AndroidServiceController(context.applicationContext)

    // --- Workload B ----------------------------------------------------------
    val thresholdStore = ThresholdStore(context)

    val focusEngine: FocusEngine = FocusPipeline(
        store = thresholdStore,
        scope = applicationScope,
        smoothingWindow = FOCUS_SMOOTHING_EPOCHS,
    )

    // --- Workload C ----------------------------------------------------------
    val notificationPolicy: NotificationPolicy = NotificationSettings(context)

    val suppressor: Suppressor = NotificationController(
        gate = DndController(context),
        queue = DeferralQueue(),
        presenter = CatchUpNotifier(context),
        recorder = sessionRecorder,
        policy = notificationPolicy,
        scope = applicationScope,
    )

    private companion object {
        /** About eight seconds at ~1 Hz: enough to ride out single noisy epochs, well inside the dwells. */
        const val FOCUS_SMOOTHING_EPOCHS = 8
    }
}
