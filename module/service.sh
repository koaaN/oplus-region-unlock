#!/system/bin/sh

# Deliberately opt-in: installing the module only installs the command.
# Enable one AUTO_UNLOCK attempt after each boot with:
#   touch /data/adb/region-unlock/auto
CONFIG_DIR=/data/adb/region-unlock
[ -f "$CONFIG_DIR/auto" ] || exit 0

mkdir -p "$CONFIG_DIR"
chmod 0700 "$CONFIG_DIR"

attempt=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$attempt" -lt 150 ]; do
    sleep 2
    attempt=$((attempt + 1))
done

/system/bin/region-unlock --wait 60 >"$CONFIG_DIR/last.log" 2>&1
chmod 0600 "$CONFIG_DIR/last.log"
