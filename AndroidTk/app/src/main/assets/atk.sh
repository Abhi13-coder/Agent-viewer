#!/system/bin/sh
# atk - talks directly to Termux's live repo, extracts real .deb files,
# runs binaries via the system linker. Pure shell, no rebuild ever needed
# to fix logic bugs — only to add new bundled native tools.

ATK_DIR="$HOME/atk"
BIN_DIR="$ATK_DIR/bin"
LIB_DIR="$ATK_DIR/lib"
CACHE_DIR="$ATK_DIR/cache"
PKG_DIR="$ATK_DIR/pkg"
TMP_DIR="$ATK_DIR/tmp"
INDEX_FILE="$ATK_DIR/Packages"
INSTALLED_DB="$ATK_DIR/installed.txt"

mkdir -p "$BIN_DIR" "$LIB_DIR" "$CACHE_DIR" "$PKG_DIR" "$TMP_DIR"
export TMPDIR="$TMP_DIR"

REPO_BASE="https://packages-cf.termux.dev/apt/termux-main"

detect_arch() {
    abi=$(getprop ro.product.cpu.abi 2>/dev/null)
    case "$abi" in
        arm64-v8a) echo "aarch64" ;;
        armeabi-v7a|armeabi) echo "arm" ;;
        x86_64) echo "x86_64" ;;
        x86) echo "i686" ;;
        *) echo "arm" ;;
    esac
}
ARCH=$(detect_arch)

if [ "$ARCH" = "aarch64" ]; then LINKER="/system/bin/linker64"; else LINKER="/system/bin/linker"; fi

# Prefer a real curl (proper TLS) if bundled; fall back to busybox's
# minimal wget (works for plain HTTP, fails TLS handshakes with some hosts).
fetch() {
    url="$1"; out="$2"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$out" "$url"
    else
        busybox wget -q -O "$out" "$url"
    fi
}

refresh_index() {
    echo "atk: fetching package index ($ARCH)..."
    fetch "$REPO_BASE/dists/stable/main/binary-$ARCH/Packages.gz" "$INDEX_FILE.gz" || { echo "atk: index download failed"; return 1; }
    busybox gunzip -f "$INDEX_FILE.gz"
}

find_package() {
    awk -v want="$1" '
        BEGIN { RS=""; FS="\n" }
        {
            name=""; ver=""; fname=""; deps=""
            for (i=1; i<=NF; i++) {
                line=$i
                if (line ~ /^Package: /) name=substr(line,10)
                else if (line ~ /^Version: /) ver=substr(line,10)
                else if (line ~ /^Filename: /) fname=substr(line,11)
                else if (line ~ /^Depends: /) deps=substr(line,10)
            }
            if (name == want) { print name "\t" ver "\t" fname "\t" deps; exit }
        }
    ' "$INDEX_FILE"
}

clean_depends() {
    echo "$1" | tr ',' '\n' | sed 's/|.*//' | sed 's/(.*//' | sed 's/^ *//;s/ *$//' | grep -v '^$'
}

resolve_and_queue() {
    pkgname="$1"
    grep -qx "$pkgname" "$SEEN_FILE" 2>/dev/null && return
    echo "$pkgname" >> "$SEEN_FILE"

    row=$(find_package "$pkgname")
    if [ -z "$row" ]; then echo "atk: warning: '$pkgname' not found, skipping"; return; fi

    deps=$(echo "$row" | cut -f4)
    for dep in $(clean_depends "$deps"); do resolve_and_queue "$dep"; done

    echo "$row" >> "$QUEUE_FILE"
}

download_deb() {
    base=$(basename "$1")
    dest="$CACHE_DIR/$base"
    if [ ! -f "$dest" ]; then
        echo "atk: downloading $base"
        fetch "$REPO_BASE/$1" "$dest" || { echo "atk: download failed: $1"; return 1; }
    fi
    echo "$dest"
}

extract_deb() {
    debfile="$1"; pkgname="$2"
    extractdir="$PKG_DIR/$pkgname"
    rm -rf "$extractdir"; mkdir -p "$extractdir"
    ( cd "$extractdir" && busybox ar -x "$debfile" ) || { echo "atk: ar extract failed"; return 1; }

    datatar=$(ls "$extractdir"/data.tar.* 2>/dev/null | head -n1)
    [ -z "$datatar" ] && { echo "atk: no data.tar.* for $pkgname"; return 1; }

    datadir="$extractdir/data"; mkdir -p "$datadir"
    case "$datatar" in
        *.xz)  busybox unxz -c "$datatar" | busybox tar -xf - -C "$datadir" ;;
        *.gz)  busybox tar -xzf "$datatar" -C "$datadir" ;;
        *.zst) echo "atk: $pkgname uses zstd — unsupported by this busybox"; return 1 ;;
        *)     busybox tar -xf "$datatar" -C "$datadir" ;;
    esac
    echo "$datadir"
}

flatten() {
    find "$1" -type f 2>/dev/null | while read -r f; do
        case "$f" in
            *.so|*.so.*) cp -f "$f" "$LIB_DIR/$(basename "$f")"; chmod +x "$LIB_DIR/$(basename "$f")" ;;
            */bin/*)     cp -f "$f" "$BIN_DIR/$(basename "$f")"; chmod +x "$BIN_DIR/$(basename "$f")" ;;
        esac
    done
}

make_wrapper() {
    toolname="$1"
    wrapper="$HOME/bin/$toolname"
    printf '%s\n' \
        "#!/system/bin/sh" \
        "export LD_LIBRARY_PATH=\"$LIB_DIR:\$LD_LIBRARY_PATH\"" \
        "exec $LINKER \"$BIN_DIR/$toolname\" \"\$@\"" \
        > "$wrapper"
    chmod +x "$wrapper" 2>/dev/null
}

cmd_install() {
    name="$1"
    [ -z "$name" ] && { echo "usage: atk install <name>"; return 1; }
    [ -f "$INDEX_FILE" ] || refresh_index

    SEEN_FILE="$TMP_DIR/seen.$$"; QUEUE_FILE="$TMP_DIR/queue.$$"
    : > "$SEEN_FILE"; rm -f "$QUEUE_FILE"

    resolve_and_queue "$name"

    if [ ! -f "$QUEUE_FILE" ]; then echo "atk: '$name' not found, nothing to install"; return 1; fi

    while IFS="$(printf '\t')" read -r pname pver pfile pdeps; do
        echo "atk: === $pname $pver ==="
        deb=$(download_deb "$pfile") || continue
        datadir=$(extract_deb "$deb" "$pname") || continue
        flatten "$datadir"
        echo "$pname	$pver" >> "$INSTALLED_DB"
    done < "$QUEUE_FILE"

    rm -f "$QUEUE_FILE" "$SEEN_FILE"

    if [ -f "$BIN_DIR/$name" ]; then
        make_wrapper "$name"
        echo "atk: '$name' installed — run it directly as: $name"
    else
        echo "atk: installed $name's deps, but no bin/$name found — check $PKG_DIR/$name"
    fi
}

case "${1:-}" in
    install)   shift; cmd_install "$1" ;;
    refresh)   refresh_index && echo "atk: index refreshed" ;;
    installed) shift; [ -f "$INSTALLED_DB" ] && grep -q "^$1	" "$INSTALLED_DB" && echo yes || echo no ;;
    *) echo "usage: atk install <name> | atk refresh | atk installed <name>" ;;
esac
