# Native still-camera adapter

`CameraCaptureScreen(directory, onResult, onDismiss)` is a host-owned full-screen CameraX dialog. The caller must authorize the `camera` capability, the Android camera permission, foreground visibility, and an explicit user action before displaying it. The app's Close control and native permission broker remain authoritative; script code never receives a camera object or frame stream.

The dialog enumerates public Camera2 logical camera IDs and their physical lens IDs, including front, back and external endpoints. Labels include focal lengths. A logical multicamera route lets Android select its active lens; a physical route pins Preview and ImageCapture using Camera2Interop.setPhysicalCameraId under its logical parent. CameraX camera-info ID filtering selects the parent. A device can expose metadata while rejecting a particular lens/resolution combination; PUB reports that failure and leaves lens/resolution controls available. Camera use cases bind to the Activity lifecycle and unbind when the dialog is disposed or switches lenses/resolutions. Closing invalidates its capture session; late saved/processed results are deleted. The caller must also invalidate callbacks when the selected project changes or a permission is revoked.

Successful capture returns `{asset: "UUID.jpg", width, height, lensFacing, lensLabel}`. The host can deliver that object in a camera event. An `image` UI node references the opaque asset token; `ProjectMedia.resolve(projectId, asset)` must resolve it under the current project before rendering. No path, content URI, base64 image, location, or raw frame crosses into JavaScript.

## Native controls

- Lens chooser includes every CameraCatalog route; metadata-budget truncation never trims the native chooser.
- Resolution selector lists all JPEG sizes reported for that endpoint including regular/high-resolution JPEG sizes, plus Highest available (the default). Exact requests use no fallback; incompatible stream combinations fail visibly. CameraX prefers higher resolution over capture rate. The actual bound size is displayed; saved JPEG pixels are never resized. Exact size selection has no fallback. Maximum-resolution sensor modes that CameraX does not expose remain unavailable.
- Zoom uses CameraX ZoomState minimum/maximum and CameraControl. Flash offers Off/Auto/On only when the lens reports flash, with flash disabled during manual exposure.
- Exposure compensation follows the live CameraX index range and EV step. Tap preview invokes supported autofocus/auto-exposure/white-balance metering; manual focus/exposure remove their respective auto-metering flags.
- ISO and shutter sliders appear only for public MANUAL_SENSOR support, reported ranges, AE_OFF and required request keys. Manual focus needs AF_OFF, a positive focus-distance range and its request keys. Physical routes additionally require the logical camera's available physical request keys.
- White-balance presets use only reported supported automatic/preset modes. AWB_OFF is omitted because PUB does not implement calibrated manual color gains/transform controls.
- Manual settings use Camera2CameraControl CaptureRequestOptions. Settings are session-local; rejected combinations produce an explicit message. Camera state errors disable Capture until a usable session opens.
- Collapsing the controls hides only their UI; the same control state and effects remain composed. Changing lens/resolution/quality starts a new control session, resets manual exposure/focus, white balance and flash, and applies the automatic options even while the panel is hidden. The UI states this reset behavior. Asynchronous control feedback uses a lifetime token keyed to both camera and lifecycle owner.
- Public request-key names and Android-reported extension IDs are inspectable. Presence does not imply every key is editable in PUB. Vendor-private controls, Samsung Pro/Night processing, raw output and CameraX extensions are not enabled in this dialog.

## Storage

`ProjectMedia(context)` provides:

- `directory(projectId): File`: only the bundled tuner ID, canonical UUID saved IDs, and `preview-UUID` identities are accepted.
- `resolve(projectId, asset): File?`: rejects traversal, absolute paths, malformed tokens and another project's assets.
- `copyPreview(fromId, toId)`: copies completed photos under the same opaque tokens before opening the saved app. Writes stage in a separate directory and publish atomically; partial copies are cleaned.
- `delete(projectId)`: removes only that validated project's photo directory. Call when deleting an app and when discarding a preview.
- `cleanupTemporary()`: call once before opening any project to remove abandoned preview/copy trees and recognized partial image writes. Canonical containment is checked at every recursive step; symlinks are not traversed. Completed saved photos are retained.
- `inventory(): JsonObject`: public static logical/physical endpoint IDs, facing/focal lengths, capabilities, control ranges, JPEG sizes, request-key names and extension availability. Camera endpoint IDs are not handset identifiers. No personal content or live readings are included. Replies fit below 24KiB: at most 32 detailed endpoint records, 24 JPEG sizes and 128 public request keys per record; verbose records compact further when needed, with explicit truncation/omission flags. Native camera choices and their complete settings lists do not use that truncated metadata.

Photos are stored in app-private internal storage with original camera JPEG pixels and compression. Native JPEG quality choices are Compact70, Balanced85, High95(default), Maximum100; the user's selection persists across camera sessions and apps. Changing quality rebinds ImageCapture and resets the manual control session. JPEG quality100 is not a lossless format.

`JpegPhoto` streams all JPEG segments/scans, strips EXIF/XMP/IPTC/comments/unknown application blocks/trailing payloads, and retains a minimal orientation tag plus ICC/Adobe color information. It does not allocate a full-resolution bitmap or recompress pixels. Upright result width/height reflect orientation. Preview decoding samples to <=1600px then applies orientation; this does not alter the stored original. Old already-rotated photos remain compatible.

Limits: 256 MiB per raw/final photo, 20 photos and 1 GiB per project, subject to available phone storage. Raw size is checked after capture completes, and temporary raw plus sanitized output may coexist. Failures delete temporary files and do not publish partial assets. Capture and sanitization run off the UI thread. A new file is synced, quota checked, and renamed atomically. Preview saving copies bytes unchanged.

No video, audio recording, continuous raw frames, OCR, gallery export, model uploads, or background camera access is implemented. Actual lens support and photo quality depend on the device. Very large capture sizes may exceed the 256MiB raw-image limit even when the device can capture them; choose a lower resolution if storage rejects the result. Public Camera2 metadata cannot reveal lenses or proprietary modes the manufacturer hides from third-party apps.

## Dependencies and validation

CameraX `camera-core`, `camera-camera2`, `camera-lifecycle`, and `camera-view`: 1.4.2. AndroidX ExifInterface: 1.3.7. Lifecycle runtime Compose: 2.8.7 for `LocalLifecycleOwner`.

`ProjectMediaDeviceTest` covers project scoping/traversal rejection, orientation-aware display and personal metadata stripping, preview-copy isolation, quotas, malformed captures, startup cleanup/symlink refusal, full-resolution 4096×3072 quality-preservation/preview-copy tests, and a read-only public-camera inventory/schema/byte-budget check. Real lens selection, manual controls and permission revocation require device verification; these fixtures do not establish camera hardware behavior.

Official API references:

- https://developer.android.com/media/camera/camerax/architecture
- https://developer.android.com/media/camera/camerax/take-photo
- https://developer.android.com/reference/androidx/camera/core/resolutionselector/ResolutionStrategy
- https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
- https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2Interop.Extender
- https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2CameraControl
- https://developer.android.com/media/camera/camerax/configuration

JPEG streaming unit tests cover metadata before/between scans, entropy stuffing/restart markers, unchanged scan/quantization bytes, color profiles, truncated/invalid files and removal of trailing payloads.
