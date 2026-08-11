#!/system/bin/sh

ui_print "- Installing the region-unlock command"
set_perm "$MODPATH/system/bin/region-unlock" 0 0 0755
set_perm "$MODPATH/system/etc/oplus-region-unlock.jar" 0 0 0644
set_perm "$MODPATH/service.sh" 0 0 0755
