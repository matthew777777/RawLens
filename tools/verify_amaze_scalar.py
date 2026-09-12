#!/usr/bin/env python3
"""Execute production GLSL scalar snippets as C++ (no GPU required).

The helper subset uses identical GLSL/C++ integer and bool semantics. This checks
padding and highlight math only, not shader compilation or end-to-end equivalence.
Run: python3 tools/verify_amaze_scalar.py
"""
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    shader = (root / "app/src/main/assets/shaders/amaze/pad.glsl").read_text()
    helpers = []
    for name in ("srcRow", "srcCol", "reflectSmallImage"):
        match = re.search(r"int " + name + r"\([^)]*\)\s*\{[^}]*\}", shader)
        if match is None:
            raise RuntimeError(f"Cannot extract production helper {name}")
        helpers.append(match.group())
    gradient = (root / "app/src/main/assets/shaders/amaze/gradcd.glsl").read_text()
    start = gradient.index("    if (c > clip8")
    end = gradient.index("    imageStore(img_a", start)
    highlight = gradient[start:end]
    helpers.append(r'''
#include <algorithm>
using std::min;
float sq(float x) { return x*x; }
void checkHighlight(float c, float gintvha, float ginthha, bool clipped) {
    float clip8 = 0.8f;
    float guha=0.9f, gdha=0.6f, glha=0.8f, grha=0.3f;
    float guar=0.7f, gdar=0.6f, glar=0.5f, grar=0.3f;
    float vcdalt=0.11f, hcdalt=0.12f, vcd=0.21f, hcd=0.22f;
''' + highlight + r'''
    assert(min(dgi1, dgi2) == (clipped ? sq(guha-gdha) : sq(0.7f-0.6f)));
    assert(min(dgh1, dgh2) == (clipped ? sq(glha-grha) : sq(0.5f-0.3f)));
    assert(vcd == (clipped ? vcdalt : 0.21f));
    assert(hcd == (clipped ? hcdalt : 0.22f));
}
''')
    checks = r'''
#include <cassert>
#include <iostream>
int main() {
    checkHighlight(0.81f, 0.2f, 0.2f, true);
    checkHighlight(0.2f, 0.81f, 0.2f, true);
    checkHighlight(0.2f, 0.2f, 0.81f, true);
    checkHighlight(0.8f, 0.8f, 0.8f, false);
    checkHighlight(0.2f, 0.2f, 0.2f, false);
    // Upstream first-tile top/left = -16. Side strips use 32-j-16;
    // corner loops separately use 32-j. Right/bottom use size-j-2.
    for (int j = 0; j < 16; ++j) {
        assert(srcRow(j, 64, false) == 16-j);
        assert(srcCol(j, 64, false) == 16-j);
        assert(srcRow(j, 64, true) == 32-j);
        assert(srcCol(j, 64, true) == 32-j);
        assert(srcRow(80+j, 64, false) == 62-j);
        assert(srcRow(80+j, 64, true) == 62-j);
        assert(srcCol(80+j, 64, false) == 62-j);
        assert(srcCol(80+j, 64, true) == 62-j);
    }
    for (int i = 0; i < 64; ++i) {
        assert(srcRow(i+16, 64, false) == i);
        assert(srcCol(i+16, 64, false) == i);
        assert(srcRow(i+16, 64, true) == i);
        assert(srcCol(i+16, 64, true) == i);
    }
    // Small crops are a RawLens extension. Every address must be valid
    // and retain its CFA parity for all four supported Bayer patterns.
    for (int n = 4; n <= 64; n += 2) {
        for (int p = -64; p < n+64; ++p) {
            int q = reflectSmallImage(p, n);
            assert(q >= 0 && q < n);
            assert((q & 1) == (p & 1));
            if (p >= 0 && p < n) assert(q == p);
        }
    }
    std::cout << "AMaZE production padding and highlight snippets: PASS\n";
}
'''
    with tempfile.TemporaryDirectory(prefix="amaze-padding-") as directory:
        source = Path(directory) / "padding.cpp"
        executable = Path(directory) / "padding"
        source.write_text("#include <cassert>\n" + "\n".join(helpers) + checks)
        subprocess.run(["c++", "-std=c++14", "-Wall", "-Wextra", str(source),
                        "-o", str(executable)], check=True)
        subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    main()
