package com.example.compx551a4.device

import android.content.Context
import android.util.Log
import com.choosemuse.libmuse.Accelerometer
import com.choosemuse.libmuse.Battery
import com.choosemuse.libmuse.ConnectionState
import com.choosemuse.libmuse.Eeg
import com.choosemuse.libmuse.Muse
import com.choosemuse.libmuse.MuseArtifactPacket
import com.choosemuse.libmuse.MuseConnectionListener
import com.choosemuse.libmuse.MuseConnectionPacket
import com.choosemuse.libmuse.MuseDataListener
import com.choosemuse.libmuse.MuseDataPacket
import com.choosemuse.libmuse.MuseDataPacketType
import com.choosemuse.libmuse.MuseListener
import com.choosemuse.libmuse.MuseManagerAndroid
import com.choosemuse.libmuse.MusePreset
import com.example.compx551a4.core.ArtifactFlags
import com.example.compx551a4.core.BandPowers
import com.example.compx551a4.core.ContactQuality
import com.example.compx551a4.core.DeviceState
import com.example.compx551a4.core.DeviceStatus
import com.example.compx551a4.core.EegSource
import com.example.compx551a4.core.FocusEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class MuseEegSource(
    private val appContext: Context,
    private val periodMs: Long = 1_000L,
    private val staleTimeoutMs: Long = 5_000L,
    private val movementThreshold: Double = 0.6,
    private val preferredMac: String? = null,
) : EegSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager: MuseManagerAndroid = MuseManagerAndroid.getInstance()

    private var activeMuse: Muse? = null
    private var tickJob: Job? = null
    private var watchdogJob: Job? = null
    private var stopped = true

    private val _status = MutableStateFlow(DeviceStatus.DISCONNECTED)
    override val status: StateFlow<DeviceStatus> = _status

    private val _epochs = MutableSharedFlow<FocusEpoch>(extraBufferCapacity = 8)
    override val epochs: Flow<FocusEpoch> = _epochs.asSharedFlow()

    private val lock = Any()
    private var live = LiveBands()
    /** Last raw value LibMuse sent per band packet type, to tell fresh values from held ones. */
    private val lastRaw = HashMap<MuseDataPacketType, List<Float>>()
    private var lastAccel: Triple<Double, Double, Double>? = null
    private var lastPacketAtMs = 0L
    private var blinkSinceLastTick = false
    private var jawClenchSinceLastTick = false
    private var movementSinceLastTick = false

    override suspend fun start() {
        if (!stopped) return
        stopped = false

        manager.setContext(appContext)
        manager.setMuseListener(museListener)

        synchronized(lock) { lastPacketAtMs = 0L }
        beginScan()

        tickJob = scope.launch { tickLoop() }
        watchdogJob = scope.launch { watchdogLoop() }
    }

    private fun beginScan() {
        _status.value = DeviceStatus(DeviceState.SCANNING, deviceName = preferredMac ?: "Muse (scanning)")
        manager.stopListening()
        manager.startListening()
        Log.d(TAG, "startListening() called, waiting for museListChanged()")
        // LibMuse only fires museListChanged() for headbands it has never seen; one already in its
        // list from an earlier session is silently "rediscovered", so check the list ourselves.
        connectToAvailableMuse()
    }

    private fun rescanAfterDrop(reason: String, delayMs: Long) {
        activeMuse?.let { muse ->
            muse.unregisterAllListeners()
            muse.disconnect()
        }
        activeMuse = null
        synchronized(lock) { lastPacketAtMs = 0L }
        _status.value = DeviceStatus.DISCONNECTED
        Log.w(TAG, "$reason — rescanning in ${delayMs}ms")
        scope.launch {
            delay(delayMs)
            if (!stopped && activeMuse == null) beginScan()
        }
    }

    override fun stop() {
        stopped = true
        manager.stopListening()
        activeMuse?.let { muse ->
            muse.unregisterAllListeners()
            muse.disconnect()
        }
        activeMuse = null
        synchronized(lock) { lastPacketAtMs = 0L }
        tickJob?.cancel()
        watchdogJob?.cancel()
        tickJob = null
        watchdogJob = null
        _status.value = DeviceStatus.DISCONNECTED
    }

    private val museListener = object : MuseListener() {
        override fun museListChanged() = connectToAvailableMuse()
    }

    @Synchronized
    private fun connectToAvailableMuse() {
        if (activeMuse != null || stopped) return
        val found = manager.muses
        Log.d(TAG, "muse list: ${found.size} muse(s) found: ${found.map { it.name }}")
        val candidate = if (preferredMac != null) {
            found.firstOrNull { it.macAddress == preferredMac }
        } else {
            found.firstOrNull()
        }
        if (candidate != null) {
            manager.stopListening()
            connectTo(candidate)
        }
    }

    private fun connectTo(muse: Muse) {
        activeMuse = muse
        synchronized(lock) {
            live = LiveBands()
            lastRaw.clear()
        }
        _status.value = DeviceStatus(DeviceState.CONNECTING, deviceName = muse.name)

        muse.unregisterAllListeners()
        muse.registerConnectionListener(connectionListener)
        muse.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE)
        muse.registerDataListener(dataListener, MuseDataPacketType.BETA_RELATIVE)
        muse.registerDataListener(dataListener, MuseDataPacketType.THETA_RELATIVE)
        muse.registerDataListener(dataListener, MuseDataPacketType.GAMMA_RELATIVE)
        muse.registerDataListener(dataListener, MuseDataPacketType.ACCELEROMETER)
        muse.registerDataListener(dataListener, MuseDataPacketType.HSI_PRECISION)
        muse.registerDataListener(dataListener, MuseDataPacketType.BATTERY)
        muse.registerDataListener(dataListener, MuseDataPacketType.ARTIFACTS)

        muse.setPreset(MusePreset.PRESET_21) // verify against your headband model, see class doc
        muse.runAsynchronously()
    }

    private val connectionListener = object : MuseConnectionListener() {
        override fun receiveMuseConnectionPacket(p: MuseConnectionPacket, muse: Muse) {
            Log.d(TAG, "connection state -> ${p.currentConnectionState}")
            when (p.currentConnectionState) {
                ConnectionState.CONNECTED -> {
                    synchronized(lock) { lastPacketAtMs = System.currentTimeMillis() }
                    _status.value = _status.value.copy(state = DeviceState.CONNECTED, deviceName = muse.name)
                }
                ConnectionState.CONNECTING -> {
                    _status.value = _status.value.copy(state = DeviceState.CONNECTING, deviceName = muse.name)
                }
                ConnectionState.DISCONNECTED -> {
                    if (!stopped) rescanAfterDrop("connection reported DISCONNECTED", RESCAN_DELAY_MS)
                }
                ConnectionState.NEEDS_UPDATE, ConnectionState.NEEDS_LICENSE -> {
                    activeMuse = null
                    _status.value = DeviceStatus.DISCONNECTED
                }
                else -> Unit
            }
        }
    }

    private val dataListener = object : MuseDataListener() {
        override fun receiveMuseDataPacket(p: MuseDataPacket, muse: Muse) {
            synchronized(lock) {
                lastPacketAtMs = System.currentTimeMillis()
                when (p.packetType()) {
                    MuseDataPacketType.ALPHA_RELATIVE -> live = live.copy(alpha = mergeFresh(p, live.alpha))
                    MuseDataPacketType.BETA_RELATIVE -> live = live.copy(beta = mergeFresh(p, live.beta))
                    MuseDataPacketType.THETA_RELATIVE -> live = live.copy(theta = mergeFresh(p, live.theta))
                    MuseDataPacketType.GAMMA_RELATIVE -> live = live.copy(gamma = mergeFresh(p, live.gamma))
                    MuseDataPacketType.HSI_PRECISION -> live = live.copy(horseshoe = readFourChannels(p))
                    MuseDataPacketType.ACCELEROMETER -> handleAccel(p)
                    MuseDataPacketType.BATTERY -> handleBattery(p)
                    else -> Unit
                }
            }
        }

        override fun receiveMuseArtifactPacket(p: MuseArtifactPacket, muse: Muse) {
            synchronized(lock) {
                if (p.blink) blinkSinceLastTick = true
                if (p.jawClench) jawClenchSinceLastTick = true
            }
        }
    }

    /**
     * LibMuse repeats a channel's last clean band value while it rejects the current window
     * (blinks, poor contact), and sends NaN until the first clean one. Keep only values that
     * changed since the previous packet; channels with nothing fresh this tick stay NaN.
     */
    private fun mergeFresh(p: MuseDataPacket, pending: List<Float>): List<Float> {
        val raw = readFourChannels(p)
        val prev = lastRaw.put(p.packetType(), raw)
        val base = if (pending.size == 4) pending else List(4) { Float.NaN }
        return raw.mapIndexed { i, v -> if (!v.isNaN() && v != prev?.getOrNull(i)) v else base[i] }
    }

    private fun readFourChannels(p: MuseDataPacket): List<Float> = listOf(
        p.getEegChannelValue(Eeg.EEG1).toFloat(),
        p.getEegChannelValue(Eeg.EEG2).toFloat(),
        p.getEegChannelValue(Eeg.EEG3).toFloat(),
        p.getEegChannelValue(Eeg.EEG4).toFloat(),
    )

    private fun handleAccel(p: MuseDataPacket) {
        val next = Triple(
            p.getAccelerometerValue(Accelerometer.X),
            p.getAccelerometerValue(Accelerometer.Y),
            p.getAccelerometerValue(Accelerometer.Z),
        )
        val prev = lastAccel
        if (prev != null) {
            val dx = next.first - prev.first
            val dy = next.second - prev.second
            val dz = next.third - prev.third
            if (sqrt(dx * dx + dy * dy + dz * dz) > movementThreshold) movementSinceLastTick = true
        }
        lastAccel = next
    }

    private fun handleBattery(p: MuseDataPacket) {
        val pct = p.getBatteryValue(Battery.CHARGE_PERCENTAGE_REMAINING).toInt()
        _status.value = _status.value.copy(batteryPct = pct)
    }

    private suspend fun tickLoop() {
        while (true) {
            delay(periodMs)
            emitEpoch()
        }
    }

    private fun emitEpoch() {
        val snapshot = synchronized(lock) {
            val contact = if (live.horseshoe.size == 4) {
                ContactQuality(
                    tp9 = live.horseshoe[0].toInt(),
                    af7 = live.horseshoe[1].toInt(),
                    af8 = live.horseshoe[2].toInt(),
                    tp10 = live.horseshoe[3].toInt(),
                )
            } else {
                ContactQuality.UNKNOWN
            }
            val result = TickSnapshot(live, contact, blinkSinceLastTick, jawClenchSinceLastTick, movementSinceLastTick)
            blinkSinceLastTick = false
            jawClenchSinceLastTick = false
            movementSinceLastTick = false
            // Band values are per-tick: only what arrived fresh since the last epoch counts.
            live = live.copy(alpha = emptyList(), beta = emptyList(), theta = emptyList(), gamma = emptyList())
            result
        }

        if (activeMuse == null) return // nothing connected yet, nothing to emit

        val newState = if (snapshot.contact.allGood) DeviceState.CONNECTED else DeviceState.POOR_CONTACT
        if (newState != _status.value.state) {
            Log.d(TAG, "contact-driven state -> $newState (contact=${snapshot.contact.asList})")
        }
        _status.value = _status.value.copy(
            contact = snapshot.contact,
            state = newState,
        )

        val alphaTemporal = averageOf(snapshot.bands.alpha, TP9_INDEX, TP10_INDEX)
        val betaAvg = average(snapshot.bands.beta)
        val thetaAvg = average(snapshot.bands.theta)
        val gammaAvg = average(snapshot.bands.gamma)
        val emgLikeArtifact = gammaAvg > EMG_GAMMA_THRESHOLD && betaAvg > EMG_BETA_THRESHOLD

        val bandPowers = BandPowers(
            alphaTemporal = alphaTemporal,
            beta1320 = betaAvg,
            theta = thetaAvg,
            emgBroadband = gammaAvg,
        )

        val flags = ArtifactFlags(
            blink = snapshot.blink,
            jawClench = snapshot.jawClench,
            movement = snapshot.movement,
            emgBroadband = emgLikeArtifact,
        )

        val valid = !flags.any && snapshot.contact.allGood && bandPowers.engagementIndex.isFinite()

        Log.d(
            TAG,
            "epoch: valid=$valid focusIndex=${bandPowers.engagementIndex} " +
                    "artifacts=$flags contact=${snapshot.contact.asList} bands=${snapshot.bands}",
        )

        _epochs.tryEmit(
            FocusEpoch(
                timestampMs = System.currentTimeMillis(),
                focusIndex = bandPowers.engagementIndex,
                bands = bandPowers,
                contact = snapshot.contact,
                artifacts = flags,
                valid = valid,
            )
        )
    }

    private suspend fun watchdogLoop() {
        while (true) {
            delay(periodMs)
            val state = _status.value.state
            // Only meaningful once connected: during SCANNING/CONNECTING no packets are expected.
            if (state != DeviceState.CONNECTED && state != DeviceState.POOR_CONTACT) continue
            val last = synchronized(lock) { lastPacketAtMs }
            if (last == 0L) continue
            val silentFor = System.currentTimeMillis() - last
            if (silentFor > staleTimeoutMs) {
                rescanAfterDrop("watchdog: no packets for ${silentFor}ms", RESCAN_DELAY_MS)
            }
        }
    }

    /** Mean of the channels that have a value; NaN when none do. */
    private fun average(values: List<Float>): Double =
        values.filterNot { it.isNaN() }.map { it.toDouble() }.average()

    private fun averageOf(values: List<Float>, vararg indices: Int): Double {
        if (values.size < 4) return Double.NaN
        return average(indices.map { values[it] })
    }

    private data class LiveBands(
        val alpha: List<Float> = emptyList(),
        val beta: List<Float> = emptyList(),
        val theta: List<Float> = emptyList(),
        val gamma: List<Float> = emptyList(),
        val horseshoe: List<Float> = emptyList(),
    )

    private data class TickSnapshot(
        val bands: LiveBands,
        val contact: ContactQuality,
        val blink: Boolean,
        val jawClench: Boolean,
        val movement: Boolean,
    )

    companion object {
        private const val TAG = "MuseEegSource"
        private const val RESCAN_DELAY_MS = 2_000L
        private const val TP9_INDEX = 0
        private const val TP10_INDEX = 3
        private const val EMG_GAMMA_THRESHOLD = 3.0
        private const val EMG_BETA_THRESHOLD = 3.0
    }
}