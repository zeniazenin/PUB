# Technical proof decisions

The application shell and all permission controls are native. Generated scripts exchange JSON with an isolated Android service, and never receive a Context, Activity, Java object, filesystem handle or network client. Android OS permission belongs to PUB; mini-app permission is an additional host-enforced policy.

## Trust boundaries

1. The host selects the local project and its declared capabilities. Source cannot choose another project identity through its messages.
2. The runtime only returns a bounded component tree and effect list. Parsing and validation happen before rendering or capability dispatch.
3. The broker evaluates global/per-project policy, Android permission and foreground state before capture. It rechecks active sessions and rejects stale callbacks after capture is stopped.
4. System permission prompts, the microphone indicator and Stop control are outside generated UI.
5. Only the native host owns ChatGPT network access and encrypted credentials. Generated scripts cannot access either; see chatgpt-builder.md for the 0.2 protocol.

## Why Rhino for the proof

The initial plan proposed QuickJS. Rhino 1.7.15.1 provides a patched Java-compatible interpreter that can run on Android without a native toolchain. It permits instruction observation and safe standard objects. Runtime replacement only changes the service engine implementation, not project capabilities or native rendering. The interpreter runs with optimization disabled. This choice does not turn Rhino itself into an OS sandbox; `isolatedProcess` supplies that boundary.

The isolated process has a watchdog for long-running built-ins and failure containment. There is not yet a hard heap allocation quota or formal security review. Version 0.2 supports generated projects through the same bounded runtime and a validated package contract. Arbitrary external imports are not supported; a hard memory quota and formal security review remain future work.

## Audio

YIN estimates the fundamental from 4096-sample windows with 2048-sample hops, using the negotiated sample rate. The detector rejects low levels and low-confidence periodicity. The JavaScript tuner app of this proof took a short median and reset it on large pitch jumps, computed the nearest selected tuning target, and rendered cents. (Current builds no longer bundle the tuner; it was a development fixture. Apps saved from it keep working.) Hardware audio processing, room acoustics and input routes still require physical testing.

## References

- Android isolated services: https://developer.android.com/guide/topics/manifest/service-element
- Android runtime permission behavior: https://developer.android.com/training/permissions/requesting
- AGP 8.9 build compatibility: https://developer.android.com/build/releases/agp-8-9-0-release-notes
- Rhino release notes: https://github.com/mozilla/rhino/releases
- YIN original paper: https://iro.umontreal.ca/~pift6080/H09/documents/papers/yin_pitch_tracker.pdf

Versions are pinned for a reproducible technical proof; this is not a claim that they are the latest toolchain or meet future Play target-SDK requirements.
