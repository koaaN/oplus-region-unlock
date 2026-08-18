# OnePlus Region Unlock

A standalone, root-assisted Android app for reading and changing the stock
region-lock state on supported OnePlus phones. Everything needed by the app is
packaged in the APK.

> [!CAUTION]
> Use this only on a phone you own or are authorized to service. Locking the
> region is intended for controlled testing and may behave differently between
> device generations. This project does not generate local unlock codes, forge
> provisioning blobs, or bypass modem signature verification.

## Supported devices

| Device | Model | PRJ-ID | Status |
|---|---|---:|---|
| OnePlus 13 | `PJZ110` / `CPH2653` | `23821` | Stock OxygenOS path tested |
| OnePlus 15 | `PLK110` / `CPH2745` | `24831` | Identity and stock framework path confirmed; stock-device validation ongoing |
| OnePlus Ace 6 | — | `24851` | Identity support added; stock-device validation ongoing |

The app checks the PRJ-ID in both the interface and Java backend. Unsupported
devices cannot send region-state commands.

## Requirements

- a supported device running a compatible stock OxygenOS or ColorOS build;
- root through Magisk, KernelSU, APatch, or another provider with a working
  `su` command;
- the stock Oplus telephony framework and subsystem-radio service.

A custom ROM may retain the vendor radio HAL while omitting the stock framework
service the app needs. In that case the app can identify the phone, but the
region check fails and all state-changing actions remain disabled.

## Install the app

### On the phone

1. Open the repository's **Actions** tab and select a successful **Build
   Android app** run.
2. Download and extract its `region-unlock-app-<commit>` artifact.
3. Transfer `oplus-region-unlock-app-v0.4.0-debug.apk` to the phone.
4. Open the APK and allow installation from that source if Android asks.
5. Open **Region Unlock** and approve the root request from your root manager.

### With ADB

Connect the phone with USB debugging enabled, then run:

```sh
adb install -r oplus-region-unlock-app-v0.4.0-debug.apk
```

Open **Region Unlock** on the phone and approve the root request.

## Unlock the region

1. Open the app and wait for the automatic checks to finish.
2. Confirm that **Root available** appears beside the device information.
3. Review the full live result in the **Region check** card. It shows region,
   mode, brand, version, operator, operation, state, and result.
4. Tap **Unlock region** at the bottom of the card and confirm.
5. When the app accepts the request, tap **Reboot now**.
6. After boot, reopen the app or tap **Refresh checks**. The expected automatic
   unlock result is `state=0` and `result=0`.

If the phone already reports state `0`, the button reads **Already unlocked**
and cannot send the request again.

## Lock the region for testing

The lock action is under **Settings > Lock region**. It requires two warning
confirmations and the exact uppercase word `LOCK` before the backend requests
locked state `1`.

Reboot, then refresh the checks to confirm the actual modem state. A request
being accepted only means it was submitted; the refreshed state is the final
result. Lock persistence has been verified on OnePlus 13, but is not currently
verified on OnePlus 15 or OnePlus Ace 6.

## State values

| State | Meaning |
|---:|---|
| `-1` | Invalid or test-locked |
| `0` | Automatically unlocked |
| `1` | Locked |
| `2` | Sale/provisioning unlocked |
| `3` | Server locked |
| `4` | Server unlocked |
| `5` | Locally unlocked with a code |

The app's **Unlock region** action requests automatic unlock state `0`. State
`2` is a separate stock provisioning path that requires a valid signed blob;
the app does not create or submit one.

## How it works

Stock OxygenOS rejects these region API calls from root UID `0`. After root is
approved, the app runs its packaged client as Android system UID `1000`, reads
the current state through the stock Oplus telephony framework, and sends the
stock subsystem-radio request. State changes happen only after an explicit
action in the app; nothing is installed to run during boot.

Safety gates require all of the following before a state-changing action is
enabled:

- a supported PRJ-ID;
- working root access;
- a successful read from the stock region service;
- a state other than `0` for the unlock action;
- explicit user confirmation.

## Build from source

Requirements:

- JDK 21;
- Android SDK Platform 36 and matching build tools;
- `sha256sum`.

Build the debug APK from the repository root:

```sh
JAVA_HOME=/path/to/jdk21 \
ANDROID_SDK_ROOT=/path/to/android-sdk \
./build-app.sh
```

The build creates:

```text
dist/oplus-region-unlock-app-v0.4.0-debug.apk
dist/SHA256SUMS
```

Verify the APK checksum with:

```sh
(cd dist && sha256sum -c SHA256SUMS)
```

GitHub Actions also builds the app on every push and pull request. Run the
**Build Android app** workflow manually from the repository's **Actions** tab
when an on-demand APK is needed. The APK and checksum are available together
as the workflow run's downloadable artifact for 14 days.

## Project layout

```text
android-app/                                Android application and interface
src/dev/oplus/regionunlock/RegionUnlock.java Packaged stock-radio client
build-app.sh                                App build and packaging script
```
