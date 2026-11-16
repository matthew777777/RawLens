// SPDX-License-Identifier: AGPL-3.0-only
// Numerical port of RapidRAW's default RAW chroma/detail preprocessing.
// Copyright Timon Käch / RapidRAW contributors; C++ port RawLens contributors, 2026.
// Source: image_processing.rs @ 1a39ae4e76d825c5abf0d4bafee798f99e0a0b63.
#include "rapidraw_cleanup.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>
#include <thread>
#include <exception>

namespace rapidraw {
namespace {
constexpr float floor = 0.002f;
using Pair = std::array<float, 2>;
using Triple = std::array<float, 3>;
struct Colors { std::vector<float> r, b; explicit Colors(size_t n) : r(n), b(n) {} };
float sq(float x) { return x*x; }
float clamp01(float x) { return std::clamp(x, 0.f, 1.f); }
float median(std::vector<float>& a) {
    if (a.empty()) return 0.f;
    auto m = a.begin() + a.size()/2;
    std::nth_element(a.begin(), m, a.end());
    return *m;
}
// Fixed small worker count: scratch stays bounded independently of device CPU count.
template<class F> void rows(int h, F fn) {
    const int n = h < 64 ? 1 : std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    std::array<std::thread, 3> workers;
    std::array<std::exception_ptr,4> errors{};
    auto run = [&](int t) {
        try { for (int y=t; y<h; y+=n) fn(y); }
        catch (...) { errors[t]=std::current_exception(); }
    };
    try {
        for (int t=1; t<n; ++t) workers[t-1]=std::thread(run,t);
        run(0);
    } catch (...) {
        for(auto& worker:workers) if(worker.joinable())worker.join();
        throw;
    }
    for(auto& worker:workers) if(worker.joinable())worker.join();
    for(auto& error:errors) if(error)std::rethrow_exception(error);
}
Triple stabilize(const float* p) {
    float l = 0.2126f*p[0] + 0.7152f*p[1] + 0.0722f*p[2];
    float q = std::sqrt(std::max(l, 0.f) + floor);
    return {q, std::sqrt(std::max(p[0],0.f)+floor)-q,
               std::sqrt(std::max(p[2],0.f)+floor)-q};
}
template<class F> float noise(int w, int h, int channels, F sample) {
    std::vector<float> d;
    d.reserve(size_t((h+7)/8)*((w+6)/7)*channels);
    for (int y=0; y<h; y+=8) for (int x=0; x<w-4; x+=7)
        for (int c=0; c<channels; ++c)
            d.push_back(std::abs(sample(y*w+x+4,c)-sample(y*w+x,c)));
    return median(d)*1.4826f/std::sqrt(2.f);
}
struct Tap { int i; float w; };
std::array<Tap,2> taps(int v, int n) {
    int i=std::min(v/2,n-1);
    if (v%2==0) return {{{std::max(i-1,0),.25f},{i,.75f}}};
    return {{{i,.75f},{std::min(i+1,n-1),.25f}}};
}
struct Up {
    float r=0,b=0,range=0;
    float rmin=INFINITY,rmax=-INFINITY,bmin=INFINITY,bmax=-INFINITY;
};
struct Planes {
    int w,h,pw;
    std::vector<float> q,r,b;
    float nq,nc;
    int at(int x,int y) const { return (y+2)*pw+x+2; }
    template<class F> Planes(int width,int height,F sample) :
        w(width),h(height),pw(w+4),q(size_t(pw)*(h+4)),r(q.size()),b(q.size()) {
        rows(h+4,[&](int py) {
            int y=std::clamp(py-2,0,h-1);
            for (int px=0;px<pw;++px) {
                auto p=sample(std::clamp(px-2,0,w-1),y);
                int i=py*pw+px; q[i]=p[0];r[i]=p[1];b[i]=p[2];
            }
        });
        nq=std::max(1e-3f,noise(pw,h+4,1,[&](int i,int){return q[i];}));
        nc=std::max(1e-3f,noise(pw,h+4,2,[&](int i,int c){return c?b[i]:r[i];}));
    }
    float eps(float amount) const { return 16.f*sq((1.5f+2.f*amount)*nq); }
    Colors filter(float amount,const Colors* reference=nullptr) const {
        const float lt=(1.5f+2.f*amount)*nq, ct=(2.f+3.f*amount)*nc;
        const float kq=1.f/sq(lt),kc=1.f/sq(ct),e=sq(lt),sigma=1.5f+amount;
        std::array<float,25> spatial;
        for (int j=0;j<5;++j) for(int i=0;i<5;++i)
            spatial[j*5+i]=std::exp(-float((i-2)*(i-2)+(j-2)*(j-2))/(2.f*sigma*sigma));
        std::vector<float> rel(q.size(),1.f),widen(q.size(),1.f);
        rows(h+4,[&](int y) {
            if(y==0||y==h+3) return;
            for(int x=1;x<pw-1;++x) {
                int i=y*pw+x;
                float g=.5f*std::max(std::abs(q[i+1]-q[i-1]),std::abs(q[i+pw]-q[i-pw]));
                float t=g/lt;rel[i]=1.f/(1.f+t*t);widen[i]=1.f/sq(1.f+2.f*t);
            }
        });
        Colors out(size_t(w)*h);
        rows(h,[&](int y) {
            for(int x=0;x<w;++x) {
                int c=at(x,y),o=y*w+x;
                float rr=0,rb=0,rw=0;
                if(reference) { rr=reference->r[o];rb=reference->b[o]; }
                else {
                    for(int j=1;j<=3;++j) for(int i=1;i<=3;++i) {
                        int s=(y+j)*pw+x+i;
                        float d=q[s]-q[c],t=std::max(1.f-d*d*kq*widen[c],0.f);
                        float wt=spatial[j*5+i]*rel[s]*t*t;
                        rr+=wt*r[s];rb+=wt*b[s];rw+=wt;
                    }
                    float inv=1.f/rw;rr*=inv;rb*=inv;
                }
                float sw=0,sd=0,sdd=0,sr=0,sb=0,sdr=0,sdb=0;
                for(int j=0;j<5;++j) for(int i=0;i<5;++i) {
                    int s=(y+j)*pw+x+i;
                    float d=q[s]-q[c],er=r[s]-rr,eb=b[s]-rb;
                    float t=std::max(1.f-(d*d*kq+(er*er+eb*eb)*kc)*widen[c],0.f);
                    float wt=spatial[j*5+i]*rel[s]*t*t,wd=wt*d;
                    sw+=wt;sd+=wd;sdd+=wd*d;sr+=wt*r[s];sb+=wt*b[s];sdr+=wd*r[s];sdb+=wd*b[s];
                }
                if(sw>1e-6f) {
                    float inv=1.f/sw,md=sd*inv,mr=sr*inv,mb=sb*inv;
                    float var=std::max(sdd*inv-md*md,0.f)+e;
                    out.r[o]=mr-(sdr*inv-mr*md)/var*md;
                    out.b[o]=mb-(sdb*inv-mb*md)/var*md;
                } else {out.r[o]=rr;out.b[o]=rb;}
            }
        });
        return out;
    }
    Up upsample(const Colors& color,int x,int y,float qc,float e) const {
        Up out;float qmin=INFINITY,qmax=-INFINITY,sd=0,sdd=0,sdr=0,sdb=0;
        for(auto ty:taps(y,h)) for(auto tx:taps(x,w)) {
            int i=ty.i*w+tx.i;float qs=q[at(tx.i,ty.i)],cr=color.r[i],cb=color.b[i];
            float wt=ty.w*tx.w,d=qs-qc,wd=wt*d;
            out.r+=wt*cr;out.b+=wt*cb;sd+=wd;sdd+=wd*d;sdr+=wd*cr;sdb+=wd*cb;
            qmin=std::min(qmin,qs);qmax=std::max(qmax,qs);
            out.rmin=std::min(out.rmin,cr);out.rmax=std::max(out.rmax,cr);
            out.bmin=std::min(out.bmin,cb);out.bmax=std::max(out.bmax,cb);
        }
        float var=std::max(sdd-sd*sd,0.f)+e;
        out.r-=(sdr-out.r*sd)/var*sd;out.b-=(sdb-out.b*sd)/var*sd;
        out.range=qmax-qmin;return out;
    }
    void core(Colors& f,const Colors& ref,float amount) const {
        std::vector<float> mags;
        for(size_t i=0;i<f.r.size();i+=7) mags.push_back(std::hypot(f.r[i]-ref.r[i],f.b[i]-ref.b[i]));
        float core=std::max(median(mags)*(1.5f+amount),1e-6f),texture=4.f*(.5f+amount);
        rows(h,[&](int y) {for(int x=0;x<w;++x) {
            float lo=INFINITY,hi=-INFINITY;
            for(int j=-1;j<=1;++j) for(int i=-1;i<=1;++i) {
                float v=q[at(x+i,y+j)];lo=std::min(lo,v);hi=std::max(hi,v);
            }
            int i=y*w+x;float er=f.r[i]-ref.r[i],eb=f.b[i]-ref.b[i];
            float keep=clamp01(std::sqrt(er*er+eb*eb)/(core+texture*(hi-lo))-1.f);
            f.r[i]=ref.r[i]+er*keep;f.b[i]=ref.b[i]+eb*keep;
        }});
    }
};
std::vector<float> chroma(std::vector<float>& rgb,int w,int h,float amount) {
    Planes half((w+1)/2,(h+1)/2,[&](int x,int y) {
        Triple sum{};
        for(int sy:{2*y,std::min(2*y+1,h-1)}) for(int sx:{2*x,std::min(2*x+1,w-1)})
            for(int c=0;c<3;++c) sum[c]+=rgb[(sy*w+sx)*3+c]*.25f;
        return stabilize(sum.data());
    });
    Colors filtered(size_t(half.w)*half.h);
    {
        Planes quarter((half.w+1)/2,(half.h+1)/2,[&](int x,int y) {
            Triple sum{};
            for(int sy:{2*y,std::min(2*y+1,half.h-1)}) for(int sx:{2*x,std::min(2*x+1,half.w-1)}) {
                int i=half.at(sx,sy);sum[0]+=half.q[i]*.25f;sum[1]+=half.r[i]*.25f;sum[2]+=half.b[i]*.25f;
            }
            return sum;
        });
        auto coarse=quarter.filter(amount);
        Colors ref(size_t(half.w)*half.h);
        rows(half.h,[&](int y){for(int x=0;x<half.w;++x){
            auto up=quarter.upsample(coarse,x,y,half.q[half.at(x,y)],half.eps(amount));
            int i=y*half.w+x;ref.r[i]=up.r;ref.b[i]=up.b;
        }});
        filtered=half.filter(amount,&ref);half.core(filtered,ref,amount);
    }
    float nc=std::max(1e-3f,noise(w,h,2,[&](int i,int c){return stabilize(&rgb[i*3])[c+1];}));
    float e=half.eps(amount),edgeScale=.5f/std::sqrt(e),core=(2.f+2.f*amount)*nc,texture=4.f*(.5f+amount);
    std::vector<float> luma(size_t(w)*h);
    rows(h,[&](int y){for(int x=0;x<w;++x){
        int i=y*w+x;float* p=&rgb[i*3];
        float l=.2126f*p[0]+.7152f*p[1]+.0722f*p[2];
        auto s=stabilize(p);luma[i]=s[0];
        auto up=half.upsample(filtered,x,y,s[0],e);
        float er=s[1]-up.r,eb=s[2]-up.b,edge=up.range*edgeScale;
        float keep=clamp01(std::sqrt(er*er+eb*eb)/(core+texture*up.range)-1.f)/(1.f+edge*edge);
        float nr=std::clamp(up.r+er*keep,up.rmin,up.rmax),nb=std::clamp(up.b+eb*keep,up.bmin,up.bmax);
        float vr=std::max(s[0]+nr,0.f),vb=std::max(s[0]+nb,0.f);
        float dr=vr*vr-floor-l,db=vb*vb-floor-l,dg=-(.2126f*dr+.0722f*db)*(1.f/.7152f);
        float m=std::min({dr,dg,db}),scale=l+m<0.f?-l/m:1.f;
        if(l>0.f){p[0]=std::max(l+dr*scale,0.f);p[1]=std::max(l+dg*scale,0.f);p[2]=std::max(l+db*scale,0.f);}
    }});
    return luma;
}
void detail(std::vector<float>& rgb,const std::vector<float>& q,int w,int h,float amount) {
    auto at=[&](int x,int y){return q[std::clamp(y,0,h-1)*w+std::clamp(x,0,w-1)];};
    auto blurRow=[&](int y,std::vector<float>& v,std::vector<float>& blur){
        for(int x=0;x<w;++x) v[x+2]=(at(x,y-2)+at(x,y+2)+4.f*(at(x,y-1)+at(x,y+1))+6.f*at(x,y))*(1.f/16.f);
        v[0]=v[1]=v[2];v[w+2]=v[w+3]=v[w+1];
        for(int x=0;x<w;++x) blur[x]=(v[x]+v[x+4]+4.f*(v[x+1]+v[x+3])+6.f*v[x+2])*(1.f/16.f);
    };
    std::vector<float> samples,v(w+4),blur(w);
    for(int y=0;y<h;y+=16){blurRow(y,v,blur);for(int x=0;x<w;x+=5) samples.push_back(std::abs(at(x,y)-blur[x]));}
    float n=median(samples)*1.4826f,threshold=std::max(sq(1.5f*n),1e-12f),strength=4.5f*amount,taper=.3f/std::sqrt(threshold);
    rows(h,[&](int y){
        std::vector<float> temp(w+4),b(w);blurRow(y,temp,b);
        for(int x=0;x<w;++x){
            float qc=at(x,y),d=qc-b[x];d=d*d*d/(d*d+threshold);d=d/(1.f+std::abs(d)*taper);
            float lo=INFINITY,hi=-INFINITY;
            for(int j=-1;j<=1;++j)for(int i=-1;i<=1;++i){float val=at(x+i,y+j);lo=std::min(lo,val);hi=std::max(hi,val);}
            float delta=strength*d,range=hi-lo,ur=hi-qc+range*.15f+.004f,dr=qc-lo+range*.05f+.002f;
            float up=std::max(delta,0.f),down=std::max(-delta,0.f)*.7f;
            float qn=qc+up*ur/(ur+up)-down*dr/(dr+down),l=qc*qc-floor,ln=std::max(qn*qn-floor,0.f);
            float* p=&rgb[(y*w+x)*3];
            if(l>1e-6f){
                float peak=std::max({p[0],p[1],p[2]}),ratio=ln/l;
                if(ratio>1.f&&peak*ratio>1.f) ratio=std::max(1.f/peak,1.f);
                for(int c=0;c<3;++c)p[c]*=ratio;
            }else for(int c=0;c<3;++c)p[c]=std::max(p[c]+ln-l,0.f);
        }
    });
}
} // namespace
void cleanup(std::vector<float>& rgb,int w,int h,float colorAmount,float detailAmount) {
    if(w<=0||h<=0||rgb.size()!=size_t(w)*h*3) throw std::invalid_argument("Invalid cleanup dimensions");
    if(!std::isfinite(colorAmount)||!std::isfinite(detailAmount)||colorAmount<0||colorAmount>1||detailAmount<0||detailAmount>1)
        throw std::invalid_argument("Invalid cleanup strength");
    std::vector<float> q;
    if(colorAmount>0)q=chroma(rgb,w,h,colorAmount);
    if(detailAmount>0){
        if(q.empty()){q.resize(size_t(w)*h);rows(h,[&](int y){for(int x=0;x<w;++x){int i=y*w+x;q[i]=stabilize(&rgb[i*3])[0];}});}
        detail(rgb,q,w,h,detailAmount);
    }
}
} // namespace rapidraw
