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
- Walks through **WhatsApp** (large QR in the browser plus terminal) and **TVClaw** group registration
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
| `TVCLAW_SKIP_SERVICE` | Skip launchd/systemd prompt |
| `TVCLAW_WA_BROWSER_QR=0` | WhatsApp QR in terminal only |
| `TVCLAW_APK_URL` | Override APK download URL |

### After install

```bash
export PATH="$HOME/.local/bin:$PATH"
tvclaw start
```

Or: `cd nanoclaw2 && npm start`. Logs: `nanoclaw2/logs/nanoclaw.log`.

### Android APK (in-repo)

On every push to **`main`** (except commits that only refresh the prebuilt file), [`.github/workflows/android-apk.yml`](.github/workflows/android-apk.yml) runs Gradle and commits **`prebuilt/tvclaw-android.apk`** back to the repo (`[skip ci]` avoids an infinite loop). You can also run the workflow manually from the Actions tab.

Installer default URL:

`https://raw.githubusercontent.com/TVClaw/TVClaw/main/prebuilt/tvclaw-android.apk`

Until the first successful workflow run on `main`, that file may be missing and the installer skips APK sideload until it exists.

### Repository layout

- `nanoclaw2/` — gateway (WhatsApp, agents, OneCLI); see [nanoclaw2/README.md](nanoclaw2/README.md)
- `TVClaw/apps/client-android/` — Android TV / phone client
