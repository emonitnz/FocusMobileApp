package com.example.compx551a4

import android.app.Application
import android.content.Context
import com.example.compx551a4.core.EegSource
import com.example.compx551a4.core.FocusEngine
import com.example.compx551a4.core.NotificationPolicy
import com.example.compx551a4.core.ServiceController
import com.example.compx551a4.core.SessionRecorder
import com.example.compx551a4.core.Suppressor
import com.example.compx551a4.data.SessionStore
import com.example.compx551a4.data.UiPreferences
import com.example.compx551a4.device.FakeEegSource
import com.example.compx551a4.ui.preview.DemoEegSource
import com.example.compx551a4.ui.preview.DemoFocusEngine
import com.example.compx551a4.ui.preview.DemoNotificationPolicy
import com.example.compx551a4.ui.preview.DemoServiceController
import com.example.compx551a4.ui.preview.DemoSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    // --- SWAP POINT --------------------------------------------------------
    // Everything below is a D-owned stand-in from ui/preview/DemoStack.kt so the app runs before
    // workloads A, B and C land. Replace one line at a time as each real implementation arrives:
    //
    //   eegSource          -> MuseEegSource / OscEegSource / FakeEegSource   (A)
    //   focusEngine        -> FocusPipeline + FocusStateMachine              (B)
    //   suppressor         -> DndController + DeferralQueue                  (C)
    //   notificationPolicy -> NotificationSettings                           (C)
    //   serviceController  -> FocusService                                   (A)
    //
    // Nothing in ui/ should need to change when they do.

    val eegSource: EegSource = FakeEegSource()

    val focusEngine: FocusEngine = DemoFocusEngine()

    val suppressor: Suppressor = DemoSuppressor(applicationScope, sessionRecorder)

    val notificationPolicy: NotificationPolicy = DemoNotificationPolicy()

    val serviceController: ServiceController = DemoServiceController(
        scope = applicationScope,
        source = eegSource,
        engine = focusEngine,
        suppressor = suppressor,
        recorder = sessionRecorder,
    )
}
