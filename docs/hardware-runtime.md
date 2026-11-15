# PUB 0.4.2 hardware support

Implemented November 5, 2026. Android 12+; target SDK 35.

## User workflow

Create an app describing the hardware it needs. The builder now receives available hardware and sensor types, supported capability groups, locale/style and the global block state, alongside the description. The same supported hardware inventory is available in runtime init.device.hardware. Personal readings, captured images, Bluetooth names and hardware addresses are not automatically sent to OpenAI.

Settings → Access has a capability selector, global block, default rule and per-app override. Native permission prompts offer session access or persistent access for saved apps. Android grants remain independent. Device pickers are native and cannot be replaced by generated UI. A Stop hardware control ends the active session. Leaving PUB or closing the app stops hardware and clears session grants.

Settings → Hardware inventory lists all public Android sensor descriptors (including vendor types), camera/lens routes, declared hardware features, display modes/HDR, CPU/ABI/RAM, audio endpoints, paired Bluetooth metadata, attached USB devices, NFC and infrared availability. Detection is separate from PUB adapter support and user permission. Refresh rereads local metadata. The generation summary is bounded to 15 KiB and omits accessory names; detailed local inventory does not enter generation. OEM-hidden components cannot be discovered through public APIs.

Settings → Hardware check is a local diagnostic app using the ordinary sandbox/permission path. It can be previewed without model generation; Save locally is optional.

## Capability scope

| Group | Available operations | Limits |
|---|---|---|
| camera | Native logical/physical lens selection, supported resolution/zoom/flash/exposure/focus/white balance, still capture and scoped image display | Full captured JPEG resolution, quality70/85/95/100, personal metadata stripped, max20 photos/1GiB per app and256MiB each; no video/raw frames/OCR |
| location | Current fix or foreground updates | Approximate grants supported; GPS needs provider/permissions; no background collection |
| sensors | Available motion, rotation, environmental, step and hinge sensors | Up to4 streams at1–10Hz; max10min per stream; Android activity grant for steps; no health/private sensor data |
| bluetooth | Bounded BLE discovery, native picker, GATT discovery/read/write/notifications; secure paired RFCOMM | One selected device per session; known UUIDs/protocol needed; no raw MAC access from scripts |
| audio | Native headphone/speaker route, device availability, short tones, offline TTS | Route waits for input/output readiness; disconnect stops microphone; no ANC/vendor-specific headphone controls |
| microphone.pitch | Native pitch/confidence/level | No raw audio files or speech transcription to generated apps |
| haptics | Short vibration, stop | Max1 second |
| torch | Flashlight on/off | Requires flash; camera may own it |
| battery | Charge/level/temperature/voltage where present | Phone battery only |
| infrared | Supported frequencies, bounded transmit | Requires emitter; max500ms transmission cannot be interrupted once inside Android API |
| nfc | Native foreground read-only NDEF reader | Max16 records/4096 bytes,30s; no tag writes, emulation or payment data |
| usb | Host device picker + Android grant, interface claim, bulk read/write | Known device protocol; max4096 bytes,2s timeout; no control transfers, filesystem mounting or generic drivers |
| files | System file picker (open, open several, folder, Save as), then stat/list/read/write/mkdir/delete/rename on what was picked, import/export with the app's private folder | Only what the user picks, by opaque handle; no storage permissions or browsing; 32 per app, kept for saved apps, session-only in previews; see files.md |

Audio and Bluetooth-data are separate. For Galaxy Buds Pro, pair/connect in Android settings and use Hardware check → Headphones & microphone → Choose audio device. Wait for audio.route before microphone start. Headset call-mode capture has different quality/bandwidth from the built-in microphone. The selected communication route is reactivated before microphone use after idle; capture verifies the actual input and rejects unexpected fallback to the phone. Disconnect requires explicit reselection.

## Runtime contract and boundaries

Hardware effects are `{type:"hardware.request",id,capability,operation,args}`. HardwareContract validates envelope/capability/operation and bounded args; native adapters validate values, limits and device identities. Replies are `{type:"hardware",id,capability,event,data}`. Streams reuse the request ID. Generated apps must handle errors, cancellation, unavailable hardware and `hardwareStopped`.

Only the native host determines project identity. Explicit device selection grants access to the selected device for the session. Generated scripts cannot open arbitrary Android classes, filesystem paths, content URIs, arbitrary device addresses or arbitrary image URLs. Isolated JavaScript still has no INTERNET permission. A capability declaration alone grants no hardware access.

Each broker is bound to a project session and foreground epoch. Closing/revoking invalidates pending Android requests, native pickers and late callbacks. Stream replies recheck policy and Android permissions. Native adapters use bounded buffers/deadlines; no unbounded frame/audio streams cross Binder. Only high-rate updates may be dropped under backpressure: while the script is busy, each sensor, location or BLE-notification stream keeps only its newest pending value, and stopping hardware discards those pending values. Request results are retained.

Files on the phone (`files`) follow the same broker rules with three differences. The system file picker is a native screen: `pick` and Save as (`export` with `createName`) need a user action and the native-screen lock, and because the picker covers PUB, its pending answer survives the Activity stop and is delivered to a new broker session of the same app run with the asking session's consent. Once file access is allowed (Allow rule or consent this session), the other files operations need no new tap, so saved apps can reopen picked documents at init; `delete` always needs one. Grants of saved apps persist (Android persistable URI permissions, counted across apps) instead of ending with the session; Settings → Access → Files on this phone adds Forget file access per app. Details: files.md.

Photo assets use host-issued UUID filenames, strict project containment, atomic writes/copy and quotas. Rejected late captures are deleted. Save failure rolls back the newly created project. Closing previews and startup cleanup remove temporary photos; saved photos survive upgrades and are removed when their app is deleted. Apps with `storage.files` also see their photos in a read-only `photos/` folder of their private files, where they can list, copy and delete them (`docs/files.md`).

The full model-facing operation contract is in app/builder/HardwareGuide.kt. Adapter details: camera-runtime.md and platform-hardware.md.

## Verification

- Debug app and device-test APK compile; 57 JVM tests pass. Android lint has zero errors.
- Nine inventory/media device tests pass on the connected phone: full Android sensor enumeration, permission invariance, generation metadata privacy/size limits, camera IDs/response size, scoped assets, orientation-aware previews, full-resolution/quality preservation, preview copying, quotas and temporary-file cleanup.
- Earlier targeted runs passed runtime isolation, generation/storage, hardware permission revocation, actual accelerometer readings, launcher/voice UI, tuner lifecycle, diagnostic-app contract and delayed microphone route cancellation.
- User confirmed headset speech playback and basic camera preview, front/back switching and photo display. Android audio-policy evidence confirmed an active Bluetooth SCO headset microphone after route restoration.
- Final launcher/tuner/route-cancellation regression run: four tests passed. Inventory UI reports 4 Android camera IDs, 8 camera/lens routes, 45 static sensor entries, 0 dynamic sensor entries and 77 declared hardware features on this phone. The physical 2.2mm rear route opens without a reported camera error; the lens chooser and capability-based control panel display correctly. The user confirmed preview, changing lenses and saved photo display all work with the expanded camera UI. Camera metadata tests do not prove every physical lens/resolution/control combination works.
- BLE/RFCOMM data peripherals, USB bulk accessories, NFC tags and infrared appliances still require compatible-device tests.

## Camera limits

The camera chooser uses every publicly readable logical/physical route; Android may reject some route/resolution combinations. Controls use advertised public ranges and keys. Vendor-only modes, RAW, video, manual white-balance gains and CameraX extensions are not enabled. Captured JPEG pixels and compression are preserved without resizing or recompression. Highest available resolution is the default; reported high-resolution JPEG sizes are included. Image quality is remembered across captures: Compact70, Balanced85, High95(default), Maximum100. Quality changes compression, not resolution. The camera shows its actual bound resolution. Personal metadata is removed; minimal orientation and color profiles are retained. Only display previews are sampled. Storage limits are 256MiB per photo and20 photos/1GiB per app, subject to free space. Vendor-only maximum-resolution sensor modes unavailable through CameraX cannot be selected.

## Reference contracts

- Android Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- BLE data exchange: https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data
- Sensor overview: https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- Location permissions: https://developer.android.com/develop/sensors-and-location/location/permissions
- USB host: https://developer.android.com/develop/connectivity/usb/host
- CameraX: https://developer.android.com/media/camera/camerax

## Full-resolution update verification

JVM JPEG-stream tests verify unchanged compressed scan/quantization bytes, removal of metadata before and between scans, truncated-file rejection, color profile preservation and trailing payload removal. Device tests verify4096×3072 pixels at JPEG qualities70 and100, orientation-correct bounded previews, unchanged bytes on preview save, and existing storage/privacy boundaries. Build/lint pass. Live capture passed: the rear camera bound at4080×3060 and saved a portrait3060×4080 JPEG at quality100. The user confirmed correct upright display.
