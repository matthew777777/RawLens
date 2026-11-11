// SPDX-License-Identifier: Apache-2.0
#include "lkr_colour_guard.h"
#include <cstdio>
#define CHECK(x) do { if (!(x)) { std::fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x); return 1; } } while(0)
int main() {
  const int w=147,h=283;
  const std::array<float,3> wb{2,1,1.8f};
  std::vector<uint16_t> reference(size_t(w)*h*4),candidate(reference.size());
  for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
    const size_t i=(size_t(y)*w+x)*4;
    const float l=.1f+.2f*y/h;
    // Opposite saturated colour regions, crossed by a luminance ramp.
    const float u=x<w/2?.12f:-.08f, v=x<w/2?-.05f:.16f;
    const float g=l-.2126f*u-.0722f*v;
    const float ref[3]={(g+u)/wb[0],g,(g+v)/wb[2]};
    const float noise=.01f*((x%4<2)?1:-1)*((y%4<2)?1:-1);
    const float nu=u+.02f+noise, nv=v-.01f-noise*.5f;
    const float ng=l-.2126f*nu-.0722f*nv;
    const float bad[3]={(ng+nu)/wb[0],ng,(ng+nv)/wb[2]};
    for(int c=0;c<3;++c){reference[i+c]=lkr::WriteHalf(ref[c]);candidate[i+c]=lkr::WriteHalf(bad[c]);}
    reference[i+3]=candidate[i+3]=lkr::WriteHalf(.7f);
  }
  // A clean fine-colour pattern shared by both predictions must be bit exact.
  auto detail = reference;
  for (int y=0;y<h;++y) for (int x=0;x<w;++x) {
    const size_t i=(size_t(y)*w+x)*4;
    detail[i]=lkr::WriteHalf(.15f + ((x+y)%2 ? .025f : -.025f));
  }
  const auto clean_detail = detail;
  CHECK(lkr::GuardPhotoChroma(clean_detail.data(),detail.data(),w,h,wb,.01f,.001f,37));
  CHECK(detail==clean_detail);
  const auto original=candidate;
  auto whole=candidate;
  CHECK(lkr::GuardPhotoChroma(reference.data(),candidate.data(),w,h,wb,.0001f,.000001f,37));
  CHECK(lkr::GuardPhotoChroma(reference.data(),whole.data(),w,h,wb,.0001f,.000001f,512));
  float max_band=0,max_y=0;double before=0,after=0,mean_u=0,mean_v=0;int count=0;
  for(int y=0;y<h;++y)for(int x=0;x<w;++x){
    const size_t i=(size_t(y)*w+x)*4;
    float yb=0,ya=0;
    const float weights[3]={.2126f,.7152f,.0722f};
    for(int c=0;c<3;++c){
      max_band=std::max(max_band,std::abs(lkr::ReadHalf(candidate[i+c])-lkr::ReadHalf(whole[i+c])));
      yb+=weights[c]*wb[c]*lkr::ReadHalf(original[i+c]);ya+=weights[c]*wb[c]*lkr::ReadHalf(candidate[i+c]);
    }
    max_y=std::max(max_y,std::abs(ya-yb));
    CHECK(candidate[i+3]==original[i+3]);
    // Exclude reference colour edge and image extension boundary for averages.
    if(x<15||x>w-16||y<15||y>h-16||std::abs(x-w/2)<15)continue;
    const float ru=wb[0]*lkr::ReadHalf(reference[i])-wb[1]*lkr::ReadHalf(reference[i+1]);
    const float rv=wb[2]*lkr::ReadHalf(reference[i+2])-wb[1]*lkr::ReadHalf(reference[i+1]);
    const float bu=wb[0]*lkr::ReadHalf(original[i])-wb[1]*lkr::ReadHalf(original[i+1]);
    const float fu=wb[0]*lkr::ReadHalf(candidate[i])-wb[1]*lkr::ReadHalf(candidate[i+1]);
    const float fv=wb[2]*lkr::ReadHalf(candidate[i+2])-wb[1]*lkr::ReadHalf(candidate[i+1]);
    before+=(bu-ru)*(bu-ru);after+=(fu-ru)*(fu-ru);mean_u+=fu-ru;mean_v+=fv-rv;++count;
    CHECK(std::abs(fu-ru)<.012f);CHECK(std::abs(fv-rv)<.012f);
  }
  CHECK(max_band<=.00025f);CHECK(max_y<.00025f);
  CHECK(std::abs(mean_u/count)<.0005);CHECK(std::abs(mean_v/count)<.0005);
  CHECK(after<before*.2);
  std::vector<uint16_t> neutral(4), tint(4);
  const float ny=.2f, nu=.03f, nv=-.02f, ng=ny-.2126f*nu-.0722f*nv;
  for(int c=0;c<3;++c) neutral[c]=lkr::WriteHalf(ny/wb[c]);
  tint[0]=lkr::WriteHalf((ng+nu)/wb[0]);tint[1]=lkr::WriteHalf(ng/wb[1]);tint[2]=lkr::WriteHalf((ng+nv)/wb[2]);
  neutral[3]=tint[3]=lkr::WriteHalf(.7f);
  CHECK(lkr::GuardPhotoChroma(neutral.data(),tint.data(),1,1,wb,.0001f,.000001f));
  CHECK(std::abs(wb[0]*lkr::ReadHalf(tint[0])-wb[1]*lkr::ReadHalf(tint[1]))<.00025f);
  CHECK(std::abs(wb[2]*lkr::ReadHalf(tint[2])-wb[1]*lkr::ReadHalf(tint[1]))<.00025f);
  CHECK(tint[3]==neutral[3]);
  auto invalid=original;invalid[123*4]=lkr::WriteHalf(INFINITY);const auto saved=invalid;
  CHECK(!lkr::GuardPhotoChroma(reference.data(),invalid.data(),w,h,wb,.0001f,.000001f));
  CHECK(invalid==saved);
  std::printf("PASS: band agreement %.8g; luminance max error %.8g; opponent mean error %.8g %.8g; synthetic colour MSE %.8g -> %.8g; alpha and transactional failure\n",max_band,max_y,mean_u/count,mean_v/count,before/count,after/count);
}
