#!/system/bin/sh

# KernelSU calls emulated-soft-reboot.sh before stop/post-fs-data/start/service.
# post-fs-data.sh and service.sh also invoke this script idempotently to cover
# upgrades from older builds that lacked the pre-stop hook.
[ "$KSU" = true ] || [ "$APATCH" = true ] || [ "$1" = "--force" ] || exit 0
MODDIR=$(CDPATH= cd -- "${0%/*}" && pwd -P) || exit 1

log_warn() {
    if command -v log >/dev/null 2>&1; then
        log -p w -t "VectorSRX" "$*"
    fi
    echo "Vector-SRX: $*" >&2
}

daemon_identity() (
    pid=$1
    case "$pid" in ''|*[!0-9]*) return 1 ;; esac
    [ "$pid" -gt 1 ] || return 1
    [ "$pid" -ne "$$" ] || return 1
    cwd=$(readlink "/proc/$pid/cwd" 2>/dev/null) || return 1
    case "$cwd" in "$MODDIR"|"$MODDIR (deleted)") ;; *) return 1 ;; esac
    executable=$(readlink "/proc/$pid/exe" 2>/dev/null) || return 1
    case "$executable" in */app_process|*/app_process32|*/app_process64) ;; *) return 1 ;; esac
    cmdline=$(tr '\000' ' ' 2>/dev/null < "/proc/$pid/cmdline") || return 1
    case "$cmdline" in
        vectord*|*/vectord*|lspd*|*/lspd*|*org.matrix.vector.daemon.VectorDaemon*) return 0 ;;
        *) return 1 ;;
    esac
)

process_start() (
    IFS= read -r record < "/proc/$1/stat" || return 1
    record=${record##*) }
    set -f
    set -- $record
    [ "$#" -ge 20 ] || return 1
    shift 19
    printf '%s\n' "$1"
)

daemon_candidates() {
    ls -ld /proc/[0-9]*/cwd 2>/dev/null | awk -v dir="$MODDIR" '
        index($0, " -> ") {
            split($0, sides, " -> ")
            if (sides[2] != dir && sides[2] != dir " (deleted)") next
            n = split(sides[1], fields, /[[:space:]]+/)
            split(fields[n], path, "/")
            if (path[2] == "proc" && path[3] ~ /^[0-9]+$/) print path[3]
        }'
}

stop_daemons() {
    candidates=$(daemon_candidates) || return 1
    for pid in $candidates; do
        start=$(process_start "$pid" 2>/dev/null) || continue
        daemon_identity "$pid" 2>/dev/null || continue
        [ "$(process_start "$pid" 2>/dev/null)" = "$start" ] || continue
        kill -9 "$pid" 2>/dev/null || {
            daemon_identity "$pid" 2>/dev/null && return 1
        }
        tries=0
        while [ "$(process_start "$pid" 2>/dev/null)" = "$start" ]; do
            IFS= read -r record < "/proc/$pid/stat" || break
            case "${record##*) }" in Z\ *|X\ *) break ;; esac
            [ "$tries" -lt 20 ] || return 1
            sleep 0.05
            tries=$((tries + 1))
        done
    done
}

visible_mount() (
    exec 3< "$1" || return 1
    mount_id=
    while read -r key value rest; do
        [ "$key" != mnt_id: ] || mount_id=$value
    done < /proc/self/fdinfo/3
    case "$mount_id" in ''|*[!0-9]*) return 1 ;; esac
    printf '%s\n' "$mount_id"
)

cleanup_target() {
    target=$1
    [ -e "$target" ] || return 0
    layers=0
    while :; do
        mount_id=$(visible_mount "$target") || return 1
        root=$(awk -v id="$mount_id" -v target="$target" '
            $1 == id && $5 == target { print $4; found = 1; exit }
            END { if (!found) exit 1 }' /proc/self/mountinfo) || return 0

        root=${root%\\040(deleted)}
        root=${root%//deleted}
        case "$root" in
            "$MODDIR/bin/dex2oat32"|"$MODDIR/bin/dex2oat64"|\
            "${MODDIR#/data}/bin/dex2oat32"|"${MODDIR#/data}/bin/dex2oat64") ;;
            *) return 0 ;;
        esac
        [ "$layers" -lt 32 ] || return 1
        umount "$target" 2>/dev/null || umount -l "$target" || return 1
        next_id=$(visible_mount "$target") || return 1
        [ "$next_id" != "$mount_id" ] || return 1
        layers=$((layers + 1))
    done
}

stop_daemons || log_warn "old daemon did not exit cleanly before soft-reboot handover"

init_ns=$(readlink /proc/1/ns/mnt 2>/dev/null)
self_ns=$(readlink /proc/self/ns/mnt 2>/dev/null)
if [ -n "$init_ns" ] && [ "$init_ns" = "$self_ns" ]; then
    for target in \
        /apex/com.android.runtime/bin/dex2oat \
        /apex/com.android.runtime/bin/dex2oatd \
        /apex/com.android.runtime/bin/dex2oat64 \
        /apex/com.android.runtime/bin/dex2oatd64 \
        /apex/com.android.art/bin/dex2oat32 \
        /apex/com.android.art/bin/dex2oatd32 \
        /apex/com.android.art/bin/dex2oat64 \
        /apex/com.android.art/bin/dex2oatd64; do
        cleanup_target "$target" || log_warn "could not detach stale compiler mount: $target"
    done
    # Also detach any accidentally leaked /data/resource-cache shadow from older builds
    if awk -v dir="$MODDIR/.resource-cache" -v rel="${MODDIR#/data}/.resource-cache" '
        $5 == "/data/resource-cache" && ($4 == dir || $4 == rel || / - tmpfs /) { found = 1; exit }
        END { exit !found }' /proc/self/mountinfo 2>/dev/null; then
        umount /data/resource-cache 2>/dev/null || umount -l /data/resource-cache 2>/dev/null || true
    fi
fi

exit 0
