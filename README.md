# Rafay Own Editor (My Auto Video Editor)

Offline Android app: pick a video (<3 min) -> AUTO EDIT -> edited MP4 saved to Gallery (Movies/Rafay Own Editor).
The manifest has NO INTERNET permission, so it cannot go online. Requires Android 10+ (arm64).

## Get the APK (pick ONE way)

### A) Android Studio (PC)
1. Install Android Studio (Koala or newer). File > Open > this folder. Let Gradle sync (needs internet ONCE to download libraries).
2. Build > Build Bundle(s)/APK(s) > Build APK(s). Output: app/build/outputs/apk/release/
   (Menu "Generate Signed App Bundle / APK" -> APK also works and lets you create your own keystore.)
3. Or terminal: `gradle wrapper --gradle-version 8.7` once, then `./gradlew :app:assembleRelease`.

### B) No PC: GitHub Actions
1. Create a free GitHub account + new private repo, upload this whole folder (keep the .github folder).
2. Actions tab > "Build APK" > Run workflow. Download the artifact "Rafay-Own-Editor-apk" (zip) when finished.

### Own signing key (optional)
`keytool -genkey -v -keystore rafay.jks -alias rafay -keyalg RSA -keysize 2048 -validity 10000`,
copy keystore.properties.example to keystore.properties and fill it in. Without it, the release APK is signed with the debug key (fine for personal use).

## Install on phone
Copy the APK to the phone (rename to "Rafay Own Editor.apk" if you like) > open it > allow "Install unknown apps" for your file manager > Install.

## FFmpeg dependency
Official ffmpeg-kit was retired in 2025. This project uses the community build `com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1` (same com.arthenica.ffmpegkit API).
If it does not resolve, download a compatible arm64 .aar (e.g. from the maintained forks), put it in app/libs/, remove that line in app/build.gradle.kts and add `implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))`.
Licensing: libx264 builds are GPL; the app tries libx264 first and falls back to the phone's hardware H.264 encoder. Keep the app personal, or check the license of the build you choose before distributing.

## How it works (pipeline)
1 probe -> 2 detect opening end (first hard scene cut 2-8.5 s) -> 3 keep last 1.00 s at 1.00x -> 4 contrast +10 / saturation -5 / light enhancement
-> 5 detect split line (strongest stable horizontal edge in 30-70% height) -> 6 on-device face detection (bundled ML Kit) in the upper part ~3x/sec = player footage intervals
-> 7 hflip of the upper section only, only inside those intervals -> 8 audio: original, atempo 1.04 (pitch kept), optional pitch shift -> 9 concat -> 10 MP4 (H.264 CRF 18 + AAC 192k, original resolution) -> 11 MediaStore.

## Settings screen (Saved Editing Settings)
All values persist. Manual overrides: opening length, split line %, mirror mode (AUTO / ALL / OFF), manual mirror intervals.
