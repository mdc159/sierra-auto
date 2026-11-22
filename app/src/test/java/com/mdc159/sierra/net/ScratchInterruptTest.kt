package com.mdc159.sierra.net

import com.mdc159.sierra.places.NominatimClient
import com.mdc159.sierra.places.PlaceCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.junit.Test

/** Scratch: coroutine cancel + runInterruptible over Nominatim blocking search — does the socket close? */
class ScratchInterruptTest {
    @Test
    fun cancelDuringNominatimSearch() {
        val stalled = StalledConnectionServer().start()
        try {
            val nominatim = NominatimClient(baseUrl = stalled.url + "search", requestSpacingMs = 0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val job = scope.launch {
                runInterruptible(Dispatchers.IO) {
                    runCatching { nominatim.search(PlaceCategory.FUEL, 38.5, -121.5, 6) }
                        .onFailure { println("SCRATCH search ended: $it") }
                }
            }
            runBlocking {
                delay(500)
                job.cancelAndJoin()
                println("SCRATCH cancelAndJoin returned")
                println("SCRATCH closed within 2s of cancel: ${stalled.clientClosedWithin(2_000)}")
            }
        } finally {
            stalled.shutdown()
        }
    }
}
