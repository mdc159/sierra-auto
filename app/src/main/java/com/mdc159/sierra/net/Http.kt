package com.mdc159.sierra.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Shared HTTP client. Every call has bounded connect, read, write and total time. */
object Http {
    const val USER_AGENT = "SierraAuto/0.1 (personal Android Auto app; github.com/mdc159/sierra-auto)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * For the owner's agents behind the Sierra bridge: a coding agent may run tools for a minute
     * or two before the first byte of its (non-streamed) answer. Callers bound each call with
     * `call.timeout()`; the read timeout only has to outlast the agent's silence.
     */
    val slowClient: OkHttpClient = client.newBuilder()
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    /** Turns a network exception into a short message fit for a car screen. */
    fun describe(e: Throwable, endpoint: String? = null): String {
        val privateServer = runCatching {
            val host = URI(endpoint.orEmpty()).host?.lowercase().orEmpty()
            val octets = host.split('.').map { it.toIntOrNull() }
            host.endsWith(".ts.net") || host.removePrefix("[").startsWith("fd7a:115c:a1e0:") ||
                (octets.size == 4 && octets[0] == 100 && octets[1]?.let { it in 64..127 } == true &&
                    octets.all { it != null && it in 0..255 })
        }.getOrDefault(false)
        if (privateServer && (e is UnknownHostException || e is ConnectException || e is SocketTimeoutException)) {
            return "Can't reach your private server. Open Tailscale, check it is connected, then retry."
        }
        return when (e) {
            is SocketTimeoutException -> "Timed out. Check signal and try again."
            is InterruptedIOException -> "Timed out. Check signal and try again."
            is UnknownHostException -> "No connection, or the server name is wrong."
            // OkHttp's message here is "Failed to connect to host/1.2.3.4:443", not car-screen text.
            is ConnectException -> "Couldn't reach the server."
            is SSLException -> "Secure connection failed."
            else -> e.message?.takeIf { it.isNotBlank() }?.take(120) ?: "Network error."
        }
    }
}

/**
 * Runs one call without blocking a thread, and aborts the call when the calling coroutine is
 * cancelled: `withTimeout`/`Job.cancel()` release the socket at once instead of waiting out
 * OkHttp's own timeouts. Blocking `Call.execute()` cannot do this — a thread interrupt does
 * not abort an OkHttp socket read — so every production call that runs inside a cancellable
 * coroutine (vault reads, transcription) awaits the call through this helper.
 *
 * Cancellation is armed for the response **headers** only: once [onResponse] resumes, the
 * continuation is complete and coroutine cancellation no longer touches the call. Use
 * [executeCancellable] when the body must be consumed cancellably too.
 *
 * The caller owns the returned [Response] and must close it, for example with `use`.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // If cancellation won the race, close the body nobody is waiting for.
            continuation.resume(response) { _, body, _ -> body.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}

/**
 * Runs one call to completion — waiting for the response *and* consuming its body through
 * [block] on [dispatcher] — while keeping coroutine cancellation wired to [Call.cancel]
 * for that whole time. [await] alone is not enough: once it resumes with the headers, its
 * cancellation hook is spent, so a server that answers with headers and then stalls the
 * body would leave `Response.body.string()` blocked until OkHttp's own read timeout, and a
 * caller timeout (the 8 s vault refresh) or Stop could not release it.
 *
 * Here a guard coroutine cancels the call the moment the caller's job is cancelled, which
 * unblocks the in-flight read at once; the socket `IOException` that closes the read then
 * surfaces as the caller's [kotlinx.coroutines.CancellationException] (checked again with
 * `ensureActive` before the original `IOException` is rethrown), never as a domain error.
 */
suspend fun <T> Call.executeCancellable(
    dispatcher: CoroutineDispatcher,
    block: suspend (Response) -> T,
): T = coroutineScope {
    // ATOMIC, so the guard enters its body even when cancellation wins the start race and
    // its `finally` — the actual `Call.cancel()` — always runs when the caller goes away.
    val guard = launch(start = CoroutineStart.ATOMIC) {
        try {
            awaitCancellation()
        } finally {
            this@executeCancellable.cancel()
        }
    }
    try {
        // Headers first (no thread held); the blocking body read runs on [dispatcher], with
        // the guard above armed for the whole response lifetime.
        await().use { response -> withContext(dispatcher) { block(response) } }
    } catch (e: IOException) {
        // Cancelling the call closes the socket, which surfaces here as an IOException.
        // That is the caller's cancellation, not a network failure: let it through.
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        guard.cancel()
    }
}
