#!/usr/bin/env bash
set -euo pipefail

TVCLAW_CLONE_DIR="${TVCLAW_CLONE_DIR:-$HOME/TVClaw}"
TVCLAW_REPO_URL="${TVCLAW_REPO_URL:-https://github.com/TVClaw/TVClaw.git}"
TVCLAW_APK_URL="${TVCLAW_APK_URL:-https://raw.githubusercontent.com/TVClaw/TVClaw/main/prebuilt/tvclaw-android.apk}"
STAR_URL="https://github.com/TVClaw/TVClaw"

print_banner() {
  cat <<'EOF'

  _________      _______ _          __          __
 |__   __\ \    / / ____| |        /\ \        / /
    | |   \ \  / / |    | |       /  \ \  /\  / / 
    | |    \ \/ /| |    | |      / /\ \ \/  \/ /  
    | |     \  / | |____| |____ / ____ \  /\  /   
    |_|      \/   \_____|______/_/    \_\/  \/    
                                                  
                                                  
        🦞   TVClaw   📺
  ═══════════════════════════
    TV + Claw — personal TV brain

EOF
}

usage() {
  echo "usage: curl -fsSL .../install.sh | bash"
  echo "   or: bash install.sh"
  echo "env: TVCLAW_SKIP_CLONE=1 TVCLAW_REPO_ROOT=/path TVCLAW_SKIP_AUTH_AI=1 TVCLAW_SKIP_WHATSAPP=1 TVCLAW_SKIP_APK=1 TVCLAW_SKIP_SERVICE=1 TVCLAW_WA_BROWSER_QR=0"
  exit 0
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

clone_repo() {
  local dest="$1"
  if [[ -e "$dest" && ! -d "$dest/.git" ]]; then
    echo "Path exists but is not a git repo: $dest" >&2
    exit 1
  fi
  if [[ ! -d "$dest/.git" ]]; then
    git clone --depth 1 --recurse-submodules "$TVCLAW_REPO_URL" "$dest"
  else
    git -C "$dest" submodule sync --recursive
    git -C "$dest" submodule update --init --recursive
  fi
  if [[ ! -f "$dest/nanoclaw2/setup.sh" ]]; then
    echo "nanoclaw2 is missing after clone. Submodules must use HTTPS in .gitmodules for curl|bash installs." >&2
    echo "Fix: rm -rf \"$dest\" and re-run, or: cd \"$dest\" && git submodule update --init --recursive" >&2
    exit 1
  fi
}

ensure_repo() {
  local rr
  if rr=$(detect_repo_root 2>/dev/null); then
    REPO_ROOT=$rr
    echo "Using existing repo: $REPO_ROOT"
  else
    if [[ "${TVCLAW_SKIP_CLONE:-}" == "1" ]]; then
      echo "Not inside TVClaw repo and TVCLAW_SKIP_CLONE=1 — set TVCLAW_REPO_ROOT or run from clone." >&2
      exit 1
    fi
    echo "Cloning $TVCLAW_REPO_URL → $TVCLAW_CLONE_DIR"
    mkdir -p "$(dirname "$TVCLAW_CLONE_DIR")"
    clone_repo "$TVCLAW_CLONE_DIR"
    REPO_ROOT="$TVCLAW_CLONE_DIR"
  fi
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
  echo "Node.js 20+ required."
  if [[ "$(uname -s)" == "Darwin" ]] && command -v brew >/dev/null 2>&1; then
    read -r -p "Install Node via Homebrew? [Y/n] " x
    if [[ -z "$x" || "$x" =~ ^[Yy] ]]; then
      brew install node@22 || brew install node
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
  echo "Install Node 20+ from https://nodejs.org or use nvm, then re-run." >&2
  exit 1
}

wait_docker() {
  local i
  for i in $(seq 1 90); do
    if docker info >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

ensure_docker() {
  local rt="$1"
  [[ "$rt" == "apple-container" ]] && return 0
  if docker info >/dev/null 2>&1; then
    return 0
  fi
  echo "Docker is required for TVClaw agents (container isolation)."
  if [[ "$(uname -s)" == "Darwin" ]]; then
    if ! command -v docker >/dev/null 2>&1; then
      if command -v brew >/dev/null 2>&1; then
        read -r -p "Install Docker Desktop via Homebrew cask? [Y/n] " x
        if [[ -z "$x" || "$x" =~ ^[Yy] ]]; then
          brew install --cask docker
        fi
      else
        echo "Install Docker Desktop from https://www.docker.com/products/docker-desktop/" >&2
      fi
    fi
    if [[ -d "/Applications/Docker.app" ]]; then
      open -a Docker 2>/dev/null || true
    fi
  elif [[ "$(uname -s)" == "Linux" ]]; then
    read -r -p "Install Docker Engine via get.docker.com (needs sudo)? [Y/n] " x
    if [[ -z "$x" || "$x" =~ ^[Yy] ]]; then
      curl -fsSL https://get.docker.com | sh
      if [[ "$(id -u)" -ne 0 ]]; then
        sudo usermod -aG docker "$USER" 2>/dev/null || true
        echo "You may need to log out and back in for the docker group."
      fi
      sudo systemctl enable --now docker 2>/dev/null || true
    fi
  fi
  echo "Waiting for Docker to respond..."
  if ! wait_docker; then
    echo "Docker did not become ready. Start Docker manually and re-run this script." >&2
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

print_url_qr() {
  local url="$1"
  (cd "$NANOCLAW_ROOT" && node --input-type=module -e "
    import q from 'qrcode-terminal';
    q.generate(process.argv[1], { small: true });
  " "$url") 2>/dev/null || echo "$url"
}

apk_install_step() {
  [[ "${TVCLAW_SKIP_APK:-}" == "1" ]] && return 0
  local tmp apkip ip port url
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"; kill "${HTTP_PID:-0}" 2>/dev/null || true' RETURN
  apkip="$tmp/tvclaw.apk"
  local local_apk="${REPO_ROOT}/prebuilt/tvclaw-android.apk"
  if [[ -f "$local_apk" ]]; then
    echo "Using checked-in APK: $local_apk"
    cp "$local_apk" "$apkip"
  else
    echo "Fetching Android client from GitHub (prebuilt/tvclaw-android.apk on main)..."
    if ! curl -fsSL -o "$apkip" "$TVCLAW_APK_URL"; then
      echo "APK not available yet — run Android workflow on main or build TVClaw/apps/client-android."
      return 0
    fi
  fi
  if command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | grep -q "device$"; then
    echo "ADB device found — installing APK (allow unknown sources on device if prompted)."
    adb install -r "$apkip" || true
    return 0
  fi
  ip=$(primary_lan_ip)
  port=18765
  if [[ -z "$ip" ]]; then
    echo "Could not detect LAN IP. Copy APK manually: $apkip"
    return 0
  fi
  cp "$apkip" "$tmp/tvclaw.apk"
  (cd "$tmp" && python3 -m http.server "$port" --bind 0.0.0.0 >/dev/null 2>&1) &
  HTTP_PID=$!
  sleep 1
  url="http://${ip}:${port}/tvclaw.apk"
  echo "Phone/TV must be on the same Wi‑Fi. Scan QR to download (installer may block — allow unknown sources):"
  print_url_qr "$url"
  echo "$url"
  read -r -p "Press Enter when done downloading/installing…" _
  kill "$HTTP_PID" 2>/dev/null || true
  HTTP_PID=0
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
    echo "Add to PATH: export PATH=\"\$HOME/.local/bin:\$PATH\""
    ;;
  esac
}

star_repo() {
  echo ""
  echo "If TVClaw helps you, star the repo: $STAR_URL"
  if [[ -t 0 ]] && [[ -t 1 ]]; then
    if [[ "$(uname -s)" == "Darwin" ]]; then
      open "$STAR_URL" 2>/dev/null || true
    elif command -v xdg-open >/dev/null 2>&1; then
      xdg-open "$STAR_URL" 2>/dev/null || true
    fi
  fi
}

main() {
  print_banner
  if [[ "$(uname -s)" == "Linux" ]] && [[ -f /proc/version ]] && grep -qiE 'microsoft|wsl' /proc/version; then
    echo "WSL2 detected — USB ADB may need extra setup; LAN QR still works."
  fi
  ensure_repo
  ensure_node
  cd "$NANOCLAW_ROOT"
  ./setup.sh
  npm run build
  local crt
  crt=$(container_runtime_flag)
  ensure_docker "$crt"
  npx tsx setup/index.ts --step environment
  npx tsx setup/index.ts --step timezone
  npx tsx setup/index.ts --step mounts -- --empty
  npx tsx setup/index.ts --step container -- --runtime "$crt"
  if [[ "${TVCLAW_SKIP_SERVICE:-}" != "1" ]]; then
    read -r -p "Install background service (launchd/systemd)? [Y/n] " sx
    if [[ -z "$sx" || "$sx" =~ ^[Yy] ]]; then
      npx tsx setup/index.ts --step service || true
    fi
  fi
  npx tsx setup/index.ts --step verify || true
  if [[ "${TVCLAW_SKIP_AUTH_AI:-}" != "1" ]]; then
    npm run auth:ai
  fi
  if [[ "${TVCLAW_SKIP_WHATSAPP:-}" != "1" ]]; then
    if [[ "${TVCLAW_WA_BROWSER_QR:-1}" == "1" ]]; then
      npm run auth -- --browser-qr
    else
      npm run auth
    fi
    npm run link:whatsapp
  fi
  apk_install_step
  install_tvclaw_cli
  echo ""
  echo "Done. Start the brain: cd $NANOCLAW_ROOT && npm start"
  echo "Logs: $NANOCLAW_ROOT/logs/nanoclaw.log"
  star_repo
}

main "$@"
