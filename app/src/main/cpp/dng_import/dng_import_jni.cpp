// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include "tinydng.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <vector>
#ifdef RAWLENS_IMPORT_JXL
#include <jxl/decode.h>
#endif

namespace {
struct Document {
    tinydng_context* ctx=nullptr;
    tinydng_document* doc=nullptr;
    ~Document() { if(doc) tinydng_document_destroy(ctx,doc);if(ctx) tinydng_context_destroy(ctx); }
};
float halfValue(uint16_t h) {
    const float sign=(h&0x8000)?-1.f:1.f;
    const int exponent=(h>>10)&31;const int mantissa=h&1023;
    if(exponent==31) return mantissa?NAN:sign*INFINITY;
    if(!exponent) return sign*std::ldexp(float(mantissa),-24);
    return sign*std::ldexp(1.f+mantissa/1024.f,exponent-15);
}
#ifdef RAWLENS_IMPORT_JXL
void decodeJxl(const char* path,const tinydng_image_info* img,float* destination) {
    std::ifstream input(path,std::ios::binary);
    if(!input) throw std::runtime_error("Cannot read JPEG XL DNG");
    const int channels=img->samples_per_pixel;
    for(size_t i=0;i<img->segment_count;++i) {
        const auto& seg=img->segments[i];
        if(seg.byte_count>512u*1024u*1024u) throw std::runtime_error("Oversized JPEG XL DNG segment");
        std::vector<uint8_t> bytes(seg.byte_count);input.seekg(seg.offset);input.read(reinterpret_cast<char*>(bytes.data()),bytes.size());
        if(!input) throw std::runtime_error("Truncated JPEG XL DNG");
        std::unique_ptr<JxlDecoder,decltype(&JxlDecoderDestroy)> decoder(JxlDecoderCreate(nullptr),JxlDecoderDestroy);
        if(!decoder) throw std::runtime_error("Cannot create JPEG XL decoder");
        JxlDecoderSubscribeEvents(decoder.get(),JXL_DEC_BASIC_INFO|JXL_DEC_FULL_IMAGE);
        JxlDecoderSetInput(decoder.get(),bytes.data(),bytes.size());JxlDecoderCloseInput(decoder.get());
        JxlBasicInfo info{};JxlPixelFormat format{uint32_t(channels),JXL_TYPE_FLOAT,JXL_NATIVE_ENDIAN,0};
        std::vector<float> pixels;bool complete=false;
        for(;;) {
            const auto status=JxlDecoderProcessInput(decoder.get());
            if(status==JXL_DEC_BASIC_INFO) {
                if(JxlDecoderGetBasicInfo(decoder.get(),&info)!=JXL_DEC_SUCCESS ||
                    !info.xsize || !info.ysize || uint64_t(info.xsize)*info.ysize>50000000 ||
                    info.num_color_channels+info.num_extra_channels!=uint32_t(channels))
                    throw std::runtime_error("Unsupported JPEG XL DNG channel layout");
            } else if(status==JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
                size_t size=0;
                if(JxlDecoderImageOutBufferSize(decoder.get(),&format,&size)!=JXL_DEC_SUCCESS || size>600000000)
                    throw std::runtime_error("Oversized JPEG XL DNG output");
                pixels.resize(size/sizeof(float));
                if(JxlDecoderSetImageOutBuffer(decoder.get(),&format,pixels.data(),size)!=JXL_DEC_SUCCESS)
                    throw std::runtime_error("Cannot decode JPEG XL DNG output");
            } else if(status==JXL_DEC_FULL_IMAGE) complete=true;
            else if(status==JXL_DEC_SUCCESS) break;
            else throw std::runtime_error("Invalid JPEG XL DNG segment");
        }
        if(!complete || info.xsize<seg.w || info.ysize<seg.h || seg.plane!=0)
            throw std::runtime_error("Incomplete JPEG XL DNG segment");
        const float scale=img->sample_format==3?1.f:float(std::ldexp(1.0,img->bits_per_sample)-1.0);
        for(uint32_t y=0;y<seg.h && seg.y+y<img->height;++y)
            for(uint32_t x=0;x<seg.w && seg.x+x<img->width;++x)
                for(int c=0;c<channels;++c)
                    destination[((size_t(seg.y+y)*img->width+seg.x+x)*channels)+c]=pixels[(size_t(y)*info.xsize+x)*channels+c]*scale;
    }
}
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_DngImportReader_decodeNative(JNIEnv* env,jobject,jstring filename,jint width,jint height,jint channels,jobject output) {
    const char* path=env->GetStringUTFChars(filename,nullptr);
    if(!path) return;
    try {
        auto* dst=static_cast<float*>(env->GetDirectBufferAddress(output));
        const int64_t count=int64_t(width)*height*channels;
        if(!dst || width<=0 || height<=0 || int64_t(width)*height>50000000 ||
            (channels!=1 && channels!=3) || env->GetDirectBufferCapacity(output)<count*4)
            throw std::runtime_error("Invalid DNG output buffer");
        tinydng_error error{};Document document;
        tinydng_config config{};config.max_image_pixels=50000000;config.max_images=64;
        document.ctx=tinydng_context_create(&config,&error);
        if(!document.ctx) throw std::runtime_error(error.message);
        tinydng_open_options open{};open.flags=TINYDNG_OPEN_PARSE_SUBIFDS|TINYDNG_OPEN_PREFER_MMAP|TINYDNG_OPEN_METADATA_ONLY;
        if(tinydng_open_file(document.ctx,path,&open,&document.doc,&error)!=TINYDNG_OK) throw std::runtime_error(error.message);
        // Prefer a CFA image for one plane; a single-plane LinearRaw (B&W) DNG has no CFA and is the fallback.
        size_t chosen=SIZE_MAX, linear=SIZE_MAX;
        for(size_t i=0;i<tinydng_image_count(document.doc);++i) {
            const auto* image=tinydng_image_get(document.doc,i);
            if(image->width==uint32_t(width) && image->height==uint32_t(height) && image->samples_per_pixel==channels) {
                if(channels!=1 || image->cfa.present) { chosen=i;break; }
                if(linear==SIZE_MAX) linear=i;
            }
        }
        if(chosen==SIZE_MAX) chosen=linear;
        if(chosen==SIZE_MAX) throw std::runtime_error("Cannot locate full-resolution DNG image");
        const auto* image=tinydng_image_get(document.doc,chosen);
        if(image->compression==52546) {
#ifdef RAWLENS_IMPORT_JXL
            decodeJxl(path,image,dst);
#else
            throw std::runtime_error("JPEG XL DNG decoder unavailable");
#endif
        } else {
            tinydng_pixels pixels{};tinydng_decode_options options{};options.num_threads=1;
            if(tinydng_decode_image(document.ctx,document.doc,chosen,&options,&pixels,&error)!=TINYDNG_OK)
                throw std::runtime_error(error.message);
            try {
                const size_t size=size_t(count)*(pixels.bits_per_sample/8);
                if(pixels.size<size || (pixels.bits_per_sample!=8 && pixels.bits_per_sample!=16 && pixels.bits_per_sample!=32))
                    throw std::runtime_error("Unsupported DNG sample depth");
                for(int64_t i=0;i<count;++i) {
                    float value;
                    if(pixels.sample_format==3) {
                        if(pixels.bits_per_sample==32) std::memcpy(&value,pixels.data+i*4,4);
                        else if(pixels.bits_per_sample==16) { uint16_t half;std::memcpy(&half,pixels.data+i*2,2);value=halfValue(half); }
                        else throw std::runtime_error("Unsupported floating DNG depth");
                    } else if(pixels.sample_format==1) {
                        if(pixels.bits_per_sample==8) value=pixels.data[i];
                        else if(pixels.bits_per_sample==16) { uint16_t code;std::memcpy(&code,pixels.data+i*2,2);value=code; }
                        else { uint32_t code;std::memcpy(&code,pixels.data+i*4,4);value=float(code); }
                    } else throw std::runtime_error("Signed DNG samples are not supported");
                    dst[i]=value;
                }
            } catch(...) { tinydng_pixels_free(document.ctx,&pixels);throw; }
            tinydng_pixels_free(document.ctx,&pixels);
        }
    } catch(const std::exception& error) {
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),error.what());
    }
    env->ReleaseStringUTFChars(filename,path);
}
