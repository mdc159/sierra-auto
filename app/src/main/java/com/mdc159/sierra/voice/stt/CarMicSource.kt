package com.mdc159.sierra.voice.stt

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.core.content.ContextCompat
import com.mdc159.sierra.agent.AgentException

/**
 * The car microphone as a [MicSource]. One instance records one session; the host shows
 * its own microphone indicator while recording and makes [read] return -1 when the driver
 * dismisses it. Needs Car App API level 5 and RECORD_AUDIO (see CarMic.isSupported and
 * CarMic.hasPermission in voice/CarMic.kt).
 */
class CarMicSource(private val carContext: CarContext) : MicSource {

    private var record: CarAudioRecord? = null

    override val sampleRate: Int get() = CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE

    override fun start() {
        // The screen asks for RECORD_AUDIO before it gets here; this check is what lint's
        // MissingPermission wants to see next to the call, and it guards a revoked grant.
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw AgentException("Allow the microphone on your phone.")
        }
        val r = try {
            CarAudioRecord.create(carContext)
        } catch (e: SecurityException) {
            throw AgentException("Allow the microphone on your phone.", e)
        } catch (e: Exception) {
            throw AgentException("Car microphone unavailable.", e)
        }
        record = r
        r.startRecording()
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int {
        val r = record ?: return -1
        return r.read(buf, offset, minOf(length, CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE))
    }

    override fun stop() {
        val r = record ?: return
        record = null
        runCatching { r.stopRecording() }
    }

    companion object {
        /** The host's read buffer; one read is about 16 ms of 16 kHz audio. */
        const val BUFFER_SIZE: Int = CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE
    }
}
