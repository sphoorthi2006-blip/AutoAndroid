# Kokoro Speak (Android)

Type text, pick a voice (or mix two), and hear it spoken by the free Kokoro AI voice model.
Everything runs offline on the phone using sherpa-onnx. No account, no API key.

This project is complete except for three things I could not bundle:
the native library, one Kotlin file, and the model. Add them in steps 3-5.

## What you get
- 32 voices in the picker: 28 English (11 US female, 9 US male, 4 UK female, 4 UK male)
  plus 4 experimental Hindi-trained voices that speak English text.
- Voice mixing: pick any main voice, pick any second voice, and slide the mix amount
  from 0 to 100%. Female + male, female + female, US + UK, any pair works.
- A "Random female + male mix" button for quick experiments.
- Speed control and Save WAV.

## 1. Install Android Studio
Free from developer.android.com/studio. Open it once and let it install the Android SDK.

## 2. Open the project
File > Open > choose the `KokoroSpeak` folder. Wait for "Gradle sync" to finish
(needs internet the first time). If Android Studio offers to upgrade the
Android Gradle Plugin, you can accept.

## 3. Add the sherpa-onnx native libraries
1. Go to https://github.com/k2-fsa/sherpa-onnx/releases and open a recent release.
2. Download the Android build (a file named like `sherpa-onnx-v<version>-android.tar.bz2`) and extract it.
3. Copy the `.so` files from each processor folder (`arm64-v8a`, `armeabi-v7a`, `x86`,
   `x86_64`) into the matching folder under `app/src/main/jniLibs/`, for example
   `app/src/main/jniLibs/arm64-v8a/`. You need both `libsherpa-onnx-jni.so` and
   `libonnxruntime.so` in each. (For a quick local test on one phone, the arm64 folder alone
   is enough if you also change `abiFilters` in `app/build.gradle.kts`.)

## 4. Add the matching Kotlin file
IMPORTANT: it must come from the SAME release version as step 3, or the app will crash.
1. On the same release page, open the source code at that tag (the "Source code" zip works).
2. Copy `sherpa-onnx/kotlin-api/Tts.kt` into:
   `app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt`
   (Create the folders if they do not exist. The file must keep `package com.k2fsa.sherpa.onnx`.)
3. If Android Studio then shows "Unresolved reference" errors, copy the other
   `.kt` files from that `kotlin-api` folder into the same place.

## 5. Add the Kokoro model (54 voices)
1. Go to https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
2. Download `kokoro-multi-lang-v1_0.tar.bz2` and extract it.
3. Copy everything inside the extracted folder (`model.onnx`, `voices.bin`, `tokens.txt`,
   `lexicon-us-en.txt`, `lexicon-zh.txt`, the `espeak-ng-data` folder and any other
   files) into:
   `app/src/main/assets/kokoro-multi-lang-v1_0/`
   so that `app/src/main/assets/kokoro-multi-lang-v1_0/model.onnx` exists.

## 6. Run it
- On your phone: Settings > About phone > tap "Build number" 7 times, then
  Settings > Developer options > turn on USB debugging. Plug in the phone,
  accept the prompt, choose it at the top of Android Studio, press the green Run button.
- The first launch copies the model out of the app (about a minute), then loads it.
- The app is large (about 350 MB) because the model is inside it.

## Using voice mixing
1. Choose a main voice under "Voice".
2. Under "Mix with another voice", pick a second voice.
3. Use the slider to set how much of the second voice to blend in (50% is an even blend).
4. Tap Speak.

The first Speak after changing the voice or mix reloads the model, so it takes a few
seconds ("Mixing voices..."). Speaking again with the same setting is instant.
Switching to "None" also reloads once.

How it works: each voice is a block of numbers in `voices.bin`. A mix is the weighted
average of two blocks. The app writes that average into a working copy of `voices.bin`
and reloads the model with it. This relies on `voices.bin` being a flat list of equal-size
blocks, one per voice. If the app says "Unexpected voices.bin layout", that assumption
did not hold for your file; tell me the message and I will adjust.

## Troubleshooting
- "App not compatible with your phone" when installing: the APK has no libraries for the
  phone's processor type. The default build includes arm64-v8a, armeabi-v7a, x86 and x86_64.
- "UnsatisfiedLinkError": the `.so` files are missing or in the wrong folder for this phone's
  processor type.
- "Unresolved reference: OfflineTts": `Tts.kt` is missing or in the wrong folder (step 4).
- Crash or error right after starting speech: library version and `Tts.kt` version
  do not match. Re-download both from the same release.
- "Model files not found in assets": step 5 folder name or location is wrong.
- Out of memory while loading: the phone has too little free RAM. Close other apps.
  Voice mixing reloads the model, so it needs the same RAM as a normal start.
- If Android Studio says the Gradle wrapper jar is missing, just let it sync;
  it downloads Gradle itself.

## Limits
- English text only. The model has voices for other languages (Spanish, French, Italian,
  Japanese, Portuguese, Hindi) but sherpa-onnx only supports English and Chinese text for it,
  so I left those voices out. The 4 Hindi-trained voices are included as experiments and
  may sound odd. To add more voices, add entries in `Voices.kt` using the speaker ids
  from the sherpa-onnx model page.
- Chinese text and its voices are not set up or tested in this app.
- Voice names and numbers follow the published speaker table for this model; check by ear.
- Audio stops if you leave the app (no background service yet).
- Licenses: Kokoro and sherpa-onnx are open source, but the bundled eSpeak NG
  data is GPL. Fine for personal use; check licenses before publishing the app.

## Building on GitHub instead (no Android Studio needed)
The workflow `.github/workflows/build-voice-app.yml` ("Voice App") does steps 3-5 for you:
it downloads the latest sherpa-onnx Android libraries, the matching `Tts.kt`, and the
Kokoro model, then builds the app. Run it from the repository's Actions tab ("Voice App" >
Run workflow). When it finishes, download `VoiceApp.apk` from the run's artifacts (or from the
`voice-app-latest` release if that step succeeded) and install it on your phone.
