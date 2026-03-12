# StreamTwin: Android Live-Streaming Client

## 📺 Overview
StreamTwin is a native, highly-optimized Android application custom-built to stream live gameplay and camera feeds directly to **Twitch**. Developed from scratch using modern Android architectures (Kotlin, Jetpack Compose, Hilt, Coroutines), this application represents a complete, deep-dive into hardware-accelerated media projection and real-time networking.

## ✨ Features
- **Dynamic RTMP Engine:** Encodes screen capture and camera feeds dynamically, interacting directly with Twitch Ingest Servers.
- **Hardware Audio Processing:** Leverages the Android `VOICE_COMMUNICATION` profile to implement hardware-level Acoustic Echo Cancellation and Automatic Noise Suppression, filtering out keyboard clacks during streams.
- **Twitch Integration:** Built-in secure OAuth login and dynamic stream-key fetching via the Twitch API.
- **Foreground Multitasking:** Runs a bullet-proof Foreground Service allowing users to stream heavy 3D mobile games without the Android OS killing the broadcast.
- **Floating Command Center:** Employs a system-level overlay window (`TYPE_APPLICATION_OVERLAY`) to inject an interactive dashboard containing a live chat feed, viewer count, ping indicator, and quick-action mute toggles.
- **Retrospective Clipping:** Continuously records a circular buffer of the stream. With the tap of a button, it retroactively saves the last 60 seconds of footage directly to the device's Gallery, bypassing Twitch server compression.
- **Resilient Networking:** Features an intelligent Exponential Backoff engine capable of autonomously recovering connection drops due to cellular handoffs or temporary Wi-Fi failures.

## 🛠 Tech Stack
- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose (Material 3)
- **Dependency Injection:** Dagger Hilt
- **Concurrency:** Coroutines & Flow
- **Network / API:** Retrofit / OkHttp
- **Local Storage:** DataStore & Android Keystore

## 🚀 Future Roadmap
The foundation of StreamTwin is highly modular, allowing for future expansion features like multi-streaming (YouTube, Kick, TikTok), customizable visual watermarks, and auto-reply chat bots.
