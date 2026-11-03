// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Learned per-device noise model (RawLens step 4). Idea from PhotonCamera's
// NoiseModeler / DynamicNoise (GPL-3, github.com/eszdman/PhotonCamera): the
// sensor's Poisson-Gaussian noise var = a*x + b follows the gain, so across
// shots a(ISO) = A*ISO + B and b(ISO) = C*ISO^2 + D. Every RawLens shot's blind
// estimate (noise_estimate.h) is stored per camera key; once enough distinct
// ISOs are seen, the fitted model is the prior and the shot's own estimate is
// evidence, mixed by the estimate's confidence (number of usable brightness
// bins: a busy frame with few flat areas trusts the model).
//
// Max analog ISO is not passed, so digital gain is folded into the ISO^2 term
// (above max analog both terms scale with gain^2 anyway).
//
// Store: small text file per key (the caller picks the path: camera id, raw
// mode, build fingerprint), newest kMaxSamples kept.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

namespace rawlens_noise {

struct Sample {
    float iso = 0, a = 0, b = 0;
    int bins = 0;
};

struct Model {
    bool ok = false;
    double A = 0, B = 0, C = 0, D = 0;  // a = A*iso + B, b = C*iso^2 + D
    int samples = 0, isos = 0;
    float predictA(float iso) const { return (float)std::max(A * iso + B, 1e-9); }
    float predictB(float iso) const { return (float)std::max(C * iso * iso + D, 0.0); }
};

constexpr int kMaxSamples = 400;
constexpr int kMinSamples = 6;
constexpr int kMinBins = 3;  // estimates with fewer bins have no b fit

inline std::vector<Sample> loadSamples(const std::string& path) {
    std::vector<Sample> s;
    if (path.empty()) return s;
    FILE* f = std::fopen(path.c_str(), "r");
    if (!f) return s;
    char line[256];
    if (!std::fgets(line, sizeof line, f) || std::string(line).rfind("rawlens-noise v1", 0) != 0) {
        std::fclose(f);
        return s;
    }
    Sample x;
    while (std::fscanf(f, "%f %g %g %d", &x.iso, &x.a, &x.b, &x.bins) == 4) {
        if (x.iso > 0 && std::isfinite(x.a) && std::isfinite(x.b) && x.a >= 0 && x.b >= 0) s.push_back(x);
    }
    std::fclose(f);
    if ((int)s.size() > kMaxSamples) s.erase(s.begin(), s.end() - kMaxSamples);
    return s;
}

inline bool saveSamples(const std::string& path, const std::vector<Sample>& s) {
    if (path.empty()) return false;
    const std::string tmp = path + ".tmp";
    FILE* f = std::fopen(tmp.c_str(), "w");
    if (!f) return false;
    std::fprintf(f, "rawlens-noise v1\n");
    const size_t from = s.size() > (size_t)kMaxSamples ? s.size() - kMaxSamples : 0;
    for (size_t i = from; i < s.size(); i++)
        std::fprintf(f, "%g %.6g %.6g %d\n", s[i].iso, s[i].a, s[i].b, s[i].bins);
    const bool ok = std::fclose(f) == 0;
    return ok && std::rename(tmp.c_str(), path.c_str()) == 0;
}

// Weighted LS y = k1*u + k0 with relative errors (weight bins / y_model^2,
// iterated), non-negative coefficients; robust: drop log-residual outliers.
struct Lin { double k1 = 0, k0 = 0; };

inline Lin fitRel(const std::vector<double>& u, const std::vector<double>& y,
                  const std::vector<double>& w0, std::vector<char>& keep,
                  const std::vector<double>* floor = nullptr) {
    Lin m;
    // Start: through origin, median ratio.
    std::vector<double> r;
    for (size_t i = 0; i < u.size(); i++) if (keep[i] && u[i] > 0) r.push_back(y[i] / u[i]);
    if (r.empty()) return m;
    std::nth_element(r.begin(), r.begin() + r.size() / 2, r.end());
    m.k1 = r[r.size() / 2];
    for (int it = 0; it < 6; it++) {
        double Sw = 0, Su = 0, Sy = 0, Suu = 0, Suy = 0;
        for (size_t i = 0; i < u.size(); i++) {
            if (!keep[i]) continue;
            const double pm = std::max(m.k1 * u[i] + m.k0 + (floor ? (*floor)[i] : 0.0), 1e-12);
            const double w = w0[i] / (pm * pm);
            Sw += w; Su += w * u[i]; Sy += w * y[i]; Suu += w * u[i] * u[i]; Suy += w * u[i] * y[i];
        }
        const double det = Sw * Suu - Su * Su;
        Lin n;
        if (det > 1e-30 * Sw * Suu) {
            n.k1 = (Sw * Suy - Su * Sy) / det;
            n.k0 = (Sy - n.k1 * Su) / Sw;
        }
        if (!(det > 1e-30 * Sw * Suu) || n.k0 < 0) { n.k0 = 0; n.k1 = Suu > 0 ? Suy / Suu : 0; }
        if (n.k1 < 0) { n.k1 = 0; n.k0 = Sw > 0 ? Sy / Sw : 0; }
        m = n;
        if (it == 2) {  // reject > 3 MAD in log space, then refine
            std::vector<double> lr, idx;
            for (size_t i = 0; i < u.size(); i++)
                if (keep[i]) {
                    const double f = floor ? (*floor)[i] : 0.0;
                    lr.push_back(std::log((y[i] + f + 1e-15) / (m.k1 * u[i] + m.k0 + f + 1e-15)));
                }
            if (lr.size() >= 5) {
                std::vector<double> t = lr;
                std::nth_element(t.begin(), t.begin() + t.size() / 2, t.end());
                const double med = t[t.size() / 2];
                for (auto& v : t) v = std::fabs(v - med);
                std::nth_element(t.begin(), t.begin() + t.size() / 2, t.end());
                const double mad = std::max(t[t.size() / 2] * 1.4826, 0.15);
                size_t j = 0;
                for (size_t i = 0; i < u.size(); i++)
                    if (keep[i]) { if (std::fabs(lr[j] - med) > 3 * mad) keep[i] = 0; j++; }
            }
        }
    }
    return m;
}

inline Model fit(const std::vector<Sample>& s) {
    Model m;
    std::vector<double> iso, iso2, a, b, w;
    std::vector<float> distinct;
    for (const auto& x : s) {
        if (x.bins < kMinBins) continue;
        iso.push_back(x.iso); iso2.push_back((double)x.iso * x.iso);
        a.push_back(x.a); b.push_back(x.b); w.push_back(x.bins);
        bool seen = false;
        for (float d : distinct) seen |= std::fabs(std::log(x.iso / d)) < 0.2f;  // ~1/3 stop
        if (!seen) distinct.push_back(x.iso);
    }
    m.samples = (int)iso.size();
    m.isos = (int)distinct.size();
    float lo = 1e9f, hi = 0;
    for (float d : distinct) { lo = std::min(lo, d); hi = std::max(hi, d); }
    if (m.samples < kMinSamples || m.isos < 3 || hi < 2 * lo) return m;
    std::vector<char> ka(iso.size(), 1), kb(iso.size(), 1);
    const Lin la = fitRel(iso, a, w, ka);
    // b is small and noisy at low ISO: its error is relative to the total
    // variance at a dark level (x = 0.05), not to b itself.
    std::vector<double> fl(b.size());
    for (size_t i = 0; i < b.size(); i++) fl[i] = 0.05 * (la.k1 * iso[i] + la.k0);
    const Lin lb = fitRel(iso2, b, w, kb, &fl);
    m.A = la.k1; m.B = la.k0;
    m.C = lb.k1; m.D = lb.k0;
    m.ok = std::isfinite(m.A) && std::isfinite(m.B) && std::isfinite(m.C) && std::isfinite(m.D) && m.A > 0;
    return m;
}

// Prior (model) + evidence (shot estimate). Mix in log space per coefficient;
// estimate weight = bins / (bins + 8); the shot may deviate from the model by
// at most 2x (bounded influence of one odd frame).
struct Combined { float a, b, w; };

inline Combined combine(const Model& m, float iso, float ea, float eb, int bins, bool estOk) {
    const float pa = m.predictA(iso), pb = m.predictB(iso);
    if (!estOk || bins <= 0) return {pa, pb, 0.0f};
    const float w = (float)bins / (bins + 8.0f);
    auto mix = [&](float p, float e) {
        if (p <= 0) return e;
        const float r = std::clamp(e / std::max(p, 1e-12f), 0.5f, 2.0f);
        return p * std::pow(r, w);
    };
    // b from an origin fit (bins < 3) is 0: keep the prior's b.
    return {mix(pa, ea), bins >= kMinBins ? mix(std::max(pb, 1e-12f), std::max(eb, 1e-12f)) : pb, w};
}

}  // namespace rawlens_noise
