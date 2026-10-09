// SPDX-License-Identifier: GPL-3.0-or-later
// Host parity check for the BGU look operator: an independent double-precision
// scalar transcription of the VfGpuImport GLSL tail vs the Halide AOT filter,
// over seeded quad fixtures x {RAW, JPEG-default, JPEG-extreme} x
// {lens-off, lens-on}. Fails the regen script on any mismatch above tolerance.
// NOTE: both sides exclude the IGN dither by design (display-res only).
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "HalideBuffer.h"
#include "bgu_look.h"

using Halide::Runtime::Buffer;

static const double INSET[3][3] = {
    {0.856627153315983, 0.137318972929847, 0.111898212999950},
    {0.095121240538159, 0.761241990602591, 0.076799418603190},
    {0.048251606145858, 0.101439036467562, 0.811302368396859},
};
static const double OUTSET[3][3] = {
    {1.127100581814437, -0.141329763498438, -0.141329763498438},
    {-0.110606643096603, 1.157823702216272, -0.110606643096603},
    {-0.016493938717835, -0.016493938717834, 1.251936406595040},
};
static const double A2R[3][3] = {
    {1.025877552449, -0.002232441770, -0.005013950857},
    {-0.020020686312, 1.004568990995, -0.025282661381},
    {-0.005775003430, -0.002349522759, 1.030082295555},
};
static const double R2S[3][3] = {
    {1.6604910021, -0.1245504745, -0.0181507634},
    {-0.5876411388, 1.1328998971, -0.1005788980},
    {-0.0728498633, -0.0083494226, 1.1187296614},
};
static const double R2P[3][3] = {
    {1.343578252570, -0.065297452837, 0.002821787226},
    {-0.282179670449, 1.075787915784, -0.019598494598},
    {-0.061398582051, -0.010490463088, 1.016776707234},
};

struct Params {
    int jpeg;
    double ev;
    double wb[4];
    double ccm[3][3];  // [col][row]
    double aces[3][3];  // [col][row]
    double white[3];
    double contrast, saturation, purity, hue, shadowEv, highlightEv, gamut, shoulder;
    int p3;
    int applyLens, greenRow, qbX, qbY, lowStep, aL, aT, aR, aB;
};

static double clampD(double v, double lo, double hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static double smoothstepD(double e0, double e1, double x) {
    double t = clampD((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

static double sigmoidD(double x) {
    // Same degree-7 polynomial as the shader (Horner here; fp32/fp64 and
    // evaluation-order differences are below the parity tolerance).
    return ((((((-17.86 * x + 78.01) * x - 126.7) * x + 92.06) * x - 28.72) * x +
             4.361) *
                x -
            0.1718) *
               x +
           0.002857;
}

static double gamutScaleD(double d, double a) {
    if (fabs(d) < 0.000001) return 1.0;
    return d > 0.0 ? (1.0 - a) / d : -a / d;
}

static double oetfD(double l) {
    double lo = 12.92 * l;
    double hi = 1.055 * pow(l, 1.0 / 2.4) - 0.055;
    return l <= 0.0031308 ? lo : hi;
}

static void matVecD(const double m[3][3], const double v[3], double o[3]) {
    for (int r = 0; r < 3; r++) o[r] = m[0][r] * v[0] + m[1][r] * v[1] + m[2][r] * v[2];
}

// Reference look for one quad. lensMap is [row][col][ch] (rows x cols x 4).
static void refLook(const uint8_t q[4], int x, int y, const Buffer<float> &lensMap,
                    const Params &p, double guide[3], double out[3]) {
    double qr = q[0] / 255.0, qgr = q[1] / 255.0, qgb = q[2] / 255.0, qb = q[3] / 255.0;
    guide[0] = qr;
    guide[1] = (qgr + qgb) * 0.5;
    guide[2] = qb;

    double lg[4] = {1.0, 1.0, 1.0, 1.0};
    if (p.applyLens) {
        int qx = p.qbX + x * p.lowStep;
        int qy = p.qbY + y * p.lowStep;
        double aw = p.aR - p.aL - 1 >= 1 ? (double)(p.aR - p.aL - 1) : 1.0;
        double ah = p.aB - p.aT - 1 >= 1 ? (double)(p.aB - p.aT - 1) : 1.0;
        double nuvx = clampD((qx - p.aL) / aw, 0.0, 1.0);
        double nuvy = clampD((qy - p.aT) / ah, 0.0, 1.0);
        int cols = lensMap.width(), rows = lensMap.height();
        double uvx = (nuvx * (cols - 1) + 0.5) / cols;
        double uvy = (nuvy * (rows - 1) + 0.5) / rows;
        double tx = uvx * cols - 0.5, ty = uvy * rows - 0.5;
        int ix0 = (int)clampD(floor(tx), 0, cols - 1);
        int iy0 = (int)clampD(floor(ty), 0, rows - 1);
        int ix1 = ix0 + 1 <= cols - 1 ? ix0 + 1 : cols - 1;
        int iy1 = iy0 + 1 <= rows - 1 ? iy0 + 1 : rows - 1;
        double fx = tx - floor(tx), fy = ty - floor(ty);
        for (int ch = 0; ch < 4; ch++) {
            double a = lensMap(ix0, iy0, ch) * (1 - fx) + lensMap(ix1, iy0, ch) * fx;
            double b = lensMap(ix0, iy1, ch) * (1 - fx) + lensMap(ix1, iy1, ch) * fx;
            lg[ch] = a * (1 - fy) + b * fy;
        }
        if (((qy + p.greenRow) & 1) != 0) {
            double t = lg[1];
            lg[1] = lg[2];
            lg[2] = t;
        }
    }
    double br = qr * lg[0], bgr = qgr * lg[1], bgb = qgb * lg[2], bb = qb * lg[3];

    if (!p.jpeg) {
        double v[3] = {br * p.wb[0], (bgr * p.wb[1] + bgb * p.wb[2]) * 0.5, bb * p.wb[3]};
        double rgb[3];
        matVecD(p.ccm, v, rgb);
        for (int i = 0; i < 3; i++) {
            double c = rgb[i] < 0.0 ? 0.0 : rgb[i];
            c *= 2.0;
            c = c / (1.0 + c);
            out[i] = pow(c, 1.0 / 2.2);
        }
        return;
    }

    double cam[3] = {br < 0 ? 0 : br,
                     ((bgr + bgb) * 0.5) < 0 ? 0 : ((bgr + bgb) * 0.5),
                     bb < 0 ? 0 : bb};
    double wBlend = smoothstepD(0.70, 0.99, cam[0]);
    double w1 = smoothstepD(0.70, 0.99, cam[1]);
    double w2 = smoothstepD(0.70, 0.99, cam[2]);
    if (w1 > wBlend) wBlend = w1;
    if (w2 > wBlend) wBlend = w2;
    double eg = exp2(p.ev);
    double camW[3] = {(cam[0] + (p.white[0] - cam[0]) * wBlend) * eg,
                      (cam[1] + (p.white[1] - cam[1]) * wBlend) * eg,
                      (cam[2] + (p.white[2] - cam[2]) * wBlend) * eg};
    double aces[3], rec[3], scene[3], sh[3];
    matVecD(p.aces, camW, aces);
    matVecD(A2R, aces, rec);
    for (int i = 0; i < 3; i++) {
        scene[i] = rec[i] < 0 ? 0 : rec[i];
        sh[i] = scene[i];
        if (scene[i] > 0.9 && p.shoulder > 0.0) {
            double t = (scene[i] - 0.9) / 0.8;
            double comp = 0.9 + 0.8 * (1.0 - exp(-t));
            double k = clampD(p.shoulder, 0.0, 1.0);
            sh[i] = scene[i] + (comp - scene[i]) * k;
        }
    }
    double v0[3], vn[3];
    matVecD(INSET, sh, v0);
    double evRange = p.shadowEv + p.highlightEv;
    double pivot = p.shadowEv / evRange;
    double lo = -2.473931188 - p.shadowEv;
    for (int i = 0; i < 3; i++) {
        double l = log2(v0[i] < 1e-10 ? 1e-10 : v0[i]);
        double n = clampD((l - lo) / evRange, 0.0, 1.0);
        vn[i] = clampD(pivot + (n - pivot) * p.contrast, 0.0, 1.0);
        vn[i] = sigmoidD(vn[i]);
    }
    double vo[3], vp[3], vpow[3];
    matVecD(OUTSET, vn, vo);
    for (int i = 0; i < 3; i++) {
        vp[i] = vn[i] + (vo[i] - vn[i]) * p.purity;
        vpow[i] = pow(vp[i] < 0 ? 0 : vp[i], 2.2);
    }
    double mappedLuma = vpow[0] * 0.2627 + vpow[1] * 0.6780 + vpow[2] * 0.0593;
    double sceneLuma = scene[0] * 0.2627 + scene[1] * 0.6780 + scene[2] * 0.0593;
    double ratio = sceneLuma > 1e-9 ? mappedLuma / (sceneLuma < 1e-9 ? 1e-9 : sceneLuma) : 1.0;
    double vh[3], vsat[3];
    for (int i = 0; i < 3; i++) vh[i] = vpow[i] + (scene[i] * ratio - vpow[i]) * p.hue;
    double gsat = vh[0] * 0.2627 + vh[1] * 0.6780 + vh[2] * 0.0593;
    for (int i = 0; i < 3; i++) vsat[i] = gsat + p.saturation * (vh[i] - gsat);
    double vd[3];
    matVecD(p.p3 ? R2P : R2S, vsat, vd);
    double anchor = clampD(vd[0] * 0.2126 + vd[1] * 0.7152 + vd[2] * 0.0722, 0.0, 1.0);
    double sc = gamutScaleD(vd[0] - anchor, anchor);
    double s1 = gamutScaleD(vd[1] - anchor, anchor);
    double s2 = gamutScaleD(vd[2] - anchor, anchor);
    if (s1 < sc) sc = s1;
    if (s2 < sc) sc = s2;
    sc = 1.0 + (clampD(sc, 0.0, 1.0) - 1.0) * p.gamut;
    for (int i = 0; i < 3; i++) {
        double v = anchor + sc * (vd[i] - anchor);
        out[i] = oetfD(clampD(v, 0.0, 1.0));
    }
}

static uint32_t rngState = 0x12345678;
static uint32_t nextRand() {
    rngState = rngState * 1664525u + 1013904223u;
    return rngState;
}

static int failures = 0;
static double maxAbsErr = 0.0;

static void checkCase(const char *name, Buffer<uint8_t> &quad, Buffer<float> &lens,
                      Buffer<float> &wb, Buffer<float> &ccm, Buffer<float> &aces,
                      Buffer<float> &white, const Params &p) {
    Buffer<float> guide(quad.width(), quad.height(), 3);
    Buffer<float> dev(quad.width(), quad.height(), 3);
    int rc = bgu_look(quad, lens, wb, ccm, aces, white, p.jpeg, (float)p.ev,
                      (float)p.contrast, (float)p.saturation, (float)p.purity,
                      (float)p.hue, (float)p.shadowEv, (float)p.highlightEv,
                      (float)p.gamut, (float)p.shoulder, p.p3, p.applyLens, p.greenRow,
                      p.qbX, p.qbY, p.lowStep, p.aL, p.aT, p.aR, p.aB, guide, dev);
    if (rc != 0) {
        printf("FAIL %s: filter rc=%d\n", name, rc);
        failures++;
        return;
    }
    int bad = 0;
    for (int y = 0; y < quad.height(); y++) {
        for (int x = 0; x < quad.width(); x++) {
            uint8_t q[4] = {quad(x, y, 0), quad(x, y, 1), quad(x, y, 2), quad(x, y, 3)};
            double gref[3], oref[3];
            refLook(q, x, y, lens, p, gref, oref);
            for (int ch = 0; ch < 3; ch++) {
                double dg = fabs(guide(x, y, ch) - gref[ch]);
                double dd = fabs(dev(x, y, ch) - oref[ch]);
                if (dg > maxAbsErr) maxAbsErr = dg;
                if (dd > maxAbsErr) maxAbsErr = dd;
                if (dg > 1e-6 || dd > 5e-4) {
                    if (bad < 6) {
                        printf("FAIL %s (%d,%d,%d): guide got %.6f want %.6f | dev got %.6f want %.6f\n",
                               name, x, y, ch, guide(x, y, ch), gref[ch], dev(x, y, ch),
                               oref[ch]);
                    }
                    bad++;
                }
            }
        }
    }
    if (bad > 0) {
        printf("FAIL %s: %d channel mismatches\n", name, bad);
        failures++;
    } else {
        printf("ok %s (%dx%d)\n", name, quad.width(), quad.height());
    }
}

int main() {
    // Odd, non-multiple-of-8 extents to exercise GuardWithIf tails.
    const int W = 24, H = 17;
    Buffer<uint8_t> quad(W, H, 4);
    quad.for_each_element([&](int x, int y, int ch) {
        // Mix: ramps, edges, saturated corners, seeded noise.
        uint8_t v;
        int pick = (x * 7 + y * 13 + ch * 29) % 5;
        if (pick == 0) {
            v = (uint8_t)((x * 255) / (W - 1));
        } else if (pick == 1) {
            static const uint8_t edges[] = {0, 1, 127, 128, 254, 255};
            v = edges[(x + y + ch) % 6];
        } else if (pick == 2) {
            v = (ch == (x + y) % 4) ? 255 : 0;
        } else {
            v = (uint8_t)(nextRand() >> 24);
        }
        quad(x, y, ch) = v;
    });

    // Synthetic smooth lens map 6x5 with per-channel variation (dome 1.0..1.35).
    Buffer<float> lens(6, 5, 4);
    lens.for_each_element([&](int x, int y, int ch) {
        double nx = (x / 5.0 - 0.5) * 2.0, ny = (y / 4.0 - 0.5) * 2.0;
        double r2 = nx * nx + ny * ny;
        lens(x, y, ch) = (float)(1.0 + 0.35 * r2 * (0.85 + 0.1 * ch));
    });

    Buffer<float> wb(4);
    Buffer<float> ccm(3, 3), aces(3, 3), white(3);

    // Case A: RAW identity.
    Params a = {};
    a.jpeg = 0;
    a.wb[0] = 1.0;
    a.wb[1] = 1.0;
    a.wb[2] = 1.0;
    a.wb[3] = 1.0;
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) a.ccm[i][j] = (i == j) ? 1.0 : 0.0;

    // Case B: RAW realistic (warm WB, CCM with negative lobes).
    Params b = a;
    b.wb[0] = 1.92;
    b.wb[1] = 1.0;
    b.wb[2] = 1.03;
    b.wb[3] = 1.52;
    double ccmReal[3][3] = {
        {1.62, -0.45, -0.12}, {-0.38, 1.35, 0.05}, {0.06, -0.48, 1.42},
    };
    memcpy(b.ccm, ccmReal, sizeof(b.ccm));

    // Case C: JPEG defaults.
    Params c = {};
    c.jpeg = 1;
    c.ev = 0.0;
    c.contrast = 1.0;
    c.saturation = 1.0;
    c.purity = 1.0;
    c.hue = 0.0;
    c.shadowEv = 10.0;
    c.highlightEv = 6.5;
    c.gamut = 0.0;
    c.shoulder = 1.0;
    c.white[0] = 1.0;
    c.white[1] = 1.0;
    c.white[2] = 1.0;
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) c.aces[i][j] = (i == j) ? 1.0 : 0.0;

    // Case D: JPEG extreme + P3.
    Params d = c;
    d.ev = 1.0;
    d.contrast = 1.5;
    d.saturation = 2.0;
    d.purity = 2.0;
    d.hue = 1.0;
    d.shadowEv = 4.0;
    d.highlightEv = 10.0;
    d.gamut = 1.0;
    d.p3 = 1;
    d.white[0] = 1.0;
    d.white[1] = 0.97;
    d.white[2] = 0.92;
    double acesReal[3][3] = {
        {0.61, 0.07, 0.02}, {0.34, 0.92, 0.11}, {0.05, 0.01, 0.87},
    };
    memcpy(d.aces, acesReal, sizeof(d.aces));

    const Params looks[4] = {a, b, c, d};
    const char *names[4] = {"raw-identity", "raw-realistic", "jpeg-default", "jpeg-extreme"};
    for (int li = 0; li < 4; li++) {
        for (int l = 0; l < 4; l++) wb(l) = (float)looks[li].wb[l];
        for (int col = 0; col < 3; col++)
            for (int row = 0; row < 3; row++) {
                ccm(col, row) = (float)looks[li].ccm[col][row];
                aces(col, row) = (float)looks[li].aces[col][row];
            }
        for (int ch = 0; ch < 3; ch++) white(ch) = (float)looks[li].white[ch];
        for (int lensOn = 0; lensOn <= 1; lensOn++) {
            Params p = looks[li];
            p.applyLens = lensOn;
            p.greenRow = li % 2;
            p.qbX = 4;
            p.qbY = 8;
            p.lowStep = 16;
            p.aL = 0;
            p.aT = 0;
            p.aR = 400;
            p.aB = 300;
            char name[64];
            snprintf(name, sizeof(name), "%s/lens%d", names[li], lensOn);
            checkCase(name, quad, lens, wb, ccm, aces, white, p);
        }
    }

    printf("max abs err over all cases: %.3g\n", maxAbsErr);
    if (failures != 0) {
        printf("FAILED: %d cases\n", failures);
        return 1;
    }
    printf("look check OK: 8 cases match (guide 1e-6, developed 5e-4)\n");
    return 0;
}
