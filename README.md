# Photo Chooser

An Android photo browser for picking photos hands-free. Scroll through your gallery as usual.
When you see a photo you want, say **«Αυτή»** (or any word you choose) and it's copied into a
folder of your choice. A counter in the corner shows how many photos you've picked.

## Features

- **Browse your way:** all photos, a time period, a single album, or a content search.
  - **Time periods** can be spoken or typed in Greek or English: «Πάσχα 2024», «Καλοκαίρι 2026»,
    «Μάιος 2025», «15 Αυγούστου 2024», «πέρσι», "summer 2024"… You can also pick two dates on a
    calendar.
- **Voice selection:**
  - Say the selection word to copy the current photo into the working folder.
  - Change the word and speech language (Ελληνικά / English) in ⚙, or turn voice off and use the
    **＋ Add** button.
  - **✕ Remove** takes a photo back out of the folder.
- **Gallery-style viewing:**
  - The grid is grouped by day, with a fast-scroll handle, and can be sorted newest or oldest first.
  - The full-screen viewer has pinch/double-tap zoom and shows the date, time and position within
    the day.
- **Content search, done entirely on the phone:**
  - Finds passports, ID cards, driving licences, receipts, bank cards, screenshots and any text in
    your photos.
  - Search in Greek or English, e.g. «διαβατήρια», «αποδείξεις», "Lidl".
  - The app asks before it indexes anything. It indexes while charging (or when you tap
    **Index now**), and turning search off deletes the stored data.
- **AI edition only, visual search:**
  - Finds photos by what they show: «θάλασσα», «σκύλος», «ηλιοβασίλεμα», "mountain"…
  - Uses the [TinyCLIP](https://huggingface.co/wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M)
    model on the phone.

Photos are **copied, never moved or modified**. Nothing is uploaded anywhere.

## Editions

Two editions are built from the same code. Install **one**: they replace each other and keep your
settings, working folder and search index.

| | Photo Chooser | Photo Chooser AI |
|---|---|---|
| Everything above except visual search | ✅ | ✅ |
| Visual search («θάλασσα», "dog"…) | — | ✅ |
| Download size | ~38 MB | ~174 MB |
| Phones | ARM 32/64-bit | ARM 64-bit |

Requires **Android 10** or newer.

## Install

Download an APK from [Releases](../../releases) and open it on the phone. You may need to allow
"Install unknown apps" for your browser or file manager. Or install over USB:

```sh
adb install -r PhotoChooser-v1.1-debug.apk
```

On first start the app asks for access to your photos and the microphone, then whether it may
index your photos for content search.

**Xiaomi / HyperOS:** to install over USB, turn on *Developer options → Install via USB* and accept
the prompt on the phone.

## Build from source

Requirements: JDK 17 and the Android SDK (platform 35, build-tools 35), with `sdk.dir` set in
`local.properties`.

```sh
./gradlew assembleStandardDebug                                    # standard edition
./gradlew testStandardDebugUnitTest testAiDebugUnitTest            # unit tests
```

### AI edition

The TinyCLIP model is too large for git, so it is converted locally into the git-ignored `models/`
folder first. This needs Python 3 and a few GB of disk space for the conversion tools:

```sh
python3 -m venv ~/.cache/photo-chooser-tools/venv
~/.cache/photo-chooser-tools/venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cpu
~/.cache/photo-chooser-tools/venv/bin/pip install transformers onnx onnxruntime pillow numpy scikit-image
~/.cache/photo-chooser-tools/venv/bin/python -I tools/convert_tinyclip.py

./gradlew assembleAiDebug
```

The script downloads the model and exports it to ONNX. It then compresses it to 8-bit (keeping
accuracy-sensitive layers at full precision), checks it against the original, and calibrates the
match threshold.

## How it works

| Part | Technology |
|---|---|
| UI | Kotlin, Jetpack Compose (Material 3) |
| Photos | MediaStore; copies go to `Pictures/<folder>/` |
| Voice selection | Android `SpeechRecognizer` in a continuous loop; Greek words are matched by sound, so «αυτή» = «αυτοί» = «αφτή» |
| Text search | ML Kit text recognition (Latin script), detection of document machine-readable codes, SQLite index |
| Visual search | TinyCLIP ViT-40M/32 via ONNX Runtime; ML Kit translation for Greek queries |
| Background indexing | WorkManager |

Source layout:

```
app/src/main/       shared code (both editions)
app/src/standard/   standard edition stub
app/src/ai/         AI edition: TinyCLIP engine, CLIP tokenizer
app/src/test*/      JVM unit tests
tools/              model conversion script
```

## Known limitations

- ML Kit has no Greek text recognition, so Greek words *written inside* photos can't be searched.
  Greek *queries* do work.
- Old Greek police ID cards (with no machine-readable code) aren't detected as ID cards.
- Visual search uses a small model. It's good at clear subjects and scenes (sea, dog, mountain,
  sunset), weaker at fine detail, and it doesn't recognise faces.
- Releases are debug-signed.

## Credits

- [TinyCLIP](https://github.com/microsoft/Cream/tree/main/TinyCLIP) by Microsoft, MIT licence
  (weights: `wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M`)
- [ONNX Runtime](https://onnxruntime.ai/), [ML Kit](https://developers.google.com/ml-kit),
  [Coil](https://coil-kt.github.io/coil/)
