#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
tools="$sdk/build-tools/35.0.0"
"$tools/apksigner" verify --verbose --print-certs "$apk"
"$tools/zipalign" -c -P 16 -v 4 "$apk"
badging=$("$tools/aapt" dump badging "$apk")
printf '%s\n' "$badging"
grep -q "package: name='com.groovebox.cafa.v4'" <<< "$badging"
grep -q "versionName='[0-9.]*-cafa.4'" <<< "$badging"
grep -q "application-label:'Loom CAFA v4'" <<< "$badging"
readelf=("$sdk"/ndk/27.2.12479018/toolchains/llvm/prebuilt/*/bin/llvm-readelf)
[[ -x "${readelf[0]}" ]] || { echo "NDK llvm-readelf not found" >&2; exit 1; }
python3 - "$apk" "${readelf[0]}" <<'PY'
import sys, zipfile, subprocess, tempfile
from pathlib import Path
with zipfile.ZipFile(sys.argv[1]) as apk:
    names = set(apk.namelist())
    for abi in ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'):
        for lib in ('libloom-native-core.so', 'libc++_shared.so'):
            assert f'lib/{abi}/{lib}' in names, f'Missing {abi}/{lib}'
    with tempfile.TemporaryDirectory(prefix='loom-apk-verify-') as temp:
        for abi in ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'):
            lib = Path(temp) / f'{abi}.so'
            lib.write_bytes(apk.read(f'lib/{abi}/libloom-native-core.so'))
            symbols = subprocess.check_output([sys.argv[2], '--dyn-syms', '--wide', str(lib)], text=True)
            for method in ('nSetCafaOutputDevice', 'nGetCafaOutputDevice', 'nSetCafaAudioEnabled', 'nIsCafaAudioEnabled'):
                assert f'Java_com_groovebox_NativeLib_{method}' in symbols, f'Missing {abi}/{method}'
    assert not any(n.endswith(('.jks', '.keystore')) for n in names), 'Signing key packaged'
print('Verified fork identity, label, four native ABIs, CAFA JNI exports and no signing keystore.')
PY
sha256sum "$apk"
