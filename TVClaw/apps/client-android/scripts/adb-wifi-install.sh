#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 ]]; then
  echo "usage: $0 <tv-lan-ip>[:adb-port] [path-to.apk]" >&2
  echo "example: $0 192.168.1.80:5555" >&2
  echo "pair first: Developer options → Wireless debugging → Pair (adb pair ip:pairing-port)" >&2
  exit 1
fi
target="$1"
apk="${2:-$(cd "$(dirname "$0")/.." && pwd)/app/build/outputs/apk/debug/app-debug.apk}"
adb connect "$target"
adb -s "$target" install -r "$apk"
