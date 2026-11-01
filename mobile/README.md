# JARVIS Mobile voice standby

Mobile source and phone releases live in
[JARVIS-Mobile](https://github.com/Jarvis-os-tech/JARVIS-Mobile).

## Choose your API setup

On first launch, choose either **I have JARVIS on my PC** or **Use my own API key**.

For PC setup, tell desktop JARVIS **“Open the phone pairing QR code.”** Scan it in
the phone app. The QR shares the PC's configured Gemini key and device pairing
credential; treat it as private. Other PC integrations continue using their
existing keys through the shared PC executor. Only scan a QR from your own PC.
The desktop QR appears in a compact corner window like picture-in-picture.
With mobile **1.2.1 or newer**, saving a new QR pairing sends an authenticated
confirmation for that exact window, which then closes and deletes its QR image.
An unrelated connection does not close it. The window expires after five minutes.

Without PC JARVIS, paste your own **Gemini API key (required)**. Voice and native
phone controls work without desktop pairing. OpenAI, Anthropic, and Groq fields
are optional and may be left blank. They are saved for optional integrations;
this version still uses Gemini for voice and does not enable those voice
providers. Provider usage is billed to the key owner's account.

Use **API keys and setup** to change setup later. Saving your own key switches
to standalone mode and disconnects PC pairing; scan the PC QR to pair again.
Keys are masked in the form, encrypted with Android Keystore-backed storage,
excluded from app backups, and never included in the APK or update requests.
Existing saved Gemini keys are migrated out of the old plaintext preference.

## Internet updates

Tap **Update JARVIS** with Wi-Fi or mobile data enabled. The online channel works
without USB, PC pairing, a shared network, or a running PC. Android asks you to
approve installation. The first version with this online updater is **1.1.2**;
older installations need that APK installed once before using online updates.
There are no automatic update checks, downloads, or installations. Publishing a
release makes it available; the phone changes only after the user presses the
button and approves the Android installer. Development builds must not be pushed
to a connected phone without the user's explicit installation request.

Downloads use HTTPS, bounded sizes, SHA-256 verification, the JARVIS package name,
an increasing version code, and the installed app's signing certificate. Desktop
pairing tokens and Gemini keys are never sent to GitHub. If the online channel is
unreachable, the paired PC remains available as a development update source.

To publish a new phone version, increment `versionCode` and `versionName`, then
run `./gradlew testDebugUnitTest assembleDebug` from `mobile`. From the repository
root run `python scripts/publish_mobile_github.py`. The publisher uploads an
immutable versioned APK before switching the `mobile-stable/current.json` channel.
Build all updates with the same signing key as the installed app. Keep that key
private and backed up; a different key cannot update an existing installation.
The publisher requires GitHub CLI access to the mobile repository. It publishes
APKs and release metadata only, never app credentials or signing keys.

Open JARVIS and tap **Enable Hey Jarvis** once. Keep microphone permission enabled,
and grant the floating overlay permission to see the voice orb over other apps.

Say **Hey Jarvis**, pause for the greeting, then ask your question. You can keep
talking during the conversation. After 15 seconds without another question
following a reply, the overlay hides and the assistant returns to standby.
Tap **Turn off assistant** to release the microphone and disable wake detection.
During a conversation, saying **bye**, **goodbye**, or **bye Jarvis** is detected
locally. JARVIS plays a brief “Bye” stored on the phone, then closes the app and
hides the floating overlay as soon as playback drains. It keeps the microphone
listening locally for **Hey Jarvis**, so you can wake it again without reopening
the app. This path does not wait for an online model reply. Fully disabling the
assistant requires **Turn off assistant** or an explicit spoken turn-off request.

Wake detection uses the bundled Vosk English model entirely on the phone.
Microphone audio is sent to Gemini only during an activated conversation.
Replies require internet access and a Gemini key configured through desktop pairing.
Credentials are read from private app settings, never embedded in the APK.

Standby runs as a microphone foreground service with a persistent notification.
It uses battery while enabled. Vivo battery management can stop background apps;
if standby stops after leaving the app, allow JARVIS to run in the background in
the phone's battery settings. Reopen JARVIS after reboot and enable standby.
This is an app-level wake listener, not a system assistant or hardware hotword service.

## Shared phone and PC execution

The Android app discovers tool definitions from the **running PC voice daemon**.
PC actions use that daemon's existing `UnifiedSkills` instance, task store,
memory and permission checks. There is no second desktop executor in the app
or transport service. The phone provides native adapters for its device status,
launching apps and opening HTTPS websites. Say “on my phone” or “on my PC” when
choosing where an action should run.

From the PC, the shared `phone_control` adapter operates the same paired phone
over wireless ADB. It supports battery/connection status, listing launcher apps,
opening apps and HTTPS websites, reading screen text and touch bounds, screenshots,
taps, swipes, focused-field text entry, and navigation/volume buttons. It checks
the saved phone identity before each action and uses the normal PC execution
policy and dry-run checks. Screen text is untrusted data and password fields are
redacted. The adapter does not unlock a secure lock screen.

Examples: “Open Calculator on my phone” from the PC, and “Open Firefox on my PC”
from the phone. Run `scripts/mobile_wireless.py --address PHONE_IP` after a
wireless reconnection; it restores the phone-to-PC tunnel and saves the paired
phone identity for PC-to-phone actions. Both devices must remain on the same
trusted hotspot/Wi-Fi with wireless debugging active. No USB cable is needed
while the wireless session is connected. Phone text entry currently uses Android
ADB input; navigation keys handle Enter separately.

For the USB connection used during development:

1. Install `systemd/jarvis-device-bridge.service` as a user service, then enable
   it with `systemctl --user enable --now jarvis-device-bridge`.
2. Connect the paired phone with USB debugging enabled.
3. Run `adb reverse tcp:8766 tcp:8766` after each USB reconnection.
4. Enable Hey Jarvis. The app shows whether the PC executor is connected.

For wireless testing on the same trusted hotspot or Wi-Fi, use
`python scripts/mobile_wireless.py --address PHONE_IP --enable-from-usb USB_SERIAL`
once while the already-authorized phone is attached. On reconnect use
`python scripts/mobile_wireless.py --address PHONE_IP`; this also restores the
shared-execution tunnel. Always select the wireless transport explicitly if USB
is also connected, for example `adb -s PHONE_IP:5555 logcat`. This setup uses
authenticated ADB TCP on the trusted local link. After reboot, re-enable it through
the authorized USB link, or use Android's Wireless debugging pairing screen for
TLS-based ADB. Stop wireless TCP debugging with `adb -s PHONE_IP:5555 usb`.

The bridge binds only to PC loopback by default and authenticates every request
with the existing desktop pairing token. Cleartext traffic on Android is allowed
only to `127.0.0.1` for this USB tunnel. LAN access requires a trusted HTTPS
certificate (`python -m jarvis_control.device_bridge --host ... --cert ... --key ...`)
and a pairing payload `exec` URL pointing at that HTTPS endpoint. USB disconnects
leave phone-local actions available; PC actions require the link and PC service.

Each remote action has a persistent request ID. Duplicate deliveries return the
stored result without running again. If execution was interrupted before its
result was recorded, JARVIS reports the outcome as unknown and does not replay it.

Speech playback runs on its own worker using short nonblocking writes, so receiving
an interruption never waits behind a spoken reply. Local voice detection cuts
queued output while microphone streaming continues; server interruption events
discard any remaining old response. Speaker playback must drain before starting
the follow-up inactivity timer.

## Build and verify

From `mobile`, run `./gradlew testDebugUnitTest assembleDebug`.
The APK is `app/build/outputs/apk/debug/app-debug.apk`.
With a configured Android phone connected, `./gradlew connectedDebugAndroidTest`
checks actual native wake recognition against speech fixtures and verifies a
greeting followed by two spoken questions and completed audio replies in the
same Gemini session. The latter uses the phone's configured key and network/API
quota. The conversation test keeps JARVIS visible to avoid Vivo freezing a
background instrumentation process. To retain installed app data, build with
`assembleDebugAndroidTest` and install both APKs with `adb install -r`, then run
`adb shell am instrument -w com.jarvis.os.test/androidx.test.runner.AndroidJUnitRunner`.

## Bundled recognition model

`app/src/main/assets/wake-model` contains `vosk-model-small-en-us-0.15`,
copyright 2020 Alpha Cephei Inc, distributed under Apache 2.0 according to the
[official model catalog](https://alphacephei.com/vosk/models).
Original archive: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip

The two Android test speech fixtures are generated phrases, sampled at 16 kHz,
16-bit mono PCM. They contain only “Hey Jarvis” and “What time is it”.
