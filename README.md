# OnePlus 13/15 Region Unlock Client

Root tooling for reading and invoking the stock OnePlus 13 and OnePlus 15
OxygenOS region-lock subsystem. It includes a rooted Android app, a PC/ADB
launcher, an Android DEX/JAR payload, a one-shot Magisk/KernelSU/APatch module,
and shareable diagnostic reports.

The OnePlus 13 path was traced and tested against OxygenOS
16.0.2.402(EX01). The extracted OnePlus 15 stock framework confirms the same
path: `RegionLockController.unlockRegionlock()` sends `(operator, 2, 0)`,
`RegionLockManager` wraps it as tag-1 TLV, and `RadioProxy` calls
`ISubsysRadio.setRegionLockStatus`. The generated OP15 AIDL assigns transaction
294 to that call. Although the OP15 vendor image also exposes
`IRadioStable/OplusRadio0`, the stock RegionLock stack uses `ISubsysRadio`.

> [!CAUTION]
> Use this only on a device you own or are authorized to service. A wrong local
> unlock code may consume a modem retry. A state-2 sale unlock requires a real
> signed provisioning blob; this project does not generate or forge one.
>
> The root module intentionally causes one automatic follow-up reboot. This is
> required for the modem to reload the unlocked state.

## What the OnePlus 13 and 15 use

The public stock API is `com.oplus.telephony.RadioManager`, backed by the
`com.oplus.telephony.ISubsysRadio` system service. Its region-lock commands are:

| Action | Stock API/transport | Successful state |
|---|---|---:|
| Read status | `getRegionLockInfo("1", callback)` | unchanged |
| Lock for testing | subsystem-radio TLV tag 1: `[0, 3, 1]` | `1` after reboot |
| Automatic unlock | subsystem-radio TLV tag 1: `[operator, 2, 0]` | `0` |
| Local unlock code | `unlockRegionLock(0, code, callback)` | `5` |
| Sale/provisioning unlock | `updateRegionLockBlob(base64Blob, callback)` | `2` |

The vendor endpoint is
`vendor.oplus.hardware.subsys_interface.subsys_radio.ISubsysRadio/slot1`
(`slot2` for the second SIM), with Binder transaction 294 for the status TLV.

OxygenOS rejects these public API calls from root UID 0. The included launchers
first obtain root and then execute the payload as Android system UID 1000, which
is accepted by the stock `SubsysPermissions` check.

State values reported by the stock OnePlus framework:

| State | Meaning |
|---:|---|
| `-1` | invalid/test-locked |
| `0` | auto-unlocked |
| `1` | locked |
| `2` | sale-unlocked |
| `3` | server-locked |
| `4` | server-unlocked |
| `5` | locally unlocked with a code |

## Method 1: Android app

The Android app provides a DeepTest-inspired interface with an **Unlock
region** button. It contains no root exploit: Magisk, KernelSU or APatch must
already be installed.

### Install

1. Download `oplus-region-unlock-app-v0.3.0-debug.apk`.
2. Install it with Android's package installer, or run:

```sh
adb install -r oplus-region-unlock-app-v0.3.0-debug.apk
```

3. Open **Region Unlock**.

The app verifies `ro.boot.prjname`, root access, and a successful stock
region-service state read before enabling an action:

| Device | Chinese model | Global model seen | PRJ-ID |
|---|---|---|---:|
| OnePlus 13 | `PJZ110` | `CPH2653` | `23821` |
| OnePlus 15 | `PLK110` | `CPH2745` | `24831` |

The OnePlus 15 identity gate was verified on the connected `PLK110` custom-ROM
device. A custom ROM may preserve the vendor HAL while omitting the stock
`RadioManager` framework layer, so actual state reads and changes require a
compatible stock OxygenOS/ColorOS framework plus root. If that read fails,
both state-changing actions remain disabled even when root is available.

### Unlock

1. Open the app and approve the root-manager prompt if shown.
2. Wait for the automatic checks to show **Root available** and the current
   region state. The **Region check** card lists the live region, mode, brand,
   version, operator, operation, state and result values.
3. If the reported state is already `0`, the disabled button reads **Already
   unlocked** and no request can be sent. Otherwise, tap **Unlock region** at
   the bottom of that card and confirm the action.
4. Wait for **Unlock request accepted**.
5. Tap **Reboot now**.
6. After boot, reopen the app. Expected state: `0`. Use **Refresh checks** to
   run both read-only checks again at any time.

The **Settings** page contains only Bright mode and the guarded **Lock region**
action. Locking requires two separate warning acknowledgements and then the
exact uppercase confirmation `LOCK`; the Java backend validates that word
again before requesting state `1`. Reboot remains a separate action.

Unsupported PRJ-IDs are blocked in both the web UI and Java backend. The lock
control has the same PRJ-ID gate as the unlock control.

## Method 2: run from a PC over ADB

This method does not permanently install anything on the phone.

### Requirements

- Python 3.10 or newer;
- Android Platform Tools (`adb`);
- USB debugging enabled and authorized;
- Magisk or KernelSU root with a working `su` command;
- a supported OnePlus 13 or OnePlus 15 running a compatible stock
  OxygenOS/ColorOS build.

### 1. Download and extract

Download `oplus-region-unlock-pc-v0.3.0.zip` from the release and extract
it. Open a terminal in the extracted `oplus-region-unlock-pc-v0.3.0` folder.

If using a source checkout instead, build it first:

```sh
./build.sh
```

On Windows, replace `python3` with `py -3` in the commands below.

### 2. Confirm ADB and root

```sh
adb devices
adb shell su -c 'id -u'
adb shell su 1000 -c 'id -u'
```

The phone must appear as `device`. The last two commands must print `0` and
`1000`, respectively. Accept any USB-debugging or root prompt on the phone.

### 3. Probe the OnePlus services (read-only)

```sh
python3 pc/region_unlock.py --probe --report
```

This checks the stock subsystem service, vendor HAL descriptor, UID transition,
and current region-lock state. It does not send an unlock command.

### 4. Read the current state (read-only)

```sh
python3 pc/region_unlock.py --status --report
```

A working, already auto-unlocked phone typically prints:

```text
region-lock-state: operator=0 operation=2 state=0 result=0 (AUTO_UNLOCKED)
```

Additional read-only diagnostics are available:

```sh
python3 pc/region_unlock.py --policy
python3 pc/region_unlock.py --settings
python3 pc/region_unlock.py --test-info
```

`--policy` decodes the restrictions, automatic-unlock matchers, MCC mode and
test flags. `--settings` reads the assistant/retry counters. `--test-info`
reads live matcher progress when the stock matcher monitor is active; it is
normally inactive while the phone is already unlocked.

### 5. Send AUTO_UNLOCK

```sh
python3 pc/region_unlock.py --auto-unlock --slot 0 --report
```

This is the command that changes modem state. It reads the existing operator,
sends the stock `[operator, operation=2, data=0]` TLV, waits, and reads the
state again. Successful AUTO_UNLOCK is `operation=2 state=0 result=0`.

Use `--slot 1` only for the second SIM slot. If more than one phone is attached,
add `--device SERIAL` to every command.

### Lock state for controlled testing

```sh
python3 pc/region_unlock.py --lock-state --slot 0 --report
adb reboot
```

This was verified to produce state `1` after reboot. It is intended only for a
development device; use AUTO_UNLOCK to return to state `0`.

### Local unlock code (only if Oplus supplied one)

```sh
python3 pc/region_unlock.py --unlock-code YOUR_CODE --slot 0 --report
```

The stock modem accepts at most 16 bytes. Do not guess codes: an incorrect code
may consume a retry. Successful local unlock is state `5`.

### Signed sale/provisioning blob

```sh
python3 pc/region_unlock.py --signed-blob region-lock.blob --slot 0 --report
```

The file may contain the raw binary blob or its Base64 representation. The PC
tool converts raw input to Base64, and the phone passes the decoded data to the
stock `setRegionLockBlob` path. The structure includes a 256-byte signature.
Successful sale unlock is exactly:

```text
operator=2 operation=0 state=2 result=0
```

A bare `(2, 0, 0)` tuple is deliberately not exposed as “sale unlock” because
that is not the stock LockAssistant provisioning route.

## Method 3: one-shot root module

The module needs no terminal commands or configuration files. It sends one
AUTO_UNLOCK request after the first completed boot, marks itself for removal,
and automatically reboots once more so the modem loads state `0`.

### 1. Install

1. Download `oplus-region-unlock-magisk-v0.3.0.zip`.
2. Open Magisk or KernelSU's module manager.
3. Select **Install from storage**, choose the ZIP, and reboot.

### What happens next

1. Android finishes its first boot after installation.
2. The module runs the client as Android UID 1000 and sends AUTO_UNLOCK.
3. It saves the command output outside the module.
4. It marks itself for removal and waits eight seconds.
5. The phone automatically reboots a second time.
6. The root manager removes the module and the modem reloads the new state.

Do not manually reboot or power off the phone while the one-shot action is in
progress. After the second boot, the module should no longer appear as
installed.

Read the surviving log with:

```sh
adb shell su -c 'cat /data/adb/region-unlock/oneshot.log'
```

The important success lines are:

```text
command-exit=0
result=command-accepted
follow-up-reboot=scheduled
```

Confirm the final modem state using the PC package:

```sh
python3 pc/region_unlock.py --status --report
```

Expected result: `state=0 result=0 (AUTO_UNLOCKED)`.

## Shareable diagnostic reports

Adding `--report` to a PC command writes a timestamped report under `reports/`.
Choose a path explicitly with:

```sh
python3 pc/region_unlock.py --status --report phone-status.txt
```

Reports contain the payload checksum, device build, command result, post-action
state, focused subsystem/radio logs, and relevant SELinux denials. Common
device identifiers are redacted on a best-effort basis. Review a report before
publishing it.

## Build from source

Requirements: JDK 17 or newer, `android.jar`, `d8`, `zip`, and `sha256sum`.

```sh
./build.sh
(cd dist && sha256sum -c SHA256SUMS)
```

Output:

```text
dist/oplus-region-unlock.jar
dist/oplus-region-unlock-magisk-v0.3.0.zip
dist/oplus-region-unlock-pc-v0.3.0.zip
dist/SHA256SUMS
```

Build the app as well with Android SDK 36 and JDK 21:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk JAVA_HOME=/path/to/jdk21 ./build-app.sh
```

Additional output:

```text
dist/oplus-region-unlock-app-v0.3.0-debug.apk
```

## Safety and implementation notes

- The PC client requires an explicit action. The root-module package is the one
  intentional exception: installing it opts into one AUTO_UNLOCK boot action.
- The Android app accepts actions only on PRJ-ID `23821` (OnePlus 13) or
  `24831` (OnePlus 15), requires root and a successful stock status read,
  disables repeat unlocks in state `0`, and keeps reboot as a separate action.
- A persistent attempt marker prevents duplicate modem requests if the boot
  script is interrupted.
- The module uses the root manager's standard `remove` marker and retains only
  `/data/adb/region-unlock/oneshot.log` and its attempt marker after removal.
- Status and stock API calls run through `RadioManager` as UID 1000.
- AUTO_UNLOCK uses the stock OnePlus subsystem-radio service and its tag-1 TLV.
- The tool does not replace the radio callback owned by the system process.
- A Binder/API success is followed by a fresh state query; the printed modem
  state, not merely “queued”, is the useful result.
- Unlock codes and signed blobs are never printed into the diagnostic report.
- This project does not calculate local codes, generate provisioning blobs,
  bypass modem signature verification, or forge successful responses.

## Project layout

```text
src/dev/op13/regionunlock/RegionUnlock.java  Android client
android-app/                                Rooted Android UI
pc/region_unlock.py                         PC/ADB launcher and reports
module/                                     Magisk/KernelSU module template
build.sh                                    build and packaging script
```
