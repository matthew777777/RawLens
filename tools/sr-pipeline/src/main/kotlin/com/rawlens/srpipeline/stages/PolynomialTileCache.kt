package com.rawlens.srpipeline.stages

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import kotlin.math.*

/** Desktop bounded-memory oracle: global Vulkan registration, exact cached
 * float samples/weights, then independent output tiles with complete source
 * halos. No tile-local registration or image blending. Scratch is temporary. */
class PolynomialTileCache(private val width: Int, private val height: Int, private val config: PipelineConfig) : AutoCloseable {
    private val directory=Files.createTempDirectory("srp-polynomial-")
    private data class Record(val frame: Path,val rob: Path,val flow: Array<Array<FloatArray>>)
    private val records=mutableListOf<Record>()
    fun record(frame: ByteBuffer,flow: Array<Array<FloatArray>>,rob: ByteBuffer) {
        fun write(name:String,buffer:ByteBuffer,count:Int):Path {
            val file=directory.resolve(name)
            FileChannel.open(file,CREATE_NEW,WRITE).use { ch ->
                val b=buffer.duplicate().apply { clear();limit(count*4) }
                while(b.hasRemaining()) ch.write(b)
            }
            return file
        }
        val n=records.size
        records+=Record(write("frame-$n.f32",frame,width*height),write("rob-$n.f32",rob,width/2*(height/2)),
            Array(flow.size) { y -> Array(flow[y].size) { x -> flow[y][x].copyOf() } })
        println("sr-pipeline: cached global registration ${n+1}")
    }
    fun finish(rgb:ByteBuffer,tilePixels:Int=512) {
        val wb=requireNotNull(config.colourDiff)
        val ow=(width*config.scale).toInt();val oh=(height*config.scale).toInt()
        val dst=rgb.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val weights=LumaFrameWeights.forBurst(records.size,config.grainRetention)
        val frames=records.map { FileChannel.open(it.frame,READ).use { ch -> ch.map(FileChannel.MapMode.READ_ONLY,0,ch.size()).order(ByteOrder.nativeOrder()).asFloatBuffer() } }
        val strengths=records.map { FileChannel.open(it.rob,READ).use { ch -> ch.map(FileChannel.MapMode.READ_ONLY,0,ch.size()).order(ByteOrder.nativeOrder()).asFloatBuffer() } }
        val count=((ow+tilePixels-1)/tilePixels)*((oh+tilePixels-1)/tilePixels)
        var completed=0
        for(y0 in 0 until oh step tilePixels) for(x0 in 0 until ow step tilePixels) {
            val tw=min(tilePixels,ow-x0);val th=min(tilePixels,oh-y0)
            val originX=x0/config.scale;val originY=y0/config.scale
            val result=Array(th) { y -> Array(tw) { x ->
                val i=((y+y0)*ow+x+x0)*3;val g=dst.get(i+1)
                floatArrayOf(wb[0]*dst.get(i)-g,g,wb[1]*dst.get(i+2)-g)
            } }
            val fit=PolynomialReconstruction(tw,th,config.scale,config.tileSizes.first(),config.kernelAlpha.average().toFloat(),config.kernelBeta.average().toFloat(),originX,originY)
            val rx0=floor((originX+.5f/config.scale)/2).toInt().coerceIn(0,width/2-1)
            val ry0=floor((originY+.5f/config.scale)/2).toInt().coerceIn(0,height/2-1)
            val rx1=floor((originX+(tw-.5f)/config.scale)/2).toInt().coerceIn(rx0,width/2-1)+1
            val ry1=floor((originY+(th-.5f)/config.scale)/2).toInt().coerceIn(ry0,height/2-1)+1
            for((index,record) in records.withIndex()) {
                val flow=record.flow;val tile=config.tileSizes.first()
                val fx0=(originX/tile).toInt().coerceIn(0,flow[0].lastIndex)
                val fy0=(originY/tile).toInt().coerceIn(0,flow.lastIndex)
                val fx1=((originX+tw/config.scale)/tile).toInt().coerceIn(fx0,flow[0].lastIndex)
                val fy1=((originY+th/config.scale)/tile).toInt().coerceIn(fy0,flow.lastIndex)
                var minX=0f;var maxX=0f;var minY=0f;var maxY=0f
                for(yy in fy0..fy1) for(xx in fx0..fx1) {
                    val f=flow[yy][xx];minX=min(minX,f[0]);maxX=max(maxX,f[0]);minY=min(minY,f[1]);maxY=max(maxY,f[1])
                }
                val sx0=(floor(originX+minX-6).toInt().coerceIn(0,width-2)/2)*2
                val sy0=(floor(originY+minY-6).toInt().coerceIn(0,height-2)/2)*2
                val sx1=((ceil(originX+tw/config.scale+maxX+6).toInt()+1)/2*2).coerceIn(sx0+2,width)
                val sy1=((ceil(originY+th/config.scale+maxY+6).toInt()+1)/2*2).coerceIn(sy0+2,height)
                val src=frames[index]
                val patch=Array(sy1-sy0) { y -> FloatArray(sx1-sx0).also { row -> src.get((sy0+y)*width+sx0,row,0,row.size) } }
                val r=strengths[index]
                val rob=Array(ry1-ry0) { y -> FloatArray(rx1-rx0).also { row -> r.get((ry0+y)*(width/2)+rx0,row,0,row.size) } }
                fit.add(patch,flow,rob,weights[index],sx0,sy0,rx0,ry0)
            }
            fit.finish(result)
            ColourDiff.reconstruct(result,wb[0],wb[1])
            for(y in 0 until th) for(x in 0 until tw) {
                val i=((y+y0)*ow+x+x0)*3
                for(ch in 0..2) dst.put(i+ch,result[y][x][ch])
            }
            println("sr-pipeline: polynomial tile ${++completed}/$count at $x0,$y0 (${tw}x$th)")
        }
        rgb.rewind()
    }
    override fun close() {
        Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(directory)
    }
}
