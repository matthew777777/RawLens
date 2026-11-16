// SPDX-License-Identifier: AGPL-3.0-only
package com.matthew.rawlens

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.SystemClock
import java.nio.ByteBuffer

/** GPU chroma/detail preprocessing. Only sparse noise samples cross to the CPU;
 * full-resolution color remains on the caller's EGL context throughout.
 */
internal class RapidRawCleanup(context: Context, private val dispatchBudget: Int = 262144) {
    private val source = context.assets.open("shaders/display/rapidraw_cleanup.glsl").bufferedReader().use { it.readText() }
    private val programs = IntArray(11)
    private var owner: Thread? = null
    private data class Texture(val id: Int, val w: Int, val h: Int, val format: Int, val bytes: Long)
    data class Prepared(val source: AmazeGpuOutput, val half: Int, val color: Int,
                        val eps: Float, val core: Float, val detailNoise: Float)

    /** Shared with the final JPEG shader so reconstruction never needs a full-size scratch image. */
    fun fusedSource(output: String, basic: Boolean, gainmap: Boolean): String =
        "#define MODE 8\n#define RAPIDRAW_OUTPUT_FUSED 1\n#define RAPIDRAW_BASIC ${if(basic)1 else 0}\n#define RAPIDRAW_GAINMAP ${if(gainmap)1 else 0}\n" +
            "layout(local_size_x=8,local_size_y=8) in;\n$source\n$output"

    fun bindPrepared(program: Int, prepared: Prepared) {
        intArrayOf(prepared.source.textureId,prepared.half,prepared.color).forEachIndexed { unit,id ->
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0+unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,id)
        }
        ivec(program,"u_full_size",prepared.source.width,prepared.source.height)
        float(program,"u_eps",prepared.eps);float(program,"u_core",prepared.core)
        noise(program,0f,prepared.detailNoise)
    }

    /** Standalone renderer retained for parity tests; production uses [withPrepared]. */
    fun <T> withCleaned(scene: AmazeGpuOutput, consume: (AmazeGpuOutput) -> T): T = withPrepared(scene) { prepared ->
        val ids=IntArray(1);GLES30.glGenTextures(1,ids,0);check(ids[0]!=0)
        val bytes=scene.width.toLong()*scene.height*8
        var tracked=false
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,ids[0])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA16F,scene.width,scene.height)
            MemoryLeakDiagnostics.glTextureAllocated(bytes);tracked=true
            val p=program(8);GLES31.glUseProgram(p);bindPrepared(p,prepared)
            ivec(p,"u_size",scene.width,scene.height)
            GLES31.glBindImageTexture(0,ids[0],0,false,0,GLES31.GL_WRITE_ONLY,GLES30.GL_RGBA16F)
            dispatchSlices(p,scene.width,scene.height)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
            checkGl("standalone cleanup output")
            consume(scene.copy(textureId=ids[0]))
        }finally{
            GLES30.glDeleteTextures(1,ids,0)
            if(tracked)MemoryLeakDiagnostics.glTextureReleased(bytes)
        }
    }

    fun <T> withPrepared(scene: AmazeGpuOutput, consume: (Prepared) -> T): T {
        require(scene.internalFormat == AmazeTextureFormat.RGBA16F && scene.colorDomain == AmazeColorDomain.ACESCG)
        check(owner == null || owner == Thread.currentThread()) { "Cleanup must stay on its EGL owner" }
        owner = Thread.currentThread()
        val start = SystemClock.elapsedRealtime()
        val textures = mutableListOf<Texture>()
        var liveBytes = 0L; var peakBytes = 0L
        fun texture(w: Int,h: Int,format: Int): Texture {
            val ids=IntArray(1);GLES30.glGenTextures(1,ids,0)
            check(ids[0]!=0)
            check(format == GLES30.GL_RGBA16F || format == GLES30.GL_RGBA32F)
            val bytes=w.toLong()*h*(if(format==GLES30.GL_RGBA32F)16 else 8)
            val t=Texture(ids[0],w,h,format,bytes)
            textures.add(t);MemoryLeakDiagnostics.glTextureAllocated(bytes)
            liveBytes+=bytes;peakBytes=maxOf(peakBytes,liveBytes)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,t.id)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,format,w,h)
            checkGl("cleanup texture allocation")
            return t
        }
        fun release(t:Texture) {
            if(textures.remove(t)){
                GLES30.glDeleteTextures(1,intArrayOf(t.id),0)
                MemoryLeakDiagnostics.glTextureReleased(t.bytes);liveBytes-=t.bytes
            }
        }
        val ssbo=IntArray(1)
        val w=scene.width;val h=scene.height;val hw=(w+1)/2;val hh=(h+1)/2;val qw=(hw+1)/2;val qh=(hh+1)/2
        val halfSamples=((hw+6)/7)*((hh+11)/8) // padded height = hh+4
        val quarterSamples=((qw+6)/7)*((qh+11)/8)
        val fullSamples=((w-4).coerceAtLeast(0)+6)/7*((h+7)/8)
        val detailSamples=((w+4)/5)*((h+15)/16)
        val coreSamples=(hw.toLong()*hh+6)/7
        val sampleCount=halfSamples.toLong()*3+quarterSamples.toLong()*3+fullSamples.toLong()*2+detailSamples
        val sampleBytes=maxOf(sampleCount,coreSamples)*4
        require(sampleBytes<=Int.MAX_VALUE)
        try {
            val half=texture(hw,hh,GLES30.GL_RGBA16F)
            dispatch(0,half,scene)
            val quarter=texture(qw,qh,GLES30.GL_RGBA16F)
            dispatch(1,quarter,scene,plane=half)
            GLES30.glGenBuffers(1,ssbo,0);check(ssbo[0]!=0)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,ssbo[0])
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER,sampleBytes.toInt(),null,GLES30.GL_STREAM_READ)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER,0,ssbo[0])
            var offset=0
            fun samples(kind:Int,count:Int,plane:Texture?,sampleWidth:Int) {
                if(count==0)return
                dispatch(7,null,scene,plane=plane,size=256 to ((count+255)/256)) { p ->
                    int(p,"u_kind",kind);int(p,"u_offset",offset);int(p,"u_count",count);int(p,"u_sample_width",sampleWidth)
                }
                offset+=count
            }
            samples(0,halfSamples*3,half,(hw+6)/7)
            samples(1,quarterSamples*3,quarter,(qw+6)/7)
            samples(2,fullSamples*2,null,((w-4).coerceAtLeast(0)+6)/7)
            samples(3,detailSamples,null,(w+4)/5)
            val noise = mapped(ssbo[0],sampleBytes.toInt()) {
                checkNotNull(nativeStatistics(it,halfSamples,quarterSamples,fullSamples,detailSamples)) { "Cleanup noise estimation failed" }
            }
            val statsAt=SystemClock.elapsedRealtime()
            val halfGrad=texture(hw+4,hh+4,GLES30.GL_RGBA32F)
            val quarterGrad=texture(qw+4,qh+4,GLES30.GL_RGBA32F)
            dispatch(2,halfGrad,scene,plane=half){noise(it,noise[0],noise[1])}
            dispatch(2,quarterGrad,scene,plane=quarter){noise(it,noise[2],noise[3])}
            val quarterColor=texture(qw,qh,GLES30.GL_RGBA16F)
            dispatch(3,quarterColor,scene,plane=quarter,aux=quarterGrad){noise(it,noise[2],noise[3]);int(it,"u_kind",0)}
            release(quarterGrad)
            val reference=texture(hw,hh,GLES30.GL_RGBA16F)
            val eps=16f*(2.5f*noise[0])*(2.5f*noise[0])
            dispatch(4,reference,scene,plane=quarter,aux=quarterColor,ref=half){float(it,"u_eps",eps)}
            release(quarter);release(quarterColor)
            val filtered=texture(hw,hh,GLES30.GL_RGBA16F)
            dispatch(10,filtered,scene,plane=half,aux=halfGrad,ref=reference){noise(it,noise[0],noise[1]);int(it,"u_kind",1)}
            release(halfGrad)
            dispatch(9,null,scene,plane=filtered,ref=reference,size=256 to ((coreSamples.toInt()+255)/256)) {
                int(it,"u_count",coreSamples.toInt());ivec(it,"u_full_size",hw,hh)
            }
            val core=mapped(ssbo[0],sampleBytes.toInt()){nativeMedian(it,coreSamples.toInt())}
            check(core.isFinite()) { "Cleanup residual estimation failed" }
            val cleanHalf=texture(hw,hh,GLES30.GL_RGBA16F)
            dispatch(5,cleanHalf,scene,plane=half,aux=filtered,ref=reference){float(it,"u_core",(core*2f).coerceAtLeast(1e-6f))}
            release(reference);release(filtered)
            // Exclude the downstream tone-map/bitmap work from cleanup timing.
            Log.i("RapidRawCleanup","GPU ${w}x$h stats=${statsAt-start}ms submit=${SystemClock.elapsedRealtime()-statsAt}ms " +
                "sparseReadback=${sampleCount*4+coreSamples*4}B preparedPeakScratch=${peakBytes+sampleBytes}B")
            return consume(Prepared(scene,half.id,cleanHalf.id,eps,3f*noise[4],noise[5]))
        } finally {
            textures.toList().forEach(::release)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER,0,0)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,0)
            if(ssbo[0]!=0)GLES30.glDeleteBuffers(1,ssbo,0)
        }
    }

    private fun dispatch(mode:Int,out:Texture?,scene:AmazeGpuOutput,plane:Texture?=null,aux:Texture?=null,
                         ref:Texture?=null,size:Pair<Int,Int>?=null,uniforms:(Int)->Unit={}) {
        val p=program(mode);GLES31.glUseProgram(p)
        val ids=intArrayOf(scene.textureId,plane?.id?:0,aux?.id?:0,ref?.id?:0)
        for(i in ids.indices){GLES30.glActiveTexture(GLES30.GL_TEXTURE0+i);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,ids[i])}
        val w=size?.first?:checkNotNull(out).w;val h=size?.second?:checkNotNull(out).h
        ivec(p,"u_size",w,h);ivec(p,"u_full_size",scene.width,scene.height)
        uniforms(p)
        if(out!=null)GLES31.glBindImageTexture(0,out.id,0,false,0,
            GLES31.GL_WRITE_ONLY,out.format)
        dispatchSlices(p,w,h,if(mode==3||mode==10) minOf(dispatchBudget,131072) else dispatchBudget)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        checkGl("cleanup pass $mode")
    }

    /** The workgroup origin and global pixel origin move together; shared halos remain identical.
     * Bound in-flight work so a still-save cannot monopolize the mobile GPU queue.
     */
    fun dispatchSlices(program: Int, width: Int, height: Int, budget: Int = dispatchBudget) {
        if(width.toLong()*height<=budget){
            ivec(program,"u_dispatch_offset",0,0)
            GLES31.glDispatchCompute((width+7)/8,(height+7)/8,1)
            return
        }
        for((index,slice) in GpuScheduling.slices(width,height,8,8,budget).withIndex()){
            ivec(program,"u_dispatch_offset",slice.x,slice.y)
            GLES31.glDispatchCompute(slice.groupsX,slice.groupsY,1)
            val sync=GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE,0)
            check(sync!=0L){"Cleanup slice fence allocation failed"}
            try {
                val deadline=SystemClock.elapsedRealtime()+10_000L
                while(true){
                    val result=GLES30.glClientWaitSync(sync,GLES30.GL_SYNC_FLUSH_COMMANDS_BIT,1_000_000L)
                    if(result==GLES30.GL_ALREADY_SIGNALED||result==GLES30.GL_CONDITION_SATISFIED)break
                    check(result!=GLES30.GL_WAIT_FAILED&&SystemClock.elapsedRealtime()<deadline){"Cleanup slice fence failed/timed out"}
                    Thread.yield()
                }
            }finally{GLES30.glDeleteSync(sync)}
            if(index%4==3)Thread.sleep(1)
        }
    }
    private fun <T> mapped(buffer:Int,bytes:Int,block:(ByteBuffer)->T):T {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,buffer)
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        val mapped=GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER,0,bytes,GLES30.GL_MAP_READ_BIT) as? ByteBuffer
        checkNotNull(mapped){"Cleanup sample mapping failed"}
        return try{block(mapped)}finally{check(GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER))}
    }
    fun close() {
        check(owner==null||owner==Thread.currentThread())
        programs.forEach{if(it!=0){GLES30.glDeleteProgram(it);MemoryLeakDiagnostics.glProgramReleased()}}
        programs.fill(0);owner=null
    }
    private fun program(mode:Int):Int {
        if(programs[mode]!=0)return programs[mode]
        val shader=GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        check(shader!=0)
        try {
            GLES31.glShaderSource(shader,"#version 310 es\n#define MODE $mode\n$source")
            GLES31.glCompileShader(shader);val ok=IntArray(1)
            GLES31.glGetShaderiv(shader,GLES31.GL_COMPILE_STATUS,ok,0)
            check(ok[0]!=0){"Cleanup shader $mode: ${GLES31.glGetShaderInfoLog(shader)}"}
            val p=GLES31.glCreateProgram();check(p!=0)
            try {
                GLES31.glAttachShader(p,shader);GLES31.glLinkProgram(p)
                GLES31.glGetProgramiv(p,GLES31.GL_LINK_STATUS,ok,0)
                check(ok[0]!=0){"Cleanup program $mode: ${GLES31.glGetProgramInfoLog(p)}"}
            }catch(e:Throwable){GLES31.glDeleteProgram(p);throw e}
            programs[mode]=p;MemoryLeakDiagnostics.glProgramAllocated();return p
        } finally{GLES31.glDeleteShader(shader)}
    }
    private fun int(p:Int,n:String,v:Int)=GLES30.glUniform1i(GLES30.glGetUniformLocation(p,n),v)
    private fun float(p:Int,n:String,v:Float)=GLES30.glUniform1f(GLES30.glGetUniformLocation(p,n),v)
    private fun ivec(p:Int,n:String,x:Int,y:Int)=GLES30.glUniform2i(GLES30.glGetUniformLocation(p,n),x,y)
    private fun noise(p:Int,q:Float,c:Float)=GLES30.glUniform2f(GLES30.glGetUniformLocation(p,"u_noise"),q,c)
    private fun checkGl(stage:String){val e=GLES30.glGetError();check(e==GLES30.GL_NO_ERROR){"$stage GL 0x${e.toString(16)}"}}
    private external fun nativeStatistics(samples:ByteBuffer,half:Int,quarter:Int,full:Int,detail:Int):FloatArray?
    private external fun nativeMedian(samples:ByteBuffer,count:Int):Float
    companion object {init{System.loadLibrary("dngCreator")}}
}
