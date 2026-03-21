#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

bump=false
gradle_args=()
for arg in "$@"; do
  if [[ "$arg" == "--bump" ]]; then
    bump=true
  else
    gradle_args+=("$arg")
  fi
done

bump_version_name() {
  local v="$1"
  if [[ "$v" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    printf '%s.%s.%s' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "$((BASH_REMATCH[3] + 1))"
  elif [[ "$v" =~ ^([0-9]+)\.([0-9]+)$ ]]; then
    printf '%s.%s.0' "${BASH_REMATCH[1]}" "$((BASH_REMATCH[2] + 1))"
  else
    return 1
  fi
}

if [[ "$bump" == true ]]; then
  gradle_file="$root/app/build.gradle.kts"
  vc_line=$(grep -E 'versionCode\s*=' "$gradle_file" | head -1)
  vm_line=$(grep -E 'versionName\s*=' "$gradle_file" | head -1)
  vc=$(sed -n 's/.*versionCode *= *\([0-9][0-9]*\).*/\1/p' <<<"$vc_line")
  vm=$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' <<<"$vm_line")
  if [[ -z "$vc" || -z "$vm" ]]; then
    echo "build-debug.sh: could not parse versionCode/versionName in $gradle_file" >&2
    exit 1
  fi
  new_vc=$((vc + 1))
  if new_vm=$(bump_version_name "$vm"); then
    :
  else
    new_vm="$vm"
    echo "build-debug.sh: versionName not M.m.p — bumped versionCode only" >&2
  fi
  if [[ "$(uname -s)" == "Darwin" ]]; then
    sed_inplace=(sed -i '')
  else
    sed_inplace=(sed -i)
  fi
  "${sed_inplace[@]}" "s/versionCode *= *[0-9][0-9]*/versionCode = $new_vc/" "$gradle_file"
  "${sed_inplace[@]}" "s/versionName *= *\"[^\"]*\"/versionName = \"$new_vm\"/" "$gradle_file"
  echo "Bumped versionCode $vc -> $new_vc, versionName \"$vm\" -> \"$new_vm\""
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null)" || true
  fi
fi
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home ]]; then
  JAVA_HOME="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
fi
export JAVA_HOME
if [[ ${#gradle_args[@]} -gt 0 ]]; then
  ./gradlew :app:clean :app:assembleDebug :app:printAppVersion "${gradle_args[@]}"
else
  ./gradlew :app:clean :app:assembleDebug :app:printAppVersion
fi
