package com.matthew.rawlens

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CleanupBenchmarkDeviceTest {
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val dir=File("/data/local/tmp/cleanup-benchmark")
 private val out get()=File(context.getExternalFilesDir(null),"cleanup-benchmark").apply{mkdirs()}
 private fun buffer(file:File):ByteBuffer {
  val b=ByteBuffer.allocateDirect(file.length().toInt()).order(ByteOrder.nativeOrder())
  FileInputStream(file).channel.use { c -> while(b.hasRemaining()) check(c.read(b)>0) };b.rewind();return b
 }
 private fun save(bitmap:Bitmap,file:File) {file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
 private fun metadata(s:JSONObject)=OfflineBurstMetadata.synthesize(s.getJSONObject("container").toString(),s.getJSONObject("frame").toString(),0)
 @Test fun compareDngCropsAndBenchmarkGpu() {
  val specs=JSONArray(File(dir,"manifest.json").readText());val results=JSONArray()
  val developer=RawDevelopmentCoordinator(context)
  val noCleanup=JpegOutputSettings(chromaCleanup=ChromaCleanup(false,0f),jpegQuality=100)
  try {
   for(i in 0 until specs.length()) {
    val spec=specs.getJSONObject(i);val id=spec.getString("id");val w=spec.getInt("width");val h=spec.getInt("height")
    val cfa=spec.optString("kind")=="cfa";val variants=if(cfa) listOf("off","wide") else listOf("off","old","wide")
    val m=metadata(spec)
    for(mode in variants) {
     val data=buffer(File(dir,if(cfa) "$id.u16" else "${id}_${if(mode=="wide") "off" else mode}.f32"))
     val settings=if(mode=="wide") JpegOutputSettings() else noCleanup
     val start=SystemClock.elapsedRealtimeNanos()
     val image=if(cfa) developer.developJpeg(data,m,outputSettings=settings)
      else developer.developMfsrJpeg(data,w,h,m,outputSettings=settings)
     val ms=(SystemClock.elapsedRealtimeNanos()-start)/1e6
     try {save(image.bitmap,File(out,"${id}_${mode}.png"))} finally {image.bitmap.recycle();image.gainmap?.recycle()}
     results.put(JSONObject().put("id",id).put("mode",mode).put("develop_ms",ms))
    }
    // Validate the CPU mirror against the actual installed wide-cleanup path.
    if(i in listOf(0,20,specs.length()-4)) {
     val data=buffer(File(dir,"${id}_wide.f32"))
     val image=developer.developMfsrJpeg(data,w,h,m,outputSettings=noCleanup)
     try {
      val reference=android.graphics.BitmapFactory.decodeFile(File(out,"${id}_wide.png").path)!!
      try {
       val a=IntArray(w*h);val b=IntArray(w*h);reference.getPixels(a,0,w,0,0,w,h);image.bitmap.getPixels(b,0,w,0,0,w,h)
       var error=0;var sum=0L;var pixelsOver2=0
       for(p in a.indices) {var pixelError=0
        for(shift in intArrayOf(0,8,16)) {val e=kotlin.math.abs(((a[p] shr shift) and 255)-((b[p] shr shift) and 255));error=maxOf(error,e);pixelError=maxOf(pixelError,e);sum+=e}
        if(pixelError>2)pixelsOver2++
       }
       val mean=sum.toDouble()/(a.size*3)
       val fraction=pixelsOver2.toDouble()/a.size
       results.put(JSONObject().put("id",id).put("native_cpu_mirror_max_rgb8_error",error).put("native_cpu_mirror_mean_rgb8_error",mean).put("native_cpu_mirror_fraction_pixels_over2",fraction))
       assertTrue("CPU mirror aggregate mismatch $id: mean=$mean fraction=$fraction",mean<.1 && fraction<.001)
      } finally {reference.recycle()}
     } finally {image.bitmap.recycle();image.gainmap?.recycle()}
    }
    Log.i("CleanupBench","${i+1}/${specs.length()} $id")
   }
  } finally {developer.close();File(out,"develop-timings.json").writeText(results.toString(2))}
  gpuBenchmark(specs.getJSONObject(0))
 }
 private fun gpuBenchmark(spec:JSONObject) {
  val source=buffer(File(dir,spec.getString("id")+"_off.f32")).asFloatBuffer()
  val sw=spec.getInt("width");val sh=spec.getInt("height");val n=1024
  val rgba=ByteBuffer.allocateDirect(n*n*16).order(ByteOrder.nativeOrder())
  for(y in 0 until n) for(x in 0 until n) {val p=((y%sh)*sw+x%sw)*3;rgba.putFloat(source.get(p));rgba.putFloat(source.get(p+1));rgba.putFloat(source.get(p+2));rgba.putFloat(1f)};rgba.rewind()
  val common=context.assets.open("shaders/amaze/cleanup.glsl").bufferedReader().use{it.readText()}
  val old=common.replace("highp vec2 wide = cc_median(d, 2);","highp vec2 wide = fine;")
  check(old!=common)
  val prefix="""#version 310 es
precision highp float;
precision highp int;
precision highp image2D;
layout(local_size_x=8,local_size_y=8) in;
layout(rgba32f,binding=0) readonly uniform highp image2D inputImage;
layout(rgba32f,binding=1) writeonly uniform highp image2D outputImage;
uniform highp vec3 white;
vec3 fetch_bal(ivec2 off) {ivec2 q=clamp(ivec2(gl_GlobalInvocationID.xy)+off,ivec2(0),ivec2(1023));return max(imageLoad(inputImage,q).rgb,vec3(0))/white;}
"""
  val suffix="""
void main(){ivec2 p=ivec2(gl_GlobalInvocationID.xy);vec3 c=fetch_bal(ivec2(0));imageStore(outputImage,p,vec4(chroma_cleanup(c)*white,1));}
"""
  val values=JSONArray();val programs=IntArray(3);val textures=IntArray(2)
  Gles31AmazeProcessor.EglComputeContext().use {
   try {
    GLES31.glGenTextures(2,textures,0)
    for(t in textures) {GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,t);GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D,1,GLES30.GL_RGBA32F,n,n)}
    GLES31.glBindTexture(GLES31.GL_TEXTURE_2D,textures[0]);GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D,0,0,0,n,n,GLES31.GL_RGBA,GLES31.GL_FLOAT,rgba)
    for(i in 0..2) {
     val shader=GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
     GLES31.glShaderSource(shader,prefix+(if(i==1) old else common)+suffix);GLES31.glCompileShader(shader)
     val status=IntArray(1);GLES31.glGetShaderiv(shader,GLES31.GL_COMPILE_STATUS,status,0);check(status[0]!=0){GLES31.glGetShaderInfoLog(shader)}
     val program=GLES31.glCreateProgram();GLES31.glAttachShader(program,shader);GLES31.glLinkProgram(program);GLES31.glDeleteShader(shader)
     GLES31.glGetProgramiv(program,GLES31.GL_LINK_STATUS,status,0);check(status[0]!=0){GLES31.glGetProgramInfoLog(program)};programs[i]=program
    }
    val neutral=spec.getJSONObject("frame").getJSONArray("asShotNeutral");val white=FloatArray(3){neutral.getDouble(it).toFloat()};val max=white.maxOrNull()!!;for(i in white.indices)white[i]/=max
    GLES31.glBindImageTexture(0,textures[0],0,false,0,GLES31.GL_READ_ONLY,GLES30.GL_RGBA32F)
    GLES31.glBindImageTexture(1,textures[1],0,false,0,GLES31.GL_WRITE_ONLY,GLES30.GL_RGBA32F)
    for(round in 0 until 24) for(k in 0..2) {
     val mode=(k+round)%3;val program=programs[mode]
     GLES31.glUseProgram(program);GLES31.glUniform3fv(GLES31.glGetUniformLocation(program,"white"),1,white,0)
     GLES31.glUniform1i(GLES31.glGetUniformLocation(program,"u_fc_median"),if(mode==0)0 else 1)
     GLES31.glUniform1f(GLES31.glGetUniformLocation(program,"u_defringe"),if(mode==0)0f else 1f)
     GLES31.glFinish();val start=SystemClock.elapsedRealtimeNanos();GLES31.glDispatchCompute(n/8,n/8,1)
     GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);GLES31.glFinish();val ms=(SystemClock.elapsedRealtimeNanos()-start)/1e6
     check(GLES31.glGetError()==GLES31.GL_NO_ERROR)
     if(round==4 && mode!=0) {
      val fb=IntArray(1);GLES30.glGenFramebuffers(1,fb,0)
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fb[0])
      GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,textures[1],0)
      check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)==GLES30.GL_FRAMEBUFFER_COMPLETE)
      val pixels=ByteBuffer.allocateDirect(sw*sh*16).order(ByteOrder.nativeOrder())
      GLES30.glReadPixels(0,0,sw,sh,GLES30.GL_RGBA,GLES30.GL_FLOAT,pixels)
      check(GLES30.glGetError()==GLES30.GL_NO_ERROR)
      val bytes=ByteArray(pixels.capacity());pixels.rewind();pixels.get(bytes)
      File(out,"gpu-${listOf("off","old","wide")[mode]}-crop.rgba32f").writeBytes(bytes)
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glDeleteFramebuffers(1,fb,0)
     }
     if(round>=4) values.put(JSONObject().put("mode",listOf("off","old","wide")[mode]).put("round",round).put("ms",ms))
    }
    File(out,"gpu-benchmark.json").writeText(JSONObject().put("width",n).put("height",n).put("method","isolated cleanup compute pass, RGBA32F, CPU submit+glFinish, rotated order, 4 warm-up + 20 measured repetitions").put("samples",values).toString(2))
   } finally {for(p in programs)if(p!=0)GLES31.glDeleteProgram(p);GLES31.glDeleteTextures(2,textures,0)}
  }
 }
}
