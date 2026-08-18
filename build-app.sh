#!/usr/bin/env bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
version=${REGION_UNLOCK_VERSION_NAME:-0.4.0}
version_code=${REGION_UNLOCK_VERSION_CODE:-4}

sdk_root=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$sdk_root" && -d /tmp/android-sdk ]]; then
    sdk_root=/tmp/android-sdk
fi
if [[ -z "$sdk_root" ]]; then
    echo "error: set ANDROID_SDK_ROOT or ANDROID_HOME" >&2
    exit 1
fi

mkdir -p "$project_dir/dist"

ANDROID_HOME="$sdk_root" \
REGION_UNLOCK_VERSION_NAME="$version" \
REGION_UNLOCK_VERSION_CODE="$version_code" \
    "$project_dir/android-app/gradlew" -p "$project_dir/android-app" :app:assembleDebug

app_apk="oplus-region-unlock-app-v$version-debug.apk"
cp "$project_dir/android-app/app/build/outputs/apk/debug/app-debug.apk" \
    "$project_dir/dist/$app_apk"

(
    cd "$project_dir/dist"
    sha256sum "$app_apk" > SHA256SUMS
)

echo "Built Android app:"
sha256sum "$project_dir/dist/$app_apk"
