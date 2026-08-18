#!/usr/bin/env bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_dir="$project_dir/build"
dist_dir="$project_dir/dist"
version=$(sed -n 's/^version=//p' "$project_dir/module/module.prop")
if [[ -z "$version" ]]; then
    echo "error: module/module.prop does not define a version" >&2
    exit 1
fi
module_archive="oplus-region-unlock-magisk-v$version.zip"
pc_archive="oplus-region-unlock-pc-v$version.zip"

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
mkdir -p "$build_dir/classes" "$build_dir/dex" "$build_dir/module/system/etc" \
    "$build_dir/pc/oplus-region-unlock-pc-v$version/pc" \
    "$build_dir/pc/oplus-region-unlock-pc-v$version/dist" "$dist_dir"
rm -f "$dist_dir/$module_archive" "$dist_dir/$pc_archive"

javac --release 8 -cp "$android_jar" -d "$build_dir/classes" \
    "$project_dir/src/dev/op13/regionunlock/RegionUnlock.java"
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
    zip -q -r "$dist_dir/$module_archive" .
)

pc_root="$build_dir/pc/oplus-region-unlock-pc-v$version"
cp "$project_dir/pc/region_unlock.py" "$pc_root/pc/region_unlock.py"
cp "$dist_dir/oplus-region-unlock.jar" "$pc_root/dist/oplus-region-unlock.jar"
cp "$project_dir/README.md" "$pc_root/README.md"
chmod 0755 "$pc_root/pc/region_unlock.py"
(
    cd "$build_dir/pc"
    zip -q -r "$dist_dir/$pc_archive" "oplus-region-unlock-pc-v$version"
)

(
    cd "$dist_dir"
    checksum_files=(oplus-region-unlock.jar "$module_archive" "$pc_archive")
    app_apk="oplus-region-unlock-app-v$version-debug.apk"
    [[ -f "$app_apk" ]] && checksum_files+=("$app_apk")
    sha256sum "${checksum_files[@]}" > SHA256SUMS
)
echo "Built:"
sed 's/^/  /' "$dist_dir/SHA256SUMS"
