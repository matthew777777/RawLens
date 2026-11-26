#include "encoding/jpeg/ParallelJpeg.h"
#include <chrono>
#include <cmath>
using namespace rawlens::encoding::jpeg;
static std::vector<unsigned char> dec(const std::vector<unsigned char>&f,int&w,int&h,long*warn){jpeg_decompress_struct c{};jpeg_error_mgr e;c.err=jpeg_std_error(&e);jpeg_create_decompress(&c);jpeg_mem_src(&c,f.data(),f.size());jpeg_read_header(&c,TRUE);jpeg_start_decompress(&c);w=c.output_width;h=c.output_height;std::vector<unsigned char>o(size_t(w)*h*3);while(c.output_scanline<c.output_height){JSAMPROW r=&o[size_t(c.output_scanline)*w*3];jpeg_read_scanlines(&c,&r,1);}jpeg_finish_decompress(&c);*warn=e.num_warnings;jpeg_destroy_decompress(&c);return o;}
static double now(){return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();}
int main(){int bad=0;int sz[][2]={{4080,3060},{4000,3000},{17,9},{1,1},{16,8},{333,777},{8160,6120},{640,8}};
for(auto&z:sz){int w=z[0],h=z[1];size_t st=size_t(w)*4+64;std::vector<unsigned char>px(st*h);unsigned s=1;for(int y=0;y<h;y++)for(int x=0;x<w*4;x++){s=s*1664525u+1013904223u;px[y*st+x]=(unsigned char)(std::min(255.0,std::max(0.0,128+100*std::sin(x*.003+y*.01)+int(s>>28)-8)));}
 for(int q:{50,95,100})for(int y4:{0,1}){std::vector<unsigned char> icc(q==95?70000:500,7);auto run=[&](int t,double&ms){ParallelJpegInput in;in.rgba=px.data();in.width=w;in.height=h;in.stride=st;in.quality=q;in.yuv444=y4;in.icc=icc.data();in.iccSize=icc.size();ParallelJpegOutput o;std::string e;double t0=now();if(!encodeParallel(in,o,e,t)){printf("ERR %s\n",e.c_str());exit(1);}ms=now()-t0;std::vector<unsigned char>f;for(auto&p:o.pieces)f.insert(f.end(),p.data,p.data+p.size);return f;};
 // sequential reference = plain libjpeg loop
 jpeg_compress_struct c{};jpeg_error_mgr er;c.err=jpeg_std_error(&er);jpeg_create_compress(&c);unsigned char*m=0;unsigned long n=0;jpeg_mem_dest(&c,&m,&n);c.image_width=w;c.image_height=h;c.input_components=4;c.in_color_space=JCS_EXT_RGBA;jpeg_set_defaults(&c);jpeg_set_quality(&c,q,TRUE);c.optimize_coding=FALSE;if(q>=98)c.dct_method=JDCT_ISLOW;for(int k=0;k<3;k++){c.comp_info[k].h_samp_factor=(k==0&&!y4)?2:1;c.comp_info[k].v_samp_factor=1;}
 double t0=now();jpeg_start_compress(&c,TRUE);while(c.next_scanline<c.image_height){JSAMPROW r=&px[c.next_scanline*st];jpeg_write_scanlines(&c,&r,1);}jpeg_finish_compress(&c);double ref=now()-t0;std::vector<unsigned char>rf(m,m+n);jpeg_destroy_compress(&c);free(m);
 double a,b;auto f4=run(4,b);auto f1=run(1,a);int w1,h1,w2,h2;long e1,e2;auto d1=dec(rf,w1,h1,&e1),d2=dec(f4,w2,h2,&e2);bool ok=d1==d2&&w1==w2&&h1==h2&&!e1&&!e2&&dec(f1,w2,h2,&e2)==d1;if(!ok)bad++;
 if(!ok||w>=4080)printf("%dx%d q%d %s %s seq %.0fms par1 %.0f par4 %.0f size %+.3f%%\n",w,h,q,y4?"444":"422",ok?"IDENTICAL":"DIFF",ref,a,b,100.0*((double)f4.size()-icc.size()-rf.size())/rf.size());}}
 printf("bad %d\n",bad);return bad;}
