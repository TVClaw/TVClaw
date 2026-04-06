#!/usr/bin/env bash
set -euo pipefail

tty_read() {
  if [[ -t 0 ]]; then
    read "$@"
  elif [[ -r /dev/tty ]]; then
    read "$@" < /dev/tty
  else
    read "$@"
  fi
}

if [[ -z "${TVCLAW_CLONE_DIR:-}" ]]; then
  TVCLAW_CLONE_DIR="${HOME:-$(cd ~ && pwd)}/TVClaw"
fi
TVCLAW_SKIP_SERVICE="${TVCLAW_SKIP_SERVICE:-1}"
TVCLAW_REPO_URL="${TVCLAW_REPO_URL:-https://github.com/TVClaw/TVClaw.git}"
TVCLAW_APK_URL="${TVCLAW_APK_URL:-https://raw.githubusercontent.com/TVClaw/TVClaw/main/prebuilt/tvclaw-android.apk}"
STAR_URL="https://github.com/TVClaw/TVClaw"

REPO_ROOT=""
NANOCLAW_ROOT=""

TVCLAW_PROGRESS_BG_PID=""
TVCLAW_PROGRESS_SEQ=0

tvclaw_progress_should_show() {
  [[ "${TVCLAW_NO_PROGRESS:-0}" != "1" ]] || return 1
  case "${CI:-}" in true|1|yes) return 1 ;; esac
  [[ -t 2 ]] || return 1
  return 0
}

tvclaw_heartbeat_stop() {
  if [[ -n "${TVCLAW_PROGRESS_BG_PID:-}" ]] && kill -0 "$TVCLAW_PROGRESS_BG_PID" 2>/dev/null; then
    kill "$TVCLAW_PROGRESS_BG_PID" 2>/dev/null || true
    wait "$TVCLAW_PROGRESS_BG_PID" 2>/dev/null || true
  fi
  TVCLAW_PROGRESS_BG_PID=""
  if tvclaw_progress_should_show; then
    printf '\r\033[2K' >&2
  fi
}

tvclaw_heartbeat_start() {
  local label="$1"
  tvclaw_heartbeat_stop
  tvclaw_progress_should_show || return 0
  (
    local i=0
    while true; do
      if (( i % 2 == 0 )); then
        printf '\r  \033[2K[%s] 🦞  %s' "$TVCLAW_PROGRESS_SEQ" "$label" >&2
      else
        printf '\r  \033[2K[%s] 📺  %s' "$TVCLAW_PROGRESS_SEQ" "$label" >&2
      fi
      sleep 0.4
      i=$((i + 1))
    done
  ) &
  TVCLAW_PROGRESS_BG_PID=$!
}

tvclaw_busy() {
  local label="$1"
  shift
  TVCLAW_PROGRESS_SEQ=$((TVCLAW_PROGRESS_SEQ + 1))
  tvclaw_heartbeat_start "$label"
  local ec=0
  "$@" || ec=$?
  tvclaw_heartbeat_stop
  return "$ec"
}

print_banner() {
  cat <<'EOF'

  _________      _______ _          __          __
 |__   __\ \    / / ____| |        /\ \        / /
    | |   \ \  / / |    | |       /  \ \  /\  / / 
    | |    \ \/ /| |    | |      / /\ \ \/  \/ /  
    | |     \  / | |____| |____ / ____ \  /\  /   
    |_|      \/   \_____|______/_/    \_\/  \/    
                                                  
                                                  
        📺🦞TVClaw   
  ═══════════════════════════
    TV + Claw — personal TV brain

EOF
}

usage() {
  echo "usage: curl -fsSL -H 'Accept: application/vnd.github.raw' https://api.github.com/repos/TVClaw/TVClaw/contents/install.sh?ref=main -o /tmp/tvclaw-install.sh && bash /tmp/tvclaw-install.sh   (recommended; prompts use /dev/tty)"
  echo "   or: curl -fsSL -H 'Accept: application/vnd.github.raw' https://api.github.com/repos/TVClaw/TVClaw/contents/install.sh?ref=main | bash"
  echo "   or: curl -fsSL https://cdn.jsdelivr.net/gh/TVClaw/TVClaw@main/install.sh | bash   (may lag behind GitHub)"
  echo "   or: bash install.sh"
  echo "env: TVCLAW_SKIP_CLONE=1 TVCLAW_REPO_ROOT=/path TVCLAW_SKIP_AUTH_AI=1 TVCLAW_SKIP_WHATSAPP=1 TVCLAW_SKIP_APK=1 TVCLAW_SKIP_SERVICE=0 TVCLAW_WA_BROWSER_QR=1"
  echo "     (background launchd/systemd is off by default; set TVCLAW_SKIP_SERVICE=0 to install it)"
  echo "     TVCLAW_SETUP_UI=0 TVCLAW_INSTALLER=0 — verbose setup logs for debugging"
  echo "     TVCLAW_NO_PROGRESS=1 — disable animated 🦞/📺 progress on stderr"
  echo "     TVCLAW_WA_BROWSER_QR=1 opens one browser tab with a large QR; default is terminal-only QR"
  echo "     TVCLAW_ADB_IP=192.168.x.x — optional adb connect; TVCLAW_SKIP_WHATSAPP=1 skips WhatsApp steps and group messages"
  echo "     TVCLAW_LOCAL_APK=/path/to.apk — use this exact APK (skips download and Gradle)"
  echo "     TVCLAW_BUILD_ANDROID_APK=1 — run Gradle assembleDebug here and serve that build (local dev / Android SDK required)"
  echo "     APK versionCode/versionName in the log need aapt (ANDROID_HOME or sdk.dir in TVClaw/apps/client-android/local.properties)"
  echo "     Stop the background brain: macOS launchctl unload ~/Library/LaunchAgents/com.nanoclaw.plist (or bootout gui/\$(id -u)/com.nanoclaw)"
  echo "     Linux: systemctl --user stop nanoclaw.service"
  echo "     Verbose installer + brain log tail: TVCLAW_SETUP_UI=0 bash install.sh"
  exit 0
}

tvclaw_android_project() {
  echo "${REPO_ROOT}/TVClaw/apps/client-android"
}

tvclaw_gradle_debug_apk() {
  echo "$(tvclaw_android_project)/app/build/outputs/apk/debug/app-debug.apk"
}

tvclaw_build_android_apk() {
  local root
  root=$(tvclaw_android_project)
  if [[ ! -f "$root/gradlew" ]]; then
    echo "TVClaw Android project or gradlew missing: $root" >&2
    return 1
  fi
  echo ""
  echo "  ··· Building TVClaw Android APK on this computer (Gradle) ···"
  echo ""
  tvclaw_busy "Gradle assembleDebug (may take a few minutes)…" bash -c "cd \"$root\" && chmod +x ./gradlew 2>/dev/null || true && exec ./gradlew assembleDebug --no-daemon"
}

tvclaw_android_sdk_home() {
  if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" ]]; then
    echo "$ANDROID_HOME"
    return
  fi
  local lp raw
  lp="$(tvclaw_android_project)/local.properties"
  [[ -f "$lp" ]] || return
  raw=$(grep '^sdk.dir=' "$lp" 2>/dev/null | head -1 | sed 's/^sdk.dir=//' | tr -d '\r')
  raw="${raw//\\//}"
  [[ -n "$raw" && -d "$raw" ]] && echo "$raw"
}

tvclaw_find_aapt() {
  local c sdkh
  sdkh=$(tvclaw_android_sdk_home)
  if [[ -n "$sdkh" && -d "${sdkh}/build-tools" ]]; then
    c=$(find "${sdkh}/build-tools" -maxdepth 2 -name aapt -type f 2>/dev/null | sort -V | tail -1)
    [[ -n "$c" ]] && {
      echo "$c"
      return
    }
  fi
  command -v aapt 2>/dev/null || true
}

tvclaw_print_apk_file_metadata() {
  local ap="$1"
  local aapt_bin line vc vn apk_bytes apk_sha
  echo "── About this APK file (what the TV should install) ──"
  aapt_bin=$(tvclaw_find_aapt)
  if [[ -n "$aapt_bin" && -f "$ap" ]]; then
    line=$("$aapt_bin" dump badging "$ap" 2>/dev/null | head -1 || true)
    if [[ -n "$line" ]]; then
      vc="" vn=""
      [[ "$line" =~ versionCode=\'([^\']*)\' ]] && vc="${BASH_REMATCH[1]}"
      [[ "$line" =~ versionName=\'([^\']*)\' ]] && vn="${BASH_REMATCH[1]}"
      echo "  versionCode: ${vc:-?}   versionName: ${vn:-?}"
    else
      echo "  version: (aapt could not read package line — check ANDROID_HOME build-tools)"
    fi
  else
    echo "  version: (install Android SDK build-tools and set ANDROID_HOME, or add aapt to PATH)"
  fi
  if stat -f %z "$ap" >/dev/null 2>&1; then
    apk_bytes=$(stat -f %z "$ap")
  else
    apk_bytes=$(stat -c %s "$ap" 2>/dev/null || echo "?")
  fi
  if command -v shasum >/dev/null 2>&1; then
    apk_sha=$(shasum -a 256 "$ap" | awk '{print $1}')
  elif command -v sha256sum >/dev/null 2>&1; then
    apk_sha=$(sha256sum "$ap" | awk '{print $1}')
  else
    apk_sha="?"
  fi
  echo "  size (bytes): ${apk_bytes}"
  echo "  SHA256: ${apk_sha}"
  echo ""
}

for a in "$@"; do
  [[ "$a" == "-h" || "$a" == "--help" ]] && usage
done

resolve_nanoclaw_root() {
  local r="$1"
  if [[ -f "$r/nanoclaw2/setup.sh" ]]; then
    echo "$r/nanoclaw2"
    return 0
  fi
  return 1
}

detect_repo_root() {
  if [[ -n "${TVCLAW_REPO_ROOT:-}" ]]; then
    if resolve_nanoclaw_root "$TVCLAW_REPO_ROOT" >/dev/null; then
      echo "$TVCLAW_REPO_ROOT"
      return 0
    fi
    echo "TVCLAW_REPO_ROOT set but nanoclaw2/setup.sh not found: $TVCLAW_REPO_ROOT" >&2
    exit 1
  fi
  local here cwd script_dir
  cwd=$(pwd)
  if resolve_nanoclaw_root "$cwd" >/dev/null; then
    echo "$cwd"
    return 0
  fi
  if [[ -n "${BASH_SOURCE[0]:-}" && "${BASH_SOURCE[0]}" != *bash && "${BASH_SOURCE[0]}" != "-" && "${BASH_SOURCE[0]}" != "/dev/stdin" ]]; then
    script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
    if resolve_nanoclaw_root "$script_dir" >/dev/null; then
      echo "$script_dir"
      return 0
    fi
  fi
  return 1
}

nanoclaw2_clone_url() {
  local dest="$1"
  local u=""
  if [[ -f "$dest/.gitmodules" ]]; then
    u=$(git config -f "$dest/.gitmodules" --get submodule.nanoclaw2.url 2>/dev/null) || true
  fi
  if [[ -n "$u" ]]; then
    echo "$u"
  else
    echo "https://github.com/TVClaw/nanoclaw2.git"
  fi
}

ensure_nanoclaw2_dir() {
  local dest="$1"
  [[ -f "$dest/nanoclaw2/setup.sh" ]] && return 0
  local url
  url=$(nanoclaw2_clone_url "$dest")
  echo "Pulling nanoclaw2 (standalone clone — parent repo may not record the submodule gitlink yet)…"
  rm -rf "$dest/nanoclaw2"
  tvclaw_busy "git clone nanoclaw2…" git clone --depth 1 "$url" "$dest/nanoclaw2"
}

clone_repo() {
  local dest="$1"
  if [[ -e "$dest" && ! -d "$dest/.git" ]]; then
    echo "Path exists but is not a git repo: $dest" >&2
    exit 1
  fi
  if [[ ! -d "$dest/.git" ]]; then
    tvclaw_busy "git clone TVClaw…" bash -c "git clone --depth 1 --recurse-submodules \"$TVCLAW_REPO_URL\" \"$dest\" || git clone --depth 1 \"$TVCLAW_REPO_URL\" \"$dest\""
  else
    tvclaw_busy "git submodule update…" bash -c "git -C \"$dest\" submodule sync --recursive 2>/dev/null || true; exec git -C \"$dest\" submodule update --init --recursive"
  fi
  ensure_nanoclaw2_dir "$dest"
  if [[ ! -f "$dest/nanoclaw2/setup.sh" ]]; then
    echo "Could not obtain nanoclaw2 — ensure https://github.com/TVClaw/nanoclaw2 is public." >&2
    exit 1
  fi
}

tvclaw_upgrade_nanoclaw2_if_stale() {
  local root="$1"
  local v="$root/nanoclaw2/setup/verify.ts"
  local nc="$root/nanoclaw2"
  [[ -d "$nc/.git" && -f "$v" ]] || return 0
  grep -q "TVCLAW_INSTALLER === '1'" "$v" 2>/dev/null && return 0
  echo "Refreshing nanoclaw2 — this checkout would fail setup verify before the AI / WhatsApp steps." >&2
  tvclaw_busy "git: nanoclaw2 @ origin/main (TVClaw installer)…" bash -c "git -C \"\$1\" fetch --depth 1 origin main && exec git -C \"\$1\" checkout -q FETCH_HEAD" _ "$nc" || {
    echo "Could not refresh nanoclaw2 (network?). Run: cd \"$nc\" && git fetch origin main && git checkout FETCH_HEAD" >&2
  }
}

ensure_repo() {
  unset REPO_ROOT 2>/dev/null || true
  REPO_ROOT=""
  if rr=$(detect_repo_root 2>/dev/null); then
    REPO_ROOT=$rr
    echo "Using this TVClaw folder: $REPO_ROOT"
  else
    if [[ "${TVCLAW_SKIP_CLONE:-}" == "1" ]]; then
      echo "Not inside TVClaw repo and TVCLAW_SKIP_CLONE=1 — set TVCLAW_REPO_ROOT or run from clone." >&2
      exit 1
    fi
    _tvclaw_install_dest="${HOME:-$(cd ~ && pwd)}/TVClaw"
    if [[ -n "${TVCLAW_CLONE_DIR:-}" ]]; then
      _tvclaw_install_dest="$TVCLAW_CLONE_DIR"
    fi
    echo "Downloading TVClaw to $_tvclaw_install_dest…"
    mkdir -p "$(dirname "$_tvclaw_install_dest")"
    clone_repo "$_tvclaw_install_dest"
    REPO_ROOT="$_tvclaw_install_dest"
  fi
  tvclaw_upgrade_nanoclaw2_if_stale "$REPO_ROOT"
  if ! NANOCLAW_ROOT=$(resolve_nanoclaw_root "$REPO_ROOT"); then
    echo "nanoclaw2/setup.sh not found under $REPO_ROOT" >&2
    exit 1
  fi
}

node_major() {
  node -e "process.stdout.write(String(parseInt(process.versions.node.split('.')[0],10)||0))" 2>/dev/null || echo 0
}

ensure_node() {
  if command -v node >/dev/null 2>&1; then
    local m
    m=$(node_major)
    if [[ "$m" -ge 20 ]]; then
      return 0
    fi
  fi
  echo "TVClaw needs Node.js installed once on this computer."
  if [[ "$(uname -s)" == "Darwin" ]] && command -v brew >/dev/null 2>&1; then
    x=""
    if ! tty_read -r -p "Install it automatically with Homebrew? [Y/n] " x; then
      x="n"
    fi
    if [[ -z "$x" || "$x" =~ ^[Yy] ]]; then
      tvclaw_busy "brew install Node.js…" bash -c "brew install node@22 || exec brew install node"
      if [[ -d "/opt/homebrew/opt/node@22/bin" ]]; then
        export PATH="/opt/homebrew/opt/node@22/bin:$PATH"
      elif [[ -d "/usr/local/opt/node@22/bin" ]]; then
        export PATH="/usr/local/opt/node@22/bin:$PATH"
      fi
    fi
  fi
  if command -v node >/dev/null 2>&1 && [[ $(node_major) -ge 20 ]]; then
    return 0
  fi
  echo "Install Node.js version 20 or newer from https://nodejs.org then run this installer again." >&2
  exit 1
}

wait_docker() {
  local i
  TVCLAW_PROGRESS_SEQ=$((TVCLAW_PROGRESS_SEQ + 1))
  for i in $(seq 1 90); do
    if docker info >/dev/null 2>&1; then
      if tvclaw_progress_should_show; then
        printf '\r\033[2K' >&2
      fi
      return 0
    fi
    if tvclaw_progress_should_show; then
      if (( i % 2 == 1 )); then
        printf '\r  \033[2K[%s] 🦞  Waiting for Docker to respond… (%ss)' "$TVCLAW_PROGRESS_SEQ" "$(( (i - 1) * 2 ))" >&2
      else
        printf '\r  \033[2K[%s] 📺  Waiting for Docker to respond… (%ss)' "$TVCLAW_PROGRESS_SEQ" "$(( (i - 1) * 2 ))" >&2
      fi
    fi
    sleep 2
  done
  if tvclaw_progress_should_show; then
    printf '\r\033[2K' >&2
  fi
  return 1
}

ensure_docker() {
  local rt="$1"
  [[ "$rt" == "apple-container" ]] && return 0
  if docker info >/dev/null 2>&1; then
    return 0
  fi
  echo "TVClaw uses Docker to run the AI helper in a safe, isolated box on your computer."
  if [[ "$(uname -s)" == "Darwin" ]]; then
    if ! command -v docker >/dev/null 2>&1; then
      if command -v brew >/dev/null 2>&1; then
        x=""
        if ! tty_read -r -p "Install Docker Desktop for me? [Y/n] " x; then
          x="n"
        fi
        if [[ -z "$x" || "$x" =~ ^[Yy] ]]; then
          tvclaw_busy "brew install Docker Desktop…" brew install --cask docker
        fi
      else
        echo "Install Docker Desktop from https://www.docker.com/products/docker-desktop/" >&2
      fi
    fi
    if [[ -d "/Applications/Docker.app" ]]; then
      open -a Docker 2>/dev/null || true
    fi
  elif [[ "$(uname -s)" == "Linux" ]]; then
    x=""
    if ! tty_read -r -p "Install Docker (your computer may ask for your password once)? [Y/n] " x; then
      x="n"
    fi
    if [[ -z "$x" || "$x" =~ ^[Yy] ]]; then
      tvclaw_busy "Docker engine install script…" bash -c "curl -fsSL https://get.docker.com | sh"
      if [[ "$(id -u)" -ne 0 ]]; then
        sudo usermod -aG docker "$USER" 2>/dev/null || true
        echo "You may need to log out and back in for the docker group."
      fi
      sudo systemctl enable --now docker 2>/dev/null || true
    fi
  fi
  echo "Waiting for Docker to finish starting…"
  if ! wait_docker; then
    echo "Docker is not ready yet. Open the Docker app, wait until it says it's running, then run this installer again." >&2
    exit 1
  fi
}

container_runtime_flag() {
  local f="$NANOCLAW_ROOT/src/container-runtime.ts"
  if [[ -f "$f" ]] && grep -q "CONTAINER_RUNTIME_BIN = 'container'" "$f"; then
    if command -v container >/dev/null 2>&1; then
      echo "apple-container"
      return 0
    fi
  fi
  echo "docker"
}

primary_lan_ip() {
  case "$(uname -s)" in
  Darwin*)
    ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true
    ;;
  Linux*)
    hostname -I 2>/dev/null | awk '{print $1}'
    ;;
  *)
    true
    ;;
  esac
}

adb_has_ready_device() {
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" { found=1 } END { exit found ? 0 : 1 }'
}

adb_pick_device_serial() {
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" { print $1; exit }'
}

try_adb_install_apk() {
  local apkip="$1"
  local serial out
  serial=$(adb_pick_device_serial)
  [[ -n "$serial" ]] || return 1
  echo ""
  echo "Installing the TVClaw TV app via adb ($serial)…"
  if out=$(tvclaw_busy "adb install TV app (can take a minute)…" bash -c "adb -s \"$serial\" install -r \"$apkip\" 2>&1"); then
    echo "$out"
    echo "✓ TV app installed on the TV (adb). Open TVClaw on the TV when you are ready."
    return 0
  fi
  echo "$out"
  return 1
}

whatsapp_apk_followup() {
  [[ "${TVCLAW_SKIP_WHATSAPP:-}" == "1" ]] && return 0
  TVCLAW_APK_HTTP_URL="${1:-}" TVCLAW_APK_FILE_PATH="${2:-}" TVCLAW_APK_INSTALLED_VIA_ADB="${3:-}" \
    tvclaw_busy "WhatsApp: posting TV app instructions…" bash -c 'cd "$1" && npx tsx src/whatsapp-apk-followup.ts' _ "$NANOCLAW_ROOT" || true
}

tvclaw_installer_brain_log_session() {
  if [[ "${TVCLAW_SKIP_SERVICE:-1}" != "1" ]]; then
    tty_read -r -p "Press Enter when the TV app is installed (or to continue)…" _ || true
    return 0
  fi
  mkdir -p "$NANOCLAW_ROOT/logs"
  local logf="$NANOCLAW_ROOT/logs/installer-npm-start.log"
  : >"$logf"
  if command -v pkill >/dev/null 2>&1; then
    pkill -f "${NANOCLAW_ROOT}/dist/index\\.js" 2>/dev/null || true
    tvclaw_busy "Preparing brain restart…" sleep 1
  fi
  TVCLAW_PROGRESS_SEQ=$((TVCLAW_PROGRESS_SEQ + 1))
  tvclaw_heartbeat_start "npm start is launching — first log lines may take a moment…"
  (
    cd "$NANOCLAW_ROOT" || exit 1
    LOG_LEVEL="${TVCLAW_BRAIN_LOG_LEVEL:-info}" TVCLAW_INSTALLER= npm start
  ) >>"$logf" 2>&1 &
  local bp=$!
  disown "$bp" 2>/dev/null || true
  local w=0
  while kill -0 "$bp" 2>/dev/null; do
    if [[ -s "$logf" ]]; then
      break
    fi
    sleep 0.25
    w=$((w + 1))
    if [[ "$w" -gt 2400 ]]; then
      break
    fi
  done
  tvclaw_heartbeat_stop
  echo ""
  echo "nanoclaw: npm start is running (PID $bp). Log file: $logf"
  echo "Streaming logs below. Press Ctrl+C once to stop following and continue the installer; the brain keeps running."
  echo ""
  tail -f "$logf" 2>/dev/null || true
}

apk_install_step() {
  [[ "${TVCLAW_SKIP_APK:-}" == "1" ]] && return 0
  local tmp apkip ip port url HTTP_PID=""
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"; [[ "$HTTP_PID" =~ ^[1-9][0-9]*$ ]] && kill "$HTTP_PID" 2>/dev/null || true' RETURN
  apkip="$tmp/tvclaw.apk"
  local prebuilt_apk="${REPO_ROOT}/prebuilt/tvclaw-android.apk"
  local gradle_apk
  gradle_apk=$(tvclaw_gradle_debug_apk)

  echo ""
  echo "════════════════════════════════════════════════════════════"
  echo "  TVClaw app on the Android TV"
  echo "════════════════════════════════════════════════════════════"
  echo ""
  echo "TVClaw is for your TV only. The same steps are also posted in your TVClaw WhatsApp group."
  echo ""
  echo "Ways to get the app onto the TV:"
  echo "  • ADB — if adb is installed and already sees your TV (you set up USB or wireless debugging yourself), we try adb install now."
  echo "  • TV browser — we start a small download page on this computer; open its address on the TV (same Wi‑Fi)."
  echo "  • File on this computer — copy the APK to a USB stick and install from the TV’s file manager."
  echo ""
  if [[ -n "${TVCLAW_ADB_IP:-}" ]]; then
    echo "Trying adb connect ${TVCLAW_ADB_IP}:5555 …"
  fi
  echo ""

  if [[ -n "${TVCLAW_LOCAL_APK:-}" && -f "${TVCLAW_LOCAL_APK}" ]]; then
    echo "Using TVCLAW_LOCAL_APK:"
    echo "  ${TVCLAW_LOCAL_APK}"
    cp "${TVCLAW_LOCAL_APK}" "$apkip"
  elif [[ "${TVCLAW_BUILD_ANDROID_APK:-}" == "1" ]]; then
    tvclaw_build_android_apk
    if [[ ! -f "$gradle_apk" ]]; then
      echo "Gradle did not produce: $gradle_apk" >&2
      echo "Install JDK 17+ and the Android SDK; set ANDROID_HOME if the build could not find the SDK." >&2
      return 1
    fi
    echo ""
    echo "Using freshly built APK (served to your TV next):"
    echo "  $gradle_apk"
    cp "$gradle_apk" "$apkip"
  elif [[ -f "$prebuilt_apk" ]]; then
    echo "Using prebuilt APK from this folder:"
    echo "  $prebuilt_apk"
    cp "$prebuilt_apk" "$apkip"
  else
    echo "Downloading TV APK from:"
    echo "  $TVCLAW_APK_URL"
    if tvclaw_progress_should_show; then
      if ! curl -fSL --progress-bar -o "$apkip" "$TVCLAW_APK_URL"; then
        echo "Download failed. Build locally and re-run with TVCLAW_BUILD_ANDROID_APK=1, or copy an APK and set TVCLAW_LOCAL_APK." >&2
        return 1
      fi
    elif ! curl -fsSL -o "$apkip" "$TVCLAW_APK_URL"; then
      echo "Download failed. Build locally and re-run with TVCLAW_BUILD_ANDROID_APK=1, or copy an APK and set TVCLAW_LOCAL_APK." >&2
      return 1
    fi
  fi

  tvclaw_print_apk_file_metadata "$apkip"

  if command -v adb >/dev/null 2>&1; then
    adb start-server >/dev/null 2>&1 || true
    if [[ -n "${TVCLAW_ADB_IP:-}" ]]; then
      tvclaw_busy "adb connect ${TVCLAW_ADB_IP}:5555 (waiting for device)…" bash -c "adb connect \"${TVCLAW_ADB_IP}:5555\" 2>/dev/null || true; exec sleep 2"
    fi
    if adb_has_ready_device && try_adb_install_apk "$apkip"; then
      whatsapp_apk_followup "" "" "1"
      echo ""
      echo "✓ TV app installed via adb. Open the TVClaw app on the TV — it will try to reach the brain on its own."
      echo "  When the TV screen says you are connected, use your WhatsApp TVClaw group to talk to the agent."
      tvclaw_installer_brain_log_session
      echo ""
      echo "✓ Installer step finished."
      return 0
    fi
  fi

  ip=$(primary_lan_ip)
  port=18765
  if [[ -z "$ip" ]]; then
    echo ""
    echo "Could not detect this computer’s LAN address. APK path on the brain (copy to USB for the TV):"
    echo "  $apkip"
    whatsapp_apk_followup "" "$apkip" ""
    echo ""
    echo "✓ Copy the APK to the TV, then open TVClaw — it will try to reach the brain on its own."
    echo "  When the TV screen says you are connected, use your WhatsApp TVClaw group to talk to the agent."
    tvclaw_installer_brain_log_session
    echo ""
    echo "✓ Installer step finished."
    return 0
  fi

  echo ""
  echo "── Open this on the Android TV’s browser (same Wi‑Fi as this computer) ──"
  echo ""

  cat >"$tmp/tvclaw_apk_serve.py" <<'EOS'
import os
import socketserver
import sys
from urllib.parse import unquote, urlparse

class Handler(socketserver.StreamRequestHandler):
    def log_message(self, format, *args):
        pass

    def handle(self):
        try:
            line = self.rfile.readline().decode("latin-1", errors="replace")
        except OSError:
            return
        if not line:
            return
        parts = line.strip().split()
        if len(parts) < 2:
            return
        method = parts[0].upper()
        req_path = parts[1]
        while True:
            try:
                h = self.rfile.readline().decode("latin-1", errors="replace")
            except OSError:
                return
            if not h or h in ("\r\n", "\n"):
                break
        if method != "GET":
            self.wfile.write(b"HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            return
        path = unquote(urlparse(req_path).path or "/")
        if not path.startswith("/"):
            path = "/" + path
        path = path.rstrip("/") or "/"
        if path not in ("/", "/tvclaw.apk", "/index.html"):
            self.wfile.write(b"HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            return
        root = self.server.serve_root
        apk = os.path.join(root, "tvclaw.apk")
        if not os.path.isfile(apk):
            self.wfile.write(b"HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            return
        try:
            with open(apk, "rb") as f:
                data = f.read()
        except OSError:
            self.wfile.write(b"HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            return
        cl = str(len(data))
        hdr = (
            "HTTP/1.1 200 OK\r\n"
            "Content-Type: application/vnd.android.package-archive\r\n"
            'Content-Disposition: attachment; filename="tvclaw.apk"\r\n'
            f"Content-Length: {cl}\r\n"
            "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n"
            "Pragma: no-cache\r\n"
            "Expires: 0\r\n"
            "Connection: close\r\n"
            "\r\n"
        ).encode("ascii")
        self.wfile.write(hdr)
        self.wfile.write(data)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True


def main():
    port = int(sys.argv[1])
    root = sys.argv[2]
    with Server(("0.0.0.0", port), Handler) as httpd:
        httpd.serve_root = root
        httpd.serve_forever()


if __name__ == "__main__":
    main()
EOS
  python3 "$tmp/tvclaw_apk_serve.py" "$port" "$tmp" >/dev/null 2>&1 &
  HTTP_PID=$!
  disown "$HTTP_PID" 2>/dev/null || true
  sleep 1
  url="http://${ip}:${port}"
  echo "This server serves the APK at the address root (no path). No-cache headers apply so the TV browser is less likely to reuse an old download."
  echo "The served file is the same tvclaw.apk matching the version, size, and SHA256 printed above."
  echo ""
  echo "Type this on the TV browser (same Wi‑Fi):"
  echo "  $url"
  echo ""
  echo "If you use the brain’s /tvclaw-client.apk link instead, open the brain home page again after each Gradle build so the download URL refreshes (TV browsers often reuse an old APK)."
  echo ""
  whatsapp_apk_followup "$url" "$apkip" ""
  tvclaw_installer_brain_log_session
  [[ "$HTTP_PID" =~ ^[1-9][0-9]*$ ]] && kill "$HTTP_PID" 2>/dev/null || true
  HTTP_PID=""
  echo ""
  echo "APK on this computer:"
  echo "  $apkip"
  echo ""
  echo "✓ Installer step finished. Open the TVClaw app on the TV — it will try to reach the brain on its own."
  echo "  When the TV screen says you are connected, use your WhatsApp TVClaw group to talk to the agent."
}

install_tvclaw_cli() {
  local bin="$REPO_ROOT/scripts/tvclaw"
  [[ -f "$bin" ]] || return 0
  mkdir -p "$HOME/.local/bin"
  chmod +x "$bin"
  ln -sf "$bin" "$HOME/.local/bin/tvclaw" 2>/dev/null || cp "$bin" "$HOME/.local/bin/tvclaw"
  case ":$PATH:" in
  *":$HOME/.local/bin:"*) ;;
  *)
    echo "Tip: open a new Terminal window so the shortcut command \"tvclaw\" works."
    ;;
  esac
}

TVCLAW_MAC_LAUNCHAGENT_PAUSED=0
TVCLAW_LINUX_USER_SYSTEMD_PAUSED=0

tvclaw_stop_background_brain() {
  case "$(uname -s)" in
  Darwin)
    local plist="$HOME/Library/LaunchAgents/com.nanoclaw.plist"
    [[ -f "$plist" ]] || return 0
    launchctl unload "$plist" 2>/dev/null || true
    launchctl bootout "gui/$(id -u)/com.nanoclaw" 2>/dev/null || true
    ;;
  Linux)
    command -v systemctl >/dev/null 2>&1 || return 0
    systemctl --user stop nanoclaw.service 2>/dev/null || true
    ;;
  esac
}

tvclaw_pause_background_brain_for_whatsapp() {
  case "$(uname -s)" in
  Darwin)
    local plist="$HOME/Library/LaunchAgents/com.nanoclaw.plist"
    [[ -f "$plist" ]] || return 0
    if ! launchctl list 2>/dev/null | grep -Fq com.nanoclaw; then
      return 0
    fi
    echo ""
    echo "Pausing the TVClaw background app so only this installer uses WhatsApp and so Gradle can replace the TV APK without an old brain holding code or files open."
    echo "If two copies share the same login you see stream errors, conflict/replaced, or broken group messages — this step avoids that."
    launchctl unload "$plist" 2>/dev/null || true
    TVCLAW_MAC_LAUNCHAGENT_PAUSED=1
    ;;
  Linux)
    command -v systemctl >/dev/null 2>&1 || return 0
    if ! systemctl --user is-active --quiet nanoclaw.service 2>/dev/null; then
      return 0
    fi
    echo ""
    echo "Stopping the TVClaw user service briefly so only this installer window uses WhatsApp."
    systemctl --user stop nanoclaw.service 2>/dev/null || true
    TVCLAW_LINUX_USER_SYSTEMD_PAUSED=1
    ;;
  esac
}

tvclaw_resume_background_brain_after_apk() {
  if [[ "${TVCLAW_MAC_LAUNCHAGENT_PAUSED:-0}" == "1" ]]; then
    TVCLAW_MAC_LAUNCHAGENT_PAUSED=0
    local plist="$HOME/Library/LaunchAgents/com.nanoclaw.plist"
    if [[ -f "$plist" ]]; then
      echo ""
      echo "Starting the TVClaw background app again…"
      launchctl load "$plist" 2>/dev/null || true
    fi
  fi
  if [[ "${TVCLAW_LINUX_USER_SYSTEMD_PAUSED:-0}" == "1" ]]; then
    TVCLAW_LINUX_USER_SYSTEMD_PAUSED=0
    echo ""
    echo "Starting the TVClaw user service again…"
    systemctl --user start nanoclaw.service 2>/dev/null || true
  fi
}

star_repo() {
  echo ""
  echo "If TVClaw is useful, a quick star on GitHub helps others find it: $STAR_URL"
  if [[ -t 0 ]] && [[ -t 1 ]]; then
    if [[ "$(uname -s)" == "Darwin" ]]; then
      open "$STAR_URL" 2>/dev/null || true
    elif command -v xdg-open >/dev/null 2>&1; then
      xdg-open "$STAR_URL" 2>/dev/null || true
    fi
  fi
}

main() {
  export TVCLAW_INSTALLER=1
  TVCLAW_INSTALL_SHELL_SUCCESS=0
  tvclaw_install_exit_cleanup() {
    tvclaw_heartbeat_stop
    if [[ "${TVCLAW_INSTALL_SHELL_SUCCESS:-0}" == "1" ]]; then
      return 0
    fi
    echo "" >&2
    echo "Installer did not finish — stopping the TVClaw background brain (if it was loaded)." >&2
    tvclaw_stop_background_brain
  }
  trap tvclaw_install_exit_cleanup EXIT

  if [[ "${TVCLAW_SETUP_UI:-1}" != "0" ]]; then
    export TVCLAW_SETUP_UI=1
    export LOG_LEVEL=error
  else
    export TVCLAW_SETUP_UI=0
    export LOG_LEVEL="${LOG_LEVEL:-info}"
  fi

  print_banner
  if [[ "$(uname -s)" == "Linux" ]] && [[ -f /proc/version ]] && grep -qiE 'microsoft|wsl' /proc/version; then
    echo "You're on Windows Subsystem for Linux — plugging in a phone over USB can be fiddly; you can still install the TV app using the download URL this installer prints (same Wi‑Fi)."
  fi
  ensure_repo
  ensure_node
  cd "$NANOCLAW_ROOT"
  if [[ "${TVCLAW_SKIP_SERVICE:-1}" == "1" ]]; then
    tvclaw_stop_background_brain
  fi
  echo ""
  echo "  ··· Getting TVClaw’s software ready on this computer ···"
  echo ""
  echo "  Nanoclaw folder (always run the brain from here — data/ + store/ live here):"
  echo "    $NANOCLAW_ROOT"
  printf '%s\n' "$NANOCLAW_ROOT" >"$REPO_ROOT/.nanoclaw-root"
  echo "  Saved nanoclaw path to $REPO_ROOT/.nanoclaw-root (for logs later: tail -f \"\$(cat \"$REPO_ROOT/.nanoclaw-root\")/logs/nanoclaw.log\")"
  echo ""
  tvclaw_busy "nanoclaw setup.sh…" ./setup.sh
  tvclaw_busy "npm run build…" bash -c "npm run -s build 2>/dev/null || exec npm run build"
  local crt
  crt=$(container_runtime_flag)
  ensure_docker "$crt"
  tvclaw_busy "setup: environment…" npx tsx setup/index.ts --step environment
  tvclaw_busy "setup: timezone…" npx tsx setup/index.ts --step timezone
  tvclaw_busy "setup: mounts…" npx tsx setup/index.ts --step mounts -- --empty
  tvclaw_busy "setup: container…" npx tsx setup/index.ts --step container -- --runtime "$crt"
  if [[ "${TVCLAW_SKIP_SERVICE:-1}" != "1" ]]; then
    echo ""
    echo "Installing launchd / systemd so the brain can stay running after you close this window…"
    tvclaw_busy "setup: background service…" npx tsx setup/index.ts --step service || true
    if [[ "${TVCLAW_SETUP_UI:-1}" == "0" ]]; then
      echo ""
      echo "────────────────────────────────────────────────────────────"
      echo "  Verbose: brain log snapshot (same as after install; not stdout from npm start)"
      echo "  Live: tail -f \"$NANOCLAW_ROOT/logs/nanoclaw.log\""
      echo "────────────────────────────────────────────────────────────"
      sleep 2
      if [[ -f "$NANOCLAW_ROOT/logs/nanoclaw.log" ]]; then
        tail -n 80 "$NANOCLAW_ROOT/logs/nanoclaw.log" 2>/dev/null || true
      else
        echo "(nanoclaw.log not created yet)"
      fi
      if [[ -f "$NANOCLAW_ROOT/logs/nanoclaw.error.log" ]] && [[ -s "$NANOCLAW_ROOT/logs/nanoclaw.error.log" ]]; then
        echo ""
        echo "── nanoclaw.error.log (last 40 lines) ──"
        tail -n 40 "$NANOCLAW_ROOT/logs/nanoclaw.error.log" 2>/dev/null || true
      fi
      echo ""
    fi
  fi
  tvclaw_busy "setup: verify…" npx tsx setup/index.ts --step verify
  if [[ "${TVCLAW_SKIP_AUTH_AI:-}" != "1" ]]; then
    echo ""
    echo "  ··· Sign in to the AI helper (OneCLI) ···"
    echo ""
    tvclaw_busy "npm run auth:ai (follow prompts in this terminal)…" npm run auth:ai
  fi
  if [[ "${TVCLAW_SKIP_WHATSAPP:-}" != "1" ]]; then
    tvclaw_pause_background_brain_for_whatsapp
    echo ""
    echo "  ··· Link WhatsApp on your phone ···"
    echo ""
    if [[ "${TVCLAW_WA_BROWSER_QR:-}" == "1" ]]; then
      tvclaw_busy "WhatsApp: auth (browser QR may open)…" npm run auth -- --browser-qr
    else
      tvclaw_busy "WhatsApp: scan QR in terminal…" npm run auth
    fi
    tvclaw_busy "WhatsApp: link TVClaw group…" npm run link:whatsapp
  fi
  if [[ "${TVCLAW_SKIP_WHATSAPP:-}" == "1" ]]; then
    echo ""
    echo "WhatsApp steps were skipped — pausing any background nanoclaw service before the TV APK step so Gradle can overwrite app-debug.apk and the next brain start loads the TypeScript build from this run."
    echo "If you also have nanoclaw running in another terminal, stop that process so it is not still serving an old /tvclaw-client.apk."
    tvclaw_pause_background_brain_for_whatsapp
  fi
  apk_install_step
  tvclaw_resume_background_brain_after_apk
  install_tvclaw_cli
  echo ""
  echo "════════════════════════════════════════════════════════════"
  echo "  Setup is complete"
  echo "════════════════════════════════════════════════════════════"
  echo ""
  if [[ "${TVCLAW_SKIP_WHATSAPP:-}" != "1" ]]; then
    echo "  • WhatsApp: use the group you set up (name shown above) to talk to your TV."
  fi
  if [[ "${TVCLAW_SKIP_APK:-}" != "1" ]]; then
    echo "  • Android TV: open the TVClaw app on the TV; it connects to the brain automatically. Then use your WhatsApp group for the agent."
    echo "  • When the TV actually connects, nanoclaw logs it and posts a short line to your main TVClaw WhatsApp group (brain must be running and linked to WhatsApp)."
  fi
  echo "  • Brain: if you used the TV app step with npm logs, that process is still running; otherwise start it with: cd \"$NANOCLAW_ROOT\" && npm start"
  echo "    (or from the TVClaw repo: tvclaw start when ~/.local/bin is on PATH)"
  echo "  • Stop a foreground brain: Ctrl+C in that terminal. Installer log stream was: \"$NANOCLAW_ROOT/logs/installer-npm-start.log\" (if created)."
  if [[ "${TVCLAW_SKIP_SERVICE:-1}" != "1" ]]; then
    echo "  • You also installed a background service; logs: tail -f \"$NANOCLAW_ROOT/logs/nanoclaw.log\""
    echo "  • Stop background: macOS: launchctl unload ~/Library/LaunchAgents/com.nanoclaw.plist"
    echo "    Linux: systemctl --user stop nanoclaw.service"
  fi
  echo "  • From repo root: cd \"\$(cat \"$REPO_ROOT/.nanoclaw-root\")\" && npm start"
  echo ""
  TVCLAW_INSTALL_SHELL_SUCCESS=1
  star_repo || true
}

main "$@"
