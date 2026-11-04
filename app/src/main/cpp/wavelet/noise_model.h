// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Learned per-device noise model (RawLens step 4). Idea from PhotonCamera's
// NoiseModeler / DynamicNoise (GPL-3, github.com/eszdman/PhotonCamera): the
// sensor's Poisson-Gaussian noise var = a*x + b follows the gain, so across
// shots a(ISO) = A*ISO + B. Each eligible shot's blind
// estimate (noise_estimate.h) is stored per camera key; once enough distinct
// ISOs are seen, the fitted model is the prior and the shot's own estimate is
// evidence, mixed by the estimate's confidence (number of usable brightness
// bins: a busy frame with few flat areas trusts the model).
//
// b(ISO) = C*ISO^2 + D*max(ISO/maxAnalogISO, 1)^2. Local ISO windows
// resist scene texture and also work before a global ISO sweep is available.
//
// Store: small text file per key (the caller picks the path: camera id, raw
// mode, build fingerprint), newest kMaxSamples kept.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace rawlens_noise {

struct Sample {
    float iso = 0, a = 0, b = 0;
    int bins = 0;
    float analogIso = 0;
    uint64_t captureId = 0;  // replay of one capture must not train twice
};

constexpr int kMaxSamples = 400;
constexpr int kMinSamples = 6;
constexpr int kMinBins = 3;
constexpr int kWindow = 10;

inline bool valid(const Sample& s) {
    return std::isfinite(s.iso) && s.iso > 0 && std::isfinite(s.a) && s.a >= 0 &&
           std::isfinite(s.b) && s.b >= 0 && (s.a > 0 || s.b > 0) &&
           s.bins >= kMinBins && std::isfinite(s.analogIso) && s.analogIso >= 0;
}

// Third-stop bins bound same-ISO domination without PhotonCamera's full-stop
// quantization. Use the actual ISO in regression, never the bin centre.
inline int isoBin(float iso) { return (int)std::lround(3.0 * std::log2(iso)); }
inline float digitalGain(float iso, float analogIso) {
    return analogIso > 0 ? std::max(iso / analogIso, 1.0f) : 1.0f;
}

inline void addSample(std::vector<Sample>& samples, const Sample& shot) {
    if (!valid(shot)) return;
    if (shot.captureId != 0) {
        for (const auto& s : samples) if (s.captureId == shot.captureId) return;
    }
    samples.push_back(shot);
    int count = 0;
    for (auto it = samples.end(); it != samples.begin();) {
        --it;
        if (isoBin(it->iso) == isoBin(shot.iso) && ++count > kWindow) {
            samples.erase(it);
            break;
        }
    }
    if (samples.size() > kMaxSamples) samples.erase(samples.begin());
}

struct Model {
    bool ok = false, global = false;
    double A = 0, B = 0, C = 0, D = 0;
    float analogIso = 0, minIso = 0, maxIso = 0;
    int samples = 0, isos = 0;
    std::vector<Sample> local;
    const Sample* nearby(float iso) const {
        for (const auto& s : local)
            if (std::fabs(std::log(iso / s.iso)) < 0.035f) return &s;
        return nullptr;
    }
    bool supports(float iso) const {
        return ok && std::isfinite(iso) && iso > 0 &&
            (nearby(iso) || (global && iso >= minIso && iso <= maxIso));
    }
    float predictA(float iso) const {
        if (const auto* s = nearby(iso)) return s->a * iso / s->iso;
        return (float)std::max(A * iso + B, 1e-9);
    }
    float predictB(float iso) const {
        if (const auto* s = nearby(iso)) return s->b * (iso / s->iso) * (iso / s->iso);
        const double dg = digitalGain(iso, analogIso);
        return (float)std::max(C * iso * iso + D * dg * dg, 0.0);
    }
};

inline std::vector<Sample> loadSamples(const std::string& path) {
    std::vector<Sample> s;
    if (path.empty()) return s;
    FILE* f = std::fopen(path.c_str(), "r");
    if (!f) return s;
    char line[256];
    if (!std::fgets(line, sizeof line, f) || std::string(line).compare("rawlens-noise v2\n") != 0) {
        std::fclose(f);
        return s;
    }
    Sample x;
    unsigned long long capture = 0;
    while (std::fgets(line, sizeof line, f)) {
        char extra;
        if (std::sscanf(line, "%f %g %g %d %f %llu %c", &x.iso, &x.a, &x.b,
                        &x.bins, &x.analogIso, &capture, &extra) != 6) continue;
        x.captureId = capture;
        addSample(s, x);
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
    std::fprintf(f, "rawlens-noise v2\n");
    const size_t from = s.size() > (size_t)kMaxSamples ? s.size() - kMaxSamples : 0;
    for (size_t i = from; i < s.size(); i++)
        std::fprintf(f, "%g %.9g %.9g %d %g %llu\n", s[i].iso, s[i].a, s[i].b, s[i].bins,
                     s[i].analogIso, (unsigned long long)s[i].captureId);
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

inline Model fit(const std::vector<Sample>& input) {
    Model m;
    std::vector<Sample> bounded;
    for (const auto& x : input) addSample(bounded, x);
    m.samples = (int)bounded.size();
    std::map<int, std::vector<Sample>> groups;
    for (const auto& x : bounded) groups[isoBin(x.iso)].push_back(x);
    m.isos = (int)groups.size();
    std::vector<Sample> representatives;
    for (auto& item : groups) {
        auto& g = item.second;
        // Texture leaks positively into variance: retain the lower half, as
        // PhotonCamera does, after normalizing slopes to a common ISO.
        std::sort(g.begin(), g.end(), [](const Sample& a, const Sample& b) {
            return a.a / a.iso < b.a / b.iso;
        });
        const size_t keep = g.size() >= 3 ? (g.size() + 1) / 2 : g.size();
        Sample r{};
        double ai = 0, bi = 0, ref = 0;
        for (size_t i = 0; i < keep; ++i) ref += g[i].iso;
        r.iso = (float)(ref / keep);
        for (size_t i = 0; i < keep; ++i) {
            const double ratio = r.iso / g[i].iso;
            ai += g[i].a * ratio; bi += g[i].b * ratio * ratio;
            r.bins += g[i].bins;
            if (g[i].analogIso > 0) r.analogIso = g[i].analogIso;
        }
        r.a = (float)(ai / keep); r.b = (float)(bi / keep);
        r.bins = std::max(kMinBins, r.bins / (int)keep);
        representatives.push_back(r);
        if (g.size() >= 3) m.local.push_back(r);
        if (r.analogIso > 0) m.analogIso = r.analogIso;
    }
    m.ok = !m.local.empty();
    if (representatives.empty()) return m;
    m.minIso = representatives.front().iso; m.maxIso = representatives.back().iso;
    if (m.samples < kMinSamples || m.isos < 3 || m.maxIso < 2 * m.minIso) return m;
    std::vector<double> iso, iso2, a, b, w, dg2;
    for (const auto& x : representatives) {
        const double dg = digitalGain(x.iso, m.analogIso), d2 = dg * dg;
        iso.push_back(x.iso); iso2.push_back((double)x.iso * x.iso / d2);
        a.push_back(x.a); b.push_back(x.b / d2); dg2.push_back(d2);
        // Equal ISO-bin influence: a burst at one ISO cannot drown the rest.
        w.push_back(std::min(x.bins, 16));
    }
    std::vector<char> ka(iso.size(), 1), kb(iso.size(), 1);
    const Lin la = fitRel(iso, a, w, ka);
    std::vector<double> fl(b.size());
    for (size_t i = 0; i < b.size(); i++) fl[i] = 0.05 * (la.k1 * iso[i] + la.k0) / dg2[i];
    const Lin lb = fitRel(iso2, b, w, kb, &fl);
    m.A = la.k1; m.B = la.k0; m.C = lb.k1; m.D = lb.k0;
    m.global = std::isfinite(m.A) && std::isfinite(m.B) && std::isfinite(m.C) &&
               std::isfinite(m.D) && m.A > 0;
    // A gain-mode discontinuity need not follow a single global law. Keep
    // calibrated local priors, but decline interpolation when retained bins
    // disagree with the fitted variance by more than 50% in either direction.
    for (size_t i = 0; i < representatives.size() && m.global; ++i) {
        if (!ka[i] || !kb[i]) continue;
        const auto& r = representatives[i];
        const double dg = digitalGain(r.iso, m.analogIso);
        for (double x : {0.001, 0.05, 0.5}) {
            const double predicted = (m.A * r.iso + m.B) * x +
                                      m.C * r.iso * r.iso + m.D * dg * dg;
            const double ratio = predicted / std::max(double(r.a) * x + r.b, 1e-12);
            if (ratio < 1.0 / 1.5 || ratio > 1.5) m.global = false;
        }
    }
    m.ok = m.ok || m.global;
    return m;
}

// Prior (model) + evidence (shot estimate). Mix in log space per coefficient;
// estimate weight = bins / (bins + 8); the shot may deviate from the model by
// at most 2x; larger disagreements are rejected. Evidence weight is capped
// at 0.25 so a single textured frame cannot lift the learned noise floor.
struct Combined { float a, b, w; };

inline Combined combine(const Model& m, float iso, float ea, float eb, int bins, bool estOk) {
    const float pa = m.predictA(iso), pb = m.predictB(iso);
    if (!estOk || bins <= 0) return {pa, pb, 0.0f};
    const float w = std::min((float)bins / (bins + 8.0f), 0.25f);
    auto mix = [&](float p, float e) {
        if (p <= 0) return 0.0f;
        const float r = e / std::max(p, 1e-12f);
        if (!std::isfinite(r) || r < 0.5f || r > 2.0f) return p;
        return p * std::pow(r, w);
    };
    // b from an origin fit (bins < 3) is 0: keep the prior's b.
    return {mix(pa, ea), bins >= kMinBins ? mix(std::max(pb, 1e-12f), std::max(eb, 1e-12f)) : pb, w};
}

}  // namespace rawlens_noise
