# words — offline English vocabulary for Android

The repository contains the Android project source, offline wordbooks, and an automated APK build.

## Project contents

- `words-android-source.zip.b64`: Base64 transport copy of the project source archive. The Actions workflow decodes and unpacks it.
- `wordbooks/gaokao_standard_2025.json`: 2,974 deduplicated entries from a machine-readable extraction of the 2025-revised curriculum Appendix 2. The published standard specifies 3,100 words; the 126-entry extraction gap is recorded transparently in `wordbooks/SOURCES.md`.
- `wordbooks/highschool_extension_3743.json`: 3,743 unique high-school extension headwords, merged from the 6,008-row licensed source list.
- `wordbooks/SOURCES.md` and `wordbooks/KyleBing-LICENSE.txt`: source notes and BSD 3-Clause licence.

## Build APK

Every push to `main` (and manual workflow dispatch) runs **Build words APK**:

1. Decode and unpack the source archive.
2. Copy both wordbooks and their license/source notes into Android assets.
3. Build `app-debug.apk` with JDK 17, Android SDK 35, and Gradle 8.9.
4. Upload the APK as the `words-debug-apk` artifact for 30 days.

Open the repository's **Actions** tab, choose the latest **Build words APK** run, and download the `words-debug-apk` artifact when the run succeeds.

## Data notes

The official standard lists curriculum headwords and tier markers but does not supply phonetics or Chinese definitions. Wordbook definitions are matched from the BSD-3 source where possible and supplemented with concise original glosses. Android TTS reads English words; audio files are not bundled. Curriculum-tier weights are not claimed as statistical Gaokao frequency rankings.

See the project README in the archive for implementation and local build notes.
