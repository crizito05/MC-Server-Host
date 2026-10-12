#!/bin/bash
set -euo pipefail

PROJECT_PATH="${PROJECT_PATH:-/mnt/d/AndroidStudio/Projects/MinecraftServer}"
JDK_SERIES="${JDK_SERIES:-openjdk-17}"
ABI="aarch64"
JNI_ABI="arm64-v8a"
WORKDIR="${WORKDIR:-$HOME/termux-jdk-build}"
REPO="https://packages.termux.dev/apt/termux-main/pool/main"

EXTRA_PACKAGES=(
  "z/zlib"
  "liba/libandroid-shmem"
  "liba/libandroid-spawn"
)

log() { printf '\n=== %s ===\n' "$1"; }
die() { printf 'ERRORE: %s\n' "$1" >&2; exit 1; }

resolve_deb_url() {
  local dir="$1" html name
  html="$(curl -fsSL "$REPO/$dir/")" || die "indice non raggiungibile: $REPO/$dir/"
  name="$(printf '%s' "$html" | grep -oE 'href="[^"]+_'${ABI}'\.deb"' | sed 's/href="//; s/"$//' | sort -V | tail -n 1)"
  [ -n "$name" ] || die "nessun pacchetto ${ABI} in $dir"
  printf '%s' "$REPO/$dir/$name"
}

download_deb() {
  local dir="$1" out="$2" url size
  url="$(resolve_deb_url "$dir")"
  printf '  %s\n' "$(basename "$url")"
  curl -fL --retry 3 --retry-delay 2 -o "$out" "$url"
  size="$(stat -c%s "$out")"
  [ "$size" -gt 100000 ] || die "download sospetto, $size byte: $url"
  file "$out" | grep -q "Debian binary package" || die "$out non e un pacchetto .deb valido"
}

extract_deb() {
  local deb="$1" dest="$2" data
  mkdir -p "$dest"
  ( cd "$dest" && ar x "$deb" )
  data="$(find "$dest" -maxdepth 1 -name 'data.tar.*' -print -quit)"
  [ -n "$data" ] || die "membro data.tar.* non trovato in $deb"
  case "$data" in
    *.xz)  tar -xJf "$data" -C "$dest" ;;
    *.gz)  tar -xzf "$data" -C "$dest" ;;
    *.zst) tar --zstd -xf "$data" -C "$dest" ;;
    *)     die "compressione non gestita: $data" ;;
  esac
  [ -d "$dest/data/data/com.termux/files/usr" ] || die "struttura Termux assente dopo l'estrazione di $deb"
}

log "Controllo dipendenze"
for cmd in curl ar tar file stat sort find; do
  command -v "$cmd" >/dev/null || die "manca $cmd"
done
printf 'ok\n'

log "Preparazione ambiente"
rm -rf "$WORKDIR"
mkdir -p "$WORKDIR/dl" "$WORKDIR/jniLibs/$JNI_ABI"
cd "$WORKDIR"
printf 'workdir: %s\n' "$WORKDIR"
printf 'serie JDK: %s\n' "$JDK_SERIES"

log "Download"
download_deb "o/$JDK_SERIES" "$WORKDIR/dl/$JDK_SERIES.deb"
for pkg in "${EXTRA_PACKAGES[@]}"; do
  download_deb "$pkg" "$WORKDIR/dl/$(basename "$pkg").deb"
done

log "Estrazione"
MERGED="$WORKDIR/merged"
mkdir -p "$MERGED"
for deb in "$WORKDIR"/dl/*.deb; do
  printf '  %s\n' "$(basename "$deb")"
  root="$WORKDIR/root_$(basename "$deb" .deb)"
  extract_deb "$deb" "$root"
  cp -a "$root/data/data/com.termux/files/usr/." "$MERGED/"
done
[ -d "$MERGED/lib/jvm" ] || die "nessuna JVM nell'albero unito"
printf 'file totali: %s\n' "$(find "$MERGED" -type f | wc -l)"

log "Rinomina dei file ELF"
MAPPING="$WORKDIR/mapping.json"
printf '{\n' > "$MAPPING"
COUNT=0
while IFS= read -r f; do
  file "$f" | grep -q "ELF" || continue
  relpath="${f#$MERGED/}"
  base="$(basename "$f")"
  case "$base" in
    lib*.so) safename="$base" ;;
    *)       safename="lib_$(printf '%s' "$relpath" | tr '/' '_').so" ;;
  esac
  cp "$f" "$WORKDIR/jniLibs/$JNI_ABI/$safename"
  chmod 755 "$WORKDIR/jniLibs/$JNI_ABI/$safename"
  [ "$COUNT" -gt 0 ] && printf ',\n' >> "$MAPPING"
  printf '  "%s": "%s"' "$relpath" "$safename" >> "$MAPPING"
  COUNT=$((COUNT + 1))
done < <(find "$MERGED" -type f)
printf '\n}\n' >> "$MAPPING"
[ "$COUNT" -gt 0 ] || die "nessun file ELF trovato"
printf 'file processati: %s\n' "$COUNT"

printf '\nVerifica libjli, deve comparire libjli.so senza prefisso:\n'
ls -la "$WORKDIR/jniLibs/$JNI_ABI/" | grep -i jli || die "libjli.so non trovata"
printf '\nVerifica libz:\n'
ls -la "$WORKDIR/jniLibs/$JNI_ABI/" | grep -i libz || printf '  ATTENZIONE: libz assente\n'

log "Copia nel progetto"
[ -d "$PROJECT_PATH" ] || die "PROJECT_PATH non esiste: $PROJECT_PATH"
DEST_JNI="$PROJECT_PATH/app/src/main/jniLibs/$JNI_ABI"
DEST_ASSETS="$PROJECT_PATH/app/src/main/assets"
rm -rf "$DEST_JNI"
mkdir -p "$DEST_JNI" "$DEST_ASSETS"
cp -a "$WORKDIR/jniLibs/$JNI_ABI/." "$DEST_JNI/"
cp "$MAPPING" "$DEST_ASSETS/native_binary_map.json"
printf 'copiati %s file in %s\n' "$(find "$DEST_JNI" -type f | wc -l)" "$DEST_JNI"
printf 'peso jniLibs: %s\n' "$(du 