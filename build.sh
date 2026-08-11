#!/usr/bin/env bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_dir="$project_dir/build"
dist_dir="$project_dir/dist"

find_android_jar() {
    if [[ -n "${ANDROID_JAR:-}" && -f "$ANDROID_JAR" ]]; then
        printf '%s\n' "$ANDROID_JAR"
        return
    fi
    if [[ -n "${ANDROID_BUILD_TOP:-}" && -f "$ANDROID_BUILD_TOP/prebuilts/sdk/current/system/android.jar" ]]; then
        printf '%s\n' "$ANDROID_BUILD_TOP/prebuilts/sdk/current/system/android.jar"
        return
    fi
    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "$sdk_root" ]]; then
        local candidate
        candidate=$(find "$sdk_root/platforms" -mindepth 2 -maxdepth 2 -name android.jar -print 2>/dev/null | sort -V | tail -n 1 || true)
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    fi
    local local_aosp=/home/koaan/android/fox_14.1/prebuilts/sdk/current/system/android.jar
    [[ -f "$local_aosp" ]] && printf '%s\n' "$local_aosp"
}

find_d8() {
    if [[ -n "${D8:-}" && -x "$D8" ]]; then
        printf '%s\n' "$D8"
        return
    fi
    if command -v d8 >/dev/null 2>&1; then
        command -v d8
        return
    fi
    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "$sdk_root" ]]; then
        local candidate
        candidate=$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -name d8 -type f -print 2>/dev/null | sort -V | tail -n 1 || true)
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    fi
    if [[ -n "${ANDROID_BUILD_TOP:-}" && -x "$ANDROID_BUILD_TOP/prebuilts/sdk/tools/linux/bin/d8" ]]; then
        printf '%s\n' "$ANDROID_BUILD_TOP/prebuilts/sdk/tools/linux/bin/d8"
        return
    fi
    local local_d8=/home/koaan/android/fox_14.1/prebuilts/sdk/tools/linux/bin/d8
    [[ -x "$local_d8" ]] && printf '%s\n' "$local_d8"
}

find_d8_jar() {
    # An explicitly selected binary takes precedence over auto-discovered JARs.
    if [[ -n "${D8:-}" ]]; then
        return
    fi
    if [[ -n "${D8_JAR:-}" && -f "$D8_JAR" ]]; then
        printf '%s\n' "$D8_JAR"
        return
    fi
    if [[ -n "${ANDROID_BUILD_TOP:-}" && -f "$ANDROID_BUILD_TOP/prebuilts/sdk/tools/linux/lib/d8.jar" ]]; then
        printf '%s\n' "$ANDROID_BUILD_TOP/prebuilts/sdk/tools/linux/lib/d8.jar"
        return
    fi
    local local_jar=/home/koaan/android/fox_14.1/prebuilts/sdk/tools/linux/lib/d8.jar
    [[ -f "$local_jar" ]] && printf '%s\n' "$local_jar"
}

android_jar=$(find_android_jar || true)
d8_bin=$(find_d8 || true)
d8_jar=$(find_d8_jar || true)
if [[ -z "$android_jar" || ( -z "$d8_bin" && -z "$d8_jar" ) ]]; then
    echo "error: set ANDROID_JAR and D8/D8_JAR, ANDROID_BUILD_TOP, or ANDROID_SDK_ROOT" >&2
    exit 1
fi

rm -rf "$build_dir"
mkdir -p "$build_dir/classes" "$build_dir/dex" "$build_dir/module/system/etc" "$dist_dir"

javac --release 8 -cp "$android_jar" -d "$build_dir/classes" \
    "$project_dir/src/dev/op15/regionunlock/RegionUnlock.java"
jar cf "$build_dir/classes.jar" -C "$build_dir/classes" .
if [[ -n "$d8_jar" ]]; then
    java -cp "$d8_jar" com.android.tools.r8.D8 --min-api 26 --lib "$android_jar" \
        --output "$build_dir/dex" "$build_dir/classes.jar"
else
    "$d8_bin" --min-api 26 --lib "$android_jar" --output "$build_dir/dex" "$build_dir/classes.jar"
fi
jar cf "$dist_dir/oplus-region-unlock.jar" -C "$build_dir/dex" classes.dex

cp -a "$project_dir/module/." "$build_dir/module/"
cp "$dist_dir/oplus-region-unlock.jar" "$build_dir/module/system/etc/oplus-region-unlock.jar"
chmod 0755 "$build_dir/module/customize.sh" "$build_dir/module/service.sh" \
    "$build_dir/module/system/bin/region-unlock"
(
    cd "$build_dir/module"
    zip -q -r "$dist_dir/oplus-region-unlock-magisk-v0.1.0.zip" .
)

(
    cd "$dist_dir"
    sha256sum oplus-region-unlock.jar \
        oplus-region-unlock-magisk-v0.1.0.zip > SHA256SUMS
)
echo "Built:"
sed 's/^/  /' "$dist_dir/SHA256SUMS"
