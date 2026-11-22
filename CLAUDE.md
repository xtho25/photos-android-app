# Photo Chooser (Android)

Gallery-style photo browser. While viewing photos full-screen, saying a chosen word
(default **«Αυτή»**) **copies** the current photo into a "working folder". A counter
shows how many photos are in that folder.

Package / applicationId: `com.voicepick.photos` (keep it, so updates install over the existing app).
Kotlin + Jetpack Compose (Material 3), single Activity, minSdk 29, target/compileSdk 35.

## Build, test, install

- JDK 17 (system). Android SDK at `~/Android/Sdk` (cmdline-tools, platform 35, build-tools 35).
  `local.properties` (git-ignored) holds `sdk.dir`.
- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- `./gradlew testDebugUnitTest`: JVM unit tests for the pure-Kotlin logic (no instrumented tests).
- `abiFilters` is set to arm64-v8a and armeabi-v7a only. ML Kit's native OCR library is large
  (~11 MB per ABI), so x86/emulator builds aren't supported.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
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
   - Search by content (`ui/SearchSection.kt`): shows index progress with Index now / Pause, a
     query field with 🎤, category chips, and the match count before Continue.
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
  Search results are loaded as all photos and then filtered by the ids from `SearchIndex`.
- `search/`: content search, phase 1 (text recognition only).
  - `IndexWorker`: WorkManager job that runs on-device OCR (ML Kit Latin text recognition, model
    bundled) on new or changed photos, decoded at ≤1600px, and stores the results in `SearchIndex`.
    A process-wide `Mutex` lets only one run happen at a time.
    - Automatic runs: periodic, hourly, with `KEY_AUTOMATIC`. They index only while plugged in.
      **WorkManager's `setRequiresCharging` misreads the charging state on HyperOS**, so the worker
      checks the battery itself (`isPluggedIn`).
    - "Index now": a one-time run with no conditions.
    - Pause: cancels by tag and pushes the next automatic run 6 h out.
    - Measured on the Redmi Note 13: about 0.27 s per photo (~45 min per 10k photos).
    - It tries to run as a foreground job with a progress notification (dataSync). Android often
      refuses this for jobs started in the background; it then continues without a notification
      (10-minute limit per run, then it resumes later).
  - `SearchIndex`: SQLite `search_index.db`, one row per MediaStore id (`modified`,
    `categories` as `,key,…`, `text` lowercase and accent-free). `search(SearchQuery)` uses LIKE.
  - `DocumentClassifier`: text and path → `Category` (passport via MRZ `P<XXX` or the word
    PASSPORT; ID card via TD1 MRZ or "IDENTITY CARD"; licence; receipt by price count plus
    keywords; bank card by card number plus expiry; screenshot by path; document by ≥25 words).
  - `SearchQuery`: Greek/English query → categories (by word prefix, e.g. `διαβατηρ`, `passport`)
    plus free-text terms that must appear in the recognized text. "ID card" / "κάρτα ταυτότητας"
    doesn't also mean bank card.
  - Known limits: ML Kit has no Greek OCR, so Greek text inside photos can't be searched (the UI
    explains this when there are no results). Old Greek ID cards without an MRZ won't be detected.
    Phase 2 (semantic image search with CLIP/MobileCLIP, plus Greek queries) is planned but not
    started; the user wants to discuss it first.
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
  (default true) and `newest_first` (default true).
- UI text is English and inline (no string resources apart from `app_name`). Dates follow the phone locale.

## Working on this repo

- **Keep this file up to date** whenever features, structure, build steps or decisions change.
- Before building a big or uncertain feature, explain the options and trade-offs and ask the user first.
- Add JVM unit tests for pure logic (see `app/src/test/`). Build and run the tests before committing.
- When a task is finished, commit on `main` with a descriptive message.
