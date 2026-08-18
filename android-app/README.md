# Android app

This directory contains the Gradle project for the Region Unlock app. See the
repository [README](../README.md) for supported devices, installation, usage,
safety notes, and implementation details.

Build the signed release APK from the repository root after configuring the
release-signing environment variables documented in the main README:

```sh
JAVA_HOME=/path/to/jdk21 ANDROID_SDK_ROOT=/path/to/android-sdk ./build-app.sh
```

Output:

```text
dist/oplus-region-unlock-app-v0.4.3-release.apk
```

Install with:

```sh
adb install -r dist/oplus-region-unlock-app-v0.4.3-release.apk
```
