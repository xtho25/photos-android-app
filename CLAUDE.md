# Photo Chooser (Android)

Gallery-style photo browser. While viewing photos full-screen, saying a chosen word
(default **«Αυτή»**) **copies** the current photo into a "working folder". A counter
shows how many photos are in that folder.

Package / applicationId: `com.voicepick.photos` (keep it, so updates install over the existing app).
Kotlin + Jetpack Compose (Material 3), single Activity, minSdk 29, target/compileSdk 35.

## Two editions (product flavors, dimension `edition`)

| | `standard` → "Photo Chooser" | `ai` → "Photo Chooser AI" |
|---|---|---|
| Content search | text/documents (OCR) | OCR **plus visual search** with TinyCLIP |
| Extra code | `src/standard/` (`Edition` stub) | `src/ai/` (`Edition`, `ai/ClipEngine`, `ai/ClipTokenizer`, INTERNET permission) |
| ABIs | arm64-v8a, armeabi-v7a | arm64-v8a only |
| Debug APK size | ~38 MB | ~174 MB (118 MB of model) |

- Both use the **same applicationId and debug key**, so installing one replaces the other and
  keeps settings, the working folder and the search index. This was the user's choice; they didn't
  want both installed side by side.
- Shared code asks `Edition.VISUAL_SEARCH` / `Edition.semanticEngine(context)` (null in standard).
  Keep edition-specific code in the flavor folders. Don't use separate branches.

## Build, test, install

- JDK 17 (system). Android SDK at `~/Android/Sdk` (cmdline-tools, platform 35, build-tools 35).
  `local.properties` (git-ignored) holds `sdk.dir`.
- Gradle wrapper 9.7.1 (updated by the user), Android Gradle Plugin 8.7.3, Kotlin 2.0.21.
  It builds fine, but Gradle reports deprecations that will break with Gradle 10, mostly from the
  older AGP. Upgrade AGP/Kotlin before ever moving to Gradle 10. Kotlin's `jvmTarget` is set via
  `kotlin { compilerOptions { … } }`, not the deprecated `kotlinOptions`.
- Builds: `./gradlew assembleStandardDebug assembleAiDebug` →
  `app/build/outputs/apk/{standard,ai}/debug/app-{standard,ai}-debug.apk`.
- Tests: `./gradlew testStandardDebugUnitTest testAiDebugUnitTest` (JVM only, no instrumented
  tests). `src/test/` is shared; `src/testAi/` holds the CLIP tokenizer/text-model checks.
- Native libs are compressed in the APK (`packaging.jniLibs.useLegacyPackaging = true`) for a
  smaller download. `.onnx` assets are `noCompress` because they're memory-mapped. No x86/emulator
  builds.
- **AI model (not in git).** `tools/convert_tinyclip.py` converts
  `wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M` (MIT) to ONNX. Run it with the tools venv:
  `~/.cache/photo-chooser-tools/venv/bin/python -I tools/convert_tinyclip.py`. That venv has
  CPU-only torch, transformers, onnx, onnxruntime and scikit-image; recreate it with
  `python3 -m venv` plus pip if it's missing (no sudo needed). The script:
  - writes the git-ignored `models/assets/clip/{image,text}_encoder.onnx`, which Gradle adds to
    the `ai` assets. The AI build fails with a hint if they're missing;
  - writes the committed `app/src/ai/assets/clip/{vocab.txt,merges.txt,tinyclip.json}` and
    `app/src/testAi/resources/clip_reference.tsv`;
  - verifies int8 against fp32 and calibrates `min_score` on scikit-image sample photos.
  - Quantisation: the image encoder is fully int8 (cos 0.983, 40 MB). For the text encoder only the
    token embedding and attention are int8 (cos 0.998, 83 MB). Quantising its MLP drops cos to
    ~0.86, so don't.
  - ONNX Runtime is pinned to 1.31.1 (Android and desktop tests). The script caps IR version at 9.
- `adb install -r app/build/outputs/apk/<edition>/debug/app-<edition>-debug.apk`
- The user's test phone is a Xiaomi Redmi Note 13 on HyperOS 2 (Android 15):
  - Installing over adb needs *Developer options → Install via USB* turned on, and the install
    prompt on the phone must be accepted.
  - `adb shell input …` (simulated taps) fails unless *USB debugging (Security settings)* is on,
    so UI flows usually can't be driven from adb. Screenshots work:
    `adb exec-out screencap -p > shot.png`.
  - Debug voice matching with `adb logcat -s KeywordListener`; debug indexing with
    `adb logcat -s IndexWorker WM-WorkerWrapper WM-GreedyScheduler`.
  - Inspect the index with `adb shell run-as com.voicepick.photos ls -la databases/`.

## App flow

`Screen` in `AppViewModel`. Navigation is plain state, with no navigation library.

1. **Source** (`ui/SourceScreen.kt`): what to browse.
   - All photos.
   - A time period: spoken (system speech dialog via `ui/SpeechInput.kt`) or typed, parsed by
     `PeriodParser`, or picked on a `DateRangePicker`. The parsed dates are shown and can be edited
     before continuing.
   - Search by content (`ui/SearchSection.kt`): if indexing isn't allowed, shows only "Turn on"
     (which asks first). Otherwise it shows "Turn off content search" (asks, then deletes the index),
     index progress with Index now / Pause, a query field with 🎤, category chips, and the match
     count (plus any visual-search message) before Continue.
   - A folder: any album, including ones the app can't write to.
2. **Folders** (`ui/FolderPickerScreen.kt`): the working folder. The last one used is offered first.
   A new folder is created as `Pictures/<name>/`. Only `Pictures/` and `DCIM/` albums are listed,
   because MediaStore only allows image inserts there.
3. **Grid** (`ui/GridScreen.kt`): photos grouped by day (`GridEntry.Header` / `GridEntry.Cell`),
   with a fast-scroll handle that shows a month bubble, a ✓ on photos already in the folder,
   and the counter badge plus ⚙ in the top bar. Below the top bar, an **Order** row switches
   between ↓ Newest first and ↑ Oldest first (`AppViewModel.setSortNewestFirst`). It reverses the
   loaded list and rebuilds the day sections, and the viewer follows the same order.
4. **Viewer** (`ui/ViewerScreen.kt`): `HorizontalPager` with pinch/double-tap zoom. Shows the
   counter, the date/time and position within the day, a mic status chip (tap it for settings),
   a flash plus vibration on add/remove, and the ＋ Add / ✕ Remove buttons. Voice listening runs
   only while this screen is visible and resumed, and only if voice selection is on in settings;
   the screen is kept on only while voice is on. With voice off, the mic chip shows
   "Voice off · use ＋ Add" (`ListenStatus.Disabled`) and photos are added with the button only.

## Code map (`app/src/main/java/com/voicepick/photos/`)

- `AppViewModel.kt`: all app state (screen, source, photos, grid sections, working folder,
  count, keyword settings) and the add/remove actions. A `ContentObserver` refreshes the folder
  count. A `Mutex` serializes add/remove. One-off UI events go through `events: SharedFlow<Feedback>`.
- `MediaRepository.kt`: MediaStore access.
  - Queries: `loadImages(source, excludeRelPath)`, `listAlbums(writableOnly)`, `folderContents`.
  - `copyToFolder`: inserts with `IS_PENDING`, then streams the bytes.
  - `removeFromFolder`: returns `NeedsConfirmation(IntentSender)` when Android requires the user's
    OK (files not created by this app).
- `KeywordListener.kt`: continuous `SpeechRecognizer` loop.
  - Restarts after every result or error, using partial results. Fires once per keyword occurrence,
    with a 1.2 s debounce.
  - Mutes `STREAM_MUSIC` while listening to hide the restart beep.
  - Falls back from offline to online recognition if the language pack is missing.
- `KeywordMatcher.kt`: word/phrase matching that ignores case and accents. Greek is compared by
  sound (`soundKey`): «αυτή» = «αυτοί» = «αφτή».
- `PeriodParser.kt`: Greek/English period phrases → `Period(label, start, end)` (both ends inclusive).
  - Without a year it uses the most recent occurrence that has already started.
  - Definitions chosen by the user: Πάσχα = Orthodox Easter Sunday ± 7 days;
    Καλοκαίρι = Jun 1–Sep 30; Φθινόπωρο = Oct–Nov; Χειμώνας Y = Dec (Y-1)–Feb Y; Άνοιξη = Mar–May;
    Χριστούγεννα = Dec 20–Jan 7.
- `PhotoSource.kt`: `All` / `Dates(start, end, label)` / `Folder(relPath)` / `Search(query)`.
  Search results are loaded as all photos and then filtered by the ids from `AppViewModel.search`.
- `AppViewModel.search(query)` returns `SearchOutcome(ids, note)`. It always runs the text search.
  In the AI edition, queries that aren't a document category also run a visual search
  (`SemanticRanker` over `SearchIndex.loadEmbeddings()`), and the results are merged. The result is
  cached for 60 s so the preview and Continue share it. Results are shown in date order like
  everything else.
- `search/`: content search.
  - `IndexWorker`: WorkManager job. It decodes each new or changed photo once (≤1600px), runs ML Kit
    Latin OCR, and in the AI edition also computes a TinyCLIP embedding, then stores everything in
    `SearchIndex`. Photos whose text is indexed but which have no embedding (e.g. after switching
    from standard to AI) only get the embedding added. A process-wide `Mutex` lets only one run
    happen at a time.
    - **Consent first:** nothing is indexed until the user allows it. On first start `App()` in
      `MainActivity` shows `ui/IndexConsentDialog` while `index_consent` isn't set. Allow schedules
      the automatic runs. Not now (or tapping outside) cancels all index work and clears
      `SearchIndex`. The worker also checks `IndexWorker.isAllowed()` at the start and between
      photos, so old schedules can't index without consent.
    - Automatic runs: periodic, hourly, with `KEY_AUTOMATIC`. They index only while plugged in.
      **WorkManager's `setRequiresCharging` misreads the charging state on HyperOS**, so the worker
      checks the battery itself (`isPluggedIn`).
    - "Index now": a one-time run with no conditions.
    - Pause: cancels by tag and pushes the next automatic run 6 h out.
    - Measured on the Redmi Note 13: about 0.27 s per photo (~45 min per 10k photos).
    - It tries to run as a foreground job with a progress notification (dataSync). Android often
      refuses this for jobs started in the background; it then continues without a notification
      (10-minute limit per run, then it resumes later).
  - `SearchIndex`: SQLite `search_index.db` (version 2), one row per MediaStore id (`modified`,
    `categories` as `,key,…`, `text` lowercase and accent-free, `embedding` BLOB of float32 LE).
    For `embedding`, NULL means not computed and an empty blob means it failed.
    `search(SearchQuery)` uses LIKE. Upgrading from v1 adds the column (the text is kept).
  - `SemanticEngine` (interface) + `SemanticRanker` (pure): a match has score ≥ `min_score` (0.249)
    and is within `margin_from_best` (0.06) of the best, max 300 results. Embeddings are
    L2-normalised, so a dot product is the cosine. `Tasks.kt`: `Task.await()`.
  - `DocumentClassifier`: text and path → `Category` (passport via MRZ `P<XXX` or the word
    PASSPORT; ID card via TD1 MRZ or "IDENTITY CARD"; licence; receipt by price count plus
    keywords; bank card by card number plus expiry; screenshot by path; document by ≥25 words).
  - `SearchQuery`: Greek/English query → categories (by word prefix, e.g. `διαβατηρ`, `passport`)
    plus free-text terms that must appear in the recognized text. "ID card" / "κάρτα ταυτότητας"
    doesn't also mean bank card.
  - Known limits: ML Kit has no Greek OCR, so Greek text inside photos can't be searched (the
    standard edition explains this when there are no results). Old Greek ID cards without an MRZ
    won't be detected.
- `src/ai/java/.../ai/ClipEngine.kt` (AI edition only):
  - Memory-maps the ONNX models from the APK (`openFd` + `FileChannel.map`).
  - Image preprocessing: halve repeatedly, scale the shortest side to 224, centre-crop, then
    `ClipPreprocess.toChw` with the CLIP mean/std.
  - Query: Greek is translated to English with ML Kit (the model downloads once and needs internet;
    failures become a `SemanticSearchException` message), then used in the `"a photo of {}."` prompt.
  - `ClipTokenizer`: pure-Kotlin CLIP BPE, tested token-for-token against Hugging Face.
- `DateFormats.kt`: dates in the **phone's language** (localized FULL/MEDIUM formats; Today/Yesterday
  via `DateUtils`).
- `MainActivity.kt`: permission gate (READ_MEDIA_IMAGES or READ_EXTERNAL_STORAGE, plus
  RECORD_AUDIO), theme (dynamic colour), and the screen switch with back handling.
- `ui/CounterBadge.kt`, `ui/SettingsDialog.kt`: the settings dialog holds the Voice selection
  on/off switch (`voiceEnabled`), the selection word and
  the speech language (el-GR / en-US).

## Behaviour rules worth keeping

- **Copy, never move.** Originals are never modified. Remove deletes only the copy in the working folder.
- "Same photo" means the same `DISPLAY_NAME` and `SIZE` (`Photo.key`). This is used for duplicate
  detection, ✓ marks and remove.
- The working folder is excluded from the browsed photos, so you never swipe onto copies.
- A photo's date is `DATE_TAKEN`, falling back to `DATE_ADDED`. Lists are sorted newest first in Kotlin.
- Settings live in SharedPreferences `settings`: `folder`, `keyword`, `language`, `voice_enabled`
  (default true), `newest_first` (default true) and `index_consent` (missing = not asked yet).
- **Never read photo contents for search without `index_consent == true`.**
- UI text is English and inline (no string resources apart from `app_name`). Dates follow the phone locale.

## Working on this repo

- GitHub: private repo `xtho25/photos-android-app` (remote `origin`, HTTPS through the `gh` login).
  Push after committing.
- **Builds go to GitHub Releases, never into git.** Each release has **both editions** (one release,
  two APKs). For a new release:
  1. Bump `versionCode` (by +1) and `versionName` in `app/build.gradle.kts`, then commit and push.
  2. Make sure `models/assets/clip/*.onnx` exist (run the convert script if not).
  3. Run `./gradlew clean assembleStandardDebug assembleAiDebug`.
  4. Copy the APKs as `PhotoChooser-v<version>-debug.apk` and `PhotoChooserAI-v<version>-debug.apk`.
  5. Run `gh release create v<version> <both apks> --target main --title "Photo Chooser v<version>" --notes …`.
  Releases are debug-signed for now. A release-signed build would need a keystore, which must
  never be committed.

- **Keep this file up to date** whenever features, structure, build steps or decisions change.
- Before building a big or uncertain feature, explain the options and trade-offs and ask the user first.
- Add JVM unit tests for pure logic (see `app/src/test/`, `app/src/testAi/`). Build both editions
  and run both test tasks before committing.
- When a task is finished, commit on `main` with a descriptive message.
