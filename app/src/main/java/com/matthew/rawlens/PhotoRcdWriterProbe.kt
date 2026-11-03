// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.hardware.HardwareBuffer
import android.media.ImageWriter
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Independent application-level encoder layout/content check; no demosaic backend assumptions. */
internal object PhotoRcdWriterProbe {
    fun verify(context: Context, writer: ImageWriter, width: Int, height: Int): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        // Caller owns a started codec and no camera/worker submissions yet.
        return writer.dequeueInputImage().use { image ->
            image.fence.use { check(!it.isValid || it.await(java.time.Duration.ofSeconds(30))) }
            val output = checkNotNull(image.hardwareBuffer)
            output.use {
                val layout = checkNotNull(VfEglImport.describeP010(output))
                val sourceWidth=40; val sourceHeight=40
                val raw=ByteBuffer.allocateDirect(sourceWidth*sourceHeight*2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(sourceHeight) { y -> repeat(sourceWidth) { x ->
                    raw.putShort((y*sourceWidth+x)*2,(256+x*31+y*47+((x+y)%4)*73).toShort())
                } }
                PhotoRcdRenderer.open(context).use { renderer ->
                    val rgb=renderer.renderRawBuffer(raw,sourceWidth,sourceHeight,sourceWidth*2,2,0,0,0,
                        sourceWidth,sourceHeight,intArrayOf(0,1,2,3),FloatArray(4),4095f,floatArrayOf(1f,1f,1f),null,null)
                    checkNotNull(VfEglImport.createLinearP010(width,height)).use { reference ->
                        val (dims,params)=VfVideoGrade.packGrade(intArrayOf(width,height),floatArrayOf(1f,1f,1f,1f),VfVideoGrade.IDENTITY_CCM,0f,false,profile=EncodedVideoProfile.BT709)
                        fun grade(target: HardwareBuffer, strides: IntArray) = check(VfVideoGrade.gradeYuvBlockingNative(rgb,target,dims,intArrayOf(sourceWidth,sourceHeight),params,strides,0,1)==VfVulkan.OK)
                        grade(reference,intArrayOf(width*2,width*2,width,height))
                        grade(output,intArrayOf(layout[0],layout[1],width,height))
                        val expected=checkNotNull(VfEglImport.sampleLinearP010Grid(reference,width,height,16,16))
                        val planes=image.planes
                        check(planes.size==3)
                        var n=0
                        repeat(16) { gy -> repeat(16) { gx ->
                            val x=((width-2)*gx/15) and -2;val y=((height-2)*gy/15) and -2
                            repeat(3) { channel ->
                                val plane=planes[channel];val px=if(channel==0)x else x/2;val py=if(channel==0)y else y/2
                                val value=plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).getShort(py*plane.rowStride+px*plane.pixelStride).toInt() and 65535
                                check(value==expected[n++]) { "Encoder-native plane content differs at $x,$y channel=$channel" }
                            }
                        } }
                    }
                }
            }
            true // Image is closed, never queued: synthetic probe is not recording content.
        }
    }
}
