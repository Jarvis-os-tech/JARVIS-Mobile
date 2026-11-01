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
