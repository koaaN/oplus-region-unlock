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

## Before you start

The phone must be rooted for either method. The tool cannot operate through
ADB alone without working `su` access.

Installing or extracting a package **does not unlock anything by itself**. You
must run the command labelled **Send AUTO_UNLOCK** in one of the guides below.
Commands containing `--probe` or `--status` only read information and do not
send the unlock request.

The implementation is based on Oplus 16.0.9.400. Other releases may use a
different transaction layout and must be verified before use.

## Method 1: PC with ADB

Use this method if you want the easiest setup and a shareable diagnostic
report. It does not install anything permanently on the phone.

### Requirements

- Linux, macOS, or Windows with Python 3.10 or newer;
- [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)
  with `adb` available in the terminal;
- USB debugging enabled and authorized on the phone;
- working device root through `su`.

### Step 1: Download and extract the PC package

Download `oplus-region-unlock-pc-v0.1.0.zip` from the
[latest release](https://github.com/koaaN/oplus-region-unlock/releases/latest)
and extract it. Open a terminal in the extracted
`oplus-region-unlock-pc-v0.1.0` directory.

If you cloned the source repository instead, run `./build.sh` first and use
the repository root as the working directory.

On Windows, use `py -3` in place of `python3` in the commands below.

### Step 2: Connect and check the phone

```sh
adb devices
adb shell su -c 'id -u'
```

Accept the USB debugging and root prompts on the phone. The first command must
show the phone as `device`; the second must print `0`.

### Step 3: Run safe checks

```sh
python3 pc/region_unlock.py --probe --report
python3 pc/region_unlock.py --status --report
```

`--probe` checks that the expected radio service and Binder descriptor exist.
`--status` reads the framework's cached region-lock state. Neither command
sends AUTO_UNLOCK.

### Step 4: Send AUTO_UNLOCK

For the primary SIM/radio slot, run:

```sh
python3 pc/region_unlock.py --slot 0 --report
```

**This is the command that sends AUTO_UNLOCK.** Omitting both `--probe` and
`--status` selects the unlock action. Use `--slot 1` only when targeting the
second radio slot on a dual-SIM device.

The default `--operator auto` is recommended. It preserves the operator value
cached by the phone process and falls back to `0` if that value is unavailable.

### Step 5: Reboot and verify

```sh
adb reboot
adb wait-for-device
python3 pc/region_unlock.py --status --report
```

The framework cache may not refresh until after a reboot. A successful result
is expected to contain:

```text
operation=2 state=0 result=0
```

Here, `2` is the AUTO_UNLOCK operation code; `0` is the expected resulting
state. A message saying the one-way Binder request was queued does not by
itself prove that the modem accepted it.

If several phones are attached, add `--device SERIAL` to every Python command:

```sh
python3 pc/region_unlock.py --device SERIAL --slot 0 --report
```

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

## Method 2: Magisk module

Use this method if you want the `region-unlock` command installed on the phone.
Installing the module alone does not send AUTO_UNLOCK.

### Step 1: Install the module

1. Download `oplus-region-unlock-magisk-v0.1.0.zip` from the
   [latest release](https://github.com/koaaN/oplus-region-unlock/releases/latest).
   Do not extract it.
2. Open Magisk, select **Modules**, then **Install from storage**.
3. Select the downloaded ZIP and reboot when installation finishes.

### Step 2: Run safe checks

Open an Android terminal app, or run `adb shell` from a PC, then execute:

```sh
su -c 'region-unlock --probe'
su -c 'region-unlock --status'
```

These commands check the radio service and current cached status. They do not
send AUTO_UNLOCK.

### Step 3: Send AUTO_UNLOCK

```sh
su -c 'region-unlock --slot 0'
```

**This is the command that sends AUTO_UNLOCK.** Use `--slot 1` only for the
second radio slot. Reboot the phone after the command completes.

### Step 4: Verify after reboot

```sh
su -c 'region-unlock --status'
```

The expected successful state is `operation=2 state=0 result=0`.

### Optional: run once after every boot

Manual execution is recommended first. To opt into one automatic attempt after
every completed boot:

```sh
su -c 'mkdir -p /data/adb/region-unlock; touch /data/adb/region-unlock/auto'
```

The most recent boot attempt is written to
`/data/adb/region-unlock/last.log`. Remove the `auto` marker to disable boot
execution:

```sh
su -c 'rm -f /data/adb/region-unlock/auto'
```

To copy the root-only boot log to a connected PC:

```sh
adb exec-out su -c 'cat /data/adb/region-unlock/last.log' > region-unlock-boot.log
```

For a filtered, redacted report from a manual attempt, use the PC method with
`--report` instead.

## Building from source

Local builds require JDK 17 or newer, `android.jar`, `d8`, `zip`, and
`sha256sum`.

Running `./build.sh` creates:

```text
dist/oplus-region-unlock.jar
dist/oplus-region-unlock-magisk-v0.1.0.zip
dist/oplus-region-unlock-pc-v0.1.0.zip
dist/SHA256SUMS
```

The PC ZIP is self-contained: extract it and run
`python3 pc/region_unlock.py ...` from its top-level directory. The Magisk ZIP
can be installed directly through the Magisk app.

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
