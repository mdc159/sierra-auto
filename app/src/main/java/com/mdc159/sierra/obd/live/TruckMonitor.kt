package com.mdc159.sierra.obd.live

import com.mdc159.sierra.obd.Dtc
import com.mdc159.sierra.obd.Elm327Client
import com.mdc159.sierra.obd.Elm327Config
import com.mdc159.sierra.obd.Elm327Info
import com.mdc159.sierra.obd.MonitorStatus
import com.mdc159.sierra.obd.ObdAdapterException
import com.mdc159.sierra.obd.ObdDisconnectedException
import com.mdc159.sierra.obd.ObdException
import com.mdc159.sierra.obd.ObdPid
import com.mdc159.sierra.obd.ObdPids
import com.mdc159.sierra.obd.ObdTimeoutException
import com.mdc159.sierra.obd.ObdTransport
import com.mdc159.sierra.obd.bt.AdapterUnavailableException
import com.mdc159.sierra.obd.bt.PendingConnect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** What the Truck screen renders. */
sealed interface TruckState {
    /** Opening the adapter and running the init sequence. [retry] is non-zero on a reconnect. */
    data class Connecting(val retry: Int = 0) : TruckState

    data class Live(val readings: TruckReadings, val adapter: Elm327Info) : TruckState

    /** Gave up. [message] is short and tells the driver what to do. */
    data class Failed(val message: String) : TruckState
}

/** What the Codes screen renders. */
sealed interface CodesState {
    data object Idle : CodesState
    data object Loading : CodesState
    data class Loaded(val stored: List<Dtc>, val status: MonitorStatus?) : CodesState
    data class Failed(val message: String) : CodesState
}

/**
 * Owns one adapter connection on its own thread and publishes decoded values.
 *
 * Lifecycle: [start] once, [close] once (from any thread). The worker opens the
 * transport with [openTransport], runs the ELM327 init, learns which PIDs the truck
 * supports, then polls the Truck screen's PIDs in a loop. Codes are read on request
 * from the same thread so there is never more than one command in flight.
 *
 * [openTransport] receives a [PendingConnect]; a factory that blocks (a Bluetooth
 * connect) registers its socket there so [close] can abort it. Otherwise a screen
 * that is backed out of during "Connecting" would leave a worker connecting for up
 * to the connect timeout, and a second Truck screen would race it for the dongle.
 *
 * A link that drops after it worked is reconnected up to [maxReconnects] times. An
 * adapter that cannot be reached at all ([AdapterUnavailableException]) fails at once:
 * the fix is on the phone, not in retrying.
 */
class TruckMonitor(
    private val openTransport: (PendingConnect) -> ObdTransport,
    private val pollIntervalMillis: Long = 250,
    private val reconnectDelayMillis: Long = 3_000,
    private val maxReconnects: Int = 3,
    private val config: Elm327Config = Elm327Config(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {

    private val _state = MutableStateFlow<TruckState>(TruckState.Connecting())
    val state: StateFlow<TruckState> = _state

    private val _codes = MutableStateFlow<CodesState>(CodesState.Idle)
    val codes: StateFlow<CodesState> = _codes

    private val stopped = AtomicBoolean(false)
    private val codesWanted = AtomicBoolean(false)
    private val transportLock = Any()
    private var transport: ObdTransport? = null
    private var pendingConnect: PendingConnect? = null
    private var worker: Thread? = null

    /** How many times [openTransport] was called, for tests and logs. */
    @Volatile
    var connectAttempts: Int = 0
        private set

    @Synchronized
    fun start() {
        if (worker != null || stopped.get()) return
        worker = Thread({ runLoop() }, "sierra-obd").apply {
            isDaemon = true
            start()
        }
    }

    /** Asks the worker to read stored codes on its next turn. */
    fun requestCodes() {
        if (stopped.get() || _state.value is TruckState.Failed) {
            _codes.value = CodesState.Failed(
                (_state.value as? TruckState.Failed)?.message ?: "Adapter not connected.",
            )
            return
        }
        _codes.value = CodesState.Loading
        codesWanted.set(true)
    }

    /**
     * Stops polling, aborts a connect still in flight and closes the adapter link.
     * Safe to call twice, from any thread.
     */
    override fun close() {
        stopped.set(true)
        worker?.interrupt()
        synchronized(transportLock) { pendingConnect }?.cancel()
        closeTransport()
    }

    /** True once the worker thread has exited (or never started). */
    val isFinished: Boolean get() = worker?.isAlive != true

    private fun closeTransport() {
        val t = synchronized(transportLock) { transport.also { transport = null } }
        t?.let { runCatching { it.close() } }
    }

    private fun runLoop() {
        var reconnects = 0
        var connectedOnce = false
        while (!stopped.get()) {
            _state.value = TruckState.Connecting(reconnects)
            try {
                connectAttempts++
                val pending = PendingConnect()
                synchronized(transportLock) {
                    if (stopped.get()) return
                    pendingConnect = pending
                }
                val t = try {
                    openTransport(pending)
                } finally {
                    pending.clear()
                    synchronized(transportLock) { pendingConnect = null }
                }
                synchronized(transportLock) {
                    if (stopped.get() || pending.isCancelled) {
                        runCatching { t.close() }
                        return
                    }
                    transport = t
                }
                val client = Elm327Client(t, config)
                val info = client.initialize()
                val supported = runCatching { client.supportedPids() }.getOrDefault(emptySet())
                connectedOnce = true
                reconnects = 0
                session(client, info, supported)
                return // stopped
            } catch (e: AdapterUnavailableException) {
                fail(e.message ?: "Adapter not available.")
                return
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (stopped.get()) return
                val canRetry = connectedOnce && reconnects < maxReconnects
                if (!canRetry) {
                    fail(describe(e))
                    return
                }
                reconnects++
                closeTransport()
                if (!pause(reconnectDelayMillis)) return
            } finally {
                closeTransport()
            }
        }
    }

    private fun fail(message: String) {
        _state.value = TruckState.Failed(message)
        if (_codes.value is CodesState.Loading) _codes.value = CodesState.Failed(message)
    }

    /** Polls until stopped. Throws when the link is gone so [runLoop] can reconnect. */
    private fun session(client: Elm327Client, info: Elm327Info, supported: Set<Int>) {
        var consecutiveTimeouts = 0
        while (!stopped.get()) {
            if (codesWanted.getAndSet(false)) readCodes(client, supported)
            val readings = poll(client, supported) { timedOut ->
                consecutiveTimeouts = if (timedOut) consecutiveTimeouts + 1 else 0
            }
            if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                throw ObdDisconnectedException("Adapter stopped answering.")
            }
            _state.value = TruckState.Live(readings, info)
            if (!pause(pollIntervalMillis)) return
        }
    }

    private fun poll(client: Elm327Client, supported: Set<Int>, onRead: (timedOut: Boolean) -> Unit): TruckReadings {
        var ok = 0
        fun <T : Any> value(pid: ObdPid<T>): T? {
            if (supported.isNotEmpty() && pid.pid !in supported) return null
            return try {
                client.read(pid).also { ok++; onRead(false) }
            } catch (e: ObdTimeoutException) {
                onRead(true)
                null
            } catch (e: ObdDisconnectedException) {
                throw e
            } catch (e: ObdException) {
                onRead(false)
                null
            }
        }
        val rpm = value(ObdPids.ENGINE_RPM)
        val speed = value(ObdPids.VEHICLE_SPEED)
        val coolant = value(ObdPids.COOLANT_TEMP)
        val intake = value(ObdPids.INTAKE_AIR_TEMP)
        val load = value(ObdPids.ENGINE_LOAD)
        val fuel = value(ObdPids.FUEL_LEVEL)
        // PID 42 is the ECM's own supply voltage; fall back to the adapter's ATRV reading.
        val voltage = value(ObdPids.CONTROL_MODULE_VOLTAGE) ?: try {
            client.readBatteryVoltage().also { ok++ }
        } catch (e: ObdDisconnectedException) {
            throw e
        } catch (e: ObdException) {
            null
        }
        return TruckReadings(
            rpm = rpm, speedKmh = speed, coolantC = coolant, intakeC = intake,
            loadPct = load, fuelPct = fuel, voltage = voltage,
            okCount = ok, updatedAtMillis = clock(),
        )
    }

    private fun readCodes(client: Elm327Client, supported: Set<Int>) {
        try {
            val stored = client.readStoredCodes()
            val status = if (supported.isEmpty() || ObdPids.MONITOR_STATUS.pid in supported) {
                try {
                    client.read(ObdPids.MONITOR_STATUS)
                } catch (e: ObdDisconnectedException) {
                    throw e
                } catch (e: ObdException) {
                    null
                }
            } else {
                null
            }
            _codes.value = CodesState.Loaded(stored, status)
        } catch (e: ObdDisconnectedException) {
            _codes.value = CodesState.Failed(describe(e))
            throw e
        } catch (e: ObdException) {
            _codes.value = CodesState.Failed(describe(e))
        }
    }

    /** Sleeps unless stopped. Returns false if the wait was cut short by [close]. */
    private fun pause(millis: Long): Boolean {
        if (stopped.get()) return false
        if (millis <= 0) return true
        return try {
            Thread.sleep(millis)
            !stopped.get()
        } catch (e: InterruptedException) {
            false
        }
    }

    companion object {
        const val MAX_CONSECUTIVE_TIMEOUTS = 3

        /** A short car-screen explanation for an OBD failure. */
        fun describe(e: Throwable): String = when (e) {
            is AdapterUnavailableException -> e.message ?: "Adapter not available."
            is ObdTimeoutException -> "Adapter didn't answer. Is it plugged in?"
            is ObdDisconnectedException -> "Lost the adapter. Check it and tap Retry."
            is ObdAdapterException -> when {
                e.reason.startsWith("UNABLE TO CONNECT") || e.reason.startsWith("BUS INIT") ->
                    "Adapter found, but the truck isn't answering. Turn the key on."
                e.reason.startsWith("CAN ERROR") || e.reason.startsWith("BUS ERROR") ->
                    "CAN bus error. Check the adapter is seated in the port."
                e.reason.startsWith("NO DATA") -> "No data from the truck. Is the engine on?"
                else -> "Adapter error: ${e.reason.take(60)}"
            }
            is ObdException -> e.message?.take(100) ?: "OBD error."
            is IOException -> "Connection error: ${e.message?.take(80) ?: "unknown"}"
            else -> e.message?.take(100) ?: "Couldn't read the truck."
        }
    }
}
