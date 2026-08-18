# Region Unlock Android app

This is an installable, root-assisted Android front end for supported OnePlus
region-lock clients. Its interface follows the DeepTest 2.0 Android app's dark
cards, purple controls, status rows, bottom navigation and confirmation sheet.

The app contains no root exploit. Magisk, KernelSU or APatch must already be
installed. When the user taps **Unlock region**, the app asks the root manager
for permission and launches the packaged client as Android UID 1000, which is
required by the stock OxygenOS `SubsysPermissions` check.

On launch, the main page automatically verifies root and reads the current
region state. **Refresh checks** runs the same read-only checks again. A
separate **Region check** card presents the parsed region, mode, brand, version,
operator, operation, state and result values; the **Unlock region** action sits
below that list. **Settings** contains only Bright mode and the guarded Lock
region action.

Safety gates:

- only PRJ-ID `23821` (`PJZ110 / OnePlus 13`), `24831`
  (`PLK110 / OnePlus 15`), or `24851` (`OnePlus Ace 6`) is accepted;
- root and a successful stock region-service state read are required before
  either state-changing action is enabled;
- state `0` is shown as **Already unlocked** and cannot be unlocked again;
- the action is confirmed in a separate sheet;
- unsupported devices cannot press the unlock button and are rejected again
  by the Java backend;
- rebooting is a separate explicit button;
- the main page performs read-only root and region-state checks;
- **Settings > Lock region** requires two separate acknowledgements followed
  by entering the exact uppercase word `LOCK`. The Java backend checks that
  confirmation again before it can request state `1`.

Build an installable debug-signed APK together with the other artifacts:

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
