// SPDX-License-Identifier: GPL-3.0-only
// Adapted from PhotonCamera MCRAW tools/mcraw/roundtrip.cpp.
#include <MediaCinemaRAW/Encoder.h>
#include <MediaCinemaRAW/Decoder.h>
#include <motioncam/RawData.hpp>
#include <motioncam/Decoder.hpp>
#include <cassert>
#include <fstream>
#include <iostream>
#include <random>
#include <cstring>

int main(int argc, char** argv) {
    std::mt19937 random(7139);
    int cases = 0;
    for (bool raw10 : {false,true}) for (bool bin : {false,true})
    for (int width : {4,64,68,128,260}) for (int mode = 0; mode < 12; ++mode) {
        int height = 24, top = 2, cropped = 16;
        int stride = (raw10 ? width/4*5 : width*2)+24;
        std::vector<uint16_t> pixels(width*height);
        for (auto& p : pixels) {
            const unsigned ranges[] = {1,2,4,8,16,32,64,256,1024,4096,16384,65536};
            p = raw10 ? random()%std::min(1024u,ranges[mode]) : random()%ranges[mode];
            if (mode == 0) p = raw10 ? 800 : 50000; // Constant, nonzero references.
        }
        // Last row deliberately omits padding, as Android Image planes may do.
        std::vector<uint8_t> raw((height-1)*stride+(raw10 ? width/4*5 : width*2),0xAD);
        for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
            uint16_t v = pixels[y*width+x];
            if (raw10) {
                raw[y*stride+x/4*5+x%4] = v>>2;
                if (x%4 == 0) raw[y*stride+x/4*5+4] = 0;
                raw[y*stride+x/4*5+4] |= (v&3) << (2*(x%4));
            } else {
                raw[y*stride+x*2] = v; raw[y*stride+x*2+1] = v>>8;
            }
        }
        std::vector<uint8_t> encoded;
        mediacinemaraw::encode(raw.data(),raw.size(),width,height,stride,raw10,top,cropped,bin,encoded);
        int w = bin ? width/2 : width, h = bin ? cropped/2 : cropped;
        std::vector<uint16_t> decoded(w*h);
        assert(motioncam::raw::Decode(decoded.data(),w,h,encoded.data(),encoded.size()) == decoded.size());
        std::vector<uint16_t> independent;
        mediacinemaraw::decode(encoded, w, h, independent);
        assert(independent == decoded);
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            unsigned expected;
            if (bin) {
                int sx = x/2*4+x%2, sy = top+y/2*4+y%2;
                expected = (unsigned(pixels[sy*width+sx])+pixels[sy*width+sx+2]
                    +pixels[(sy+2)*width+sx]+pixels[(sy+2)*width+sx+2]+2)/4;
            } else expected = pixels[(top+y)*width+x];
            assert(decoded[y*w+x] == expected);
        }
        ++cases;
    }
    std::vector<uint8_t> raw(64*8*2,255), encoded;
    for (int bad : {0,1,2}) {
        bool threw = false;
        try { mediacinemaraw::encode(raw.data(),bad == 0 ? 10 : raw.size(),64,8,128,false,
                                  bad == 1 ? 1 : 0,bad == 2 ? 6 : 8,false,encoded); }
        catch (const std::invalid_argument&) { threw = true; }
        assert(threw);
    }

    std::cout << "Passed " << cases << " cases against both decoders\n";
}
