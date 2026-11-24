#!/system/bin/sh

# On the first emulated soft reboot after upgrading from a build without
# emulated-soft-reboot.sh, KernelSU promotes modules_update before post-fs-data
# and restarts Android after this stage returns. Run cleanup here as well.
[ "$KSU" = true ] || exit 0
MODDIR="${0%/*}"
exec sh "$MODDIR/emulated-soft-reboot.sh"
