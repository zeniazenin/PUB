package app.pub.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import app.pub.core.PitchDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MicrophoneCapture(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val captureLock = Any()
    @Volatile private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission") // The broker checks before start and for every delivered result.
    fun start(onPitch: (PitchDetector.Result, Int) -> Unit, onError: (String) -> Unit, input: android.media.AudioDeviceInfo? = null) {
        stop()
        val token = generation.get()
        executor.execute {
            var audio: AudioRecord? = null
            try {
                val manager = context.getSystemService(AudioManager::class.java)
                val unprocessed = manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
                val source = if (input != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION else if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
                for (rate in listOf(48000, 44100)) {
                    val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (size <= 0) continue
                    val candidate = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(size * 2, 16384))
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) { audio = candidate; break }
                    candidate.release()
                }
                val capture = checkNotNull(audio) { "No supported microphone configuration" }
                if(input != null) check(capture.setPreferredDevice(input)) { "Selected microphone route is unavailable" }
                val rate = capture.sampleRate
                val detector = PitchDetector(rate, 4096, 65.0, 400.0)
                val raw = ShortArray(2048)
                val frame = FloatArray(4096)
                var filled = 0
                synchronized(captureLock) {
                    if (generation.get() != token) return@execute
                    recorder = capture
                    capture.startRecording()
                }
                check(capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                val routeDeadline = android.os.SystemClock.elapsedRealtime() + 2000
                while (generation.get() == token) {
                    val count = capture.read(raw, 0, raw.size, AudioRecord.READ_BLOCKING)
                    if (count < 0) error("Microphone read failed ($count)")
                    if (count == 0) continue
                    if(input != null) {
                        val routed = capture.routedDevice
                        val matches = routed != null && routed.type == input.type &&
                            (routed.id == input.id || (input.address.isNotBlank() && routed.address == input.address))
                        if(!matches) {
                            if(android.os.SystemClock.elapsedRealtime() >= routeDeadline) error("Selected microphone route was not activated. Reconnect or choose an audio device again.")
                            continue // Never deliver samples from a fallback microphone.
                        }
                    }
                    if (filled + count > frame.size) {
                        val drop = filled + count - frame.size
                        System.arraycopy(frame, drop, frame, 0, filled - drop)
                        filled -= drop
                    }
                    for (i in 0 until count) frame[filled + i] = raw[i] / 32768f
                    filled += count
                    if (filled == frame.size) {
                        val result = detector.detect(frame)
                        main.post { if (generation.get() == token) onPitch(result, rate) }
                    }
                }
            } catch (e: Exception) {
                main.post { if (generation.get() == token) onError(e.message ?: "Microphone unavailable") }
            } finally {
                runCatching { audio?.stop() }
                audio?.release()
                if (recorder === audio) recorder = null
            }
        }
    }
    fun stop() {
        synchronized(captureLock) {
            generation.incrementAndGet() // Invalidates already queued samples before releasing hardware.
            runCatching { recorder?.stop() }
        }
    }
    fun close() { stop(); executor.shutdownNow() }
}
