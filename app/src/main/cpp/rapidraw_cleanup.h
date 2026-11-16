// SPDX-License-Identifier: AGPL-3.0-only
// Numerical port of RapidRAW image_processing.rs, commit 1a39ae4e76d825c5abf0d4bafee798f99e0a0b63.
// Upstream: https://github.com/CyberTimon/RapidRAW (Timon Käch and contributors).
#pragma once
#include <vector>
namespace rapidraw {
// Scene-linear sRGB, packed RGB. No exposure, white balance or display curve here.
void cleanup(std::vector<float>& rgb, int width, int height,
             float colorAmount = 0.5f, float detailAmount = 0.35f);
}
