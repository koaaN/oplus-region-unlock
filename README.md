# OnePlus 13 Region Unlock Client

Root tooling for reading and invoking the stock OnePlus 13 (CPH2653)
OxygenOS region-lock subsystem. It includes a PC/ADB launcher, an Android
DEX/JAR payload, a Magisk/KernelSU module, and shareable diagnostic reports.

This OP13 branch was traced and tested against OxygenOS 16.0.2.402(EX01). It
does **not** use the older `IRadioStable/OplusRadio0` route from the OP15
implementation.

> [!CAUTION]
> Use this only on a device you own or are authorized to service. A wrong local
> unlock code may consume a modem retry. A state-2 sale unlock requires a real
> signed provisioning blob; this project does not generate or forge one.

## What the OP13 uses

The public stock API is `com.oplus.telephony.RadioManager`, backed by the
`com.oplus.telephony.ISubsysRadio` system service. Its region-lock commands are:

| Action | Stock API/transport | Successful state |
|---|---|---:|
| Read status | `getRegionLockInfo("1", callback)` | unchanged |
| Automatic unlock | subsystem-radio TLV tag 1: `[operator, 2, 0]` | `0` |
| Local unlock code | `unlockRegionLock(0, code, callback)` | `5` |
| Sale/provisioning unlock | `updateRegionLockBlob(base64Blob, callback)` | `2` |

The vendor endpoint is
`vendor.oplus.hardware.subsys_interface.subsys_radio.ISubsysRadio/slot1`
(`slot2` for the second SIM), with Binder transaction 294 for the status TLV.

OxygenOS rejects these public API calls from root UID 0. The included launchers
first obtain root and then execute the payload as Android system UID 1000, which
is accepted by the stock `SubsysPermissions` check.

State values reported by the OP13 framework:

| State | Meaning |
|---:|---|
| `-1` | invalid/test-locked |
| `0` | auto-unlocked |
| `1` | locked |
| `2` | sale-unlocked |
| `3` | server-locked |
| `4` | server-unlocked |
| `5` | locally unlocked with a code |

## Method 1: run from a PC over ADB

This method does not permanently install anything on the phone.

### Requirements

- Python 3.10 or newer;
- Android Platform Tools (`adb`);
- USB debugging enabled and authorized;
- Magisk or KernelSU root with a working `su` command;
- a OnePlus 13 running a compatible stock OxygenOS/ColorOS build.

### 1. Download and extract

Download `oplus-region-unlock-pc-v0.2.0.zip` from the OP13 release and extract
it. Open a terminal in the extracted `oplus-region-unlock-pc-v0.2.0` folder.

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

### 3. Probe the OP13 services (read-only)

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

### 5. Send AUTO_UNLOCK

```sh
python3 pc/region_unlock.py --auto-unlock --slot 0 --report
```

This is the command that changes modem state. It reads the existing operator,
sends the stock OP13 `[operator, operation=2, data=0]` TLV, waits, and reads the
state again. Successful AUTO_UNLOCK is `operation=2 state=0 result=0`.

Use `--slot 1` only for the second SIM slot. If more than one phone is attached,
add `--device SERIAL` to every command.

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
that is not the stock OP13 LockAssistant provisioning route.

## Method 2: install the root module

Installing the module adds the `region-unlock` command. Installation alone does
not send anything to the modem.

### 1. Install

1. Download `oplus-region-unlock-magisk-v0.2.0.zip`.
2. Open Magisk or KernelSU's module manager.
3. Select **Install from storage**, choose the ZIP, and reboot.

### 2. Run read-only checks

From `adb shell` or an Android terminal, run:

```sh
su -c 'region-unlock --probe'
su -c 'region-unlock --status'
```

The launcher automatically changes from root UID 0 to system UID 1000.

### 3. Send AUTO_UNLOCK

```sh
su -c 'region-unlock --auto-unlock --slot 0'
```

Then verify:

```sh
su -c 'region-unlock --status --slot 0'
```

### 4. Optional boot execution

First validate manual execution. To opt into one AUTO_UNLOCK request after each
completed boot:

```sh
su -c 'mkdir -p /data/adb/region-unlock'
su -c 'touch /data/adb/region-unlock/auto'
```

Read the last boot result:

```sh
su -c 'cat /data/adb/region-unlock/last.log'
```

Disable boot execution:

```sh
su -c 'rm -f /data/adb/region-unlock/auto'
```

For a local code or signed blob, prefer the PC method so secrets do not need to
be manually shell-escaped.

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
dist/oplus-region-unlock-magisk-v0.2.0.zip
dist/oplus-region-unlock-pc-v0.2.0.zip
dist/SHA256SUMS
```

## Safety and implementation notes

- Every action is explicit; running the command without an action prints an
  error and does not touch the modem.
- Status and stock API calls run through `RadioManager` as UID 1000.
- AUTO_UNLOCK uses the OP13 subsystem-radio service and its tag-1 TLV.
- The tool does not replace the radio callback owned by the system process.
- A Binder/API success is followed by a fresh state query; the printed modem
  state, not merely “queued”, is the useful result.
- Unlock codes and signed blobs are never printed into the diagnostic report.
- This project does not calculate local codes, generate provisioning blobs,
  bypass modem signature verification, or forge successful responses.

## Project layout

```text
src/dev/op13/regionunlock/RegionUnlock.java  Android client
pc/region_unlock.py                         PC/ADB launcher and reports
module/                                     Magisk/KernelSU module template
build.sh                                    build and packaging script
```
