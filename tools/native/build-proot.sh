#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CACHE="$ROOT/.cache/native-proot"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
JOBS="\${NATIVE_BUILD_JOBS:-4}"
API=26
NDK_VERSION="29.0.14206865"
PROOT_REPO="https://github.com/DroidNative/proot.git"
PROOT_COMMIT="4ac94ecbb567608725e1d68d5ea617800a76c9c4"
TALLOC_VERSION="2.5.0"
TALLOC_SHA256="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"
SQLITE_VERSION="3540100"
SQLITE_SHA256="926ef8d3eab93fbf6e06011901273eec076b25594a4e1cdadd50c987f4f6d64e"

ANDROID_NDK="\${ANDROID_NDK:-\${ANDROID_NDK_ROOT:-}}"
if [ -z "$ANDROID_NDK" ]; then
  SDK="\${ANDROID_SDK_ROOT:-\${ANDROID_HOME:-}}"
  if [ -z "$SDK" ] && [ -f "$ROOT/local.properties" ]; then
    SDK="$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" | head -n1)"
  fi
  ANDROID_NDK="$SDK/ndk/$NDK_VERSION"
fi
[ -d "$ANDROID_NDK" ] || { echo "Android NDK $NDK_VERSION not found: $ANDROID_NDK" >&2; exit 1; }
TC="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$TC/aarch64-linux-android\${API}-clang" ] || { echo "NDK toolchain missing for arm64-v8a" >&2; exit 1; }
CC="$TC/aarch64-linux-android\${API}-clang"
AR="$TC/llvm-ar"
STRIP="$TC/llvm-strip"
OBJCOPY="$TC/llvm-objcopy"
OBJDUMP="$TC/llvm-objdump"
mkdir -p "$CACHE" "$OUT"

fetch_archive() {
  local url="$1" sha="$2" file="$3" dir="$4"
  if [ ! -d "$CACHE/$dir" ]; then
    curl --retry 3 --retry-all-errors -fsSL "$url" -o "$CACHE/$file"
    echo "$sha  $CACHE/$file" | sha256sum -c -
    tar -xf "$CACHE/$file" -C "$CACHE"
    rm -f "$CACHE/$file"
  fi
}

echo "Fetching pinned Android-compatible PRoot sources..."
if [ ! -d "$CACHE/proot-src/.git" ]; then
  git clone -q "$PROOT_REPO" "$CACHE/proot-src"
fi
git -C "$CACHE/proot-src" fetch -q origin "$PROOT_COMMIT"
git -C "$CACHE/proot-src" checkout -q --detach "$PROOT_COMMIT"

fetch_archive \
  "https://www.samba.org/ftp/talloc/talloc-\${TALLOC_VERSION}.tar.gz" \
  "$TALLOC_SHA256" \
  "talloc-\${TALLOC_VERSION}.tar.gz" \
  "talloc-\${TALLOC_VERSION}"
fetch_archive \
  "https://www.sqlite.org/2026/sqlite-autoconf-\${SQLITE_VERSION}.tar.gz" \
  "$SQLITE_SHA256" \
  "sqlite-autoconf-\${SQLITE_VERSION}.tar.gz" \
  "sqlite-autoconf-\${SQLITE_VERSION}"

BUILD="$CACHE/build-arm64"
mkdir -p "$BUILD/talloc/include"
TALLOC_SRC="$CACHE/talloc-\${TALLOC_VERSION}"
if [ ! -f "$BUILD/talloc/libtalloc.a" ]; then
  "$CC" -c -O2 -fPIC -D_GNU_SOURCE -D__STDC_WANT_LIB_EXT1__=1 \
    -I"$ROOT/tools/native/talloc" -I"$TALLOC_SRC/lib/replace" -I"$TALLOC_SRC" \
    "$TALLOC_SRC/talloc.c" -o "$BUILD/talloc/talloc.o"
  "$CC" -c -O2 -fPIC "$ROOT/tools/native/talloc/replace_min.c" \
    -o "$BUILD/talloc/replace_min.o"
  "$AR" rcs "$BUILD/talloc/libtalloc.a" "$BUILD/talloc/talloc.o" "$BUILD/talloc/replace_min.o"
  cp "$TALLOC_SRC/talloc.h" "$BUILD/talloc/include/"
fi

PROOT_BUILD="$BUILD/proot"
if [ ! -d "$PROOT_BUILD/.git" ]; then
  git clone -q "$CACHE/proot-src" "$PROOT_BUILD"
fi
git -C "$PROOT_BUILD" reset -q --hard "$PROOT_COMMIT"
git -C "$PROOT_BUILD" clean -q -fdx
make -s -C "$PROOT_BUILD/src" clean >/dev/null 2>&1 || true

echo "Building PRoot for Android arm64-v8a..."
CPPFLAGS="-I$BUILD/talloc/include -DARG_MAX=131072 -DVERSION=\\\"espforge-proot-\${PROOT_COMMIT:0:12}\\\"" \
LDFLAGS="-L$BUILD/talloc -Wl,-z,max-page-size=16384" \
make -s -C "$PROOT_BUILD/src" -j"$JOBS" proot \
  CC="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
  PROOT_UNBUNDLE_LOADER=/nonexistent \
  SQLITE_DIR="$CACHE/sqlite-autoconf-\${SQLITE_VERSION}"

"$STRIP" -o "$OUT/libproot.so" "$PROOT_BUILD/src/proot"
cp "$PROOT_BUILD/src/loader/loader" "$OUT/libproot-loader.so"
chmod 755 "$OUT/libproot.so" "$OUT/libproot-loader.so"

echo "Native runtime binaries:"
file "$OUT/libproot.so" "$OUT/libproot-loader.so"
readelf -h "$OUT/libproot.so" | grep -E 'Class:|Machine:'
test -s "$OUT/libproot-loader.so"
