#!/usr/bin/env bash
# Collects the real ABI-split APKs that Gradle produced, gives them their published
# names (fOCUS-<variant>-<abi>.apk) in ./dist, and VERIFIES them by content:
#   * file exists and is non-empty
#   * package id is com.focus.app (aapt2 dump badging)
#   * native libraries inside the APK match the ABI (unzip listing, not the file name)
#   * signature verifies (apksigner); release APKs must not be debug-signed
#   * exactly 5 APKs per requested variant end up in ./dist
#
# Usage: collect-and-verify-apks.sh <variant>...      e.g.  debug   |   debug release
# Files are copied, never rebuilt or repackaged: each one is a genuine Gradle output.
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <variant>... (debug|release)" >&2
  exit 2
fi

PACKAGE="com.focus.app"
OUT_DIR="dist"
ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT/build-tools" ]; then
  echo "::error::Android SDK build-tools not found (ANDROID_HOME='${SDK_ROOT}')" >&2
  exit 1
fi
BUILD_TOOLS="$(ls -d "$SDK_ROOT"/build-tools/* | sort -V | tail -n 1)"
AAPT2="$BUILD_TOOLS/aapt2"
APKSIGNER="$BUILD_TOOLS/apksigner"
for tool in "$AAPT2" "$APKSIGNER"; do
  [ -x "$tool" ] || { echo "::error::Missing tool: $tool" >&2; exit 1; }
done
echo "Using build-tools: $BUILD_TOOLS"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

find_apk() { # <variant> <abi-or-universal>
  local variant="$1" abi="$2"
  local matches=( "app/build/outputs/apk/${variant}/app-${abi}-${variant}"*.apk )
  if [ "${#matches[@]}" -ne 1 ] || [ ! -e "${matches[0]}" ]; then
    echo "::error::Expected exactly one APK for ${variant}/${abi}, found: ${matches[*]}" >&2
    return 1
  fi
  printf '%s\n' "${matches[0]}"
}

native_abis() { # <apk> -> sorted, comma-separated ABI folders under lib/
  unzip -Z1 "$1" | awk -F/ '$1 == "lib" && NF >= 3 { print $2 }' | sort -u | paste -sd, -
}

SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"
{
  echo "### APK verification"
  echo
  echo "| File | Size (bytes) | Native ABIs inside | Package | Signer |"
  echo "|---|---|---|---|---|"
} >> "$SUMMARY"

verify_apk() { # <variant> <abi-or-universal> <src> <dest>
  local variant="$1" abi="$2" src="$3" dest="$4"
  cp "$src" "$dest"

  [ -s "$dest" ] || { echo "::error::$dest is empty" >&2; return 1; }
  local size; size="$(stat -c %s "$dest")"

  local badging pkg
  badging="$("$AAPT2" dump badging "$dest")"
  pkg="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
  if [ "$pkg" != "$PACKAGE" ]; then
    echo "::error::$dest has package '$pkg', expected '$PACKAGE'" >&2
    return 1
  fi

  local abis; abis="$(native_abis "$dest")"
  if [ "$abi" = "universal" ]; then
    for expected in "${ABIS[@]}"; do
      case ",$abis," in
        *",$expected,"*) ;;
        *) echo "::error::$dest (universal) lacks native libs for $expected (found: ${abis:-none})" >&2; return 1 ;;
      esac
    done
  else
    if [ "$abis" != "$abi" ]; then
      echo "::error::$dest must contain only '$abi' native libs, found: '${abis:-none}'" >&2
      return 1
    fi
  fi

  local certs signer
  certs="$("$APKSIGNER" verify --print-certs "$dest")" || { echo "::error::apksigner rejected $dest" >&2; return 1; }
  signer="$(printf '%s\n' "$certs" | sed -n 's/^Signer #1 certificate DN: //p' | head -n 1)"
  if [ "$variant" = "release" ]; then
    case "$signer" in
      *"Android Debug"*) echo "::error::$dest is signed with a debug certificate" >&2; return 1 ;;
    esac
  fi

  echo "OK  $(basename "$dest")  size=$size  abis=$abis  package=$pkg  signer=$signer"
  echo "| \`$(basename "$dest")\` | $size | ${abis} | $pkg | ${signer} |" >> "$SUMMARY"
}

for variant in "$@"; do
  case "$variant" in debug | release) ;; *) echo "::error::unknown variant '$variant'" >&2; exit 2 ;; esac
  for abi in "${ABIS[@]}" universal; do
    src="$(find_apk "$variant" "$abi")"
    verify_apk "$variant" "$abi" "$src" "$OUT_DIR/fOCUS-${variant}-${abi}.apk"
  done
  count="$(find "$OUT_DIR" -maxdepth 1 -name "fOCUS-${variant}-*.apk" | wc -l | tr -d ' ')"
  if [ "$count" -ne 5 ]; then
    echo "::error::Expected exactly 5 ${variant} APKs in $OUT_DIR, found $count" >&2
    exit 1
  fi
done

expected_total=$(( 5 * $# ))
total="$(find "$OUT_DIR" -maxdepth 1 -name 'fOCUS-*.apk' | wc -l | tr -d ' ')"
if [ "$total" -ne "$expected_total" ]; then
  echo "::error::Expected $expected_total APKs in $OUT_DIR, found $total" >&2
  exit 1
fi
echo "All $total APKs verified."
