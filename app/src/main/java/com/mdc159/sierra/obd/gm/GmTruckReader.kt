package com.mdc159.sierra.obd.gm

import com.mdc159.sierra.obd.Elm327Client
import com.mdc159.sierra.obd.ObdAdapterException
import com.mdc159.sierra.obd.ObdNegativeResponseException
import com.mdc159.sierra.obd.ObdParseException
import com.mdc159.sierra.obd.ObdTimeoutException

/**
 * Reads [GmDid]s through an initialised, read-only [Elm327Client].
 *
 * Each read goes through `Elm327Client.readExtended`, so it inherits the client's
 * allowlist check, header switching (`AT SH <module>` then back to `7DF`), deadline
 * and resync behaviour. Negative responses, `NO DATA` and timeouts come back as
 * [GmReading]s instead of exceptions; a dropped link or a blocked command still
 * throws, because those are not answers.
 *
 * Call from a background thread. Not wired into any screen.
 */
class GmTruckReader(private val client: Elm327Client) {

    /** One read of [did] from [module] (the DID's own module unless overridden). */
    fun <T> read(did: GmDid<T>, module: GmModule = did.module): GmReading<T> = try {
        GmReading.Value(client.readExtended(did.toExtendedPid(module)), module)
    } catch (e: ObdNegativeResponseException) {
        GmReading.NegativeResponse(e.code, module)
    } catch (e: ObdAdapterException) {
        GmReading.NoReply(e.reason, module)
    } catch (e: ObdTimeoutException) {
        GmReading.NoReply("timeout after ${e.timeoutMillis} ms", module)
    } catch (e: ObdParseException) {
        GmReading.Malformed(e.message ?: "unparseable reply", module)
    }

    /**
     * Reads [did] from its module, then from each fallback module while the answer
     * is "wrong address" (`NO DATA`, or a negative response that suggests the DID is
     * unknown there). Returns the first value, otherwise the last answer.
     *
     * Meant for the first test on the truck, where the goal is to learn which module
     * answers. Once that is known, use [read] with the right module and stop probing.
     */
    fun <T> readWithFallbacks(did: GmDid<T>): GmReading<T> {
        var last = read(did)
        if (last.isValue) return last
        for (module in did.fallbackModules) {
            if (!worthRetrying(last)) return last
            last = read(did, module)
            if (last.isValue) return last
        }
        return last
    }

    /** Every reading in [GmDids.FIRST_TEST], probing fallback modules. */
    fun firstTest(): List<GmReading<*>> = GmDids.FIRST_TEST.map { readWithFallbacks(it) }

    private fun worthRetrying(reading: GmReading<*>): Boolean = when (reading) {
        is GmReading.NoReply -> true
        is GmReading.NegativeResponse -> reading.suggestsWrongModule
        is GmReading.Malformed -> false
        is GmReading.Value -> false
    }
}
