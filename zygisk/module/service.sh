#!/system/bin/sh
# Extract the directory path and change directory
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1

# KernelSU replays service.sh after an emulated soft reboot. Clean up any old
# surviving daemon and stale compiler mounts before launching the replacement.
if [ "$KSU" = true ]; then
  sh "$MODDIR/emulated-soft-reboot.sh"
fi

# The daemon shadows /data/resource-cache only inside a confirmed private mount
# namespace (VECTOR_SHADOW_OK=1). Leaking a shadow into the global mount
# namespace hides framework-res RRO idmaps (such as android.ext.services on GMS
# ROMs) and causes a system_server PMS boot loop across soft reboots.
if unshare -m true 2>/dev/null; then
  unshare -m /system/bin/sh -c '
      if mount -o rslave none / 2>/dev/null; then
        VECTOR_SHADOW_OK=1 exec "$0" "$@"
      else
        echo "vector: mount namespace stays shared; daemon starts without resource-cache shadow" >&2
        VECTOR_NO_SHADOW=1 exec "$0" "$@"
      fi
  ' "$MODDIR/daemon" "$@" &
else
  echo "vector: no private mount namespace available; daemon starts without resource-cache shadow" >&2
  VECTOR_NO_SHADOW=1 "$MODDIR/daemon" "$@" &
fi
