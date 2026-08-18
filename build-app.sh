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

for variable in \
    REGION_UNLOCK_KEYSTORE \
    REGION_UNLOCK_STORE_PASSWORD \
    REGION_UNLOCK_KEY_ALIAS \
    REGION_UNLOCK_KEY_PASSWORD; do
    if [[ -z "${!variable:-}" ]]; then
        echo "error: set $variable to build a signed release APK" >&2
        exit 1
    fi
done
if [[ ! -f "$REGION_UNLOCK_KEYSTORE" ]]; then
    echo "error: release keystore not found: $REGION_UNLOCK_KEYSTORE" >&2
    exit 1
fi

mkdir -p "$project_dir/dist"

ANDROID_HOME="$sdk_root" \
REGION_UNLOCK_VERSION_NAME="$version" \
REGION_UNLOCK_VERSION_CODE="$version_code" \
    "$project_dir/android-app/gradlew" -p "$project_dir/android-app" :app:assembleRelease

app_apk="oplus-region-unlock-app-v$version-release.apk"
cp "$project_dir/android-app/app/build/outputs/apk/release/app-release.apk" \
    "$project_dir/dist/$app_apk"

(
    cd "$project_dir/dist"
    sha256sum "$app_apk" > SHA256SUMS
)

echo "Built Android app:"
sha256sum "$project_dir/dist/$app_apk"
