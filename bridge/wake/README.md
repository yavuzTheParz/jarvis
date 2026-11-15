# The wake word

Jarvis listens for its name on the phone, in 80 ms pieces: openWakeWord's melspectrogram and speech-embedding
models (`app/src/main/assets/wake/*.onnx`) turn sound into features, and `jarvis.bin`, a small classifier
trained here, decides whether the last second ends in "Jarvis". Nothing leaves the phone.

The `jarvis.bin` in the repository was trained only on synthetic voices (`generate.py`, edge-tts). It hears
those very well, but **real voices through a real phone microphone much less reliably**. Ten minutes of
training on your own voice fixes that. Until then, long-press the ring to talk.

## Train it on your voice

You need Python with `uv` (or pip), `ffmpeg`, and about 15 minutes.

1. **Record yourself on the phone** with any voice recorder app (it should be the microphone Jarvis uses):
   - `name`: say "Jarvis" about 30 times, with a second or two of silence between them. Vary it a little:
     normal, quiet, from arm's length, "hey Jarvis", "okay Jarvis".
   - `talk`: a minute or two of talking normally *without* saying the name. Read something aloud, for example.

   Copy both files to the computer.

2. **Make the clips** (run from the repository root):

   ```bash
   uv run --with numpy --with soundfile python bridge/wake/split_recording.py name.m4a clips/pos
   uv run --with numpy --with soundfile python bridge/wake/split_recording.py talk.m4a clips/neg
   ```

3. **Add the synthetic voices** (about 10 minutes; it uses Microsoft's free online TTS through `edge-tts` and
   only sends the fixed phrases in `generate.py`):

   ```bash
   uv run --with edge-tts python bridge/wake/generate.py clips
   ```

4. **Train** (5 to 15 minutes on a CPU):

   ```bash
   uv run --with numpy --with onnxruntime --with soundfile \
     python bridge/wake/train.py clips app/src/main/assets/wake app/src/main/assets/wake/jarvis.bin
   ```

   Your clips (`real__*`) count more than the synthetic ones. `--test-mine DIR` checks the result on recordings
   of yours that were left out of `clips/`.

5. **Rebuild and install**: `./gradlew assembleDebug && scripts/setup-phone.sh`.

`clips/` is git-ignored: your voice stays on your machine.

## Checking it

`./gradlew test` runs `WakeWordTest`: voices left out of training must be heard, and ordinary talk must
(almost) never wake it. To test on your own voice, put raw 16 kHz mono 16-bit clips in a folder and run
`JARVIS_WAKE_MINE=/that/folder ./gradlew test --no-daemon`.
