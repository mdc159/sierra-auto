package com.mdc159.sierra.net

import okhttp3.OkHttpClient
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

    /** Turns a network exception into a short message fit for a car screen. */
    fun describe(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "Timed out. Check signal and try again."
        is InterruptedIOException -> "Timed out. Check signal and try again."
        is UnknownHostException -> "No connection, or the server name is wrong."
        // OkHttp's message here is "Failed to connect to host/1.2.3.4:443", not car-screen text.
        is ConnectException -> "Couldn't reach the server."
        is SSLException -> "Secure connection failed."
        else -> e.message?.takeIf { it.isNotBlank() }?.take(120) ?: "Network error."
    }
}
