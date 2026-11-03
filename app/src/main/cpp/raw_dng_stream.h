// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "tinydng.h"
#include <stdint.h>

/* Lossless RAW16 serialization with bounded strip workspace and no whole-file
 * output buffer. Caller retains ownership of the source pixels and sink. */
static inline tinydng_status rawlens_stream_raw_dng(
    tinydng_context* ctx, tinydng_write_io sink,
    const tinydng_write_image* image, tinydng_error* error) {
    tinydng_write_options options = {0};
    options.compression = TINYDNG_COMPRESSION_NONE;
    options.header_first = 1;
    tinydng_tiling tiling = {0};
    tiling.rows_per_strip = 32;
    tinydng_writer* writer = NULL;
    tinydng_status status = tinydng_writer_create(ctx, sink, image, &options,
                                                 &tiling, &writer, error);
    if (status != TINYDNG_OK) return status;
    for (uint32_t y = 0, index = 0; y < image->height; y += 32, ++index) {
        const uint8_t* pixels = image->data + (size_t)y * image->width * 2;
        status = tinydng_writer_write_strip(writer, index, pixels, error);
        if (status != TINYDNG_OK) {
            /* finish always releases writer state, including incomplete writes. */
            tinydng_error ignored = {TINYDNG_OK};
            tinydng_writer_finish(writer, &ignored);
            return status;
        }
    }
    return tinydng_writer_finish(writer, error);
}
