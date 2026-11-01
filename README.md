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
