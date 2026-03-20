To ensure a smooth "vibe coding" experience with Cursor or Claude Engineer, we need to provide a single, comprehensive `CLAUDE.md` that acts as the "Master Blueprint."

This version includes the **unified repo structure** (prepped for Apple TV), the **Core Protocol**, and **testable POC commands** so the AI can start generating functional code immediately.

### `CLAUDE.md`

```markdown
# TVClaw Master Blueprint (v1.0)

## 1. Project Vision
TVClaw is an AI-powered agentic system that allows users to control their TV (Android TV MVP, Apple TV v2) via DM (WhatsApp/Telegram). 
- **The Brain:** A central NanoClaw server (Mac/PC) that handles LLM reasoning.
- **The Hands:** Lightweight clients on TV hardware executing Accessibility and Media commands.

## 2. Repo Architecture (Monorepo)
```text
/TVClaw
├── /apps
│   ├── server-brain           # Node.js + NanoClaw (The "Brain")
│   │   ├── src/index.ts       # Entry: Connects to DMs (WhatsApp/Telegram)
│   │   ├── src/engine.ts      # NanoClaw orchestration & LLM config
│   │   └── src/tools/         # NanoClaw Skills (tv_control.ts, vision.ts)
│   ├── client-android         # Kotlin (The Android "Hands")
│   │   ├── app/src/main/java  # AccessibilityService & WebSocket Client
│   │   └── app/build.gradle   # Min SDK 26 (Android 8.0+)
│   └── client-apple           # Swift (Future: Apple TV Remote Protocol)
├── /packages
│   ├── protocol               # Shared JSON schemas & Type definitions
│   │   └── commands.json      # The "Language" both Brain and TV speak
│   └── ui-maps                # Selectors for Netflix, YouTube, etc.
│       └── netflix.json       # e.g., {"search_btn": "com.netflix.ninja:id/search"}
├── .env.example               # GEMINI_API_KEY, WHATSAPP_TOKEN, TV_IP
├── docker-compose.yml         # Local stack (Brain + Redis + Ollama)
└── CLAUDE.md                  # This file

```

## 3. The TVClaw Protocol (JSON)

All communication between Brain and TV must follow this structure:

```json
{
  "request_id": "uuid-v4",
  "timestamp": "iso-string",
  "payload": {
    "action": "LAUNCH_APP | SEARCH | MEDIA_CONTROL | VISION_SYNC",
    "params": {
      "app_id": "com.netflix.ninja",
      "query": "Dinosaur documentaries",
      "control": "PLAY | PAUSE | REWIND_30",
      "value": "optional_string_or_int"
    }
  }
}

```

## 4. Initial POC Commands (For Testing)

Use these three scenarios to verify the "vibe" is working:

### POC 1: The "Simple Remote"

* **User:** "Mute the TV."
* **Brain Tool:** `send_command({ action: "MEDIA_CONTROL", params: { control: "MUTE" } })`
* **Android Success:** `AudioManager.setStreamMute(STREAM_MUSIC, true)`

### POC 2: The "App Navigator"

* **User:** "Open Netflix and search for Leo Messi."
* **Brain Tool:** `send_command({ action: "SEARCH", params: { app_id: "com.netflix.ninja", query: "Messi" } })`
* **Android Success:** Deep link to Netflix + Accessibility click on search icon.

### POC 3: The "Vision Loop" (Ad Detection)

* **User:** "Tell me when the ads are over."
* **Process:** 1. Brain sends `VISION_SYNC`.
2. Android takes `MediaProjection` screenshot, sends Base64 to Brain.
3. Brain asks Gemini: "Is this an advertisement?"
4. If false, Brain DMs User: "The show is back on!"

## 5. Technical Constraints & Patterns

* **Android Constraints:** Must run as a `BackgroundService` and `AccessibilityService`. Do not block the Main Thread for WebSocket IO.
* **Apple TV Note:** Future support will use `pyatv`-style IP control or HDMI-CEC since Accessibility APIs are locked. Keep the `protocol` package platform-agnostic.
* **NanoClaw Integration:** Use [NanoClaw](https://github.com/qwibitai/nanoclaw) alongside or merged with `server-brain`. Import TV sends from `server-brain` subpath `nanoclaw` (`createTvCommandSink`, `sendToAllTvs`, `visionSyncCommand`). Do not over-engineer the agent loop.
* **Vibe Coding Goal:** Focus on **Human-in-the-loop** safety. The Brain should always confirm high-impact actions (like "Buy this movie") before executing.

## 6. Development Workflow

1. Start `server-brain` (TypeScript).
2. Sideload `client-android` (Debug APK) to Nvidia Shield/Chromecast.
3. Pair via local IP over WebSockets.

```

---


```