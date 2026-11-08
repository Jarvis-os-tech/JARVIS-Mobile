# JARVIS Mobile

Android voice assistant with **Hey Jarvis** standby, spoken replies, voice interruptions, phone controls, contact calling, and optional shared PC execution.

[Download the latest phone APK](https://github.com/Jarvis-os-tech/JARVIS-Mobile/releases) and install it once. Future versions are available through **Update JARVIS** over Wi-Fi or mobile data. Your PC can be off. Android asks you to approve installation.

Say **bye** to hide the assistant and return to standby. Only **Hey Jarvis** wakes it. Pair with your desktop JARVIS to configure the voice API key and enable PC actions. API credentials are stored on the phone and are not included in this repository or APK.

## Build

Requires Java 17 and Android SDK 34:

```sh
cd mobile
./gradlew testDebugUnitTest assembleDebug
```

See [phone setup and updates](mobile/README.md). The optional PC executor lives in [JARVIS-OMA](https://github.com/Jarvis-os-tech/JARVIS-OMA); this repository contains the Android client and its release tools, with no duplicate PC executor.

## Publish an update

Increment the Android version, build and test, then run `python scripts/publish_mobile_github.py` from this repository. Use the same private signing key as previous phone builds. Never commit signing keys, local Android paths, pairing tokens, or API credentials.

### Continuous memory (1.3.0)

Phone conversations are saved in a private local database and recalled across sessions and app restarts, including when using a personal Gemini key without a PC. Paired phones exchange acknowledged conversation records with the PC's continuous memory, load shared context when waking, and can search older PC conversations while connected. Unsynchronized phone records remain queued while the PC is unreachable. Recent shared history is cached on the phone; older uncached PC history requires the PC connection. The PC needs the matching continuous-memory bridge update.

Silence no longer starts a 15/20/30-second standby countdown. Say **bye** to hear the farewell and hide the assistant in **Hey Jarvis** standby, or explicitly request standby. Network/provider failures can still interrupt a voice connection; saved history survives reconnection. Updates remain manual: tap **Update JARVIS** and approve the Android installation prompt.

### Voice connection repair (1.3.2)

Uses the documented Gemini 2.5 Flash Native Audio Live model instead of a general text model that rejects bidirectional voice. Model fallback only occurs for an explicit unsupported-model rejection. Duplicate close events and callbacks from old connections cannot stop a replacement session. A missing setup acknowledgement times out after 15 seconds so activation can be retried. Network exception URLs containing API keys are excluded from logs. Continuous memory, strict Hey Jarvis wake-up, and goodbye standby behavior remain available. Install manually using Update JARVIS.

### Faster voice and phone system controls (1.4.0)

Voice activation no longer waits for PC tool discovery. PC capabilities are preloaded in the background and discovered on demand through a compact pair of PC tools, instead of sending the entire PC catalog on each voice connection. App names resolve locally so opening a named installed app takes one tool call; ambiguous names still require clarification. Native voice uses zero extra thinking budget, a 400 ms speech-end window, and brief replies by default. First-audio timing is logged without conversation text. Cloud and network latency still varies.

New direct controls include auto-brightness, auto-rotate, screen timeout, ringer mode and Do Not Disturb, with actual setting readback. Voice navigation can lock the screen or open power options. Settings panels now cover Wi-Fi, Bluetooth, hotspot, mobile data, airplane mode, location, battery saver, notifications, apps, storage, security, privacy, language, time and VPN. Android restricts direct connectivity toggles: use the requested settings panel and user-enabled accessibility navigation, then read back the state. Opening a panel is not a completed toggle. System settings, accessibility and Do Not Disturb access have explicit buttons in JARVIS Settings; Android permissions remain the user's choice. Install updates manually with Update JARVIS.

### Public updater repair (1.4.1)

The home and settings version labels now read Android's installed package version; the old fixed `v1.2.3`/`v1.3.0` labels did not indicate the actual installed build. Public manifest requests have a short deadline and fresh download URLs. Downloads show percent and MB, retain interrupted partial files, and resume only when the user taps Update again. Range responses, size, SHA-256, package version and installed signing identity are verified. The UI reports the actual error and offers a public browser download fallback. Updates and Android installation approval remain manual.

### Voice response and recovery (1.5.0)

Restores the PC-aligned `gemini-3.8-live` default; the earlier compatibility repair had selected the slower 2.5 native audio model. Supported 3.1 and 2.5 native audio fallbacks remain available. The standard 3.8 model uses its fixed reasoning profile, without unsupported thinking-budget settings.

The phone now frames speech explicitly: a local turn ends after about 400 ms of quiet, rather than waiting indefinitely for server-side speech detection. Short pauses stay inside the same turn, first phonemes have pre-roll, and early speech is buffered during connection setup with a strict memory limit. Quiet standby sends no microphone audio. Noise calibration reduces missed quiet speech and background-noise turns. Deliberate interruptions retain their existing speaker-echo threshold. Already-completed replies no longer wait for an interruption acknowledgement. Filler audio is removed when real speech arrives.

Voice response monitoring applies only to outstanding requests, never idle conversations. Stalled replies, failed uploads and transient disconnections reconnect without replaying previous commands; retries are bounded, and invalid credentials stop retrying. Failed microphone and speaker handles are cleared instead of leaving a false listening/playing state. Session guards reject stale queued callbacks. Local goodbye detection now receives trailing silence and still hides the assistant after the farewell.

Encrypted preferences are reused per application context; encryption and isolated preference namespaces remain intact. Startup performs a bounded keyless connection warm-up without microphone audio or API credentials. Live synthetic tests cover consecutive spoken replies, manual interruptions, output format, and native tool schemas. Hardware microphone/output behavior still needs validation after manual installation; cloud latency varies. Installation remains user-initiated.

### Microphone stream recovery and resilience (1.5.1)

Hardens audio capture against transient `AudioRecord` buffer read errors and device hardware preemption (such as during phone locks, audio focus switches, and Android / Nothing OS background transitions). Instead of terminating the microphone stream and turning off the assistant on buffer read drops, `AudioCaptureEngine` automatically performs bounded exponential backoff and re-initialization of the audio record pipeline (up to 5 attempts).

Service health monitoring now tracks consecutive failures with recovery rather than shutting down the voice pipeline on brief transient dropouts. Failure listeners trigger session reconnection safely while preserving voice state.
