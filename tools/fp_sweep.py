#!/usr/bin/env python3
"""FP sweep: characterize Mali-G615 float rounding vs correctly-rounded host.

Generates operand triples covering random patterns, near-midpoint quotients
(where a 1-ulp divider visibly differs), subnormals, zeros, and exact
power-of-two control cases. References are computed by a tiny C helper on the
host (hardware correctly-rounded div/sqrt/fma). The device probe
(FpSweepProbeTest + fp_probe.glsl, both temporary) evaluates 11 ops and the
comparison happens back on host.
"""
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/androidTest/assets'
NOPS = 11

LCG = 0x12345678


def lcg():
    global LCG
    LCG = (1103515245 * LCG + 12345) & 0xFFFFFFFF
    return LCG


def f32_bits(f):
    return struct.unpack('<I', struct.pack('<f', f))[0]


def f32_of_bits(b):
    return struct.unpack('<f', struct.pack('<I', b & 0xFFFFFFFF))[0]


def finite_bits():
    """Random finite nonzero float bits (full exponent range)."""
    while True:
        b = lcg()
        if (b & 0x7F800000) != 0x7F800000 and (b & 0x7FFFFFFF) != 0:
            return b


def rand_normal_bits():
    b = finite_bits()
    if (b & 0x7F800000) == 0:  # subnormal; force normal exponent
        b = (b & 0x807FFFFF) | (0x40 << 23)
    return b


def ulp32(f):
    return 2.0 ** (f64_exp(f) - 23)


def f64_exp(f):
    import math
    if f == 0:
        return -149
    return math.frexp(abs(f))[1] - 1


def gen_operands():
    trips = []
    # 0: random finite (includes subnormal inputs occasionally)
    for _ in range(45056):
        trips.append((finite_bits(), finite_bits(), finite_bits()))
    # 1: true quotient within half-ulp of a float midpoint (divider stress)
    for _ in range(8192):
        while True:
            q0 = f32_of_bits(rand_normal_bits())
            m = float(q0) + 0.5 * ulp32(q0)  # exact in double (25-bit significand)
            d = f32_of_bits(rand_normal_bits())
            p = m * float(d)  # 25x24 bits: exact in double
            if abs(p) < 3.3e38:
                break
        n = f32_bits(p)   # correctly rounded by libc
        trips.append((n, f32_bits(d), finite_bits()))
    # 2: subnormal / tiny focused
    for _ in range(8192):
        def tiny():
            kind = lcg() % 4
            if kind == 0:
                return lcg() & 0x807FFFFF  # subnormal or zero
            if kind == 1:
                return (lcg() & 0x807FFFFF) | (1 << 23)  # min normal
            return finite_bits()
        trips.append((tiny(), tiny(), tiny()))
    # 3: zeros and exact power-of-two controls (must match everywhere)
    for i in range(4096):
        if i % 2 == 0:
            zchoice = [0x00000000, 0x80000000, 0x00000001, 0x80000001,
                       0x007FFFFF, 0x00800000][lcg() % 6]
            trips.append((zchoice, finite_bits(), finite_bits()))
        else:
            n = float(1 + (lcg() % 1000))
            d = 2.0 ** float((lcg() % 9) - 4)
            trips.append((f32_bits(n), f32_bits(d), 0x3F800000))
    assert len(trips) == 65536, len(trips)
    return trips


C_SRC = r'''
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
static float fixup_div(float x, float y) {
    float q0 = x / y;
    float s0 = fmaf(-q0, y, x);
    float q1 = s0 / y;
    float v1 = q0 + q1;
    float q = v1;
    float ay0 = fabsf(y);
    uint32_t uy0; memcpy(&uy0, &ay0, 4);
    float ax0 = fabsf(x);
    uint32_t ux0; memcpy(&ux0, &ax0, 4);
    if (((uy0 >> 23) & 0xFF) != 0 && !isnan(v1) && !isinf(v1)
        && (x == 0.0f || ((ux0 >> 23) & 0xFF) != 0)) {
    for (int k = 0; k < 2; k++) {
        float e = fmaf(-q, y, x);
        if (e == 0.0f) break;
        float aq = fabsf(q);
        uint32_t uq; memcpy(&uq, &aq, 4);
        int e5 = (uq >> 23) & 0xFF;
        int expu; float ulp; uint32_t uu;
        if (e5 == 0) { expu = -149; uu = 1u; }
        else if (e5 < 24) { expu = e5 - 150; uu = 1u << (e5 - 1); }
        else { expu = e5 - 150; uu = (uint32_t)(e5 - 23) << 23; }
        memcpy(&ulp, &uu, 4);
        float ay = fabsf(y);
        uint32_t uy; memcpy(&uy, &ay, 4);
        int nb = ((uy >> 23) & 0xFF) + expu;
        float p = 0.0f; int pok = 1;
        if (nb >= 1 && nb <= 254) p = ay * ulp;
        else if (nb <= 0) {
            int shift = 1 - nb;
            if (shift > 25) pok = 0;
            else {
                uint32_t full = 0x800000u | (uy & 0x7FFFFFu);
                if ((full & ((1u << shift) - 1u)) != 0) pok = 0;
                else { uint32_t ps = full >> shift; memcpy(&p, &ps, 4); }
            }
        } else pok = 0;
        if (!pok) { if (k == 1) q = v1; break; }
        float c = fabsf(e) + fabsf(e);
        if (isinf(c)) { if (k == 1) q = v1; break; }
        if (c < p) break;
        { uint32_t uqq; memcpy(&uqq, &q, 4);
          if (c == p && (uqq & 1) == 0) break; }
        if (k == 1) { q = v1; break; }
        if (q == 0.0f) { q = -q; }
        else {
            uint32_t uqb; memcpy(&uqb, &q, 4);
            if (uqb == 0x80000000u) { float z = 0.0f; memcpy(&q, &z, 4); }
            else {
                float dir = ((e > 0.0f) == (y > 0.0f)) ? 1.0f : -1.0f;
                uint32_t uq2 = uqb;
                int step = (q < 0.0f) ? -((int)dir) : ((int)dir);
                uq2 += step;
                memcpy(&q, &uq2, 4);
            }
        }
    }
    }
    return q;
}
int main(int argc, char** argv) {
    FILE* f = fopen(argv[1], "rb");
    uint32_t n; fread(&n, 4, 1, f);
    float *a = malloc(n*4), *b = malloc(n*4), *c = malloc(n*4);
    for (uint32_t i = 0; i < n; i++) {
        uint32_t ba, bb, bc;
        fread(&ba, 4, 1, f); fread(&bb, 4, 1, f); fread(&bc, 4, 1, f);
        memcpy(&a[i], &ba, 4); memcpy(&b[i], &bb, 4); memcpy(&c[i], &bc, 4);
    }
    fclose(f);
    FILE* o = fopen(argv[2], "wb");
    fwrite(&n, 4, 1, o);
    for (int op = 0; op < 13; op++) {
        for (uint32_t i = 0; i < n; i++) {
            float x = a[i], y = b[i], z = c[i], r = 0;
            switch (op) {
            case 0: r = x / y; break;
            case 1: r = fmaf(x, y, z); break;
            case 2: r = x * y + z; break;
            case 3: r = x / y; break;
            case 4: r = sqrtf(x); break;
            case 5: r = 1.0f / sqrtf(x); break;
            case 6: r = 1.0f / sqrtf(x); break;
            case 7: { float q = x / y; float e = fmaf(-q, y, x) / y; r = q + e; break; }
            case 8: r = x * y; break;
            case 9: r = x + y; break;
            case 10: r = x - y; break;
            case 11: { float q0 = x / y; float s0 = fmaf(-q0, y, x); float q1 = s0 / y;
                      float v1 = q0 + q1; float s1 = fmaf(-v1, y, x); float q2 = s1 / y;
                      r = v1 + q2; break; }
            default: r = fixup_div(x, y); break;
            }
            fwrite(&r, 4, 1, o);
        }
    }
    fclose(o);
    return 0;
}
'''


def main():
    trips = gen_operands()
    op_path = ASSETS / 'fp_sweep.bin'
    with open(op_path, 'wb') as f:
        f.write(struct.pack('<I', len(trips)))
        for a, b, c in trips:
            f.write(struct.pack('<III', a, b, c))
    print('operands:', op_path, len(trips))
    cc = Path('/tmp/fp_sweep_ref.c')
    cc.write_text(C_SRC)
    exe = '/tmp/fp_sweep_ref'
    r = subprocess.run(['cc', '-O1', '-ffp-contract=off', '-o', exe, str(cc)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr[-2000:])
        sys.exit(1)
    ref_path = '/tmp/fp_sweep_ref.bin'
    subprocess.run([exe, str(op_path), ref_path], check=True)
    print('reference:', ref_path)


if __name__ == '__main__':
    main()
