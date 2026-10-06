// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Platform shims for the hdrplus host: Android NDK (AAssetManager +
// android/log) vs desktop (fopen + stdio). The Android path is the
// production path; desktop exists only for tools/hdrplus-parity
// (MoltenVK parity runs of the same host + recorder sources).
#pragma once

#ifdef __ANDROID__
#include <android/asset_manager.h>
#include <android/log.h>
#define HP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "hdrplus", __VA_ARGS__)
#define HP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "hdrplus", __VA_ARGS__)
#else
#include <cstdio>
#define HP_LOGI(...)                                                     \
    do {                                                                 \
        std::printf("[hdrplus] ");                                       \
        std::printf(__VA_ARGS__);                                        \
        std::printf("\n");                                               \
    } while (0)
#define HP_LOGE(...)                                                     \
    do {                                                                 \
        std::fprintf(stderr, "[hdrplus] ");                              \
        std::fprintf(stderr, __VA_ARGS__);                               \
        std::fprintf(stderr, "\n");                                      \
    } while (0)
#endif
