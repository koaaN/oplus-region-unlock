#!/usr/bin/env python3
"""Push and run the Oplus region-unlock Binder client over ADB."""

from __future__ import annotations

import argparse
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
    r"region.?lock|sale.?unlock|updateRegionlockStatus|RIL_REQUEST_UPDATE_REGION_LOCK_STATUS|"
    r"IRadioStable|OplusRadio|\b6050\b|\b7012\b|avc:\s*denied",
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


def operator(value: str) -> str:
    if value == "auto":
        return value
    try:
        number = int(value, 0)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be auto or an integer") from error
    if not 0 <= number <= 255:
        raise argparse.ArgumentTypeError("must be from 0 through 255")
    return str(number)


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


def java_command(arguments: list[str]) -> str:
    return (
        f"CLASSPATH={shlex.quote(REMOTE_JAR)} "
        "app_process /system/bin dev.op15.regionunlock.RegionUnlock "
        + " ".join(shlex.quote(value) for value in arguments)
    )


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
{focused_logs(dmesg_output)}
"""
    destination.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="adb executable (default: adb)")
    parser.add_argument("--device", help="adb serial")
    parser.add_argument("--slot", type=int, choices=(0, 1), default=0)
    parser.add_argument("--operator", type=operator, default="auto", metavar="AUTO|BYTE")
    parser.add_argument("--wait", type=int, default=15, metavar="SECONDS")
    parser.add_argument("--probe", action="store_true", help="only validate the service and descriptor")
    parser.add_argument("--status", action="store_true", help="only print the phone process's cached state")
    parser.add_argument(
        "--sale-unlock",
        action="store_true",
        help="experimentally request SALE_UNLOCKED state 2 instead of AUTO_UNLOCK state 0",
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

    if not 0 <= args.wait <= 300:
        parser.error("--wait must be from 0 through 300")
    if sum((args.probe, args.status, args.sale_unlock)) > 1:
        parser.error("--probe, --status, and --sale-unlock are mutually exclusive")
    if args.sale_unlock and args.operator != "auto":
        parser.error("--operator cannot be used with --sale-unlock; sale operator is fixed at 2")

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

    pushed = run(adb + ["push", str(payload), REMOTE_JAR])
    if pushed.returncode != 0:
        return pushed.returncode

    java_args = [
        "--slot", str(args.slot),
        "--operator", str(args.operator),
        "--wait", str(args.wait),
    ]
    if args.probe:
        java_args.append("--probe")
    if args.status:
        java_args.append("--status")
    if args.sale_unlock:
        java_args.append("--sale-unlock")
    command = java_command(java_args)

    wants_report = args.report is not None
    action = (
        "probe"
        if args.probe
        else "status"
        if args.status
        else "sale_unlock"
        if args.sale_unlock
        else "auto_unlock"
    )
    requested_operator = "2 (sale)" if args.sale_unlock else args.operator
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

        result = run(adb + ["shell", "su", "-c", command], capture=wants_report)
        if wants_report:
            tool_output = result.stdout or ""
            if tool_output:
                print(tool_output, end="" if tool_output.endswith("\n") else "\n")
            time.sleep(2.5 if action in ("auto_unlock", "sale_unlock") else 0.3)
            status_result = run(
                adb + ["shell", "su", "-c", java_command(["--status"])],
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
