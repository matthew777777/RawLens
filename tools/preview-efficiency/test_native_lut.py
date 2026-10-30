#!/usr/bin/env python3
"""Run the production LUT-upload function against fake JNI/Vulkan on the host.

Checks content changes, tail clearing, context reset and failed-flush retries.
This does not validate Android driver synchronization or GPU execution.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / 'app/src/main/cpp/vf_vulkan_vf_viewfinder.inc').read_text()
start = source.index('extern "C" JNIEXPORT jint JNICALL\nJava_com_matthew_rawlens_VfVulkan_uploadLutNative(')
end = source.index('\nextern "C"', start + 1)
production = source[start:end]
preamble = r'''
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <mutex>
#include <vector>
#define JNIEXPORT
#define JNICALL
#define LOGW(...) ((void)0)
using jint = int; using jsize = int; using jbyte = int8_t;
using jobject = void*; using jbyteArray = std::vector<jbyte>*;
constexpr int VFVK_OK=0, VFVK_NOT_INITIALIZED=-1, VFVK_BAD_ARGUMENT=-2, VFVK_SUBMIT_FAILED=-3;
constexpr int VFVK_BUSY=10, VFVK_DEVICE_LOST=9, VK_ERROR_DEVICE_LOST=-4;
using VkResult=int;
constexpr uint32_t kLutSites=4, kLutCodes=65536;
constexpr int VK_SUCCESS=0, VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE=1;
constexpr uint64_t VK_WHOLE_SIZE=~uint64_t(0);
struct VkMappedMemoryRange {int sType; void* memory; uint64_t offset, size;};
struct JNIEnv {
    bool exception=false;
    jsize GetArrayLength(jbyteArray a) {return a->size();}
    void GetByteArrayRegion(jbyteArray a, int off, int n, jbyte* dst) {
        std::copy_n(a->data()+off,n,dst);
    }
    bool ExceptionCheck() {return exception;}
};
struct Context {
    void* lutMapped=nullptr; void* lutMemory=nullptr; void* device=nullptr;
    bool lutCoherent=false, lutUploadValid=false, computeArmed=false;
    int fence=0;
    uint32_t lutRow=0;
    std::vector<jbyte> lutUploadScratch, lutLastUpload;
};
Context* g=nullptr; std::mutex vkMutex;
int flushes=0; bool failFlush=false;
int fenceStatus=VK_SUCCESS;
int vkGetFenceStatus(void*,int) {return fenceStatus;}
int ensureLut(Context*) {return VFVK_OK;}
int vkFlushMappedMemoryRanges(void*,int,VkMappedMemoryRange*) {
    ++flushes; return failFlush ? -1 : VK_SUCCESS;
}
'''
checks = r'''
int main() {
    JNIEnv env;
    std::vector<uint8_t> mapped(kLutSites*kLutCodes,0);
    Context ctx; ctx.lutMapped=mapped.data(); g=&ctx;
    auto upload = [&](std::vector<jbyte>& data) {
        return Java_com_matthew_rawlens_VfVulkan_uploadLutNative(&env,nullptr,data.size()/4-1,&data);
    };
    auto check = [&](const std::vector<jbyte>& data) {
        size_t row=data.size()/4;
        for (size_t s=0;s<4;++s) for (size_t i=0;i<kLutCodes;++i)
            assert(mapped[s*kLutCodes+i] == (i<row ? uint8_t(data[s*row+i]) : 255));
    };
    std::vector<jbyte> a(4*1024,12);
    assert(upload(a)==0); assert(flushes==1); check(a);
    assert(upload(a)==0); assert(flushes==1);
    auto same=a; assert(upload(same)==0); assert(flushes==1);
    a[4]=23; assert(upload(a)==0); assert(flushes==2); check(a);
    std::vector<jbyte> small(4*128,3);
    assert(upload(small)==0); assert(flushes==3); check(small);
    assert(upload(a)==0); assert(flushes==4); check(a);
    failFlush=true; a[5]=34;
    assert(upload(a)==VFVK_SUBMIT_FAILED); assert(!ctx.lutUploadValid);
    assert(upload(a)==VFVK_SUBMIT_FAILED); assert(flushes==6);
    failFlush=false; assert(upload(a)==0); assert(flushes==7); check(a);
    assert(upload(a)==0); assert(flushes==7);
    // Recreated backing storage must be populated even with identical inputs.
    std::fill(mapped.begin(),mapped.end(),0); ctx.lutRow=0; ctx.lutUploadValid=false;
    assert(upload(a)==0); assert(flushes==8); check(a);
    ctx.lutCoherent=true; a[6]=45;
    assert(upload(a)==0); assert(flushes==8); check(a);
    assert(upload(a)==0); assert(flushes==8);
    // A timed-out dispatch may still read the old table. Do not write into it
    // or bind descriptors until its fence completes; BUSY is backpressure.
    ctx.computeArmed=true; fenceStatus=1; a[7]=56;
    auto previous=mapped;
    assert(upload(a)==VFVK_BUSY); assert(mapped==previous); assert(ctx.computeArmed);
    fenceStatus=VK_ERROR_DEVICE_LOST;
    assert(upload(a)==VFVK_DEVICE_LOST); assert(mapped==previous);
    fenceStatus=VK_SUCCESS;
    assert(upload(a)==0); assert(!ctx.computeArmed); check(a);
    std::puts("Native LUT content/tails/reset/flush-retry checks PASS");
}
'''
with tempfile.TemporaryDirectory(prefix='rawlens-lut-check-') as temp:
    cpp = Path(temp) / 'test.cpp'
    binary = Path(temp) / 'test'
    cpp.write_text(preamble + production + checks)
    subprocess.run(['c++', '-std=c++14', '-Wall', '-Wextra', '-Werror', str(cpp), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
