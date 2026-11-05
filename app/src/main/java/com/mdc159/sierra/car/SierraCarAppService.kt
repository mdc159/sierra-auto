package com.mdc159.sierra.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mdc159.sierra.agent.Conversation
import com.mdc159.sierra.voice.Speaker

/**
 * Entry point Android Auto binds to.
 *
 * Release builds only accept the known Android Auto / Automotive hosts (the library's
 * allowlist checks the host package and signing certificate). Debug builds accept any
 * host so the Desktop Head Unit and test hosts work.
 */
class SierraCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = SierraSession()
}

/** One Android Auto connection. Owns the voice output and the short conversation. */
class SierraSession : Session() {

    val conversation = Conversation()

    private var speakerInstance: Speaker? = null
    val speaker: Speaker
        get() = speakerInstance ?: Speaker(carContext).also { speakerInstance = it }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                speakerInstance?.shutdown()
                speakerInstance = null
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = HomeScreen(carContext, this)
}
