#!/usr/bin/env bash
# Builds ttfx (the Terminal screensaver's effects) for the TVs: native/out/lib/armeabi-v7a/libttfx.so, which build.sh
# then puts in the APK. Optional: without it the APK builds as before and the Terminal look stays hidden.
# ttfx is the unmodified upstream source at native/ttfx/UPSTREAM_COMMIT, with its 28 crates vendored (no network).
# Needs rustup with the armv7-linux-androideabi target (rustup target add armv7-linux-androideabi) and an NDK
# (sdkmanager "ndk;29.0.14206865"). The app runs it as a child process: a crash in it ends the child, not the app.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)}"
[ -d "$NDK/toolchains/llvm" ] || { echo "NDK not found. Run: sdkmanager \"ndk;29.0.14206865\" (or set ANDROID_NDK_HOME)."; exit 1; }
TC="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
# rustup's cargo, not a distribution's: only rustup has the Android standard library.
CARGO="${CARGO:-$HOME/.cargo/bin/cargo}"
[ -x "$CARGO" ] || { echo "rustup's cargo not found at $CARGO (set CARGO)."; exit 1; }
"$HOME/.cargo/bin/rustup" target list --installed 2>/dev/null | grep -qx armv7-linux-androideabi \
  || { echo "Run: rustup target add armv7-linux-androideabi"; exit 1; }

# minSdk 28, as the app. Asserts and overflow checks stay on while the 32-bit build is new (note 12, V3).
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TC/armv7a-linux-androideabi28-clang"
export CC_armv7_linux_androideabi="$TC/armv7a-linux-androideabi28-clang" AR_armv7_linux_androideabi="$TC/llvm-ar"
(cd ttfx && "$CARGO" build --release --locked --offline --target armv7-linux-androideabi \
  --config 'source.crates-io.replace-with="vendored-sources"' --config 'source.vendored-sources.directory="vendor"' \
  --config 'profile.release.debug-assertions=true' --config 'profile.release.overflow-checks=true' \
  --config 'profile.release.strip=false')

# The installer only extracts lib/<abi>/lib*.so, with execute permission: the binary goes in under that name.
rm -rf out && mkdir -p out/lib/armeabi-v7a out/assets/notices
cp ttfx/target/armv7-linux-androideabi/release/ttfx out/ttfx.unstripped   # for ndk-stack, if it ever crashes
"$TC/llvm-strip" -o out/lib/armeabi-v7a/libttfx.so out/ttfx.unstripped
cat ttfx/LICENSE <(echo) ttfx/NOTICE > out/assets/notices/ttfx.txt
echo "Built native/out/lib/armeabi-v7a/libttfx.so ($(du -h out/lib/armeabi-v7a/libttfx.so | cut -f1)). Now ./build.sh."
