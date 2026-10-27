// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <climits>
#include <initializer_list>

// JNI arrays and Halide strides use signed 32-bit extents. Validate before
// multiplying or pinning any array; -1 can never match a JNI array length.
inline int bguElementCount(std::initializer_list<int> dims) {
    int count = 1;
    for (int dim : dims) {
        if (dim <= 0 || count > INT_MAX / dim) return -1;
        count *= dim;
    }
    return count;
}
