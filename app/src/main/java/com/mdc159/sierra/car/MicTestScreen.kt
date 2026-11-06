package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.voice.diagnostics.Microphones
import com.mdc159.sierra.voice.diagnostics.VoiceDiagnostics
import com.mdc159.sierra.voice.duplex.MicSource
import com.mdc159.sierra.voice.stt.MicLevels
import com.mdc159.sierra.voice.tts.AndroidAudioFocus
import com.mdc159.sierra.voice.tts.AudioFocusGate
import kotlinx.coroutines.*

/** Five seconds of the selected input, no speech detector and no network request. */
class MicTestScreen(carContext: CarContext) : Screen(carContext) {
    private var input: MicSource? = null
    private var job: Job? = null
    private var result: String? = null
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { input?.stop(); job?.cancel() }
        })
        job = lifecycleScope.launch {
            val focus = AndroidAudioFocus(carContext)
            try {
                val mic = Microphones.selected(carContext).also { input = it }
                val levels = runInterruptible(Dispatchers.IO) {
                    if (!focus.request(AudioFocusGate.Mode.LISTEN) { mic.stop() }) throw IllegalStateException("Audio focus denied")
                    val levels = MicLevels(mic.sampleRate)
                    try {
                        mic.start()
                        val buf = ByteArray(1280)
                        while (levels.durationMs < 5_000) {
                            val n = mic.read(buf)
                            if (n < 0) break
                            if (n > 0) levels.offer(buf, n)
                        }
                    } finally { mic.stop(); focus.abandon() }
                    levels
                }
                result = when {
                    levels.bytes == 0L -> "No audio arrived. See Microphone & diagnostics on the phone."
                    levels.isDigitalSilence -> "Microphone sent silence. See diagnostics on the phone."
                    else -> "Audio received. Peak level ${levels.peakRms.toInt()}. Details saved on the phone."
                }
                VoiceDiagnostics.event(carContext, "Car mic test", "completed; bytes=${levels.bytes}; silent=${levels.isDigitalSilence}")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                VoiceDiagnostics.failure(carContext, "Car mic test failed", e)
                result = e.message ?: "Microphone test failed. See diagnostics on the phone."
            } finally { input?.stop(); focus.abandon() }
            invalidate()
        }
    }
    override fun onGetTemplate(): Template = template(result) { input?.stop(); job?.cancel(); screenManager.pop() }
    companion object {
        fun template(result: String?, stop: () -> Unit): Template = MessageTemplate.Builder(result ?: "Speak for five seconds. This test sends no audio to a server.")
            .setTitle("Microphone test").setHeaderAction(Action.BACK)
            .addAction(Action.Builder().setTitle(if (result == null) "Stop" else "Done").setOnClickListener(stop).build()).build()
    }
}
