#!/system/bin/sh

ui_print "- Installing the OnePlus 13/15 one-shot region unlocker"
ui_print "- Reboot once to start the unlock"
ui_print "- The phone will automatically reboot one more time"
ui_print "- The module then removes itself; its log is kept at:"
ui_print "  /data/adb/region-unlock/oneshot.log"

CONFIG_DIR=/data/adb/region-unlock
mkdir -p "$CONFIG_DIR"
rm -f "$CONFIG_DIR/oneshot-attempted"
chmod 0700 "$CONFIG_DIR"

set_perm "$MODPATH/system/bin/region-unlock" 0 0 0755
set_perm "$MODPATH/system/etc/oplus-region-unlock.jar" 0 0 0644
set_perm "$MODPATH/service.sh" 0 0 0755
