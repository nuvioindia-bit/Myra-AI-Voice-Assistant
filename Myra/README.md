# MYRA — Android AI Voice Assistant

MYRA is a production-ready Android AI Voice Assistant built with Kotlin. It connects to Google Gemini Live API via WebSocket for real-time bidirectional voice conversations.

## Features

- **Real-time Voice AI** — Gemini Live WebSocket with native audio (Bidirectional GenerateContent)
- **Multiple Voices** — 8 voice options (Aoede, Charon, Kore, Fenrir, Puck, Leda, Orus, Zephyr)
- **Personality Modes** — GF Mode (Hinglish), Professional Mode, Assistant Mode
- **Phone Actions** — Make calls, send SMS, WhatsApp messages, open/close apps
- **Prime Contacts** — Quick voice commands for favorite contacts
- **Incoming Call Handler** — MYRA announces callers and accepts/rejects via voice
- **Floating Orb Overlay** — Accessible via double power button press
- **Accessibility Service** — Voice-controlled app navigation

## Architecture

- **Language:** Kotlin
- **Architecture:** MVVM
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34

## Project Structure

```
app/src/main/java/com/myra/assistant/
├── ai/                     # Gemini Live WebSocket, Audio engine, Command parser
├── model/                  # Data classes (AppCommand, ChatMessage)
├── service/                # Accessibility, Call monitor, Overlay, Receivers
├── ui/main/                # MainActivity, OrbAnimationView, Waveform, Chat
├── ui/settings/            # SettingsActivity
└── viewmodel/              # MainViewModel (phone actions)
```

## Building with GitHub Actions

This repository includes a GitHub Actions workflow (`.github/workflows/build.yml`) that automatically builds both Release and Debug APKs on every push to `main`/`master`.

### Manual Build

1. Clone the repository
2. Open in Android Studio (or use command line with Gradle)
3. Run `gradle wrapper --gradle-version 8.2` to generate the wrapper
4. Build with `./gradlew assembleRelease`

## Setup

1. Install the APK on your Android device
2. Open Settings (gear icon in top-right)
3. Enter your **Gemini API Key**
4. Set your name, preferred voice, and personality mode
5. Add **Prime Contacts** for quick voice calling
6. Enable **Accessibility Service** when prompted (required for app control)
7. Grant all requested permissions (Microphone, Contacts, Phone, SMS, etc.)

## Voice Commands

| Command (English/Hinglish) | Action |
|---|---|
| "open YouTube" / "YouTube kholo" | Opens app |
| "close WhatsApp" / "WhatsApp band karo" | Closes app |
| "call [name]" / "[name] ko call karo" | Makes phone call |
| "SMS bhejo [name] ko" | Opens SMS composer |
| "volume up/down" / "volume badhao/kam karo" | Adjusts volume |
| "torch on/off" | Toggles flashlight |
| "WiFi on/off" | Toggles WiFi |
| "Bluetooth on/off" | Toggles Bluetooth |
| "call my close friend" | Calls first prime contact |

## Important Notes

- **API Key:** Get your Gemini API key from [Google AI Studio](https://aistudio.google.com/app/apikey)
- **Accessibility:** Required for voice-controlled app closing and UI interaction
- **Overlay Permission:** Required for the floating orb
- **Phone Permissions:** Required for incoming call detection and call control

## License

This project is for personal use. All rights reserved.
