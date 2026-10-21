#!/bin/sh
# RawTherapee HLRecovery_inpaint oracle (docs/refactor/jpeg-pipeline-study.md).
# Builds the verbatim references/RawTherapee/hilite_recon.cc body against RT
# headers fetched at a pinned commit (SHA-256 checked). Usage: tools/hl_oracle/build.sh <outdir>
set -eu
RT=b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd); OUT=${1:?outdir}
mkdir -p "$OUT"; cp "$HERE/hl_env.h" "$HERE/driver.cpp" "$OUT/"; cd "$OUT"
while read -r sha file; do
  curl -sfL -o "$file" "https://raw.githubusercontent.com/Beep6581/RawTherapee/$RT/rtengine/$file"
  echo "$sha  $file" | shasum -a 256 -c - >/dev/null
done <<EOF
d92ed39d56e227cb7de6490aec687ea70a72a7353722556130ed15f1dac54c49 array2D.h
1576e85dda50f09b7c06f4fa0c7b0bcfbd1ddfd1a8ef723753b09f04adb754cb noncopyable.h
a98596cdea2f0c59821e24436b6cbfc6872eec5080d58765404e647d6f22b881 LUT.h
95dfa71be6138de2e6c40bc9d03cee8a5dd4008aef561e38f47708f88dee778a opthelper.h
72d705fbd0343190860be396f47b9b1f694d9de2968222810b2d6474813f8fc8 sleef.h
5eb032804ce6f386d59e87ffb9600800d052568f008d5e9c1f6978c7470d34df rescale.h
88c382a75bcb5dcafb62f6a527c713b2368f0d61b86e811ce3b97504a704706e rt_math.h
e3bef685b2dbd083ea10d890f4ffc1f7e0f4eb9fc1a18a22d732dd77936211c6 boxblur.h
2ec518170b6696c0f277193aef3b5a404672e530c5c2ab0b79f89b9aa5fd9edf boxblur.cc
e8e911ee9fb0a2f87c85331d41fb3014210b4ebe51b950e83b92ee05031a0973 guidedfilter.h
bbea1c69463e1c872f176d37fbde17c7e1a3968d77ac9ab4f6c28c7aad307318 guidedfilter.cc
EOF
grep -v "#include \"imagefloat.h\"" guidedfilter.cc > guidedfilter_noimg.cc
python3 - "$ROOT/references/RawTherapee/hilite_recon.cc" hl_body.cpp <<PY
import sys
L=open(sys.argv[1]).read().splitlines(keepends=True)
cut=next(i for i,l in enumerate(L) if 'void RawImageSource::highlight_recovery_opposed' in l)
open(sys.argv[2],'w').write('#include "hl_env.h"\n'+''.join(l for l in L[:cut] if not l.startswith('#include '))+'\n}\n')
PY
c++ -std=c++17 -O2 -I. driver.cpp boxblur.cc guidedfilter_noimg.cc hl_body.cpp -o hl_oracle
echo "built $OUT/hl_oracle"
