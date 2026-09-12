/* Host regression for the exact vendored v3 writer used by Android. */
#include "tinydng.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static void be32(unsigned char *p, unsigned v) {
    p[0]=v>>24; p[1]=v>>16; p[2]=v>>8; p[3]=v;
}
int main(int argc, char **argv) {
    assert(argc == 2);
    tinydng_error err = {0};
    tinydng_context *ctx = tinydng_context_create(NULL, &err);
    assert(ctx);
    unsigned short pixels[16*12];
    for (unsigned i=0;i<192;i++) pixels[i]=(unsigned short)(i*313);
    double noise[6]={0.01,0.0001,0.03,0.0002,0.04,0.0004};
    unsigned active[4]={2,2,10,14};
    unsigned char op[4+4*(16+80)]={0};
    be32(op,4);
    for (unsigned i=0;i<4;i++) {
        unsigned char *p=op+4+i*96;
        be32(p,9); be32(p+4,0x01030000); be32(p+8,1); be32(p+12,80); p+=16;
        unsigned vals[10]={i/2,i%2,8,12,0,1,2,2,1,1};
        for(unsigned j=0;j<10;j++) be32(p+j*4,vals[j]);
        be32(p+40,0x3ff00000); be32(p+48,0x3ff00000); /* spacing 1 */
        be32(p+72,1); be32(p+76,0x40000000+i*0x400000); /* gains 2,3,4,6 */
    }
    unsigned char version[4]={1,4,0,0};
    tinydng_field fields[]={
        {50706,1,4,version,4},
        {50829,4,4,(unsigned char*)active,sizeof(active)},
        {51041,12,6,(unsigned char*)noise,sizeof(noise)},
        {51009,7,sizeof(op),op,sizeof(op)}
    };
    tinydng_write_image img={0};
    img.width=16; img.height=12; img.samples_per_pixel=1; img.bits_per_sample=16;
    img.photometric=32803; img.data=(unsigned char*)pixels; img.data_size=sizeof(pixels);
    img.fields=fields; img.field_count=4;
    tinydng_write_options opts={0};
    unsigned char *encoded=NULL; size_t size=0;
    assert(tinydng_write_memory(ctx,&img,&opts,&encoded,&size,&err)==TINYDNG_OK);
    FILE *f=fopen(argv[1],"wb"); assert(f); assert(fwrite(encoded,1,size,f)==size); assert(fclose(f)==0);
    tinydng_document *doc=NULL;
    assert(tinydng_open_memory(ctx,encoded,size,NULL,&doc,&err)==TINYDNG_OK);
    const tinydng_image_info *info=tinydng_image_get(doc,0);
    assert(info && info->raw.noise_profile_count==6 && info->raw.gainmap_count==4);
    for(unsigned i=0;i<6;i++) assert(info->raw.noise_profile[i]==noise[i]);
    for(unsigned i=0;i<4;i++) {
        assert(info->raw.active_area[i]==active[i]);
        assert(info->raw.gainmaps[i].top==i/2 && info->raw.gainmaps[i].left==i%2);
        assert(info->raw.gainmaps[i].pixel_count==1);
    }
    tinydng_pixels decoded={0};
    assert(tinydng_decode_image(ctx,doc,0,NULL,&decoded,&err)==TINYDNG_OK);
    assert(memcmp(decoded.data,pixels,sizeof(pixels))==0);
    tinydng_pixels_free(ctx,&decoded);
    tinydng_document_destroy(ctx,doc);
    tinydng_buffer_free(ctx,encoded);
    assert(tinydng_context_memory_used(ctx)==0);
    /* Duplicate structural tags and malformed lengths must fail cleanly. */
    fields[0].tag=256;
    assert(tinydng_write_memory(ctx,&img,&opts,&encoded,&size,&err)!=TINYDNG_OK);
    assert(tinydng_context_memory_used(ctx)==0);
    fields[0].tag=50706; fields[0].size=3;
    assert(tinydng_write_memory(ctx,&img,&opts,&encoded,&size,&err)!=TINYDNG_OK);
    assert(tinydng_context_memory_used(ctx)==0);
    tinydng_context_destroy(ctx);
    puts("PASS: RAW pixels, DOUBLE noise, GainMap, ActiveArea, malformed metadata and allocation cleanup");
}
