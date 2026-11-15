// RawLens diagnostic harness for rawr's GPL-3.0-only block-CFA converter.
// Source pixels remain unchanged. Output is conventional Bayer at half width/height.
#include "third_party/rawr/CfaRemosaicFast.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

using namespace rawrcam::imaging;
using Clock = std::chrono::steady_clock;

static void check(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}
static std::vector<uint16_t> convert(const std::vector<uint16_t>& src, uint32_t w,
                                    uint32_t h, uint32_t order, float black,
                                    float white, uint32_t threads, bool report) {
    check(w >= 16 && h >= 16 && w % 8 == 0 && h % 8 == 0, "Dimensions must be multiples of 8, at least 16");
    check(order < 4 && threads >= 1 && threads <= 32, "Invalid CFA order or thread count");
    check(black >= 0 && white > black && white <= 65535, "Invalid black/white levels");
    check(src.size() == size_t(w) * h, "Input size mismatch");
    std::vector<uint16_t> out(size_t(w / 2) * (h / 2));
    auto a = Clock::now();
    const auto model = buildBlockCfaModel(src.data(), w, h, 4, order, black, white, threads);
    check(model.valid(), "Invalid block-CFA model");
    auto b = Clock::now();
    binBlockCfaToBayer(src.data(), model, out.data(), threads);
    auto c = Clock::now();
    if (report) {
        const auto ms = [](auto t) { return std::chrono::duration<double, std::milli>(t).count(); };
        std::cout << "{\"width\":" << w / 2 << ",\"height\":" << h / 2
                  << ",\"block\":1,\"order\":" << order << ",\"black\":" << black
                  << ",\"white\":" << white << ",\"model_ms\":" << ms(b - a)
                  << ",\"conversion_ms\":" << ms(c - b) << ",\"total_ms\":" << ms(c - a) << "}\n";
    }
    return out;
}
static void selftest() {
    constexpr uint32_t w = 64, h = 48;
    for (uint32_t order = 0; order < 4; ++order) {
        std::vector<uint16_t> raw(w * h);
        for (uint32_t y = 0; y < h; ++y)
            for (uint32_t x = 0; x < w; ++x)
                raw[y * w + x] = uint16_t(164 + 100 * cfafast::colourAt(order, (y / 4) % 2, (x / 4) % 2));
        auto original = raw;
        auto out = convert(raw, w, h, order, 64, 1023, 1, false);
        auto parallel = convert(raw, w, h, order, 64, 1023, 4, false);
        check(raw == original, "Converter mutated source");
        check(out == parallel, "Thread count changes output");
        for (uint32_t y = 0; y < h / 2; ++y)
            for (uint32_t x = 0; x < w / 2; ++x) {
                int expected = 164 + 100 * cfafast::colourAt(order, y % 2, x % 2);
                if (std::abs(int(out[y * (w / 2) + x]) - expected) > 3) {
                    std::cerr << "order=" << order << " x=" << x << " y=" << y << " expected=" << expected << " actual=" << out[y * (w / 2) + x] << "\n";
                    check(false, "Output is not expected Bayer colour");
                }
            }
        for (uint16_t flat : {uint16_t(64), uint16_t(1023)}) {
            std::fill(raw.begin(), raw.end(), flat);
            out = convert(raw, w, h, order, 64, 1023, 4, false);
            check(std::all_of(out.begin(), out.end(), [=](auto v) { return v == flat; }), "Black or saturation changed");
        }
    }
    std::cout << "PASS: four CFA orders, source preservation, thread invariance, black and saturation\n";
}
int main(int argc, char** argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--selftest") { selftest(); return 0; }
        check(argc == 9, "Usage: convert input.raw16 output.raw16 width height order black white threads; or --selftest");
        const auto number = [](const char* v) { size_t used; auto n = std::stoul(v, &used); check(used == std::string(v).size() && n <= UINT32_MAX, "Invalid integer"); return uint32_t(n); };
        uint32_t w = number(argv[3]), h = number(argv[4]);
        check(w > 0 && h > 0 && uint64_t(w) * h <= 250000000, "Input too large");
        std::ifstream input(argv[1], std::ios::binary | std::ios::ate);
        check(bool(input) && input.tellg() == std::streamoff(uint64_t(w) * h * 2), "Expected tightly packed little-endian RAW16");
        check(!std::ifstream(argv[2]).good(), "Output exists; choose a new path");
        std::vector<uint16_t> raw(size_t(w) * h);
        input.seekg(0); input.read(reinterpret_cast<char*>(raw.data()), std::streamsize(raw.size() * 2));
        check(bool(input), "Failed to read input");
        auto out = convert(raw, w, h, number(argv[5]), float(number(argv[6])), float(number(argv[7])), number(argv[8]), true);
        std::ofstream output(argv[2], std::ios::binary);
        output.write(reinterpret_cast<const char*>(out.data()), std::streamsize(out.size() * 2));
        check(bool(output), "Failed to write output");
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
