// SPDX-License-Identifier: GPL-3.0-or-later
// Independent double-precision BGU oracle: directly accumulate weighted
// samples, then pivoted Gaussian elimination. No packed Gram channels,
// separable Func blurs, or Halide solver are reused. Reference: google/bgu
// f2d6f2d, src/halide/fit_and_slice_3x4.cpp, fast approximation (2016).
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <vector>
#include "HalideBuffer.h"
#include "bgu_fit.h"
using Halide::Runtime::Buffer;

static double weight(int d) {
    return std::abs(d) <= 3 ? 1.0 / std::pow(std::abs(d) + 1.0, 3) : 0.0;
}

static std::array<double, 12> reference(const Buffer<float> &guide,
                                      const Buffer<float> &dev,
                                      int gx, int gy, int gz, int s, float r,
                                      float lambda) {
    double a[4][7] = {};
    for (int by = gy - 3; by <= gy + 3; ++by)
        for (int bx = gx - 3; bx <= gx + 3; ++bx)
            for (int dy = 0; dy < s; ++dy)
                for (int dx = 0; dx < s; ++dx) {
                    int x = std::clamp(bx * s + dx - s / 2, 0, guide.width() - 1);
                    int y = std::clamp(by * s + dy - s / 2, 0, guide.height() - 1);
                    double v[4] = {guide(x,y,0), guide(x,y,1), guide(x,y,2), 1};
                    // Halide round uses nearest with ties to even (nearbyint at
                    // the default host rounding mode), including exact half bins.
                    float luma = std::clamp(0.25f * guide(x,y,0) +
                        0.5f * guide(x,y,1) + 0.25f * guide(x,y,2), 0.f, 1.f);
                    int z = (int)std::nearbyint(luma * (1.f / r));
                    double w = weight(bx-gx) * weight(by-gy) * weight(z-gz);
                    for (int i = 0; i < 4; ++i) {
                        for (int j = 0; j < 4; ++j) a[i][j] += w * v[i] * v[j];
                        for (int c = 0; c < 3; ++c) a[i][4+c] += w * v[i] * dev(x,y,c);
                    }
                }
    double n = a[3][3];
    double epsilon = 1e-6 * (n + 1);
    double gain = (a[3][4] + 2*a[3][5] + a[3][6] + epsilon) /
                  (a[3][0] + 2*a[3][1] + a[3][2] + epsilon);
    double wl = lambda * (n+1);
    for (int i=0; i<4; ++i) a[i][i] += wl;
    for (int c=0; c<3; ++c) a[c][4+c] += wl * gain;
    for (int k=0; k<4; ++k) {
        int pivot=k;
        for (int i=k+1; i<4; ++i) if (std::abs(a[i][k])>std::abs(a[pivot][k])) pivot=i;
        for (int j=0; j<7; ++j) std::swap(a[k][j], a[pivot][j]);
        double diag=a[k][k];
        for (int j=k; j<7; ++j) a[k][j] /= diag;
        for (int i=0; i<4; ++i) if (i!=k) {
            double f=a[i][k];
            for (int j=k; j<7; ++j) a[i][j] -= f*a[k][j];
        }
    }
    std::array<double,12> out;
    for (int c=0; c<3; ++c) for (int k=0; k<4; ++k) out[c*4+k]=a[k][4+c];
    return out;
}

int main() {
    int cases=0;
    for (auto shape : {std::array<int,2>{1,1}, {7,5}, {17,13}})
        for (int mode=0; mode<6; ++mode) {
            int w=shape[0], h=shape[1], s=4;
            float r=0.125f, lambda=1e-6f;
            Buffer<float> guide(w,h,3), dev(w,h,3);
            unsigned seed=12345;
            for (int y=0; y<h; ++y) for (int x=0; x<w; ++x) for (int c=0; c<3; ++c) {
                seed=seed*1664525u+1013904223u;
                float v=mode==0 ? 0.f : mode==1 ? 1.f : mode==2 ? (c+1)*0.07f :
                    mode==3 ? ((x<w/2) ? 0.02f : 0.9f) :
                    mode==5 ? (2*((x+y)%8)+1)/16.f : (seed>>8)/16777216.f;
                guide(x,y,c)=v;
                dev(x,y,c)=v*(0.7f+c*0.1f)+0.01f*(c+1);
            }
            int gw=(w+s-1)/s, gh=(h+s-1)/s, gz=9;
            Buffer<float> grid(gw,gh,gz,12);
            if (bgu_fit(guide,dev,s,r,lambda,grid)) return 1;
            double coeffMax=0, predictionMax=0;
            std::vector<std::array<double,12>> refs(gw*gh*gz);
            for (int z=0; z<gz; ++z) for (int y=0; y<gh; ++y) for (int x=0; x<gw; ++x) {
                auto expected=reference(guide,dev,x,y,z,s,r,lambda);
                refs[x+gw*(y+gh*z)]=expected;
                for (int c=0; c<12; ++c) {
                    double got=grid(x,y,z,c);
                    if (!std::isfinite(got)) return 1;
                    coeffMax=std::max(coeffMax,std::abs(got-expected[c]));
                }
            }
            // Compare trilinear predictions at the actual training pixels,
            // including spatial and intensity boundaries. An individual grid
            // cell outside the data support is not an observable model.
            for (int py=0; py<h; ++py) for (int px=0; px<w; ++px) for (int c=0; c<3; ++c) {
                double coord[3]={double(px)/s, double(py)/s,
                    std::clamp(0.25*guide(px,py,0)+0.5*guide(px,py,1)+0.25*guide(px,py,2),0.,1.)/r};
                int dims[3]={gw,gh,gz};
                double diff=0;
                for(int dz=0; dz<2; ++dz) for(int dy=0; dy<2; ++dy) for(int dx=0; dx<2; ++dx) {
                    int delta[3]={dx,dy,dz}, cell[3];
                    double wt=1;
                    for(int k=0;k<3;++k) {
                        int base=(int)std::floor(coord[k]);
                        double f=coord[k]-base;
                        wt*=delta[k] ? f : 1-f;
                        cell[k]=std::clamp(base+delta[k],0,dims[k]-1);
                    }
                    auto &expected=refs[cell[0]+gw*(cell[1]+gh*cell[2])];
                    for(int k=0;k<4;++k) {
                        double v=k==3 ? 1. : guide(px,py,k);
                        diff+=wt*v*(grid(cell[0],cell[1],cell[2],c*4+k)-expected[c*4+k]);
                    }
                }
                predictionMax=std::max(predictionMax,std::abs(diff));
            }
            printf("oracle %dx%d mode=%d coeffMax=%.6g predictionMax=%.6g\n",w,h,mode,coeffMax,predictionMax);
            // One quarter of an 8-bit code in the observable sliced output.
            // Coefficient errors are diagnostic in under-constrained cells.
            if (predictionMax>0.25/255) return 1;
            ++cases;
        }
    printf("independent BGU oracle OK: %d cases\n",cases);
}
