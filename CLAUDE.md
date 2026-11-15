# Jarvis: notes for Claude

Jarvis is an Android launcher (Kotlin, no Compose, plain Views) plus an optional Python bridge that lets Claude answer as Jarvis. Read `README.md` for what it does.

## When the user asks you to set it up

Go step by step, run the commands yourself, and check each step before moving on. Ask only when a choice is really theirs: phone-only or with the bridge, and their settings (wake-up hour, currency). Never ask them to type secrets into the chat.

### 1. Toolchain

- **JDK 17** is required (newer JDKs break this Android Gradle Plugin). Check with `java -version` or look for an existing JDK 17. If there is none, install Temurin 17 with the system package manager (`brew install --cask temurin@17`, `sudo apt install openjdk-17-jdk`, `winget install EclipseAdoptium.Temurin.17.JDK`), or download it into the user's home folder without root from https://adoptium.net. Set `JAVA_HOME` for the build commands.
- **Android SDK** with `platforms;android-35`, `build-tools;35.0.0` and `platform-tools`. If Android Studio is installed, its SDK is usually at `~/Android/Sdk` (Linux), `~/Library/Android/sdk` (macOS) or `%LOCALAPPDATA%\Android\Sdk` (Windows). Otherwise download the command-line tools from https://developer.android.com/studio#command-tools, unpack them to `<sdk>/cmdline-tools/latest`, and run `yes | sdkmanager --licenses` and `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`.
- Write `local.properties` with `sdk.dir=<path>`, or export `ANDROID_HOME`.

### 2. Settings

Ask for the user's wake-up hour, when their morning should end, when their evening starts, their currency, and which apps they want to allow during the morning rule (package names; `adb shell pm list packages` lists them once the phone is connected). Write `app/src/main/assets/config.json` from `config.example.json`. It is git-ignored. Leave `bridgeUrl` empty for phone-only.

### 3. Build and test

```bash
./gradlew test assembleDebug
```

All tests must pass. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

### 4. Install on the phone

The user turns on Developer options (tap Build number 7 times) and USB debugging, connects the phone, and accepts the prompt on it. Check with `adb devices`, then run:

```bash
scripts/setup-phone.sh
```

It reports every step it could not do. Walk the user through those by hand (the list is in the README). On Samsung and some other phones, *Allow restricted settings* has to be tapped on the phone before the accessibility service can be turned on. The app's info page has it under the ⋮ menu.

Then have the user press Home and confirm Jarvis appears. If they have a wireless setup instead (`adb pair`), the same script works.

### 5. Bridge (only if they want Claude to answer)

Requirements: Python 3.10+ (`uv` is easiest), `ffmpeg`, Claude Code logged in (`claude -p "hi"` should answer), and Tailscale on both the PC and the phone, signed in to the same account.

```bash
cd bridge
python3 make_token.py
uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python -r requirements.txt
mkdir -p models/piper
curl -L -o models/piper/en_GB-alan-medium.onnx https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx
curl -L -o models/piper/en_GB-alan-medium.onnx.json https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx.json
cp .env.example .env
```

- Set `JARVIS_BIND` in `.env` to the PC's Tailscale IPv4 (`tailscale ip -4`). On a machine without an NVIDIA GPU set `JARVIS_WHISPER=small.en`.
- Run `python3 -m unittest test_server` in `bridge/`, then start the server (`set -a; . ./.env; set +a; .venv/bin/python server.py`) and test it from the PC: `curl -s -H "Authorization: Bearer $(cat .token)" -d '{"message":"hello","context":""}' http://$JARVIS_BIND:8787/chat`.
- Put `"bridgeUrl": "http://<tailscale ip>:8787"` in `config.json`, **rebuild** (the token is baked into the APK from `bridge/.token`) and run `scripts/setup-phone.sh` again.
- Linux: `systemctl --user enable --now ~/jarvis/bridge/systemd/jarvis-bridge.service` (adjust the paths in it if the repo is elsewhere) and `loginctl enable-linger $USER` so it runs without a login. macOS/Windows: offer a launchd agent or a scheduled task, or just running it in a terminal.

The bridge must never listen on a public address. Keep it on the Tailscale IP.

### 6. Wake word (optional, recommended)

The shipped wake-word model is trained on synthetic voices only and misses many real voices. Offer to train it on the user's voice: walk them through `bridge/wake/README.md` (two recordings on their phone, then split, generate, train, rebuild). Run the commands for them once they have copied the recordings to the computer.

### 7. Finish

Tell the user how to talk to it (long-press the ring; "Jarvis" once the wake word is trained), and that Settings → Default apps → Home app switches back to their old launcher at any time.

## Working on the code

- `./gradlew test` runs the app's unit tests on the JVM. Everything language- or rule-related lives in plain Kotlin files with no Android imports (`Commands.kt`, `OfflinePlan.kt`, `Core.kt`, `Vault.kt`, `Stats.kt`, `Money.kt`) and is tested there. Keep new logic that way.
- The JVM tests use Java's regex engine, but Android uses ICU, which is stricter: for example a character class ending in a colon (`[\d:]`) compiles on the JVM and crashes on the phone. After changing a regex, open the screens that use it on a device or emulator.
- `bridge/test_server.py` covers the bridge: `python3 -m unittest test_server` in `bridge/`.
- Claude is called with `--tools ""`: it has no tools at all. Keep it that way. Everything it asks for is filtered by `clean_action()` in `server.py`. A new phone action needs four pieces: an entry in `persona.md`, a case in `clean_action()`, a case in `Bridge.kt`'s parser, and its handling in `Jarvis.perform()`.
- Secrets: `bridge/.token`, `bridge/.env` and `app/src/main/assets/config.json` are git-ignored. Never commit them, and never print the token.
