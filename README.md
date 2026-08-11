# Oplus Region Unlock HAL Client

Root-only diagnostic and command-line tooling for the Oplus region-lock radio
HAL found in OxygenOS/ColorOS 16.0.9.400.

The project provides:

- a PC-side ADB launcher;
- a standalone Android DEX/JAR payload;
- an installable Magisk module;
- filtered, redacted diagnostic reports suitable for sharing.

> [!CAUTION]
> This is experimental device-specific tooling. It invokes a stock modem
> operation but cannot guarantee that the modem will accept it. Back up your
> device, understand the implications of root access, and use it only on a
> device you own or are authorized to service.

## Confirmed protocol

The stock framework sends AUTO_UNLOCK through the stable Oplus radio AIDL
service:

```text
service:     vendor.oplus.hardware.radio.IRadioStable/OplusRadio{slot}
descriptor:  vendor.oplus.hardware.radio.IOplusRadio
method:      updateRegionlockStatus(int serial, byte operator, byte operation, byte data)
transaction: 13, one-way
arguments:   serial, operator, operation=2, data=0
```

The service interface name and Binder descriptor differ intentionally. They
match the stock `OplusRadioAidl` implementation.

A successful modem result is expected to contain:

```text
operation=2 state=0 result=0
```

## Requirements

For PC/ADB operation:

- Linux, macOS, or Windows with Python 3.10 or newer;
- Android Platform Tools (`adb`);
- USB debugging enabled;
- working device root through `su`.

For local builds:

- JDK 17 or newer;
- `android.jar` and `d8` from an Android SDK or AOSP checkout;
- `zip` and `sha256sum`.

The implementation is based on Oplus 16.0.9.400. Other releases may use a
different transaction layout and must be verified before use.

## Quick start over ADB

Build the payload if `dist/` is not already present:

```sh
./build.sh
```

Connect the rooted phone and perform non-mutating checks first:

```sh
adb devices
python3 pc/region_unlock.py --probe --report
python3 pc/region_unlock.py --status --report
```

Queue AUTO_UNLOCK on slot 0 and capture a diagnostic report:

```sh
python3 pc/region_unlock.py --slot 0 --report
```

If several devices are attached, select one explicitly:

```sh
python3 pc/region_unlock.py --device SERIAL --slot 0 --report
```

The launcher prints the generated report path when it finishes.

## Diagnostic reports

`--report` captures one shareable text file containing:

- the command output;
- the post-command framework state;
- focused radio, RIL, and region-lock logcat messages;
- relevant SELinux denials;
- device build identification;
- the exact payload SHA-256 checksum.

Reports are stored under `reports/` with a UTC timestamp. Choose a particular
path by supplying it after the option:

```sh
python3 pc/region_unlock.py --slot 0 --report phone-test.txt
```

The report filters unrelated logs and applies best-effort redaction to
IMEI/IMSI/ICCID-like values, phone numbers, long numeric identifiers, and MAC
addresses. Always review a report before publishing it.

## Magisk module

Build and install `dist/oplus-region-unlock-magisk-v0.1.0.zip` through the
Magisk app. After rebooting:

```sh
su -c 'region-unlock --probe'
su -c 'region-unlock --status'
su -c 'region-unlock --slot 0'
```

Installing the module does not automatically send AUTO_UNLOCK. To opt into one
attempt after every completed boot:

```sh
su -c 'mkdir -p /data/adb/region-unlock; touch /data/adb/region-unlock/auto'
```

The most recent boot attempt is written to
`/data/adb/region-unlock/last.log`. Remove the `auto` marker to disable boot
execution.

## Build outputs

Running `./build.sh` creates:

```text
dist/oplus-region-unlock.jar
dist/oplus-region-unlock-magisk-v0.1.0.zip
dist/SHA256SUMS
```

Validate them with:

```sh
(cd dist && sha256sum -c SHA256SUMS)
```

## Design and safety notes

- The payload validates the Binder descriptor before sending anything.
- It never calls `setCallback()`. Replacing the callback owned by the phone
  process can destabilize telephony.
- The default `--operator auto` preserves the operator from the phone process's
  cached `OplusRegionLockState`. If unavailable, it warns and falls back to `0`,
  matching the stock controller.
- A high changing serial reduces collision risk with live RIL requests.
- Binder acceptance only proves that the one-way request was queued. It does
  not prove modem acceptance.
- Because the request originates outside the phone process, its asynchronous
  response is not associated with a normal `RILExt` request. The framework
  cache may remain stale until telephony performs another tracked status query,
  often after a reboot.
- The Magisk policy is restricted to locating and calling the radio and phone
  Binder services required by this tool.
- This project does not generate sale unlock credentials, patch signed region
  data, forge a modem response, or implement the separate `state=2` sale-unlock
  flow.

## Suggested end-to-end verification

```sh
python3 pc/region_unlock.py --probe --report
python3 pc/region_unlock.py --status --report
python3 pc/region_unlock.py --slot 0 --report
adb reboot
adb wait-for-device
python3 pc/region_unlock.py --status --report
```

The final status should report:

```text
operation=2 state=0 result=0
```

## Project layout

```text
src/                         Android Binder client source
pc/region_unlock.py          PC/ADB launcher and report collector
module/                      Magisk module template
build.sh                     Reproducible build and packaging script
```

Generated build products and diagnostic reports are intentionally excluded
from Git.

## Current validation status

The stock transaction layout, generated DEX, shell scripts, Python launcher,
archive structure, checksums, filtering, and report redaction have been
validated locally. An end-to-end modem test has not yet been completed because
no ADB device was connected during development.
