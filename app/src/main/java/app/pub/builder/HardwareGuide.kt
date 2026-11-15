package app.pub.builder

/** Exact native hardware vocabulary supplied to the model; no Android APIs reach JavaScript. */
object HardwareGuide {
    val CONTRACT = """
        Hardware access is optional and foreground-only. Declare only the capability groups your app uses:
        camera, location, sensors, bluetooth, haptics, torch, audio, battery, infrared, nfc, usb.
        Check device.hardware availability from the provided generation context. Missing hardware, denied permissions,
        cancellation, disconnects and Android settings must produce honest recoverable UI, never fabricated readings.
        Emit {type:'hardware.request',id:'short_unique_request_id',capability:'group',operation:'operation',args:{...}}.
        id must use only letters/digits/_/- and be <=64 characters. One new permission request at a time.
        Begin access from an action event caused by a user button. Native host owns permissions and device selection.
        choose, capture, route and NFC reading always require an explicit button action; never run them from init or callbacks.
        After permission and device selection, data operations may follow result events. Do not loop requests on errors.
        Responses: {type:'hardware',id,capability,event,data}. Stream events reuse the initiating id.
        Handle error/cancelled and {type:'hardwareStopped'} by clearing active indicators and providing a Start button.
        Closing the app, leaving PUB, or changing a hardware policy stops sessions. Request new access on return.
        Photos and readings stay local unless the user explicitly puts them in an app description. No network APIs are exposed.

        camera: list {} returns public camera/lens routes and supported settings metadata (possibly compacted); capture {} opens native preview with user-selected logical/physical lens and supported resolution, JPEG quality, zoom, flash, exposure, focus and white-balance controls. Android may reject lens/resolution combinations. Do not promise manufacturer-only modes. Scripts cannot silently choose capture settings.
        photo event data {asset:'UUID.jpg',width,height,lensFacing,jpegQuality}; retain asset in storage.project to reopen it later.
        Display {type:'image',asset:asset,description:'photo description <=200 chars'}. Only this app's captured JPEG assets can be shown. Maximum4 image nodes per screen.
        No arbitrary paths, URLs, camera stream frames, video, OCR or visual AI are supported. Photos preserve captured pixel resolution; native quality choices70/85/95/100 control JPEG compression (default95, remembered). Quota20 photos/1GiB per app,256MiB per photo. Display previews are sampled; originals are not. Respect exact dimensions returned by capture; do not promise sensor maximums hidden by Android.

        sensors: list {}; start {sensor,rateHz?:1..10,durationMs?:1000..600000}; stop {sensor?:name}.
        sensor names: accelerometer, gyroscope, magnetic_field, gravity, linear_acceleration, rotation_vector,
        light, proximity, pressure, relative_humidity, ambient_temperature, step_counter, step_detector,
        game_rotation_vector, geomagnetic_rotation_vector, gyroscope_uncalibrated, magnetic_field_uncalibrated, accelerometer_uncalibrated, hinge_angle.
        Uncalibrated sensors include bias estimates in values[3..5]; hinge_angle is degrees.
        Maximum4 active sensors. Standard Android SI sensor units; event sensor data contains sensor,values,accuracy,timestampNs.
        Accelerometer/gravity/linear_acceleration m/s², gyroscope rad/s, magnetic_field microtesla,
        light lux, proximity cm, pressure hPa, ambient_temperature Celsius, relative_humidity percent.
        Step counters count since reboot; subtract an initial baseline for session totals. Step access needs Android activity permission.
        Rotation vectors are not compass degrees. Use the correct math and report magnetic accuracy when making a compass.

        location: current {provider?:'auto'|'gps'|'network',timeoutMs?:1000..30000};
        start {provider?:'auto'|'gps'|'network',intervalMs?:1000..60000,durationMs?:1000..600000}; stop {}.
        event location data gives latitude,longitude,accuracyMeters and other available fields. Approximate-only grants are valid;
        GPS may need precise permission and provider enabled. No background location or automatic map downloads.

        bluetooth: choose {scanMs?:1000..10000,bondedOnly?:boolean} opens host device picker after bounded discovery.
        device event returns opaque session id,name,paired,transport. Never invent or pass MAC addresses.
        connect {} connects the chosen BLE device and returns services with service/characteristic UUIDs and properties, plus mtu and maxWriteWithoutResponse.
        read {service:UUID,characteristic:UUID}; write {service,characteristic,data:[bytes0..255],withResponse?:boolean}; max512 bytes; withResponse:false max maxWriteWithoutResponse bytes.
        subscribe {service,characteristic,enabled?:boolean}; value event data has bytes,service,characteristic,notification. Notifications: max10/s per characteristic, newest value kept.
        disconnect {}; classicConnect {service:UUID} uses secure RFCOMM on a chosen paired device;
        classicWrite {data:[bytes]} max4096; classicRead {maxBytes?:1..4096,timeoutMs?:1000..10000}.
        UUID accepts canonical UUID or 4/8 hex shorthand. Know the accessory protocol; discovering a device does not reveal its command semantics.
        BLE/RFCOMM does not route headphone audio or provide proprietary Galaxy Buds ANC controls; use audio below for sound.

        audio: devices {}; route {} opens native communication-device chooser (headphones, speaker, etc);
        wait for audio.route success before starting microphone.pitch. routeReset {} restores normal routing.
        tone {tone?:'beep'|'ack'|'error'|'digit0'..'digit9',durationMs?:50..3000,volume?:0..100};
        speak {text:1..500 characters,language?:BCP47,rate?:0.5..2,pitch?:0.5..2}; stop {}.
        Speech requires an installed offline voice. routeLost means the headphone connection was lost; microphone is stopped.
        Headset call-mode input may have reduced quality; do not promise studio-quality recording or tuner accuracy.

        haptics: vibrate {durationMs?:1..1000,amplitude?:1..255}; stop {}.
        torch: set {enabled:boolean}. Flashlight stops on close/background; camera may temporarily own the flash.
        battery: get {} reports local battery level/charging/temperature if available; not Bluetooth accessory battery.
        infrared: frequencies {}; transmit {frequencyHz:int,patternUs:[positive durations]} max200 durations,total<=500000us.
        Requires an actual infrared emitter and known device protocol; most phones lack an emitter.
        nfc: read {} opens native foreground reader; tag data {technologies,records:[{tnf,type,payloadBase64}],truncated}.
        Read-only NDEF; max16 records/4096 payload bytes; no card emulation/payment/private credential access or tag writes.
        usb: choose {} opens native device chooser and Android device permission; device data lists interfaces/endpoints.
        claim {interface:index}; read {endpoint:address,maxBytes?:1..4096,timeoutMs?:1..2000};
        write {endpoint:address,data:[bytes0..255],timeoutMs?:1..2000}; disconnect {}.
        Only chosen-device bulk endpoints are supported. A known accessory protocol/appropriate interface is necessary;
        not automatic filesystem mounting, printer support, USB audio or every vendor driver.
    """.trimIndent()
}
