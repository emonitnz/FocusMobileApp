package com.example.compx551a4.device

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import kotlin.math.sqrt

class OscEegSource(
    private val port: Int = 5000,
    private val periodMs: Long = 1_000L,
    private val staleTimeoutMs: Long = 5_000L,
    private val movementThreshold: Double = 0.6,
) : EegSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiveJob: Job? = null
    private var tickJob: Job? = null
    private var watchdogJob: Job? = null
    private var socket: DatagramSocket? = null

    private val _status = MutableStateFlow(DeviceStatus.DISCONNECTED)
    override val status: StateFlow<DeviceStatus> = _status

    private val _epochs = MutableSharedFlow<FocusEpoch>(extraBufferCapacity = 8)
    override val epochs: Flow<FocusEpoch> = _epochs.asSharedFlow()

    private val stateLock = Mutex()
    private var live = LiveBands()
    private var lastAccel: Triple<Double, Double, Double>? = null
    private var lastPacketAtMs = 0L
    private var blinkSinceLastTick = false
    private var jawClenchSinceLastTick = false
    private var movementSinceLastTick = false

    override suspend fun start() {
        if (receiveJob?.isActive == true) return

        _status.value = DeviceStatus(DeviceState.SCANNING, deviceName = "Mind Monitor (OSC :$port)")

        val sock = try {
            DatagramSocket(port)
        } catch (e: Exception) {
            _status.value = DeviceStatus.DISCONNECTED
            return
        }
        socket = sock

        receiveJob = scope.launch(Dispatchers.IO) { receiveLoop(sock) }
        tickJob = scope.launch { tickLoop() }
        watchdogJob = scope.launch { watchdogLoop() }
    }

    override fun stop() {
        receiveJob?.cancel()
        tickJob?.cancel()
        watchdogJob?.cancel()
        socket?.close()
        socket = null
        receiveJob = null
        tickJob = null
        watchdogJob = null
        _status.value = DeviceStatus.DISCONNECTED
    }

    private suspend fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(1024)
        while (true) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
            } catch (e: SocketException) {
                break
            }
            val msg = OscDecoder.decode(packet.data, packet.length) ?: continue
            handleMessage(msg)
        }
    }

    private suspend fun handleMessage(msg: OscMessage) {
        val now = System.currentTimeMillis()
        stateLock.withLock {
            lastPacketAtMs = now
            when (msg.address) {
                "/muse/elements/alpha_absolute" -> if (msg.args.size == 4) live = live.copy(alpha = msg.args)
                "/muse/elements/beta_absolute" -> if (msg.args.size == 4) live = live.copy(beta = msg.args)
                "/muse/elements/theta_absolute" -> if (msg.args.size == 4) live = live.copy(theta = msg.args)
                "/muse/elements/gamma_absolute" -> if (msg.args.size == 4) live = live.copy(gamma = msg.args)
                "/muse/elements/horseshoe" -> if (msg.args.size == 4) live = live.copy(horseshoe = msg.args)
                "/muse/elements/blink" -> if ((msg.args.getOrNull(0) ?: 0f) > 0f) blinkSinceLastTick = true
                "/muse/elements/jaw_clench" -> if ((msg.args.getOrNull(0) ?: 0f) > 0f) jawClenchSinceLastTick = true
                "/muse/acc" -> if (msg.args.size == 3) {
                    val next = Triple(msg.args[0].toDouble(), msg.args[1].toDouble(), msg.args[2].toDouble())
                    val prev = lastAccel
                    if (prev != null) {
                        val dx = next.first - prev.first
                        val dy = next.second - prev.second
                        val dz = next.third - prev.third
                        if (sqrt(dx * dx + dy * dy + dz * dz) > movementThreshold) movementSinceLastTick = true
                    }
                    lastAccel = next
                }
                "/muse/batt" -> {
                    msg.args.getOrNull(0)?.let { raw ->
                        _status.value = _status.value.copy(batteryPct = (raw / 10f).toInt())
                    }
                }
                else -> Unit
            }

            if (_status.value.state == DeviceState.SCANNING) {
                _status.value = _status.value.copy(state = DeviceState.CONNECTED)
            }
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            delay(periodMs)
            emitEpoch()
        }
    }

    private suspend fun emitEpoch() {
        val snapshot = stateLock.withLock {
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
            result
        }

        _status.value = _status.value.copy(
            contact = snapshot.contact,
            state = if (snapshot.contact.allGood) DeviceState.CONNECTED else DeviceState.POOR_CONTACT,
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

        val hasData = snapshot.bands.alpha.size == 4 && snapshot.bands.beta.size == 4
        val valid = !flags.any && snapshot.contact.allGood && hasData

        _epochs.emit(
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
            val silentFor = System.currentTimeMillis() - lastPacketAtMs
            if (lastPacketAtMs != 0L && silentFor > staleTimeoutMs && _status.value.state != DeviceState.DISCONNECTED) {
                _status.value = DeviceStatus.DISCONNECTED
            }
        }
    }

    private fun average(values: List<Float>): Double =
        if (values.isEmpty()) 0.0 else values.map { it.toDouble() }.average()

    private fun averageOf(values: List<Float>, vararg indices: Int): Double {
        if (values.size < 4) return 0.0
        return indices.map { values[it].toDouble() }.average()
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
        private const val TP9_INDEX = 0
        private const val TP10_INDEX = 3
        private const val EMG_GAMMA_THRESHOLD = 3.0
        private const val EMG_BETA_THRESHOLD = 3.0
    }
}

private data class OscMessage(val address: String, val args: List<Float>)

private object OscDecoder {

    fun decode(data: ByteArray, length: Int): OscMessage? {
        if (length <= 0 || data[0] != '/'.code.toByte()) return null

        val (address, afterAddress) = readString(data, 0, length) ?: return null
        var offset = afterAddress
        if (offset >= length || data[offset] != ','.code.toByte()) return OscMessage(address, emptyList())

        val (typeTag, afterTypeTag) = readString(data, offset, length) ?: return OscMessage(address, emptyList())
        offset = afterTypeTag

        val args = mutableListOf<Float>()
        for (tag in typeTag.drop(1)) {
            when (tag) {
                'f' -> {
                    if (offset + 4 > length) break
                    args += readFloat(data, offset)
                    offset += 4
                }
                'i' -> {
                    if (offset + 4 > length) break
                    args += readInt(data, offset).toFloat()
                    offset += 4
                }
                'T' -> args += 1f
                'F' -> args += 0f
                's' -> {
                    val (_, next) = readString(data, offset, length) ?: break
                    offset = next
                }
                else -> break
            }
        }
        return OscMessage(address, args)
    }

    private fun readString(data: ByteArray, offset: Int, length: Int): Pair<String, Int>? {
        var end = offset
        while (end < length && data[end] != 0.toByte()) end++
        if (end >= length) return null
        val str = String(data, offset, end - offset, Charsets.US_ASCII)
        var next = end + 1
        next += (4 - (next % 4)) % 4
        return str to next
    }

    private fun readFloat(data: ByteArray, offset: Int): Float = Float.fromBits(readInt(data, offset))

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
}

