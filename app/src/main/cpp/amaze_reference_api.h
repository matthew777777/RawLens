// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <vector>
// Input and output use RawTherapee's native 65535 float scale. No color transform.
std::vector<float> amazeReference(const float* raw, int width, int height,
                                const unsigned* pattern, float initialGain);
