#!/bin/bash
# Managed gh entrypoint for the DSHA proroot runtime.
# The CLI uses raw syscalls, so host-visible config, CA, and a libc CONNECT
# proxy must be supplied explicitly. Login still uses gh auth login --web;
# credentials remain in the gh hosts.yml until native credential storage lands.
set -u

ROOT_HOST="${DSHA_GH_HOST_ROOT:-}"
DEFAULT_GH="/opt/gh/bin/gh"
REAL_GH="${DSHA_GH_BIN:-$DEFAULT_GH}"
PROXY_JS="${DSHA_GH_PROXY_SCRIPT:-/usr/local/share/dsha/gh-proxy.mjs}"
STATE_DIR="${DSHA_GH_PROXY_STATE_DIR:-/tmp/dsha-gh-proxy}"
REQUESTED_PORT="${DSHA_GH_PROXY_PORT:-7891}"
PID_FILE="$STATE_DIR/pid"
PORT_FILE="$STATE_DIR/port"
LOG_FILE="$STATE_DIR/proxy.log"
LOCK_DIR="$STATE_DIR/lock"

# Managed gh release pin. This block must stay identical to
# tools/gh-cli/packages.lock.json; tools/test-gh-managed-shim.mjs fails when the
# two copies drift. Bump both together, never fetch a version outside this pin.
GH_VERSION="2.103.3"
GH_TARBALL_URL="https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_arm64.tar.gz"
GH_TARBALL_SHA256="ee32df2323c8847f5fac6cb6b4b69ca75828e9329a8f22f677021f7b3306ce63"
GH_BINARY_SHA256="a82a52e1bbe05bcd485f8cbbd5aa67e11a76fce6bdad394a2d179e1c24cfe367"
GH_BINARY_BYTES="38142112"
GH_MEMBER="gh_${GH_VERSION}_linux_arm64/bin/gh"
GH_INSTALL_LOCK="${DSHA_GH_BOOTSTRAP_LOCK:-${TMPDIR:-/tmp}/dsha-gh-bootstrap.lock}"
GH_BOOTSTRAP="${DSHA_GH_BOOTSTRAP:-1}"

if [ "${DSHA_GH_IDENTITY_NOTICE:-1}" = "1" ]; then
    echo "gh: requests use the SunsetRNE GitHub identity configured in this DSHA environment" >&2
fi

proxy_alive() {
    [ -s "$PID_FILE" ] || return 1
    [ -s "$PORT_FILE" ] || return 1
    local pid port
    pid="$(tr -dc '0-9' < "$PID_FILE")"
    port="$(tr -dc '0-9' < "$PORT_FILE")"
    [ -n "$pid" ] && [ -n "$port" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    if [ -r "/proc/$pid/cmdline" ]; then
        case "$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)" in
            *gh-proxy.mjs*) ;;
            *) return 1 ;;
        esac
    fi
    (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null
}

release_lock() {
    rm -f "$LOCK_DIR/owner" 2>/dev/null || true
    rmdir "$LOCK_DIR" 2>/dev/null || true
}

acquire_lock() {
    mkdir -p "$STATE_DIR" 2>/dev/null || return 1
    local i=0 owner
    while ! mkdir "$LOCK_DIR" 2>/dev/null; do
        owner=""
        [ -s "$LOCK_DIR/owner" ] && owner="$(tr -dc '0-9' < "$LOCK_DIR/owner")"
        if [ -z "$owner" ]; then
            # mkdir is the atomic lock; give the owner a short window to publish
            # its PID before deciding that an interrupted writer left a stale lock.
            i=$((i + 1))
            [ "$i" -lt 100 ] || { rmdir "$LOCK_DIR" 2>/dev/null || true; return 1; }
            sleep 0.05
            continue
        fi
        if ! kill -0 "$owner" 2>/dev/null; then
            rm -f "$LOCK_DIR/owner" 2>/dev/null || true
            rmdir "$LOCK_DIR" 2>/dev/null || true
            continue
        fi
        i=$((i + 1))
        [ "$i" -lt 100 ] || return 1
        sleep 0.05
    done
    printf '%s\n' "$$" >"$LOCK_DIR/owner"
    return 0
}

ensure_proxy() {
    proxy_alive && return 0
    acquire_lock || return 1
    if proxy_alive; then
        release_lock
        return 0
    fi
    rm -f "$PORT_FILE" "$PID_FILE"
    if ! command -v node >/dev/null 2>&1; then
        release_lock
        return 1
    fi
    nohup node "$PROXY_JS" --port "$REQUESTED_PORT" --state-dir "$STATE_DIR" \
        >>"$LOG_FILE" 2>&1 </dev/null &
    local pid=$!
    printf '%s\n' "$pid" >"$PID_FILE"
    local i=0
    while [ "$i" -lt 100 ]; do
        if proxy_alive; then
            release_lock
            return 0
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi
        sleep 0.05
        i=$((i + 1))
    done
    release_lock
    return 1
}

sha256_of() {
    [ -f "$1" ] || return 1
    command -v sha256sum >/dev/null 2>&1 || return 1
    sha256sum -- "$1" 2>/dev/null | cut -d' ' -f1
}

# The managed default binary is the only path this wrapper may install into.
# A custom DSHA_GH_BIN is user-owned: report it and never write there.
binary_ready() {
    [ -x "$REAL_GH" ] || return 1
    [ "$REAL_GH" = "$DEFAULT_GH" ] || return 0
    [ "$(stat -c %s -- "$REAL_GH" 2>/dev/null)" = "$GH_BINARY_BYTES" ] || return 1
    if [ "${DSHA_GH_VERIFY:-0}" = "1" ]; then
        [ "$(sha256_of "$REAL_GH")" = "$GH_BINARY_SHA256" ] || return 1
    fi
    return 0
}

manual_recovery() {
    local tarball_name="gh_${GH_VERSION}_linux_arm64.tar.gz"
    cat >&2 <<EOF
gh: managed GitHub CLI is missing or damaged: $REAL_GH
gh: nothing was executed and nothing was installed.
gh: install the pinned release (linux-arm64) with:
  curl -fsSLO --proto '=https' $GH_TARBALL_URL
  echo "$GH_TARBALL_SHA256  $tarball_name" | sha256sum -c -
  tar -xzf $tarball_name $GH_MEMBER
  install -D -m 0755 $GH_MEMBER $REAL_GH
gh: or set DSHA_GH_BIN to an existing gh binary.
gh: automatic bootstrap is controlled by DSHA_GH_BOOTSTRAP (1=on, 0=off).
EOF
}

release_install_lock() {
    rmdir "$GH_INSTALL_LOCK" 2>/dev/null || true
}

# Fetch only the pinned tarball, verify it, extract only the pinned member,
# verify that member, then publish atomically. ANY mismatch installs nothing.
bootstrap_gh() {
    if ! command -v curl >/dev/null 2>&1 \
        || ! command -v tar >/dev/null 2>&1 \
        || ! command -v sha256sum >/dev/null 2>&1 \
        || ! command -v install >/dev/null 2>&1; then
        echo "gh: curl, tar, sha256sum and install are required to bootstrap gh; nothing was installed" >&2
        return 1
    fi
    local waited=0
    mkdir -p "$(dirname "$REAL_GH")" 2>/dev/null || return 1
    mkdir -p "$(dirname "$GH_INSTALL_LOCK")" 2>/dev/null || true
    while ! mkdir "$GH_INSTALL_LOCK" 2>/dev/null; do
        waited=$((waited + 1))
        [ "$waited" -lt 600 ] || return 1
        sleep 0.05
    done
    # Another gh invocation may have finished the install while we waited.
    if binary_ready; then
        release_install_lock
        return 0
    fi
    local tmp tarball src actual rc=1
    tmp="$(mktemp -d "${TMPDIR:-/tmp}/dsha-gh-install.XXXXXX")" || {
        release_install_lock
        return 1
    }
    tarball="$tmp/gh.tar.gz"
    echo "gh: installing pinned GitHub CLI v${GH_VERSION} into $REAL_GH" >&2
    if ! curl -fsSL --proto '=https' --tlsv1.2 -o "$tarball" "$GH_TARBALL_URL"; then
        echo "gh: could not download $GH_TARBALL_URL; nothing was installed" >&2
    elif [ "$(sha256_of "$tarball")" != "$GH_TARBALL_SHA256" ]; then
        echo "gh: downloaded archive checksum mismatch; nothing was installed" >&2
    elif ! tar -xzf "$tarball" -C "$tmp" --no-same-owner "$GH_MEMBER" 2>/dev/null; then
        echo "gh: pinned archive does not contain $GH_MEMBER; nothing was installed" >&2
    else
        src="$tmp/$GH_MEMBER"
        actual="$(sha256_of "$src")"
        if [ "$actual" != "$GH_BINARY_SHA256" ] \
            || [ "$(stat -c %s -- "$src" 2>/dev/null)" != "$GH_BINARY_BYTES" ]; then
            echo "gh: extracted binary checksum mismatch; nothing was installed" >&2
        elif install -m 0755 "$src" "$REAL_GH.tmp.$$" && mv -f "$REAL_GH.tmp.$$" "$REAL_GH"; then
            rc=0
        else
            rm -f "$REAL_GH.tmp.$$"
            echo "gh: could not publish $REAL_GH; nothing was installed" >&2
        fi
    fi
    rm -rf "$tmp"
    release_install_lock
    if [ "$rc" = "0" ]; then
        echo "gh: installed pinned GitHub CLI v${GH_VERSION} ($GH_BINARY_SHA256)" >&2
    fi
    return "$rc"
}

ensure_binary() {
    binary_ready && return 0
    if [ "$REAL_GH" != "$DEFAULT_GH" ] || [ "$GH_BOOTSTRAP" = "0" ]; then
        manual_recovery
        return 1
    fi
    if bootstrap_gh && binary_ready; then return 0; fi
    manual_recovery
    return 1
}

if ! ensure_binary; then
    exit 127
fi

if ensure_proxy; then
    PROXY_PORT="$(tr -dc '0-9' < "$PORT_FILE")"
    export HTTPS_PROXY="http://127.0.0.1:${PROXY_PORT}"
    export HTTP_PROXY="http://127.0.0.1:${PROXY_PORT}"
    export https_proxy="$HTTPS_PROXY"
    export http_proxy="$HTTP_PROXY"
    export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}"
    export no_proxy="$NO_PROXY"
else
    echo "gh: local GitHub proxy could not start; see $LOG_FILE" >&2
fi

if [ -z "${GH_CONFIG_DIR:-}" ]; then
    if [ -n "$ROOT_HOST" ]; then
        export GH_CONFIG_DIR="$ROOT_HOST/root/.config/gh"
    else
        export GH_CONFIG_DIR="/root/.config/gh"
    fi
elif [ -n "$ROOT_HOST" ] && [ "$GH_CONFIG_DIR" = "/root/.config/gh" ]; then
    export GH_CONFIG_DIR="$ROOT_HOST/root/.config/gh"
fi

# gh is a static Go binary: it bypasses the proroot path translation, so only
# host paths reach its CA, config and file arguments. This wrapper runs
# translated, so its -f checks only see the view proroot binds into the guest
# (the canonical rootfs path). Android exposes one data tree through both
# /data/user/0 and /data/data: try the alternate view before failing loudly.
# Silently falling back to the stale system bundle would turn a host-root
# problem into a misleading x509 error.
alternate_host_root() {
    local root="$1" rest
    case "$root" in
        /data/user/0/*)
            rest="${root#/data/user/0/}"
            printf '/data/data/%s' "$rest"
            ;;
        /data/data/*)
            rest="${root#/data/data/}"
            printf '/data/user/0/%s' "$rest"
            ;;
        *) return 1 ;;
    esac
}

if [ -n "$ROOT_HOST" ]; then
    if [ -n "${DSHA_GH_CA:-}" ]; then
        if [ ! -f "$DSHA_GH_CA" ]; then
            echo "gh: DSHA_GH_CA points to a missing CA bundle: $DSHA_GH_CA" >&2
            exit 1
        fi
        export SSL_CERT_FILE="$DSHA_GH_CA"
    else
        CA_FILE="$ROOT_HOST/usr/local/share/dsha/ca-certificates.crt"
        if [ ! -f "$CA_FILE" ]; then
            ALT_ROOT="$(alternate_host_root "$ROOT_HOST" 2>/dev/null || true)"
            CA_FILE=""
            if [ -n "$ALT_ROOT" ] \
                && [ -f "$ALT_ROOT/usr/local/share/dsha/ca-certificates.crt" ]; then
                CA_FILE="$ALT_ROOT/usr/local/share/dsha/ca-certificates.crt"
            fi
        fi
        if [ -z "$CA_FILE" ]; then
            cat >&2 <<EOF
gh: managed CA bundle is missing for this runtime root: $ROOT_HOST
gh: also tried the alternate /data/user/0 <-> /data/data view.
gh: refusing to fall back to the stale system CA bundle; api.github.com TLS would fail.
gh: repair the runtime from the maintenance page, or set DSHA_GH_CA to a valid PEM bundle.
EOF
            exit 1
        fi
        export SSL_CERT_FILE="$CA_FILE"
    fi
fi

host_path() {
    local p="$1"
    [ -n "$ROOT_HOST" ] || { printf '%s' "$p"; return; }
    case "$p" in
        "$ROOT_HOST"/*) printf '%s' "$p"; return ;;
        /*) p="$(realpath -m -- "$p")" ;;
        *) p="$(realpath -m -- "$PWD/$p")" ;;
    esac
    printf '%s%s' "$ROOT_HOST" "$p"
}

rewrite_arg() {
    local a="$1"
    [ -n "$ROOT_HOST" ] || { printf '%s' "$a"; return; }
    case "$a" in
        "$ROOT_HOST"/*|*://*|-*|'') printf '%s' "$a"; return ;;
        *=@*)
            local k="${a%%=@*}" f="${a#*=@}"
            if [ -f "$f" ]; then printf '%s=@%s' "$k" "$(host_path "$f")"; return; fi
            ;;
        @*)
            local f="${a#@}"
            if [ -f "$f" ]; then printf '@%s' "$(host_path "$f")"; return; fi
            ;;
    esac
    local base="${a%%#*}" label=""
    [ "$base" != "$a" ] && label="#${a#*#}"
    if [ -f "$base" ]; then printf '%s%s' "$(host_path "$base")" "$label"; return; fi
    printf '%s' "$a"
}

if [ "${DSHA_GH_RAW:-0}" != "1" ]; then
    args=()
    has_repo=0
    for a in "$@"; do
        case "$a" in -R|--repo|--repo=*|-R?*) has_repo=1 ;; esac
        args+=("$(rewrite_arg "$a")")
    done
    case "${1:-}" in
        pr)
            if [ "${2:-}" = "checkout" ]; then
                echo "gh: pr checkout needs git, which cannot run through this proroot adapter; use the host git checkout flow" >&2
                exit 2
            fi
            ;;
        repo)
            if [ "${2:-}" = "clone" ]; then
                echo "gh: repo clone needs git, which cannot run through this proroot adapter; use git clone from the host-visible workspace" >&2
                exit 2
            fi
            ;;
        release|run|issue|workflow|cache|secret|variable|label|ruleset|attestation|browse)
            if [ "$has_repo" = 0 ] && [ -z "${GH_REPO:-}" ] && command -v git >/dev/null 2>&1; then
                url="$(git remote get-url origin 2>/dev/null || true)"
                slug="$(printf '%s' "$url" | sed -nE 's#^(git@github\.com:|https://([^@/]+@)?github\.com/)([^/]+/[^/]+)/?$#\3#p' | sed -E 's#\.git$##')"
                [ -n "$slug" ] && export GH_REPO="$slug"
            fi
            ;;
    esac
    exec "$REAL_GH" "${args[@]}"
fi

exec "$REAL_GH" "$@"
