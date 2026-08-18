#!/usr/bin/env python3
"""Push and run the Oplus region-unlock Binder client over ADB."""

from __future__ import annotations

import argparse
import base64
import binascii
from datetime import datetime, timezone
import hashlib
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time


REMOTE_JAR = "/data/local/tmp/oplus-region-unlock.jar"
LOG_FILTER = re.compile(
    r"region.?lock|sale.?unlock|SubsysPermissions|ISubsysRadio|setRegionLock|"
    r"RadioManager|\b2328\b|\b2329\b|\b2330\b",
    re.IGNORECASE,
)
SELINUX_FILTER = re.compile(
    r"avc:\s*denied.*(?:scontext=u:r:(?:ksu|magisk)|"
    r"tcontext=u:r:rild|hal_subsys_service|app_process|region.?lock)",
    re.IGNORECASE,
)
SENSITIVE_KEY_VALUE = re.compile(
    r"(?i)\b(imei|imsi|iccid|msisdn|subscriber(?:id)?|device[_ -]?id|serial(?:no)?)"
    r"(\s*[:=]\s*)([^\s,;]+)"
)
LONG_DIGITS = re.compile(r"(?<!\d)\d{10,20}(?!\d)")
PHONE_NUMBER = re.compile(r"(?<!\w)\+\d{7,15}(?!\d)")
MAC_ADDRESS = re.compile(r"(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")


def run(command: list[str], *, capture: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        check=False,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )


def redact(text: str) -> str:
    text = SENSITIVE_KEY_VALUE.sub(
        lambda match: f"{match.group(1)}{match.group(2)}<redacted>", text
    )
    text = PHONE_NUMBER.sub("<redacted-phone>", text)
    text = LONG_DIGITS.sub("<redacted-number>", text)
    return MAC_ADDRESS.sub("<redacted-mac>", text)


def focused_logs(text: str) -> str:
    lines = [line for line in text.splitlines() if LOG_FILTER.search(line)]
    if not lines:
        return "<no matching region-lock/radio lines captured>"
    # Keep reports manageable if a noisy device emits repeated radio messages.
    return redact("\n".join(lines[-1500:]))


def focused_selinux(text: str) -> str:
    lines = [line for line in text.splitlines() if SELINUX_FILTER.search(line)]
    if not lines:
        return "<no relevant SELinux denials captured>"
    return redact("\n".join(lines[-300:]))


def java_command(arguments: list[str]) -> str:
    return (
        f"CLASSPATH={shlex.quote(REMOTE_JAR)} "
        "app_process /system/bin dev.op13.regionunlock.RegionUnlock "
        + " ".join(shlex.quote(value) for value in arguments)
    )


def system_uid_command(adb: list[str], arguments: list[str]) -> list[str]:
    return adb + ["shell", "su", "1000", "-c", java_command(arguments)]


def load_signed_blob(path: Path) -> str:
    raw = path.read_bytes()
    compact = b"".join(raw.split())
    try:
        decoded = base64.b64decode(compact, validate=True)
        if len(decoded) >= 256:
            return compact.decode("ascii")
    except (binascii.Error, UnicodeDecodeError, ValueError):
        pass
    if len(raw) < 256:
        raise ValueError("blob is too short to contain its 256-byte signature")
    return base64.b64encode(raw).decode("ascii")


def get_property(adb: list[str], name: str) -> str:
    result = run(adb + ["shell", "getprop", name], capture=True)
    return result.stdout.strip() if result.returncode == 0 else "<unavailable>"


def report_path(value: str, project: Path) -> Path:
    if value:
        return Path(value).expanduser().resolve()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return project / "reports" / f"region-unlock-report-{stamp}.txt"


def write_report(
    destination: Path,
    *,
    action: str,
    slot: int,
    requested_operator: str,
    payload_sha256: str,
    properties: dict[str, str],
    tool_output: str,
    status_output: str,
    logcat_output: str,
    dmesg_output: str,
) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    generated = datetime.now(timezone.utc).isoformat()
    property_lines = "\n".join(
        f"{name}={redact(value)}" for name, value in properties.items()
    )
    content = f"""Oplus region-unlock diagnostic report
generated_utc={generated}
action={action}
slot={slot}
requested_operator={requested_operator}
payload_sha256={payload_sha256}

Privacy note: this report is filtered and applies best-effort redaction to
IMEI/IMSI/ICCID/device identifiers, long digit strings, phone numbers, and MAC
addresses. Review it before sharing.

[device]
{property_lines}

[tool-output]
{redact(tool_output.strip()) or '<no output>'}

[framework-status-after]
{redact(status_output.strip()) or '<not available>'}

[focused-logcat]
{focused_logs(logcat_output)}

[focused-selinux]
{focused_selinux(dmesg_output)}
"""
    destination.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="adb executable (default: adb)")
    parser.add_argument("--device", help="adb serial")
    parser.add_argument("--slot", type=int, choices=(0, 1), default=0)
    parser.add_argument("--wait", type=int, default=15, metavar="SECONDS")
    parser.add_argument("--probe", action="store_true", help="only validate the service and descriptor")
    parser.add_argument("--status", action="store_true", help="only read the current OnePlus state")
    parser.add_argument(
        "--policy", action="store_true", help="decode the active region-lock policy (read-only)"
    )
    parser.add_argument(
        "--settings", action="store_true", help="read retry and assistant settings (read-only)"
    )
    parser.add_argument(
        "--test-info", action="store_true", help="read matcher progress counters (read-only)"
    )
    parser.add_argument(
        "--auto-unlock",
        action="store_true",
        help="send the stock AUTO_UNLOCK request (successful state: 0)",
    )
    parser.add_argument(
        "--lock-state",
        action="store_true",
        help="send the stock LOCK_STATE request (expected state: 1)",
    )
    parser.add_argument(
        "--unlock-code",
        metavar="CODE",
        help="submit a provisioned local unlock code (successful state: 5)",
    )
    parser.add_argument(
        "--signed-blob",
        type=Path,
        metavar="FILE",
        help="submit a raw or Base64 signed provisioning blob (sale success: state 2)",
    )
    parser.add_argument("--keep", action="store_true", help="leave the temporary JAR on the device")
    parser.add_argument(
        "--report",
        nargs="?",
        const="",
        metavar="FILE",
        help="write a filtered, redacted diagnostic report (default: reports/timestamp.txt)",
    )
    args = parser.parse_args()

    if not 1 <= args.wait <= 300:
        parser.error("--wait must be from 1 through 300")
    actions = sum((
        args.probe,
        args.status,
        args.policy,
        args.settings,
        args.test_info,
        args.auto_unlock,
        args.lock_state,
        args.unlock_code is not None,
        args.signed_blob is not None,
    ))
    if actions != 1:
        parser.error("choose exactly one action: --probe, --status, --policy, --settings, --test-info, --auto-unlock, --lock-state, --unlock-code, or --signed-blob")

    project = Path(__file__).resolve().parents[1]
    payload = project / "dist" / "oplus-region-unlock.jar"
    if not payload.is_file():
        print(f"error: payload is missing: {payload}", file=sys.stderr)
        print("Run ./build.sh first.", file=sys.stderr)
        return 2

    adb = [args.adb]
    if args.device:
        adb += ["-s", args.device]

    state = run(adb + ["get-state"], capture=True)
    if state.returncode != 0 or state.stdout.strip() != "device":
        print(state.stdout.rstrip(), file=sys.stderr)
        print("error: no usable ADB device", file=sys.stderr)
        return 3

    root = run(adb + ["shell", "su", "-c", "id -u"], capture=True)
    if root.returncode != 0 or root.stdout.strip() != "0":
        print(root.stdout.rstrip(), file=sys.stderr)
        print("error: device root was not granted", file=sys.stderr)
        return 4

    system_uid = run(adb + ["shell", "su", "1000", "-c", "id -u"], capture=True)
    if system_uid.returncode != 0 or system_uid.stdout.strip() != "1000":
        print(system_uid.stdout.rstrip(), file=sys.stderr)
        print("error: su could not start the payload as Android UID 1000", file=sys.stderr)
        return 5

    pushed = run(adb + ["push", str(payload), REMOTE_JAR])
    if pushed.returncode != 0:
        return pushed.returncode

    java_args = ["--slot", str(args.slot), "--wait", str(args.wait)]
    if args.probe:
        java_args.append("--probe")
    if args.status:
        java_args.append("--status")
    if args.policy:
        java_args.append("--policy")
    if args.settings:
        java_args.append("--settings")
    if args.test_info:
        java_args.append("--test-info")
    if args.auto_unlock:
        java_args.append("--auto-unlock")
    if args.lock_state:
        java_args.append("--lock-state")
    if args.unlock_code is not None:
        java_args += ["--unlock-code", args.unlock_code]
    if args.signed_blob is not None:
        try:
            encoded_blob = load_signed_blob(args.signed_blob.expanduser())
        except (OSError, ValueError) as error:
            print(f"error: cannot load signed blob: {error}", file=sys.stderr)
            return 6
        java_args += ["--signed-blob", encoded_blob]

    wants_report = args.report is not None
    action = (
        "probe"
        if args.probe
        else "status"
        if args.status
        else "policy"
        if args.policy
        else "settings"
        if args.settings
        else "test_info"
        if args.test_info
        else "auto_unlock"
        if args.auto_unlock
        else "lock_state"
        if args.lock_state
        else "local_unlock"
        if args.unlock_code is not None
        else "signed_blob"
    )
    requested_operator = "current" if args.auto_unlock else "0" if args.lock_state else "stock API"
    destination = report_path(args.report, project) if wants_report else None
    properties: dict[str, str] = {}
    tool_output = ""
    status_output = ""
    logcat_output = ""
    dmesg_output = ""
    logcat_process: subprocess.Popen[str] | None = None
    logcat_file = None

    try:
        if wants_report:
            properties = {
                "ro.product.device": get_property(adb, "ro.product.device"),
                "ro.build.version.release": get_property(adb, "ro.build.version.release"),
                "ro.build.version.incremental": get_property(adb, "ro.build.version.incremental"),
                "ro.build.version.security_patch": get_property(adb, "ro.build.version.security_patch"),
            }
            logcat_file = tempfile.TemporaryFile(mode="w+t", encoding="utf-8")
            logcat_process = subprocess.Popen(
                adb + [
                    "logcat", "-b", "radio", "-b", "main", "-b", "system",
                    "-v", "threadtime", "-T", "1",
                ],
                text=True,
                stdout=logcat_file,
                stderr=subprocess.STDOUT,
            )
            time.sleep(0.4)

        result = run(system_uid_command(adb, java_args), capture=wants_report)
        if wants_report:
            tool_output = result.stdout or ""
            if tool_output:
                print(tool_output, end="" if tool_output.endswith("\n") else "\n")
        if args.settings:
            retry_setting = run(
                adb + ["shell", "settings", "get", "global", "region_max_retry_time"],
                capture=True,
            )
            retry_value = retry_setting.stdout.strip()
            if retry_setting.returncode != 0 or retry_value in ("", "null"):
                retry_line = "configured-max-retry-time=10 (stock default; setting is unset)"
            else:
                retry_line = f"configured-max-retry-time={retry_value}"
            print(retry_line)
            if wants_report:
                tool_output += ("" if tool_output.endswith("\n") else "\n") + retry_line + "\n"
        if wants_report:
            time.sleep(2.5 if action in ("auto_unlock", "lock_state", "local_unlock", "signed_blob") else 0.3)
            status_result = run(
                system_uid_command(adb, ["--slot", str(args.slot), "--status"]),
                capture=True,
            )
            status_output = status_result.stdout or ""
            dmesg_result = run(adb + ["shell", "su", "-c", "dmesg"], capture=True)
            dmesg_output = dmesg_result.stdout or ""
        return_code = result.returncode
    finally:
        if logcat_process is not None:
            if logcat_process.poll() is None:
                logcat_process.terminate()
                try:
                    logcat_process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    logcat_process.kill()
                    logcat_process.wait(timeout=5)
        if logcat_file is not None:
            logcat_file.seek(0)
            logcat_output = logcat_file.read()
            logcat_file.close()
        if not args.keep:
            run(adb + ["shell", "rm", "-f", REMOTE_JAR], capture=True)

    if destination is not None:
        write_report(
            destination,
            action=action,
            slot=args.slot,
            requested_operator=requested_operator,
            payload_sha256=hashlib.sha256(payload.read_bytes()).hexdigest(),
            properties=properties,
            tool_output=tool_output,
            status_output=status_output,
            logcat_output=logcat_output,
            dmesg_output=dmesg_output,
        )
        print(f"Shareable diagnostic report: {destination}")
    return return_code


if __name__ == "__main__":
    raise SystemExit(main())
