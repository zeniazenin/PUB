# PlatformHardware adapter contract

This adapter does not authorize scripts. The parent broker must enforce the manifest capability, global and per-project rules, current foreground state, and Android runtime permission before calling `handle`. Every emitted event must be wrapped with the originating request ID and guarded by the broker's session generation. Close the adapter on project close/background or hardware-policy revocation and create a new adapter for the next session. `close()` is terminal.

Public API:

```kotlin
PlatformHardware(context, chooseDevice = { choices, selectedIdCallback -> /* native picker */ })
inventory(): JsonObject
handle(capability: String, operation: String, args: JsonObject, emit: (String, JsonObject) -> Unit)
val preferredAudioInput: AudioDeviceInfo?
prepareMicrophone(onReady: (AudioDeviceInfo?) -> Unit, onError: (String) -> Unit)
close()
```

`DeviceChoice(id, label)` is supplied by the host broker. The audio picker may show the local hardware product name so the user can identify Galaxy Buds or other endpoints. Script events use only generic device labels and Android session device IDs; Bluetooth addresses never leave the adapter. `inventory()` contains static capability presence and available sensor types/units only. It starts no streams and contains no coordinates, readings, product names, addresses, or identifiers.

## Operation arguments

All arguments are JSON objects. Optional fields have the defaults shown. Values outside documented bounds fail. Unknown operations fail. Errors emit `error` with `{code,message}`. Common codes are `invalid_arguments`, `permission_denied`, `unavailable`, `busy`, `limit`, `timeout`, `route_timeout`, `cancelled`.

| Capability / operation | Arguments | Events |
|---|---|---|
| `sensors / list` | `{}` | `sensors.list` with `sensors` array |
| `sensors / start` | `{sensor, rateHz?:10, durationMs?:300000}`; rate 1–10 Hz, duration 1–600 seconds; maximum 4 simultaneous sensor types | `sensors.started`, repeated `sensor`, then `sensors.stopped` on expiry |
| `sensors / stop` | `{sensor?}`; omission stops all sensor streams | `sensors.stopped` |
| `location / current` | `{provider?:"auto", timeoutMs?:20000}`; provider auto/gps/network, timeout 1–30 seconds | One `location`, or `location.unavailable` |
| `location / start` | `{provider?:"auto", intervalMs?:1000,durationMs?:300000}`; interval 1–60 seconds, duration 1–600 seconds | `location.started`, repeated `location`, `location.stopped` on expiry, `location.unavailable` on provider loss |
| `location / stop` | `{}` | `location.stopped`; cancels current-fix and stream requests |
| `haptics / vibrate` | `{durationMs?:100,amplitude?:128}`; duration 1–1000 ms, amplitude 1–255 | `haptics.done` acknowledges the bounded pulse was scheduled |
| `haptics / stop` | `{}` | `haptics.stopped` |
| `torch / set` | `{enabled:boolean}` | `torch.changed` |
| `audio / tone` | `{tone?:"beep",durationMs?:250,volume?:70}`; tone beep/ack/error/digit0…digit9, duration 50–3000 ms, volume 0–100 | `audio.started`, `audio.done` |
| `audio / speak` | `{text,language?:BCP47,rate?:1,pitch?:1}`; nonblank text max 500 characters, rate/pitch 0.5–2; max 15 seconds including initialization | `audio.started`, `audio.done`, or error; installed offline voice required |
| `audio / stop` | `{}` | `audio.stopped`; stops tone/speech without changing the selected route |
| `audio / devices` | `{}` | `audio.devices` with `devices:[{id,type,label}]`; communication outputs only |
| `audio / route` | `{}` | Opens native device picker, then `audio.route` with `{id,type,label,inputAvailable:true}` after route and matching input are ready; max 5-second route transition |
| `audio / routeReset` | `{}` | `audio.routeReset`; releases this app's routing request and restores the prior audio mode |
| `battery / get` | `{}` | `battery` |
| `infrared / frequencies` | `{}` | `infrared.frequencies` with `rangesHz:[{min,max}]` |
| `infrared / transmit` | `{frequencyHz,patternUs:[...]}`; supported carrier, 1–200 positive integer pulse/gap entries, sum ≤500000 µs; max one in flight and 750 ms between starts | `infrared.done` or error |

Only one location request/stream is retained. Starting a new one cancels the previous one. Sensor streams are keyed by sensor type; restarting a type replaces its old stream. Sensors are explicitly throttled at emission time, because Android's sensor sampling interval is only a hint.

The sensor keys are `accelerometer`, `accelerometer_uncalibrated`, `gyroscope`, `gyroscope_uncalibrated`, `magnetic_field`, `magnetic_field_uncalibrated`, `gravity`, `linear_acceleration`, `rotation_vector`, `game_rotation_vector`, `geomagnetic_rotation_vector`, `hinge_angle`, `light`, `proximity`, `pressure`, `relative_humidity`, `ambient_temperature`, `step_counter`, and `step_detector`. The availability list contains only types reported by this phone's Android sensor manager, including supported virtual sensors; no private vendor or heart rate/body sensors are exposed.

## Readings and units

`sensor` emits `{sensor,unit,timestampNs,accuracy,values}`. `timestampNs` uses Android's monotonic sensor-event clock, not Unix time. Vectors use Android device coordinates: X to the right, Y toward the top, Z outward from the display. Accelerometer/gravity/linear acceleration use m/s²; gyroscope rad/s; magnetic field µT; light lux; proximity cm; pressure hPa; relative humidity percent; ambient temperature °C. Rotation vector is Android's rotation-vector component array, optionally including scalar and heading-accuracy values; it is not Euler angles. Step counter is the Android total since boot while activated; step detector emits 1 per detected step.

Uncalibrated accelerometer, gyroscope, and magnetic-field arrays contain the X/Y/Z readings followed by their X/Y/Z estimated bias, in the same units as their calibrated counterparts; all six values are retained. Game rotation vector has no magnetic north reference and may drift; geomagnetic rotation vector uses the magnetic field without the gyroscope. Both use Android rotation-vector components. Hinge angle is the angle between the folding device's integral parts in degrees.

`location` emits latitude/longitude in degrees, optional `accuracyMeters`, Unix `timeMs`, monotonic `elapsedRealtimeNanos`, `approximate`, and `mock`. With fine access, optional altitude (meters), speed (m/s), and bearing (degrees) are also included. Android supplies approximate coordinates when only coarse permission is granted. If fine permission disappears during an active request, the request stops before a queued precise fix can be delivered; the app must restart location with coarse access. No background location, geofences, or activity service is used.

`battery` emits optional `percent`, `charging`, `powerConnected`, optional `temperatureCelsius`, and optional `voltageMillivolts`. It reads the protected sticky battery status without registering a persistent receiver.

## Audio routing

Routing uses Android 12+ communication-device APIs, not Bluetooth GATT or deprecated SCO start/stop calls. Selection happens only in a host-owned picker. Endpoint availability is revalidated after selection. Success waits for both the selected communication output and its corresponding input device; output-only or not-yet-ready routes fail clearly. The selected device preference survives Android's idle communication-mode demotion. Before starting capture, the host must call `prepareMicrophone`: it reasserts communication mode and device selection, then waits up to five seconds for the matching native input. `onReady(null)` means no explicit device was selected (or routing was explicitly reset); a disconnected or denied selected route calls `onError`, never null. Pending preparation is busy, and reset, new selection or close invalidate stale callbacks. Callbacks run on main; the host must additionally guard its microphone/session generation and permission when they arrive.

For a prepared communication input, use `AudioRecord.setPreferredDevice` and the `VOICE_COMMUNICATION` recording source, then verify `AudioRecord.routedDevice` after recording starts. Preference alone is not evidence of actual routing. `preferredAudioInput` is only a current-route snapshot, so its null result must not be used as permission to fall back from a selected device. Tone and speech operations also prepare the chosen route before playback.

If the selected endpoint disconnects or Bluetooth access ends, the adapter releases its routing request and emits `audio.routeLost`. **The parent must stop active microphone capture on this event** to avoid unintentional fallback to a different microphone. A disconnected preference remains blocked until a new device selection or explicit `audio.routeReset`; reconnecting does not silently choose a microphone. Idle route demotion alone does not count as disconnection. Closing clears the app's routing request and restores the prior audio mode if another call has not taken it over. `audio.stop` does not reset the route; `audio.routeReset` and `close` stop output and reset the route.

Tones use the music stream normally and the voice-call stream with an active communication route. TTS uses the corresponding audio attributes. The adapter never changes global stream volume. Speech selects an installed voice whose Android metadata says it does not require network synthesis; absent voices fail rather than trigger an installer or silently use cloud synthesis.

## Permissions and declarations

- `ACCESS_COARSE_LOCATION`; optionally `ACCESS_FINE_LOCATION`. To offer precise location, request both together, accepting the user's approximate-only choice.
- `ACTIVITY_RECOGNITION` for step counter/detector only. Ordinary motion/environment sensors do not require it.
- `CAMERA` for torch.
- Normal permissions: `VIBRATE`, `TRANSMIT_IR`, `MODIFY_AUDIO_SETTINGS`.
- `BLUETOOTH_CONNECT` for audio endpoint listing/routing and native product-name access. No scan, pairing, or GATT operation is performed by this adapter.
- Manifest visibility query for TTS engines: action `android.intent.action.TTS_SERVICE`.
- Hardware feature declarations should be optional (`required=false`) so missing hardware returns `unavailable` rather than preventing installation.

All registered listeners, timers, pending fixes, vibration, torch, tones, TTS, and routing are stopped/released on close; late emissions are suppressed. Consumer IR transmission is a synchronous OS operation with no cancellation API. A transmission already inside that call may complete after close, bounded by the validated 500 ms total pattern; queued work is cancelled and no callback is delivered after close.

## Authoritative Android references

- [SensorManager sampling and listener cleanup](https://developer.android.com/reference/android/hardware/SensorManager)
- [SensorEvent coordinate and unit definitions](https://developer.android.com/reference/android/hardware/SensorEvent)
- [LocationManager current fixes, cancellation, and updates](https://developer.android.com/reference/android/location/LocationManager)
- [AudioManager communication-device routing](https://developer.android.com/reference/android/media/AudioManager)
- [TextToSpeech lifecycle](https://developer.android.com/reference/android/speech/tts/TextToSpeech) and [Voice network requirements](https://developer.android.com/reference/android/speech/tts/Voice)
- [CameraManager torch mode](https://developer.android.com/reference/android/hardware/camera2/CameraManager)
- [VibrationEffect one-shot bounds](https://developer.android.com/reference/android/os/VibrationEffect)
- [ConsumerIrManager carrier frequencies and synchronous transmission](https://developer.android.com/reference/android/hardware/ConsumerIrManager)

Implementation has not yet been built or validated against physical hardware; the integration owner performs those checks.
