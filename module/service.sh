#!/system/bin/sh

# One-shot lifecycle:
#   1. Wait for the first completed boot after installation.
#   2. Send the stock OnePlus AUTO_UNLOCK request exactly once.
#   3. Mark this module for removal.
#   4. Reboot so the modem state is reloaded and the root manager removes us.

MODULE_DIR=/data/adb/modules/oplus-region-unlock
CONFIG_DIR=/data/adb/region-unlock
ATTEMPT_MARKER=$CONFIG_DIR/oneshot-attempted
LOG_FILE=$CONFIG_DIR/oneshot.log

mkdir -p "$CONFIG_DIR"
chmod 0700 "$CONFIG_DIR"

# Never send a second modem request if a previous run was interrupted after the
# command. Still ensure that the root manager removes the module next reboot.
if [ -f "$ATTEMPT_MARKER" ]; then
    touch "$MODULE_DIR/remove"
    exit 0
fi

touch "$ATTEMPT_MARKER"
chmod 0600 "$ATTEMPT_MARKER"

exec >"$LOG_FILE" 2>&1
chmod 0600 "$LOG_FILE"

PROJECT_ID=$(getprop ro.boot.prjname)
if [ -z "$PROJECT_ID" ]; then
    PROJECT_ID=$(getprop ro.boot.project_name)
fi

echo "OnePlus region unlock: one-shot boot run"
echo "started=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "build=$(getprop ro.build.version.incremental)"
echo "project-id=$PROJECT_ID"

case "$PROJECT_ID" in
    23821|24831|24851)
        echo "device-supported=1"
        ;;
    *)
        echo "error: unsupported project; expected 23821 (OnePlus 13), 24831 (OnePlus 15), or 24851 (OnePlus Ace 6)"
        touch "$MODULE_DIR/remove"
        echo "module-removal=pending-next-reboot"
        exit 1
        ;;
esac

attempt=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$attempt" -lt 150 ]; do
    sleep 2
    attempt=$((attempt + 1))
done

if [ "$(getprop sys.boot_completed)" != "1" ]; then
    echo "error: Android did not finish booting within 300 seconds"
    touch "$MODULE_DIR/remove"
    echo "module-removal=pending-next-reboot"
    exit 1
fi

echo "boot-completed=1"

# Boot completion can precede the vendor radio endpoint by a few seconds. Probe
# read-only until both the framework manager and subsystem-radio HAL are ready.
radio_attempt=0
radio_ready=0
while [ "$radio_attempt" -lt 12 ]; do
    echo "radio-probe-attempt=$((radio_attempt + 1))"
    if /system/bin/region-unlock --probe --wait 10; then
        radio_ready=1
        break
    fi
    radio_attempt=$((radio_attempt + 1))
    sleep 5
done

if [ "$radio_ready" != "1" ]; then
    echo "error: OnePlus region-lock subsystem did not become ready"
    result=1
else
    echo "action=AUTO_UNLOCK"
    /system/bin/region-unlock --auto-unlock --wait 60
    result=$?
fi
echo "command-exit=$result"

# Magisk, KernelSU, and APatch honor this standard module removal marker during
# the next boot. The log lives outside the module so it survives removal.
touch "$MODULE_DIR/remove"
echo "module-removal=pending"

if [ "$result" -eq 0 ]; then
    echo "result=command-accepted"
else
    echo "result=command-failed"
fi
echo "follow-up-reboot=scheduled"
echo "finished=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sync
sleep 8
/system/bin/reboot
