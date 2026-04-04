# TVClaw

TV + Claw: a small stack to run the TVClaw brain (NanoClaw) on your machine, talk to it from WhatsApp, and sideload the Android client.

## One-line install (macOS / Linux)

```bash
curl -fsSL https://raw.githubusercontent.com/TVClaw/TVClaw/main/install.sh | bash
```

Already cloned this repo? From the repository root:

```bash
bash install.sh
```

Windows: use **WSL2** and run the same `curl` line inside Ubuntu on WSL.

The installer:

- Bootstraps **Node.js 20+** (Homebrew on macOS if you allow it)
- Installs **nanoclaw** dependencies, builds TypeScript, builds the **agent container** (Docker on Linux and typical macOS setups; Apple Container only if your tree uses it and the `container` CLI is present)
- Runs setup steps (timezone, mounts, container, optional background service, verify)
- Installs **OneCLI** and asks for your **Anthropic API key**
- Walks through **WhatsApp** (QR in the terminal; optional large QR in the browser) and **TVClaw** group registration
- Tries **ADB install** for the Android APK, or serves the APK on your LAN and prints a **QR code** so you do not type a URL
- Links `tvclaw` into `~/.local/bin` and opens the GitHub page so you can **star** the repo: [github.com/TVClaw/TVClaw](https://github.com/TVClaw/TVClaw)

### Environment variables (optional)

| Variable | Purpose |
|----------|---------|
| `TVCLAW_REPO_ROOT` | Use an existing checkout (skip clone detection) |
| `TVCLAW_SKIP_CLONE` | Fail if not already in a repo instead of cloning to `~/TVClaw` |
| `TVCLAW_CLONE_DIR` | Clone destination (default `~/TVClaw`) |
| `TVCLAW_SKIP_AUTH_AI` | Skip OneCLI / Anthropic step |
| `TVCLAW_SKIP_WHATSAPP` | Skip WhatsApp auth and group link |
| `TVCLAW_SKIP_APK` | Skip Android APK download / sideload helper |
| `TVCLAW_SKIP_SERVICE` | Skip background auto-start |
| `TVCLAW_WA_BROWSER_QR=1` | Also open one browser tab with a large WhatsApp QR (default: terminal only) |
| `TVCLAW_MACOS_NOTIFY=1` | macOS only: show a desktop banner when WhatsApp needs re-linking (default: off) |
| `TVCLAW_SETUP_UI=0` | Verbose setup blocks and full logs (default: short, friendly installer output) |
| `TVCLAW_APK_URL` | Override APK download URL |
| `TVCLAW_ADB_IP` | Optional: TV IP for `adb connect …:5555` before APK install |

### macOS: notifications or TVClaw still running after you stop the installer

Repeated **TVClaw** banners usually mean the **brain process** is still running in the background (for example the login item installed during setup). It is trying to use WhatsApp and asking you to scan a QR again. **Desktop alerts from TVClaw are off by default** in current versions; if you enabled them with `TVCLAW_MACOS_NOTIFY=1`, set that variable off or update to the latest code.

To stop the background app until you want it:

```bash
launchctl unload "$HOME/Library/LaunchAgents/com.nanoclaw.plist" 2>/dev/null || true
```

Also quit any Terminal window where you ran `tvclaw start` or `npm start`, and optionally **Docker Desktop** if you do not need it.

### After install

```bash
export PATH="$HOME/.local/bin:$PATH"
tvclaw start
```

Or: `cd nanoclaw2 && npm start`. Logs: `nanoclaw2/logs/nanoclaw.log`.

### WhatsApp setup looks scary — what do the messages mean?

The installer pauses the background brain while you link WhatsApp so only one process uses the session. If you still see **`conflict` / `replaced`**, something else (another Terminal, an old `npm start`) is using the same `nanoclaw2/store/auth` — stop it and run `npm run auth` / `npm run link:whatsapp` again. **`515`** is usually a short reconnect; **`MessageCounterError` / old counters** often mean split or stale keys: stop every TVClaw process, then `rm -rf nanoclaw2/store/auth` and link WhatsApp again from a single window.

### Android APK

By default `install.sh` uses **`prebuilt/tvclaw-android.apk`** if it exists in the clone, otherwise **downloads** from **`TVCLAW_APK_URL`** (GitHub raw on `main` by default), then serves that file to the TV.

Local development build: **`TVCLAW_BUILD_ANDROID_APK=1 bash install.sh`** (needs **JDK 17+** and **Android SDK** / **`ANDROID_HOME`**). Or set **`TVCLAW_LOCAL_APK`** to an existing APK path.

On every push to **`main`**, [`.github/workflows/android-apk.yml`](.github/workflows/android-apk.yml) builds and may commit **`prebuilt/tvclaw-android.apk`** (`[skip ci]` avoids an infinite loop).

### Repository layout

- `nanoclaw2/` — gateway (WhatsApp, agents, OneCLI); see [nanoclaw2/README.md](nanoclaw2/README.md)
- `TVClaw/apps/client-android/` — Android TV / phone client
