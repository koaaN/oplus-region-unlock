# Android app

This directory contains the Gradle project for the Region Unlock app. See the
repository [README](../README.md) for supported devices, installation, usage,
safety notes, and implementation details.

Build the debug-signed APK from the repository root:

```sh
JAVA_HOME=/path/to/jdk21 ANDROID_SDK_ROOT=/path/to/android-sdk ./build-app.sh
```

Output:

```text
dist/oplus-region-unlock-app-v0.4.0-debug.apk
```

Install with:

```sh
adb install -r dist/oplus-region-unlock-app-v0.4.0-debug.apk
```
