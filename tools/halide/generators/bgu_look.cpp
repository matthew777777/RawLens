// SPDX-License-Identifier: GPL-3.0-or-later
// BGU viewfinder: the low-resolution "operator". Evaluates the full VF look on
// one low-res quad texel: WB gains + CCM + Reinhard + gamma in RAW mode, or
// the calibrated camera-to-ACEScg + AgX + gamut + sRGB OETF chain in JPEG mode.
//
// Transcribed from the shared GLSL tail (VfGpuImport.TONEMAP_TAIL_TEMPLATE +
// helpers). The ONLY intentional deviation is the IGN dither: it is
// display-resolution noise the bilateral fit must never see, so the slice
// shader (not this operator) adds final dither at present time.
//
// Inputs mirror the FrameState snapshot 1:1 (gains, CCM, camToAces, camWhite,
// exposure EV, 7 AgX params, highlight shoulder, displayP3, lens map +
// geometry). Output is a float RGB pair: the unlensed guide (fit input) and
// the developed look (fit output).
#include "Halide.h"

using namespace Halide;

// Pinned AgX/display matrices from VfGpuImport, stored [col][row] exactly as
// the GLSL mat3() column-major literals.
static const float AGX_INSET[3][3] = {
    {0.856627153315983f, 0.137318972929847f, 0.111898212999950f},
    {0.095121240538159f, 0.761241990602591f, 0.076799418603190f},
    {0.048251606145858f, 0.101439036467562f, 0.811302368396859f},
};
static const float AGX_OUTSET[3][3] = {
    {1.127100581814437f, -0.141329763498438f, -0.141329763498438f},
    {-0.110606643096603f, 1.157823702216272f, -0.110606643096603f},
    {-0.016493938717835f, -0.016493938717834f, 1.251936406595040f},
};
static const float ACESCG_TO_REC2020[3][3] = {
    {1.025877552449f, -0.002232441770f, -0.005013950857f},
    {-0.020020686312f, 1.004568990995f, -0.025282661381f},
    {-0.005775003430f, -0.002349522759f, 1.030082295555f},
};
static const float REC2020_TO_SRGB[3][3] = {
    {1.6604910021f, -0.1245504745f, -0.0181507634f},
    {-0.5876411388f, 1.1328998971f, -0.1005788980f},
    {-0.0728498633f, -0.0083494226f, 1.1187296614f},
};
static const float REC2020_TO_DISPLAY_P3[3][3] = {
    {1.343578252570f, -0.065297452837f, 0.002821787226f},
    {-0.282179670449f, 1.075787915784f, -0.019598494598f},
    {-0.061398582051f, -0.010490463088f, 1.016776707234f},
};

namespace {

struct Vec3 {
    Expr r, g, b;
};

Vec3 matVecConst(const float m[3][3], Vec3 v) {
    return Vec3{
        m[0][0] * v.r + m[1][0] * v.g + m[2][0] * v.b,
        m[0][1] * v.r + m[1][1] * v.g + m[2][1] * v.b,
        m[0][2] * v.r + m[1][2] * v.g + m[2][2] * v.b,
    };
}

Expr smoothstepGlsl(float edge0, float edge1, Expr x) {
    Expr t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// AgX sigmoid polynomial, Horner form of the shader's degree-7 expansion.
Expr agxSigmoid(Expr x) {
    return Expr(-17.86f) * x * x * x * x * x * x * x +
           Expr(78.01f) * x * x * x * x * x * x +
           Expr(-126.7f) * x * x * x * x * x + Expr(92.06f) * x * x * x * x +
           Expr(-28.72f) * x * x * x + Expr(4.361f) * x * x + Expr(-0.1718f) * x +
           Expr(0.002857f);
}

Expr gamutScale(Expr d, Expr a) {
    return select(abs(d) < 0.000001f, 1.0f,
                  select(d > 0.0f, (1.0f - a) / d, -a / d));
}

Expr srgbOetfCh(Expr l) {
    Expr lo = 12.92f * l;
    Expr hi = 1.055f * pow(l, 1.0f / 2.4f) - 0.055f;
    return select(l <= 0.0031308f, lo, hi);
}

}  // namespace

class BguLook : public Halide::Generator<BguLook> {
public:
    // Low-res quad texels (R, Gr, Gb, B), normalized u8 like the VF export.
    Input<Buffer<uint8_t>> quad_{"quad", 3};
    // HAL lens-shading map, Camera2 order (R, G-even, G-odd, B) floats.
    Input<Buffer<float>> lens_{"lens", 3};
    // RAW branch: sanitized WB gains [R, Gr, Gb, B] + HAL CCM [col][row].
    Input<Buffer<float>> wb_{"wb", 1};
    Input<Buffer<float>> ccm_{"ccm", 2};
    // JPEG branch: calibrated camera-to-ACEScg [col][row] + neutral white.
    Input<Buffer<float>> camToAces_{"camToAces", 2};
    Input<Buffer<float>> camWhite_{"camWhite", 1};

    Input<int> jpeg_{"jpeg"};
    Input<float> exposureEv_{"exposureEv"};
    Input<float> agxContrast_{"agxContrast"};
    Input<float> agxSaturation_{"agxSaturation"};
    Input<float> agxPurity_{"agxPurity"};
    Input<float> agxHue_{"agxHue"};
    Input<float> agxShadowEv_{"agxShadowEv"};
    Input<float> agxHighlightEv_{"agxHighlightEv"};
    Input<float> agxGamut_{"agxGamut"};
    Input<float> highlightShoulder_{"highlightShoulder"};
    Input<int> displayP3_{"displayP3"};
    // Lens geometry: sensor quad origin of low-res texel (0,0), sensor-pixel
    // step per low-res texel, active array, green-row parity, apply flag.
    Input<int> applyLens_{"applyLens"};
    Input<int> lensGreenRow_{"lensGreenRow"};
    Input<int> quadBaseX_{"quadBaseX"};
    Input<int> quadBaseY_{"quadBaseY"};
    Input<int> lowStep_{"lowStep"};
    Input<int> activeL_{"activeL"};
    Input<int> activeT_{"activeT"};
    Input<int> activeR_{"activeR"};
    Input<int> activeB_{"activeB"};

    Output<Buffer<float>> guide_{"guide", 3};
    Output<Buffer<float>> developed_{"developed", 3};

    void generate() {
        Var x("x"), y("y"), c("c");

        auto qch = [&](int ch) { return cast<float>(quad_(x, y, ch)) / 255.0f; };
        Expr qr = qch(0), qgr = qch(1), qgb = qch(2), qb = qch(3);

        // Guide: unlensed quad with merged greens. The lens gain is part of
        // the OPERATOR (below), never of the guide the grid slices on.
        guide_(x, y, c) = select(c == 0, qr, select(c == 1, (qgr + qgb) * 0.5f, qb));

        // Lens gain: manual bilinear over the float map with CLAMP_TO_EDGE,
        // matching the shader's hardware LINEAR sample of the same UV.
        Expr qx = quadBaseX_ + x * lowStep_;
        Expr qy = quadBaseY_ + y * lowStep_;
        Expr aw = cast<float>(max(activeR_ - activeL_ - 1, 1));
        Expr ah = cast<float>(max(activeB_ - activeT_ - 1, 1));
        Expr nuvx = clamp((cast<float>(qx - activeL_)) / aw, 0.0f, 1.0f);
        Expr nuvy = clamp((cast<float>(qy - activeT_)) / ah, 0.0f, 1.0f);
        Expr cols = cast<float>(lens_.width());
        Expr rows = cast<float>(lens_.height());
        Expr uvx = (nuvx * (cols - 1.0f) + 0.5f) / cols;
        Expr uvy = (nuvy * (rows - 1.0f) + 0.5f) / rows;
        Expr tx = uvx * cols - 0.5f;
        Expr ty = uvy * rows - 0.5f;
        Expr ix0 = clamp(cast<int>(floor(tx)), 0, lens_.width() - 1);
        Expr iy0 = clamp(cast<int>(floor(ty)), 0, lens_.height() - 1);
        Expr ix1 = clamp(ix0 + 1, 0, lens_.width() - 1);
        Expr iy1 = clamp(iy0 + 1, 0, lens_.height() - 1);
        Expr fx = tx - floor(tx);
        Expr fy = ty - floor(ty);
        auto lensAt = [&](Expr ix, Expr iy, int ch) { return lens_(ix, iy, ch); };
        auto bilerp = [&](int ch) {
            return lerp(lerp(lensAt(ix0, iy0, ch), lensAt(ix1, iy0, ch), fx),
                        lerp(lensAt(ix0, iy1, ch), lensAt(ix1, iy1, ch), fx), fy);
        };
        Expr lgR = bilerp(0), lgGe = bilerp(1), lgGo = bilerp(2), lgB = bilerp(3);
        Expr oddRow = ((qy + lensGreenRow_) % 2) != 0;
        Expr gGr = select(oddRow, lgGo, lgGe);
        Expr gGb = select(oddRow, lgGe, lgGo);
        Expr useLens = applyLens_ != 0;
        Expr br = qr * select(useLens, lgR, 1.0f);
        Expr bgr = qgr * select(useLens, gGr, 1.0f);
        Expr bgb = qgb * select(useLens, gGb, 1.0f);
        Expr bb = qb * select(useLens, lgB, 1.0f);

        // RAW branch: WB gains, CCM on merged greens, +1 EV, Reinhard, gamma.
        Expr rawR = br * wb_(0);
        Expr rawGr = bgr * wb_(1);
        Expr rawGb = bgb * wb_(2);
        Expr rawB = bb * wb_(3);
        Expr rawG = (rawGr + rawGb) * 0.5f;
        // Column-major mat3 * vec, out[row] = sum_col M[col][row] * v[col].
        auto ccmRow = [&](int row) {
            return ccm_(0, row) * rawR + ccm_(1, row) * rawG + ccm_(2, row) * rawB;
        };
        Expr rawOutR = pow(max(ccmRow(0), 0.0f) * 2.0f /
                               (max(ccmRow(0), 0.0f) * 2.0f + 1.0f),
                           1.0f / 2.2f);
        Expr rawOutG = pow(max(ccmRow(1), 0.0f) * 2.0f /
                               (max(ccmRow(1), 0.0f) * 2.0f + 1.0f),
                           1.0f / 2.2f);
        Expr rawOutB = pow(max(ccmRow(2), 0.0f) * 2.0f /
                               (max(ccmRow(2), 0.0f) * 2.0f + 1.0f),
                           1.0f / 2.2f);

        // JPEG branch: the save-path AgX sequence from the shared tail.
        Vec3 cam0{br, (bgr + bgb) * 0.5f, bb};
        Vec3 cam{max(cam0.r, 0.0f), max(cam0.g, 0.0f), max(cam0.b, 0.0f)};
        Expr wR = smoothstepGlsl(0.70f, 0.99f, cam.r);
        Expr wG = smoothstepGlsl(0.70f, 0.99f, cam.g);
        Expr wB = smoothstepGlsl(0.70f, 0.99f, cam.b);
        Expr wBlend = max(wR, max(wG, wB));
        Expr evGain = pow(2.0f, exposureEv_);
        Vec3 camW{
            lerp(cam.r, camWhite_(0), wBlend) * evGain,
            lerp(cam.g, camWhite_(1), wBlend) * evGain,
            lerp(cam.b, camWhite_(2), wBlend) * evGain,
        };
        auto acesRow = [&](int row) {
            return camToAces_(0, row) * camW.r + camToAces_(1, row) * camW.g +
                   camToAces_(2, row) * camW.b;
        };
        Vec3 toAces{acesRow(0), acesRow(1), acesRow(2)};
        Vec3 rec = matVecConst(ACESCG_TO_REC2020, toAces);
        Vec3 scene{max(rec.r, 0.0f), max(rec.g, 0.0f), max(rec.b, 0.0f)};
        auto shoulder = [&](Expr s) {
            Expr t = (s - 0.9f) / 0.8f;
            Expr comp = 0.9f + 0.8f * (1.0f - exp(-t));
            return lerp(s, comp, clamp(highlightShoulder_, 0.0f, 1.0f));
        };
        Vec3 sh{
            select((scene.r > 0.9f) && (highlightShoulder_ > 0.0f), shoulder(scene.r), scene.r),
            select((scene.g > 0.9f) && (highlightShoulder_ > 0.0f), shoulder(scene.g), scene.g),
            select((scene.b > 0.9f) && (highlightShoulder_ > 0.0f), shoulder(scene.b), scene.b),
        };
        Vec3 v0 = matVecConst(AGX_INSET, sh);
        const float invLn2 = 1.4426950408889634f;
        Vec3 vlog{
            log(max(v0.r, 1e-10f)) * invLn2,
            log(max(v0.g, 1e-10f)) * invLn2,
            log(max(v0.b, 1e-10f)) * invLn2,
        };
        Expr evRange = agxShadowEv_ + agxHighlightEv_;
        Expr pivot = agxShadowEv_ / evRange;
        Expr lo = -2.473931188f - agxShadowEv_;
        auto normCh = [&](Expr v) { return clamp((v - lo) / evRange, 0.0f, 1.0f); };
        Vec3 vn{normCh(vlog.r), normCh(vlog.g), normCh(vlog.b)};
        Vec3 vc{
            clamp(pivot + (vn.r - pivot) * agxContrast_, 0.0f, 1.0f),
            clamp(pivot + (vn.g - pivot) * agxContrast_, 0.0f, 1.0f),
            clamp(pivot + (vn.b - pivot) * agxContrast_, 0.0f, 1.0f),
        };
        Vec3 vs{agxSigmoid(vc.r), agxSigmoid(vc.g), agxSigmoid(vc.b)};
        Vec3 vo = matVecConst(AGX_OUTSET, vs);
        Vec3 vp{
            lerp(vs.r, vo.r, agxPurity_),
            lerp(vs.g, vo.g, agxPurity_),
            lerp(vs.b, vo.b, agxPurity_),
        };
        Vec3 vpow{
            pow(max(vp.r, 0.0f), 2.2f),
            pow(max(vp.g, 0.0f), 2.2f),
            pow(max(vp.b, 0.0f), 2.2f),
        };
        Expr mappedLuma = vpow.r * 0.2627f + vpow.g * 0.6780f + vpow.b * 0.0593f;
        Expr sceneLuma = scene.r * 0.2627f + scene.g * 0.6780f + scene.b * 0.0593f;
        Expr ratio = select(sceneLuma > 1e-9f, mappedLuma / max(sceneLuma, 1e-9f), 1.0f);
        Vec3 vh{
            lerp(vpow.r, scene.r * ratio, agxHue_),
            lerp(vpow.g, scene.g * ratio, agxHue_),
            lerp(vpow.b, scene.b * ratio, agxHue_),
        };
        Expr gsat = vh.r * 0.2627f + vh.g * 0.6780f + vh.b * 0.0593f;
        Vec3 vsat{
            gsat + agxSaturation_ * (vh.r - gsat),
            gsat + agxSaturation_ * (vh.g - gsat),
            gsat + agxSaturation_ * (vh.b - gsat),
        };
        // Output matrix select must stay an expression (no Func branch).
        auto outMatRow = [&](int row) {
            Expr srgb = REC2020_TO_SRGB[0][row] * vsat.r + REC2020_TO_SRGB[1][row] * vsat.g +
                        REC2020_TO_SRGB[2][row] * vsat.b;
            Expr p3 = REC2020_TO_DISPLAY_P3[0][row] * vsat.r + REC2020_TO_DISPLAY_P3[1][row] * vsat.g +
                      REC2020_TO_DISPLAY_P3[2][row] * vsat.b;
            return select(displayP3_ != 0, p3, srgb);
        };
        Vec3 vd{outMatRow(0), outMatRow(1), outMatRow(2)};
        Expr anchor = clamp(vd.r * 0.2126f + vd.g * 0.7152f + vd.b * 0.0722f, 0.0f, 1.0f);
        Expr dr = vd.r - anchor, dg = vd.g - anchor, db = vd.b - anchor;
        Expr sc = min(gamutScale(dr, anchor), min(gamutScale(dg, anchor), gamutScale(db, anchor)));
        Expr scm = lerp(1.0f, clamp(sc, 0.0f, 1.0f), agxGamut_);
        Vec3 vg{anchor + scm * dr, anchor + scm * dg, anchor + scm * db};
        Vec3 enc{
            srgbOetfCh(clamp(vg.r, 0.0f, 1.0f)),
            srgbOetfCh(clamp(vg.g, 0.0f, 1.0f)),
            srgbOetfCh(clamp(vg.b, 0.0f, 1.0f)),
        };

        Expr isJpeg = jpeg_ != 0;
        developed_(x, y, c) =
            select(c == 0, select(isJpeg, enc.r, rawOutR),
                   select(c == 1, select(isJpeg, enc.g, rawOutG), select(isJpeg, enc.b, rawOutB)));

        // Manual CPU schedule with small-extent-safe tails (see README).
        guide_.compute_root()
            .parallel(y, 8, TailStrategy::GuardWithIf)
            .vectorize(x, 8, TailStrategy::GuardWithIf);
        developed_.compute_root()
            .parallel(y, 8, TailStrategy::GuardWithIf)
            .vectorize(x, 8, TailStrategy::GuardWithIf);
    }
};

HALIDE_REGISTER_GENERATOR(BguLook, bgu_look)
