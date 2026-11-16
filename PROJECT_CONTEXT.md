# StreamTwin - Context & Chronological Work Log

> **Last Updated**: 2026-11-16 09:18:00  
> **Status**: Active / Production Release Ready (v1.3.3 - Code 28)  

---

## 📌 1. Project Overview & Business Goals
StreamTwin is a native Android live-streaming companion and client built for streamers and gamers to broadcast screens and gameplay directly to Twitch, YouTube, Kick, and RTMP destinations, featuring dynamic multi-RTMP streaming, voice audio filtering, retrospective 60-second clipping, and a floating overlay control.

## 🛠️ 2. Tech Stack & Environment
- **Platform**: Android Native (Kotlin 2.0.21, Jetpack Compose, Material 3)
- **Architecture**: MVVM, Dagger Hilt 2.55, Coroutines, Flow
- **SDK Versions**: `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`
- **Current Version**: `versionCode = 28`, `versionName = "1.3.3"`
- **Streaming & Media**: PedroSG94 RootEncoder 2.6.7, MediaProjection, AudioRecord (VOICE_COMMUNICATION AEC/NS)
- **Build System**: Gradle 8.13, AGP 8.8+

## 🏗️ 3. Architecture & Key Files
- [`app/build.gradle.kts`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/build.gradle.kts): Core configuration, targetSdk 36, versionCode 28, versionName 1.3.3.
- [`AndroidManifest.xml`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/AndroidManifest.xml): Native custom scheme OAuth redirect (`com.streamtwin` & `streamtwin`), zero http/https scheme filters.
- [`MainActivity.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/MainActivity.kt): Primary UI entry point and native OAuth intent handler.
- [`YouTubeApiManager.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/data/remote/YouTubeApiManager.kt): YouTube Data API v3 live stream auto-creation, safe ingestion parsing, and unlisted broadcast fallback.
- [`StreamingService.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/service/StreamingService.kt): Core Multi-RTMP service with automatic token refresh via GoogleAuthUtil, robust RTMP URL construction, and manual RTMP fallback.
- [`SecureStorageManager.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/data/local/SecureStorageManager.kt): Hardware-backed encrypted shared preferences with `clearAll()` data-purge method.
- [`SettingsScreen.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/ui/settings/SettingsScreen.kt): Settings UI featuring the prominent "Disconnect Accounts & Delete App Data" action and confirmation dialog.
- [`keystore.properties`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/keystore.properties): Release signing configuration.

---

## 🕒 4. Chronological Work Log (Latest on Top)

### 📅 [2026-11-16 09:18:00] - Account & Data Deletion Compliance Implementation (v1.3.3 - Code 28)
- **Goal / Request**: Implement Play Store User Data policy compliance for in-app account & data deletion: add a clear "Disconnect Accounts & Delete App Data" button wiping all stored OAuth tokens, account emails, and stream keys from `SecureStorageManager`. Bump version to 28 (`1.3.3`) and build `bundleRelease`.
- **What Was Done**:
  1. Added `clearAll()` method to `SecureStorageManager.kt` using `sharedPreferences.edit().clear().apply()`.
  2. Enhanced `disconnect()` in `SettingsViewModel.kt` to wipe all keys/tokens from `SecureStorageManager`, reset RTMP values in memory, reset Twitch user state, and toggle off platform flags in `StreamDataStore`.
  3. Added prominent **Account & Data Management** card and **"Disconnect Accounts & Delete App Data"** outlined action with confirmation `AlertDialog` in `SettingsScreen.kt`.
  4. Bumped `versionCode = 28` and `versionName = "1.3.3"` in `app/build.gradle.kts`.
  5. Built and signed release bundle (`bundleRelease`) producing verified `app-release.aab` (6.26 MB) with R8 shrinking and resource optimization.
- **Files Modified / Created**:
  - [`app/build.gradle.kts`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/build.gradle.kts)
  - [`app/src/main/java/com/streamtwin/data/local/SecureStorageManager.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/data/local/SecureStorageManager.kt)
  - [`app/src/main/java/com/streamtwin/ui/settings/SettingsViewModel.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/ui/settings/SettingsViewModel.kt)
  - [`app/src/main/java/com/streamtwin/ui/settings/SettingsScreen.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/ui/settings/SettingsScreen.kt)
  - [`PROJECT_CONTEXT.md`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/PROJECT_CONTEXT.md)

### 📅 [2026-11-16 07:41:00] - YouTube Live Streaming & Play Console Deep Link Diagnosis & Hardening (v1.3.2 - Code 27)
- **Goal / Request**: Inspect and diagnose why Play Store users cannot go live on YouTube and why Google Play Console flags an unassociated web domain deep link; verify Android 16 (`targetSdk 36`) readiness, bump version to 27 (`1.3.2`), and run release bundle verification.
- **Root Causes Diagnosed**:
  1. **Play Console Domain Verification Flag**: `AndroidManifest.xml` previously had `<data android:scheme="https" android:host="localhost" android:pathPrefix="/streamtwin/callback" />`. Play Console treats any intent-filter with `http`/`https` schemes as a web deep link requiring an `assetlinks.json` verification file hosted at that domain, which fails on `localhost`.
  2. **Play Store YouTube Sign-In Failure (Code 10 DEVELOPER_ERROR)**: Play Store builds are re-signed by Google with the Play App Signing Key. If only local debug/upload SHA-1 keys are registered in Google Cloud Console, Google Play Services throws `ApiException: 10`. Previously, there was no manual YouTube configuration fallback or descriptive diagnostic for users.
  3. **Sensitive Scope Consent Unhandled**: Google's `youtube.force-ssl` is a sensitive/restricted scope. `GoogleAuthUtil.getToken()` throws `UserRecoverableAuthException` when consent is required. Without launching user consent, the token flow failed immediately.
  4. **60-Minute Token Expiration**: OAuth access tokens expire after 1 hour. Previously, the token was stored statically without automated refresh, causing HTTP 401 Unauthorized upon starting a broadcast later.
  5. **Missing YouTube Live Stream Resource**: `YouTubeApiManager.kt` assumed the user already had a pre-created live stream in YouTube Studio. For new or casual streamers, `liveStreams.list` returned an empty items array, triggering `JSONException` and failing stream initialization.
  6. **StreamingService Secondary RTMP Construction**: If `youtubeRtmpUrl` was empty, secondary RTMP client registration constructed invalid URLs (`"/{streamKey}"`), risking secondary connection failure.
- **Fixes Applied & Verified**:
  - **Manifest Scheme Cleanup**: Replaced all web domain schemes with native Android schemes (`com.streamtwin://callback` and `streamtwin://callback`). Verified zero `http`/`https` scheme tags exist across all source and merged release manifests.
  - **User Consent & Intent Launching**: Protected `userRecoverableAuthLauncher` with `consentIntent != null` checks in `ConnectScreen.kt` and `SettingsScreen.kt`, launching Google's consent dialog seamlessly.
  - **Automated Background Token Refresh**: Stored account identity in `SecureStorageManager` and refreshed tokens via `GoogleAuthUtil.getToken()` in `StreamingService.kt` and `YouTubeChatService.kt`.
  - **Auto-Creation of Live Streams**: Added `createLiveStream` fallback in `YouTubeApiManager.kt` using `liveStreams.insert`, plus automated retry with `privacyStatus = "unlisted"` if `public` broadcasts are restricted.
  - **Manual YouTube Stream Key & RTMP Fallback**: Provided manual YouTube Stream Key & RTMP URL fields in both `ConnectScreen` and `SettingsScreen`, with prioritized fallback in `StreamingService.kt`.
  - **Hardened RTMP URL Resolution**: Guaranteed `ytBaseUrl` fallback (`rtmp://a.rtmp.youtube.com/live2`) across primary, secondary, and retry paths in `StreamingService.kt`.
  - **Build & Verification**: Verified `compileSdk = 36`, `targetSdk = 36`, `versionCode = 27`, `versionName = "1.3.2"`. All unit tests passed (`BUILD SUCCESSFUL in 36s`). Built, minified (R8), and signed `play-upload-v27/StreamTwin-v1.3.2-v27.aab` (`BUILD SUCCESSFUL in 1m 33s`).
- **Files Modified / Created**:
  - [`app/src/main/AndroidManifest.xml`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/AndroidManifest.xml)
  - [`app/src/main/java/com/streamtwin/MainActivity.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/MainActivity.kt)
  - [`app/src/main/java/com/streamtwin/data/local/SecureStorageManager.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/data/local/SecureStorageManager.kt)
  - [`app/src/main/java/com/streamtwin/data/remote/YouTubeApiManager.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/data/remote/YouTubeApiManager.kt)
  - [`app/src/main/java/com/streamtwin/service/StreamingService.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/service/StreamingService.kt)
  - [`app/src/main/java/com/streamtwin/service/YouTubeChatService.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/service/YouTubeChatService.kt)
  - [`app/src/main/java/com/streamtwin/ui/connect/ConnectScreen.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/ui/connect/ConnectScreen.kt)
  - [`app/src/main/java/com/streamtwin/ui/settings/SettingsScreen.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/ui/settings/SettingsScreen.kt)
  - [`app/src/main/java/com/streamtwin/ui/settings/SettingsViewModel.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/ui/settings/SettingsViewModel.kt)
  - [`app/src/main/java/com/streamtwin/MainViewModel.kt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/src/main/java/com/streamtwin/MainViewModel.kt)
  - [`app/build.gradle.kts`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/build.gradle.kts)
  - [`play-upload-v27/StreamTwin-v1.3.2-v27.aab`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/play-upload-v27/StreamTwin-v1.3.2-v27.aab)
  - [`play-upload-v27/release-notes.txt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/play-upload-v27/release-notes.txt)
  - [`PROJECT_CONTEXT.md`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/PROJECT_CONTEXT.md)
- **Key Decisions / Technical Notes**: Native custom schemes completely bypass web domain ownership verification in Play Console. Dual Google OAuth + manual RTMP stream key ensures 100% of users can stream to YouTube regardless of Play App Signing SHA-1 configuration.

### 📅 [2026-10-11 10:41:00] - Google Play Console Target API 36 & Version Code 26 Release
- **Goal / Request**: User received Play Console notifications about updating target API level by Aug 31, 2026 and unverified deep link, followed by "Version code 23 has already been used" on upload. User requested version code 26.
- **What Was Done**:
  - Located project in [`C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin).
  - Clarified that deep link notification is an advisory for HTTP localhost OAuth redirect and requires no domain purchase ($0, non-blocking).
  - Updated [`app/build.gradle.kts`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/build.gradle.kts) to `versionCode = 26`, `versionName = "1.3.1"`, `targetSdk = 36`.
  - Built production bundle with `./gradlew :app:bundleRelease`.
  - Exported [`StreamTwin-v1.3.1-v26.aab`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/play-upload-v26/StreamTwin-v1.3.1-v26.aab) and [`release-notes.txt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/play-upload-v26/release-notes.txt) into `play-upload-v26`.
- **Files Modified / Created**:
  - [`app/build.gradle.kts`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/app/build.gradle.kts)
  - [`play-upload-v26/StreamTwin-v1.3.1-v26.aab`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/play-upload-v26/StreamTwin-v1.3.1-v26.aab)
  - [`play-upload-v26/release-notes.txt`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/play-upload-v26/release-notes.txt)
  - [`PROJECT_CONTEXT.md`](file:///C:/Users/manis/.gemini/antigravity-ide/scratch/StreamTwin/PROJECT_CONTEXT.md)
- **Key Decisions / Technical Notes**: Kept `storeFile=streamtwin_keystore.jks` in release signing; targetSdk 36 satisfies Google Play's 2026 policy requirements.
