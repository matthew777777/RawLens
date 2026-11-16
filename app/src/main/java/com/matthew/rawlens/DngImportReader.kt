// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DNG metadata selection/normalization; TinyDNG handles strips, tiles and decompression. */
internal object DngImportReader {
    data class Image(val width: Int,val height: Int,val rgb: ByteBuffer,val orientation: Int,
                     val tags: Map<Int,DoubleArray>,val pattern: BayerPattern?=null) {
        fun matrix(tag: Int): ImmutableDoubleValues? = tags[tag]?.let { values ->
            require(values.size==9 && values.all(Double::isFinite)) { "Invalid DNG color matrix" }
            ImmutableDoubleValues(DoubleArray(9) { values[(it%3)*3+it/3] })
        }
    }
    private external fun decodeNative(path: String,width: Int,height: Int,channels: Int,output: ByteBuffer)

    fun read(file: File): Image {
        val tags=readTags(file)
        fun scalar(tag: Int,default: Int)=tags[tag]?.first()?.toInt() ?: default
        val width=scalar(256,0);val height=scalar(257,0);val channels=scalar(277,1)
        require(width>0 && height>0 && width.toLong()*height<=50_000_000) { "DNG dimensions exceed the 50 MP limit" }
        require(tags[50728]?.let { it.size==3 && it.all { n -> n.isFinite() && n>0 } }==true) { "DNG has no valid AsShotNeutral white balance" }
        require(channels==3 || channels==1) { "Unsupported DNG color-plane count" }
        val cfa=scalar(262,0)==32803
        val pattern=if(cfa) {
            require(tags[33421]?.contentEquals(doubleArrayOf(2.0,2.0))==true) { "This DNG uses a non-Bayer CFA layout" }
            val colors=tags[33422] ?: error("Missing CFA pattern")
            val planes=tags[50710] ?: doubleArrayOf(0.0,1.0,2.0)
            require(colors.size==4) { "Invalid CFA pattern" }
            val actual=colors.map { planes.getOrNull(it.toInt())?.toInt() ?: -1 }
            when(actual) {
                listOf(0,1,1,2) -> BayerPattern.RGGB
                listOf(1,0,2,1) -> BayerPattern.GRBG
                listOf(1,2,0,1) -> BayerPattern.GBRG
                listOf(2,1,1,0) -> BayerPattern.BGGR
                else -> error("Unsupported DNG CFA colors")
            }
        } else null
        require((cfa && channels==1) || (!cfa && channels==3)) { "Unsupported DNG image layout" }
        require(scalar(50711,1)==1) { "Staggered DNG CFA layouts are not supported" }
        val bits=tags[258]?.first()?.toInt() ?: 16
        require(bits in 1..32) { "Unsupported DNG bit depth" }
        val black=tags[50714] ?: doubleArrayOf(0.0)
        val white=tags[50717] ?: doubleArrayOf(if(scalar(339,1)==3) 1.0 else Math.scalb(1.0,bits)-1)
        val repeat=tags[50713] ?: doubleArrayOf(1.0,1.0)
        require(repeat.size==2 && repeat.all { it in 1.0..64.0 }) { "Invalid DNG black-level repeat" }
        val repeatH=repeat[0].toInt();val repeatW=repeat[1].toInt()
        require(black.size==1 || black.size==repeatH*repeatW*channels) { "Unsupported DNG black-level layout" }
        require(white.size==1 || white.size==channels) { "Unsupported DNG white-level layout" }
        val raw=ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width,height),channels*4)).order(ByteOrder.nativeOrder())
        TinyDngImageWriter.load();decodeNative(file.absolutePath,width,height,channels,raw)
        val active=tags[50829] ?: doubleArrayOf(0.0,0.0,height.toDouble(),width.toDouble())
        require(active.size==4) { "Invalid DNG active area" }
        val top=active[0].toInt();val left=active[1].toInt();val bottom=active[2].toInt();val right=active[3].toInt()
        require(top>=0 && left>=0 && bottom<=height && right<=width && bottom>top && right>left) { "Invalid DNG active area" }
        val origin=tags[50719] ?: doubleArrayOf(0.0,0.0)
        val cropSize=tags[50720] ?: doubleArrayOf((right-left).toDouble(),(bottom-top).toDouble())
        require(origin.size==2 && cropSize.size==2) { "Invalid DNG default crop" }
        val cropX=left+origin[0].toInt();val cropY=top+origin[1].toInt()
        var w=cropSize[0].toInt();var h=cropSize[1].toInt()
        require(cropX>=left && cropY>=top && w>0 && h>0 && cropX.toLong()+w<=right && cropY.toLong()+h<=bottom) { "DNG default crop is outside active area" }
        if(cfa) { w=w and -2;h=h and -2;require(w>=4 && h>=4) { "Bayer DNG is too small" } }
        val fullCrop=cropX==0 && cropY==0 && w==width && h==height
        val result=if(fullCrop) raw else ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(w,h),channels*4)).order(ByteOrder.nativeOrder())
        val linearization=tags[50712]
        val deltaH=tags[50715];val deltaV=tags[50716]
        require(deltaH==null || deltaH.size==right-left) { "Invalid horizontal black-level correction" }
        require(deltaV==null || deltaV.size==bottom-top) { "Invalid vertical black-level correction" }
        val gains=readGainMaps(tags[51008])+readGainMaps(tags[51009])
        for(y in 0 until h) for(x in 0 until w) for(c in 0 until channels) {
            val sx=x+cropX;val sy=y+cropY
            var code=raw.getFloat(((sy*width+sx)*channels+c)*4).toDouble()
            if(linearization!=null) {
                val index=code.toInt();require(index in linearization.indices) { "DNG linearization index outside table" };code=linearization[index]
            }
            val b=black[if(black.size==1) 0 else ((sy%repeatH)*repeatW+sx%repeatW)*channels+c]+
                (deltaH?.get(sx-left) ?: 0.0)+(deltaV?.get(sy-top) ?: 0.0)
            val whiteLevel=white[if(white.size==1) 0 else c]
            require(code.isFinite() && b.isFinite() && whiteLevel.isFinite() && whiteLevel>b) { "DNG has invalid sample or black/white levels" }
            var value=((code-b)/(whiteLevel-b)).coerceAtLeast(0.0)
            gains.forEach { gain -> value*=gain.at(sx,sy,c,width,height) }
            result.putFloat(((y*w+x)*channels+c)*4,value.toFloat())
        }
        result.position(0)
        // AnalogBalance belongs before CameraCalibration in the DNG color contract.
        // Fold it into the calibration matrices, as SceneLinearColorMetadata's
        // camera-capture input has no separate AnalogBalance field.
        val resolved=tags.toMutableMap()
        tags[50727]?.let { balance ->
            require(balance.size==3 && balance.all { it.isFinite() && it>0 }) { "Invalid DNG analog balance" }
            for(id in listOf(50723,50724)) {
                val matrix=tags[id] ?: doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
                require(matrix.size==9)
                resolved[id]=DoubleArray(9) { matrix[it]*balance[it/3] }
            }
        }
        return Image(w,h,result,scalar(274,1).takeIf { it in 1..8 } ?: 1,resolved,pattern?.shifted(cropX,cropY))
    }

    /** TIFF/SubIFD traversal: choose the full-resolution raw IFD, never its JPEG preview. */
    internal fun readTags(file: File): Map<Int,DoubleArray> = RandomAccessFile(file,"r").use { input ->
        require(input.length()>=8) { "Incomplete DNG header" }
        val header=ByteArray(8).also(input::readFully)
        val order=when(String(header,0,2,Charsets.US_ASCII)) { "II" -> ByteOrder.LITTLE_ENDIAN;"MM" -> ByteOrder.BIG_ENDIAN;else -> error("Not a TIFF/DNG file") }
        fun buffer(bytes: ByteArray)=ByteBuffer.wrap(bytes).order(order)
        fun u32(b: ByteBuffer,offset: Int)=b.getInt(offset).toLong() and 0xffffffffL
        val head=buffer(header);require((head.getShort(2).toInt() and 65535)==42) { "BigTIFF DNGs are not supported" }
        val wanted=setOf(254,256,257,258,259,262,274,277,284,330,33421,33422,339,50706,
            50710,50711,50712,50713,50714,50715,50716,50717,50719,50720,50721,50722,50723,50724,
            50727,50728,50730,50778,50779,50829,50964,50965,51008,51009)
        val candidates=mutableListOf<Map<Int,DoubleArray>>();val visited=mutableSetOf<Long>()
        fun visit(offset: Long,parent: Map<Int,DoubleArray>) {
            if(offset==0L) return
            require(visited.add(offset) && visited.size<=64 && offset in 8..input.length()-2) { "Invalid or cyclic DNG directory" }
            input.seek(offset)
            val count=buffer(ByteArray(2).also(input::readFully)).short.toInt() and 65535
            require(count<=4096 && offset+2+count*12L+4<=input.length()) { "Invalid DNG directory size" }
            val tags=parent.filterKeys { it>=50700 || it==274 }.toMutableMap()
            repeat(count) { index ->
                input.seek(offset+2+index*12L);val entry=buffer(ByteArray(12).also(input::readFully))
                val tag=entry.getShort(0).toInt() and 65535;if(tag !in wanted) return@repeat
                val type=entry.getShort(2).toInt() and 65535;val n=u32(entry,4)
                val unit=when(type) { 1,6,7 -> 1;3,8 -> 2;4,9,11,13 -> 4;5,10,12 -> 8;else -> error("Unsupported DNG field type $type") }
                require(n in 1..2_000_000 && n*unit<=16_000_000) { "DNG metadata field is too large" }
                val length=(n*unit).toInt()
                val payload=if(length<=4) entry.array().copyOfRange(8,8+length) else {
                    val pos=u32(entry,8);require(pos>=8 && pos+length<=input.length()) { "DNG field outside file" }
                    input.seek(pos);ByteArray(length).also(input::readFully)
                }
                val b=buffer(payload)
                tags[tag]=DoubleArray(n.toInt()) {
                    when(type) {
                        1,7 -> (b.get().toInt() and 255).toDouble();6 -> b.get().toDouble()
                        3 -> (b.short.toInt() and 65535).toDouble();8 -> b.short.toDouble()
                        4,13 -> (b.int.toLong() and 0xffffffffL).toDouble();9 -> b.int.toDouble()
                        11 -> b.float.toDouble();12 -> b.double
                        else -> {
                            val numerator=b.int;val denominator=b.int
                            val num=if(type==5) (numerator.toLong() and 0xffffffffL).toDouble() else numerator.toDouble()
                            val den=if(type==5) (denominator.toLong() and 0xffffffffL).toDouble() else denominator.toDouble()
                            require(den!=0.0) { "Invalid DNG rational" };num/den
                        }
                    }
                }
            }
            val photo=tags[262]?.first()?.toInt()
            if(photo==32803 || photo==34892) candidates+=tags.toMap()
            tags[330]?.forEach { visit(it.toLong(),tags) }
            input.seek(offset+2+count*12L);val next=u32(buffer(ByteArray(4).also(input::readFully)),0)
            visit(next,parent)
        }
        visit(u32(head,4),emptyMap())
        require(candidates.isNotEmpty()) { "No RAW image found in DNG" }
        candidates.maxBy { (it[256]?.first() ?: 0.0)*(it[257]?.first() ?: 0.0) }
    }

    private data class Gain(val top: Int,val left: Int,val bottom: Int,val right: Int,val plane: Int,val planes: Int,
        val rowPitch: Int,val colPitch: Int,val rows: Int,val cols: Int,val mapPlanes: Int,
        val spacingY: Double,val spacingX: Double,val originY: Double,val originX: Double,val values: FloatArray) {
        fun at(x: Int,y: Int,c: Int,width: Int,height: Int): Double {
            if(x !in left until right || y !in top until bottom || c !in plane until plane+planes ||
                (y-top)%rowPitch!=0 || (x-left)%colPitch!=0) return 1.0
            val gx=(((x+.5)/width-originX)/spacingX).coerceIn(0.0,(cols-1).toDouble())
            val gy=(((y+.5)/height-originY)/spacingY).coerceIn(0.0,(rows-1).toDouble())
            val ix=gx.toInt();val iy=gy.toInt();val fx=gx-ix;val fy=gy-iy
            fun v(dx: Int,dy: Int)=values[((minOf(iy+dy,rows-1)*cols+minOf(ix+dx,cols-1))*mapPlanes)+
                if(mapPlanes==1) 0 else c-plane].toDouble()
            return (v(0,0)*(1-fx)+v(1,0)*fx)*(1-fy)+(v(0,1)*(1-fx)+v(1,1)*fx)*fy
        }
    }
    private fun readGainMaps(data: DoubleArray?): List<Gain> {
        if(data==null) return emptyList()
        val b=ByteBuffer.wrap(ByteArray(data.size) { data[it].toInt().toByte() }).order(ByteOrder.BIG_ENDIAN)
        require(b.remaining()>=4);val count=b.int;require(count in 0..4096)
        val result=mutableListOf<Gain>()
        repeat(count) {
            require(b.remaining()>=16);val id=b.int;b.int;val flags=b.int;val size=b.int
            require(size>=0 && size<=b.remaining()) { "Invalid DNG opcode" };val end=b.position()+size
            if(id==9) {
                require(size>=76) { "Incomplete DNG gain map" }
                val top=b.int;val left=b.int;val bottom=b.int;val right=b.int;val plane=b.int;val planes=b.int
                val rowPitch=b.int;val colPitch=b.int;val rows=b.int;val cols=b.int
                val sy=b.double;val sx=b.double;val oy=b.double;val ox=b.double;val mapPlanes=b.int
                val n=rows.toLong()*cols*mapPlanes
                require(rows>0 && cols>0 && mapPlanes in 1..3 && (mapPlanes==1 || mapPlanes>=planes) &&
                    planes>0 && plane>=0 && rowPitch>0 && colPitch>0 && sy>0 && sx>0 && sy.isFinite() && sx.isFinite() &&
                    oy.isFinite() && ox.isFinite() && n<=2_000_000 && n*4<=end-b.position()) { "Invalid DNG gain map" }
                val values=FloatArray(n.toInt()) { b.float }
                require(values.all { it.isFinite() && it>0 }) { "Invalid DNG gain" }
                result+=Gain(top,left,bottom,right,plane,planes,rowPitch,colPitch,rows,cols,mapPlanes,sy,sx,oy,ox,values)
            } else require(flags and 1 != 0) { "Required DNG opcode $id is not supported" }
            b.position(end)
        }
        return result
    }
}
