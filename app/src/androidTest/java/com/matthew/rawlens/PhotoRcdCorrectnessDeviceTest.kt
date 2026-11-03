// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/** Headless, finite synthetic/CC0 input only. No activity, camera or recording. */
@RunWith(AndroidJUnit4::class)
class PhotoRcdCorrectnessDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun values(name: String, hash: String): FloatArray {
        val bytes = instrumentation.context.assets.open("photo_rcd_reference/$name").use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("fixture hash", hash, actual)
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }
    private fun cropPhase(channels: IntArray, left: Int, top: Int): Int {
        var phase = 0
        for (q in 0..3) {
            val sensorSite = (((q / 2 + top) and 1) * 2) + ((q % 2 + left) and 1)
            phase = phase or ((when (sensorSite) { channels[0] -> 0; channels[3] -> 2; else -> 1 }) shl (q * 2))
        }
        return phase
    }
    private fun phase(pattern: String): Int = pattern.mapIndexed { q,c -> "RGB".indexOf(c) shl (q*2) }.reduce(Int::or)

    @Test fun canonicalBaselineAndOptimizedMatchPinnedOracle() {
        val manifest = JSONObject(instrumentation.context.assets.open("photo_rcd_reference/manifest.json").bufferedReader().use { it.readText() })
        val cases = manifest.getJSONArray("cases")
        val results = JSONArray()
        val report = JSONObject().put("device", android.os.Build.MODEL).put("scope", "headless correctness only; no recording/performance test").put("cases", results)
        val directory = File(context.getExternalFilesDir(null), "photo-rcd-correctness").apply { mkdirs() }
        try {
            PhotoRcdRenderer.open(context, false).use { baseline ->
                PhotoRcdRenderer.open(context, true).use { optimized ->
                    PhotoRcdRenderer.open(context,true,true).use { cached ->
                    PhotoRcdRenderer.open(context,true,true,true).use { tiled ->
                    PhotoRcdRenderer.open(context,true,true,false,true).use { imageBased ->
                    repeat(cases.length()) { index ->
                        val fixture = cases.getJSONObject(index); val w=fixture.getInt("width");val h=fixture.getInt("height")
                        val cfa=values(fixture.getString("input"),fixture.getString("input_sha256"))
                        val expected=values(fixture.getString("expected"),fixture.getString("expected_sha256"))
                        val first=baseline.canonical(w,h,phase(fixture.getString("cfa")),cfa)
                        val second=optimized.canonical(w,h,phase(fixture.getString("cfa")),cfa)
                        var maximum=0f;var sum=0.0;var border=0f
                        for(i in expected.indices) {
                            val pixel=i/3;val channel=i%3;val value=first[pixel*4+channel]
                            assertTrue("nonfinite case $index",value.isFinite())
                            val error=abs(value-expected[i]);maximum=maxOf(maximum,error);sum+=error
                            val x=pixel%w;val y=pixel/w
                            if(x<9 || y<9 || x>=w-9 || y>=h-9)border=maxOf(border,error)
                        }
                        assertArrayEquals("same-device optimization must be exact, case $index",first,second,0f)
                        assertArrayEquals("response-cache optimization must be exact, case $index",first,cached.canonical(w,h,phase(fixture.getString("cfa")),cfa),0f)
                        assertArrayEquals("tiled reconstruction must be exact, case $index",first,tiled.canonical(w,h,phase(fixture.getString("cfa")),cfa),0f)
                        assertArrayEquals("image intermediates must be exact, case $index",first,imageBased.canonical(w,h,phase(fixture.getString("cfa")),cfa),0f)
                        val row=JSONObject().put("index",index).put("fixture",fixture).put("max_abs",maximum).put("mean_abs",sum/expected.size).put("border_max_abs",border)
                        results.put(row);Log.i("PhotoRcdCorrectness",row.toString())
                        assertTrue("canonical max case $index: $maximum",maximum<=2e-5f)
                        assertTrue("canonical mean case $index",sum/expected.size<=2e-6)
                    }
                    // Invalid requests cannot poison the next valid frame.
                    for(bad in listOf(0,255,132)) {
                        try { baseline.canonical(19,19,bad,FloatArray(361));fail("bad phase accepted") }
                        catch (_: IllegalStateException) { }
                    }
                    baseline.canonical(19,19,148,FloatArray(361))
                    for(bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,-.01f,1.01f)) {
                        try { baseline.canonical(19,19,148,FloatArray(361) { bad });fail("bad normalized input accepted") }
                        catch (_: IllegalStateException) { }
                    }
                    baseline.canonical(19,19,148,FloatArray(361))
                    }
                    }
                    }
                }
            }
            report.put("passed",true)
        } finally { File(directory,"canonical.json").writeText(report.toString(2)) }
    }

    /** Diagnostic: reports the compatibility candidate's differences without
     * treating a successful dispatch as a passing canonical numerical gate. */
    @Test fun recordingCandidateOracleDiagnostic() {
        val manifest=JSONObject(instrumentation.context.assets.open("photo_rcd_reference/manifest.json").bufferedReader().use { it.readText() })
        val cases=manifest.getJSONArray("cases");val rows=JSONArray()
        var maximum=0f;var interior=0f
        PhotoRcdRenderer.open(context,cacheResponses=InstrumentationRegistry.getArguments().getString("responseCache")=="true",images=InstrumentationRegistry.getArguments().getString("images")=="true",recordingCandidate=true,baked=InstrumentationRegistry.getArguments().getString("baked")=="true",packed=InstrumentationRegistry.getArguments().getString("packed")=="true",directRaw=InstrumentationRegistry.getArguments().getString("directRaw")=="true").use { renderer ->
            repeat(cases.length()) { index ->
                val fixture=cases.getJSONObject(index);val w=fixture.getInt("width");val h=fixture.getInt("height")
                val cfa=values(fixture.getString("input"),fixture.getString("input_sha256"))
                val expected=values(fixture.getString("expected"),fixture.getString("expected_sha256"))
                val actual=renderer.canonical(w,h,phase(fixture.getString("cfa")),cfa)
                var localMax=0f;var localInterior=0f
                for(i in expected.indices) {
                    val pixel=i/3;val value=actual[pixel*4+i%3]
                    assertTrue("candidate nonfinite $index",value.isFinite())
                    val error=abs(value-expected[i]);localMax=maxOf(localMax,error)
                    val x=pixel%w;val y=pixel/w
                    if(x>=9 && y>=9 && x<w-9 && y<h-9)localInterior=maxOf(localInterior,error)
                }
                maximum=maxOf(maximum,localMax);interior=maxOf(interior,localInterior)
                rows.put(JSONObject().put("fixture",fixture).put("max_abs",localMax).put("interior_max_abs",localInterior))
            }
        }
        val report=JSONObject().put("scope","candidate diagnostic; finite dispatch is not numerical acceptance")
            .put("cases",rows).put("max_abs",maximum).put("interior_max_abs",interior)
            .put("interior_max_budget_pass",interior<=2e-5f).put("device",android.os.Build.MODEL)
        val directory=File(context.getExternalFilesDir(null),"photo-rcd-correctness").apply { mkdirs() }
        File(directory,"recording-candidate.json").writeText(report.toString(2))
        assertTrue("candidate canonical max error: $maximum",maximum<=2e-5f)
    }

    @Test fun rawAdapterCorrectionsAndGradeOwnership() = adapterCheck(null)

    @Test fun recordedLutThroughSeparateVulkanGrade() {
        val text=buildString {
            append("LUT_3D_SIZE 16\n")
            repeat(16) { b -> repeat(16) { g -> repeat(16) { r -> append("${.1f+.8f*r/15f} ${.2f+.5f*g/15f} ${1f-b/15f}\n") } } }
        }
        adapterCheck(CubeLut.parse(text.reader()))
    }

    /** Persistent integer-input route versus the validated float-input adapter.
     * Tolerance is final binary16 storage (one ULP <= .000977 below 2),
     * plus the established FP32 numerical budget; arithmetic stays FP32.
     */
    @Test fun packedIntegerInputMatchesValidatedAdapter() {
        check(VfEglImport.available)
        val rows=JSONArray()
        PhotoRcdRenderer.open(context,requirementsInput=true,cacheResponses=InstrumentationRegistry.getArguments().getString("normalizeOnce")=="true" && InstrumentationRegistry.getArguments().getString("responseCache")=="true",normalizeOnce=InstrumentationRegistry.getArguments().getString("normalizeOnce")=="true",sharedFinal=InstrumentationRegistry.getArguments().getString("sharedFinal")=="true",tileLocal=InstrumentationRegistry.getArguments().getString("tileLocal")=="true",gpuImage=InstrumentationRegistry.getArguments().getString("gpuImage")=="true").use { packed ->
            PhotoRcdRenderer.open(context).use { baseline ->
                for(size in listOf(19 to 21,41 to 38,194 to 195)) {
                    for(origin in 0..3) for(stride in 2..4) {
                        val w=size.first;val h=size.second;val left=origin and 1;val top=origin shr 1
                        val sw=w+2;val sh=h+2;val row=(sw-1)*stride+9
                        val raw=ByteBuffer.allocateDirect((sh-1)*row+(sw-1)*stride+2).order(ByteOrder.LITTLE_ENDIAN)
                        repeat(sh) { y -> repeat(sw) { x -> raw.putShort(y*row+x*stride,((x*137+y*991+17)%5000).toShort()) } }
                        val black=floatArrayOf(64f,64.5f,63.25f,65f)
                        val channels=intArrayOf(0,1,2,3);val wb=floatArrayOf(1.7f,1f,1.3f)
                        val cropPhase=phase(listOf("RGGB","GRBG","GBRG","BGGR")[origin])
                        val normalized=FloatArray(w*h) { j ->
                            val x=j%w+left;val y=j/w+top;val b=black[(y and 1)*2+(x and 1)]
                            (((x*137+y*991+17)%5000-b)/(4095f-b)).coerceIn(0f,1f)
                        }
                        val fp32Compared=InstrumentationRegistry.getArguments().getString("gpuImage")!="true"
                        val canonical=if(fp32Compared) packed.canonicalRaw(raw,intArrayOf(sw,sh,row,stride,left,top,w,h,cropPhase),black,4095f) else FloatArray(0)
                        val oracle=baseline.canonical(w,h,cropPhase,normalized)
                        var fpMax=0f;var fpSum=0.0
                        for(j in canonical.indices) {
                            assertTrue(canonical[j].isFinite());val error=abs(canonical[j]-oracle[j]);fpMax=maxOf(fpMax,error);fpSum+=error
                        }
                        assertTrue("FP32 tile/local RAW error $fpMax",fpMax<=2e-5f)
                        if(fp32Compared) assertTrue("FP32 tile/local RAW mean",fpSum/canonical.size<=2e-6)
                        val a=packed.renderRawBuffer(raw,sw,sh,row,stride,0,left,top,w,h,channels,black,4095f,wb,null,null)
                        val b=baseline.renderRawBuffer(raw,sw,sh,row,stride,0,left,top,w,h,channels,black,4095f,wb,null,null)
                        val actual=checkNotNull(VfEglImport.dumpFp16(a,w,h));val expected=checkNotNull(VfEglImport.dumpFp16(b,w,h))
                        var maximum=0f
                        for(j in actual.indices) { assertTrue(actual[j].isFinite());maximum=maxOf(maximum,abs(actual[j]-expected[j])) }
                        assertTrue("packed adapter $w x $h origin=$origin stride=$stride max=$maximum",maximum<=.001f)
                        rows.put(JSONObject().put("width",w).put("height",h).put("origin",origin).put("pixel_stride",stride).put("max_abs_half",maximum).put("fp32_compared",fp32Compared).put("max_abs_fp32",if(fp32Compared) fpMax else JSONObject.NULL).put("mean_abs_fp32",if(fp32Compared) fpSum/canonical.size else JSONObject.NULL))
                    }
                }
                val valid=ByteBuffer.allocateDirect(19*19*2)
                try { packed.renderRawBuffer(valid,19,19,38,2,0,0,0,19,19,intArrayOf(0,1,2,3),FloatArray(4),0f,floatArrayOf(1f,1f,1f),null,null);fail("invalid white accepted") }
                catch(_: IllegalStateException) { }
                packed.renderRawBuffer(valid,19,19,38,2,0,0,0,19,19,intArrayOf(0,1,2,3),FloatArray(4),4095f,floatArrayOf(1f,1f,1f),null,null)
            }
        }
        val scaledRows=JSONArray()
        PhotoRcdRenderer.open(context).use { baseline ->
            for(scale in listOf(2,3,4,8)) PhotoRcdRenderer.open(context,requirementsInput=true,reconstructionScale=scale).use { reduced ->
                for(size in listOf(79 to 83,193 to 195)) for(origin in 0..3) {
                    val w=size.first;val h=size.second;val left=origin and 1;val top=origin shr 1
                    if(scale==8 && w<100) continue
                    val sw=w+2;val sh=h+2;val row=sw*3+7
                    val raw=ByteBuffer.allocateDirect(row*sh).order(ByteOrder.LITTLE_ENDIAN)
                    fun code(x:Int,y:Int)=(x*137+y*991+17)%5000
                    repeat(sh) { y -> repeat(sw) { x -> raw.putShort(y*row+x*3,code(x,y).toShort()) } }
                    val ow=2*((w+2*scale-1)/(2*scale));val oh=2*((h+2*scale-1)/(2*scale))
                    val rw=ow+2;val rh=oh+2;val reference=ByteBuffer.allocateDirect(rw*rh*2).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(oh) { y -> repeat(ow) { x ->
                        var total=0;var count=0
                        repeat(scale) { dy -> repeat(scale) { dx ->
                            val sx=(x/2)*2*scale+(x and 1)+2*dx;val sy=(y/2)*2*scale+(y and 1)+2*dy
                            if(sx<w && sy<h) { total+=code(sx+left,sy+top);count++ }
                        } }
                        reference.putShort(((y+top)*rw+x+left)*2,(if(count==0) 0 else (total+count/2)/count).toShort())
                    } }
                    val black=floatArrayOf(64f,64.5f,63.25f,65f);val wb=floatArrayOf(1.7f,1f,1.3f);val channels=intArrayOf(0,1,2,3)
                    val a=reduced.renderRawBuffer(raw,sw,sh,row,3,0,left,top,w,h,channels,black,4095f,wb,null,null)
                    assertEquals(ow,a.width);assertEquals(oh,a.height)
                    val b=baseline.renderRawBuffer(reference,rw,rh,rw*2,2,0,left,top,ow,oh,channels,black,4095f,wb,null,null)
                    val actual=checkNotNull(VfEglImport.dumpFp16(a,ow,oh));val expected=checkNotNull(VfEglImport.dumpFp16(b,ow,oh))
                    var maximum=0f
                    for(j in actual.indices) { assertTrue(actual[j].isFinite());maximum=maxOf(maximum,abs(actual[j]-expected[j])) }
                    assertTrue("reduced scale=$scale origin=$origin max=$maximum",maximum<=.001f)
                    scaledRows.put(JSONObject().put("scale",scale).put("source_width",w).put("source_height",h).put("origin",origin).put("reconstruction_width",ow).put("reconstruction_height",oh).put("max_abs_half",maximum))
                }
            }
        }
        File(File(context.getExternalFilesDir(null),"photo-rcd-correctness").apply { mkdirs() },"requirements-adapter.json")
            .writeText(JSONObject().put("cases",rows).put("passed",true).put("scaled_cases",scaledRows).put("scope","36 full-resolution and 28 independently area-reduced adapter comparisons; final half boundary; not perceptual quality acceptance").toString(2))
    }

    /**
     * Frame-global recording RCD (A) against the exact tile-local port (B).
     * A is not bit-exact: it reconstructs frame-globally and its final pass
     * runs in FP16. Budget (provisional, set from host runs: 75 dB without
     * LSC, max ~3.9e-3 with LSC): max |A-B| <= 4e-3, mean <= 2e-4 per FP16
     * channel. Then the fused grade+P010 pass must match a CPU grade of A's own
     * RGB at the same exact sample positions within 2 codes (host check: max 1).
     */
    @Test fun frameGlobalWithinBudgetAndFusedGradeMatchesSeparate() {
        check(VfEglImport.available)
        fun asset(path:String)=context.assets.open(path).use { it.readBytes() }
        check(VfVulkan.initNative(asset("shaders/vf/vf_superpixel.spv"))==VfVulkan.OK)
        check(VfVideoGrade.recordCubeUploadNative(null,0,null)==VfVulkan.OK)
        check(VfVideoGrade.initGradeNative(asset("shaders/vf/vf_videograde.spv"))==VfVulkan.OK)
        check(VfVideoGrade.initGradeYuvNative(asset("shaders/vf/vf_gradeyuv.spv"))==VfVulkan.OK)
        check(VfVideoGrade.gradeLutUploadNative(VfVideoGrade.bakeLut(VfVideoGrade.DEFAULT_CONTRAST))==VfVulkan.OK)
        val rows=JSONArray();val p010Rows=JSONArray()
        val patterns=listOf("cfa" to listOf(intArrayOf(0,1,2,3),intArrayOf(1,0,3,2),intArrayOf(2,3,0,1),intArrayOf(3,2,1,0)))
        val ccm=floatArrayOf(1.6f,-.45f,-.15f,-.2f,1.4f,-.2f,0f,-.5f,1.5f)
        PhotoRcdRenderer.open(context,requirementsInput=true,normalizeOnce=true,tileLocal=true,frameGlobal=true).use { global ->
            PhotoRcdRenderer.open(context).use { baseline ->
                for(size in listOf(132 to 66,200 to 120,388 to 220)) for(left in listOf(0,4)) for(top in 0..1) for(channels in patterns[0].second) {
                    val w=size.first;val h=size.second
                    val sw=w+left+4;val sh=h+top+2;val row=(sw*2+15) and -8
                    val raw=ByteBuffer.allocateDirect(row*sh).order(ByteOrder.LITTLE_ENDIAN)
                    // Smooth ramp plus fine texture: exercises both RCD directions.
                    fun code(x:Int,y:Int)=(300+x*9+y*5+((x*137+y*991+17)%997)).coerceAtMost(4000)
                    repeat(sh) { y -> repeat(sw) { x -> raw.putShort(y*row+x*2,code(x,y).toShort()) } }
                    val black=floatArrayOf(64f,64.5f,63.25f,65f);val wb=floatArrayOf(1.7f,1f,1.3f)
                    val a=global.renderRawBuffer(raw,sw,sh,row,2,0,left,top,w,h,channels,black,4095f,wb,null,null)
                    val b=baseline.renderRawBuffer(raw,sw,sh,row,2,0,left,top,w,h,channels,black,4095f,wb,null,null)
                    val actual=checkNotNull(VfEglImport.dumpFp16(a,w,h));val expected=checkNotNull(VfEglImport.dumpFp16(b,w,h))
                    var maximum=0f;var sum=0.0
                    var worstAt=-1
                    for(j in actual.indices) { assertTrue(actual[j].isFinite());val e=abs(actual[j]-expected[j]);if(e>maximum){maximum=e;worstAt=j};sum+=e }
                    val mean=sum/actual.size
                    assertTrue("frame-global $w x $h left=$left top=$top ch=${channels.toList()} max=$maximum at x=${worstAt/4%w} y=${worstAt/4/w} c=${worstAt%4} got=${actual.getOrNull(worstAt)} want=${expected.getOrNull(worstAt)}",maximum<=4e-3f)
                    assertTrue("frame-global $w x $h left=$left top=$top mean=$mean",mean<=2e-4)
                    rows.put(JSONObject().put("width",w).put("height",h).put("left",left).put("top",top).put("channels",JSONArray(channels.toList())).put("max_abs_half",maximum).put("mean_abs_half",mean))
                    if(left!=0 || channels[0]!=0) continue
                    for(profile in EncodedVideoProfile.entries) for(out in listOf(w to h,(w/2+1) and -2 to ((h/2+1) and -2),(w*2/3) and -2 to ((h*2/3) and -2))) {
                        val ow=out.first;val oh=out.second;val strides=intArrayOf(ow*2,ow*2,ow,oh)
                        val (dims,params)=VfVideoGrade.packGrade(intArrayOf(ow,oh),floatArrayOf(1.1f,1f,.9f,1f),ccm,.5f,false,profile=profile)
                        checkNotNull(VfEglImport.createLinearP010(ow,oh)).use { fused ->
                            val rgb=global.renderRawBuffer(raw,sw,sh,row,2,0,left,top,w,h,channels,black,4095f,wb,null,null)
                            val fd=global.renderP010RawBuffer(raw,sw,sh,row,2,left,top,w,h,channels,black,4095f,wb,null,null,fused,params,strides)
                            assertTrue("fused fd",fd>=0)
                            try { assertTrue("fused completion",VfEglImport.awaitSyncFd(fd,10_000)) } finally { VfEglImport.closeSyncFd(fd) }
                            // Reference: the fused path's exact integer sampling, srcOf(o) =
                            // ((2o+1)*src)/(2*out), graded on the CPU from A's own RGB. (vf_gradeyuv
                            // computes (o+0.5)*src/out with GPU float division, which is not
                            // correctly rounded: at exact 2:1 it can read the neighbouring pixel.)
                            val d=checkNotNull(VfEglImport.dumpFp16(rgb,w,h));val ev=Math.pow(2.0,.5).toFloat();val gn=floatArrayOf(1.1f,1f,.9f)
                            val site11=(cropPhase(channels,left,top) shr 6) and 3
                            val shiftX=if(2*ow==w && 2*oh==h && site11==1) -1 else 0
                            fun src(o:Int,n:Int,sN:Int)=((2*o+1)*sN)/(2*n)
                            fun yuvAt(ox:Int,oy:Int):FloatArray {
                                val q=(src(oy,oh,h)*w+src(ox,ow,w)+shiftX)*4
                                val dev=FloatArray(3){r->(0..2).map{i->ccm[r*3+i]*gn[i]*ev*d[q+i]}.sum()}
                                val e=when(profile) {
                                    EncodedVideoProfile.BT709 -> VfVideoGrade.encodePixel709(dev)
                                    EncodedVideoProfile.SLOG3 -> VfVideoGrade.encodePixelSlog3(dev)
                                    EncodedVideoProfile.HLG -> VfVideoGrade.encodePixelHlg(dev)
                                    EncodedVideoProfile.ACES -> VfVideoGrade.encodePixelAces(dev)
                                }
                                val kr=if(profile==EncodedVideoProfile.HLG).2627f else .2126f
                                val kb=if(profile==EncodedVideoProfile.HLG).0593f else .0722f
                                val l=kr*e[0]+(1-kr-kb)*e[1]+kb*e[2]
                                return floatArrayOf(l,(e[2]-l)/(2*(1-kb))+.5f,(e[0]-l)/(2*(1-kr))+.5f)
                            }
                            var worst=0;val bad=ArrayList<String>();var nbad=0
                            for(y in 0 until oh-1 step maxOf(2,(oh/24) and -2)) for(x in 0 until ow-1 step maxOf(2,(ow/24) and -2)) {
                                val f=checkNotNull(VfEglImport.sampleP010Center(fused,ow*2,ow*2,ow,oh,x,y))
                                val cell=listOf(yuvAt(x,y),yuvAt(x+1,y),yuvAt(x,y+1),yuvAt(x+1,y+1))
                                val want=intArrayOf((64+876*cell[0][0].coerceIn(0f,1f)).roundToInt(),
                                    (64+896*cell.map{it[1]}.average().toFloat().coerceIn(0f,1f)).roundToInt(),
                                    (64+896*cell.map{it[2]}.average().toFloat().coerceIn(0f,1f)).roundToInt())
                                repeat(3) { c ->
                                    assertEquals("fused P010 low bits",0,f[c] and 63)
                                    val err=abs((f[c] shr 6)-want[c]);worst=maxOf(worst,err)
                                    if(err>2){nbad++;if(bad.size<6)bad.add("($x,$y)c$c ${f[c] shr 6}/${want[c]}")}
                                }
                            }
                            assertTrue("fused $profile $w x $h -> $ow x $oh top=$top bad=$nbad $bad",nbad==0)
                            p010Rows.put(JSONObject().put("profile",profile.name).put("source",JSONArray(listOf(w,h))).put("output",JSONArray(listOf(ow,oh))).put("top",top).put("max_code_error",worst))
                        }
                    }
                }
            }
        }
        File(File(context.getExternalFilesDir(null),"photo-rcd-correctness").apply { mkdirs() },"frame-global.json")
            .writeText(JSONObject().put("passed",true).put("budget",JSONObject().put("max_abs_half",4e-3).put("mean_abs_half",2e-4).put("fused_p010_codes",2))
                .put("cases",rows).put("fused_p010_cases",p010Rows)
                .put("scope","frame-global RCD vs exact port, 48 synthetic crops; fused grade vs CPU grade of the same RGB at exact sample positions, 4 profiles x 3 scales x 2 crops; not perceptual acceptance").toString(2))
    }

    @Test fun chromaUsesBothRowsOfEachOutputCell() {
        check(VfEglImport.available)
        fun asset(path:String)=context.assets.open(path).use { it.readBytes() }
        check(VfVulkan.initNative(asset("shaders/vf/vf_superpixel.spv"))==VfVulkan.OK)
        check(VfVideoGrade.recordCubeUploadNative(null,0,null)==VfVulkan.OK)
        check(VfVideoGrade.initGradeNative(asset("shaders/vf/vf_videograde.spv"))==VfVulkan.OK)
        check(VfVideoGrade.initGradeYuvNative(asset("shaders/vf/vf_gradeyuv.spv"))==VfVulkan.OK)
        check(VfVideoGrade.gradeLutUploadNative(VfVideoGrade.bakeLut(VfVideoGrade.DEFAULT_CONTRAST))==VfVulkan.OK)
        val rows=JSONArray();var distinguishesTopOnly=false
        val sw=34;val sh=26
        HardwareBuffer.create(sw,sh,HardwareBuffer.RGBA_FP16,1,
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_CPU_READ_OFTEN or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).use { rgb ->
            for(phase in 0..1) {
                check(VfEglImport.fillHalfChromaRows(rgb,sw,sh,phase)>0)
                for(profile in EncodedVideoProfile.entries) for(fullRgb in 0..1) for(size in listOf(sw to sh,22 to 18)) {
                    val ow=size.first;val oh=size.second
                    checkNotNull(VfEglImport.createLinearP010(ow,oh)).use { output ->
                        val (dims,params)=VfVideoGrade.packGrade(intArrayOf(ow,oh),floatArrayOf(1f,1f,1f,1f),VfVideoGrade.IDENTITY_CCM,0f,false,profile=profile)
                        check(VfVideoGrade.gradeYuvBlockingNative(rgb,output,dims,intArrayOf(sw,sh),params,intArrayOf(ow*2,ow*2,ow,oh),0,fullRgb)==VfVulkan.OK)
                        fun yuv(x:Int,y:Int):FloatArray {
                            val sourceY=((y+.5f)*sh/oh).toInt().coerceIn(0,sh-1)
                            val blue=((sourceY+phase) and 1)!=0
                            val r=if(blue).125f else .75f;val b=if(blue).75f else .125f
                            val developed=if(fullRgb==1)floatArrayOf(r,.25f,b)else floatArrayOf(r,.5f*(.25f+b),1f)
                            val encoded=when(profile) {
                                EncodedVideoProfile.BT709 -> VfVideoGrade.encodePixel709(developed)
                                EncodedVideoProfile.SLOG3 -> VfVideoGrade.encodePixelSlog3(developed)
                                EncodedVideoProfile.HLG -> VfVideoGrade.encodePixelHlg(developed)
                                EncodedVideoProfile.ACES -> VfVideoGrade.encodePixelAces(developed)
                            }
                            val kr=if(profile==EncodedVideoProfile.HLG).2627f else .2126f
                            val kb=if(profile==EncodedVideoProfile.HLG).0593f else .0722f
                            val luma=kr*encoded[0]+(1-kr-kb)*encoded[1]+kb*encoded[2]
                            return floatArrayOf(luma,(encoded[2]-luma)/(2*(1-kb))+.5f,(encoded[0]-luma)/(2*(1-kr))+.5f)
                        }
                        var maximum=0
                        repeat(4) { gy -> repeat(4) { gx ->
                            val x=(((ow-2)*gx/3) and -2);val y=(((oh-2)*gy/3) and -2)
                            val quad=arrayOf(yuv(x,y),yuv(x+1,y),yuv(x,y+1),yuv(x+1,y+1))
                            val u=((quad[0][1]+quad[1][1])+(quad[2][1]+quad[3][1]))*.25f
                            val v=((quad[0][2]+quad[1][2])+(quad[2][2]+quad[3][2]))*.25f
                            if(abs(u-(quad[0][1]+quad[1][1])*.5f)*896>8) distinguishesTopOnly=true
                            repeat(4) { q ->
                                val actual=checkNotNull(VfEglImport.sampleP010Center(output,ow*2,ow*2,ow,oh,x+(q and 1),y+(q shr 1)))
                                val expected=intArrayOf((64+876*quad[q][0].coerceIn(0f,1f)).roundToInt(),
                                    (64+896*u.coerceIn(0f,1f)).roundToInt(),(64+896*v.coerceIn(0f,1f)).roundToInt())
                                repeat(3) { c ->
                                    assertEquals("P010 low bits",0,actual[c] and 63)
                                    val error=abs((actual[c] shr 6)-expected[c]);maximum=maxOf(maximum,error)
                                    assertTrue("cell $profile fullRgb=$fullRgb phase=$phase $ow x $oh ($x,$y) q=$q c=$c error=$error",error<=2)
                                }
                            }
                        } }
                        rows.put(JSONObject().put("profile",profile.name).put("full_rgb",fullRgb).put("row_phase",phase).put("output",JSONArray(listOf(ow,oh))).put("max_code_error",maximum))
                    }
                }
            }
        }
        assertTrue("fixture must reject top-row-only chroma",distinguishesTopOnly)
        File(File(context.getExternalFilesDir(null),"photo-rcd-correctness").apply { mkdirs() },"p010-cell-chroma.json")
            .writeText(JSONObject().put("passed",true).put("cases",rows).put("scope","32 alternating-row cases; both luma rows and four-pixel chroma; profiles, scaling and fullRGB/superpixel").toString(2))
    }

    private fun adapterCheck(cube: CubeLut?) {
        check(VfEglImport.available) { "Required native readback unavailable" }
        fun asset(path:String)=context.assets.open(path).use { it.readBytes() }
        check(VfVulkan.initNative(asset("shaders/vf/vf_superpixel.spv"))==VfVulkan.OK)
        check(VfVideoGrade.recordCubeUploadNative(cube?.rgbaHalfBits(),cube?.size?:0,cube?.gpuParameters(1f))==VfVulkan.OK)
        check(VfVideoGrade.initGradeNative(asset("shaders/vf/vf_videograde.spv"))==VfVulkan.OK)
        check(VfVideoGrade.initGradeYuvNative(asset(VfVideoGrade.recordAsset("shaders/vf/vf_gradeyuv.spv",cube!=null)))==VfVulkan.OK)
        check(VfVideoGrade.gradeLutUploadNative(VfVideoGrade.bakeLut(VfVideoGrade.DEFAULT_CONTRAST))==VfVulkan.OK)
        val sw=44;val sh=42;val rowBytes=sw*4+12;val w=39;val h=37
        val raw=ByteBuffer.allocateDirect(rowBytes*sh).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sh) { y -> repeat(sw) { x -> raw.putShort(y*rowBytes+x*4,1024.toShort()) } }
        PhotoRcdRenderer.open(context,true,cacheResponses=InstrumentationRegistry.getArguments().getString("responseCache")=="true",images=InstrumentationRegistry.getArguments().getString("images")=="true",recordingCandidate=InstrumentationRegistry.getArguments().getString("recordingCandidate")=="true",baked=InstrumentationRegistry.getArguments().getString("baked")=="true",packed=InstrumentationRegistry.getArguments().getString("packed")=="true",directRaw=InstrumentationRegistry.getArguments().getString("directRaw")=="true",requirementsInput=InstrumentationRegistry.getArguments().getString("requirementsInput")=="true",normalizeOnce=InstrumentationRegistry.getArguments().getString("normalizeOnce")=="true",sharedFinal=InstrumentationRegistry.getArguments().getString("sharedFinal")=="true",tileLocal=InstrumentationRegistry.getArguments().getString("tileLocal")=="true",gpuImage=InstrumentationRegistry.getArguments().getString("gpuImage")=="true").use { renderer ->
            for(profile in EncodedVideoProfile.entries) {
                val rgb=renderer.renderRawBuffer(raw,sw,sh,rowBytes,4,0,1,1,w,h,intArrayOf(0,1,2,3),
                    FloatArray(4),4096f,floatArrayOf(1f,1f,1f),null,null)
                val pixels=checkNotNull(VfEglImport.dumpFp16(rgb,w,h)) { "required FP16 readback failed" }
                for(i in pixels.indices) assertEquals("flat adapter output",if(i%4==3)1f else .25f,pixels[i],.00025f)
                val ow=32;val oh=24
                val output=checkNotNull(VfEglImport.createLinearP010(ow,oh))
                try {
                    val (dims,params)=VfVideoGrade.packGrade(intArrayOf(ow,oh),floatArrayOf(1f,1f,1f,1f),VfVideoGrade.IDENTITY_CCM,0f,false,profile=profile)
                    val fd=VfVideoGrade.gradeYuvSubmitNative(rgb,output,dims,intArrayOf(w,h),params,intArrayOf(ow*2,ow*2,ow,oh),0,1)
                    check(fd>=0) { "Required separate grade failed $fd" }
                    val delivery=renderer.gradeSubmitted(0,fd)
                    try { check(VfEglImport.awaitSyncFd(delivery,30_000)) { "delivery fence incomplete" } }
                    finally { VfEglImport.closeSyncFd(delivery) }
                    val grid=checkNotNull(VfEglImport.sampleLinearP010Grid(output,ow,oh,4,4))
                    assertTrue("empty P010 readback",grid.isNotEmpty())
                    for(word in grid) assertEquals("P010 low bits",0,word and 63)
                    val developed=floatArrayOf(.25f,.25f,.25f)
                    var encoded=when(profile) {
                        EncodedVideoProfile.BT709 -> VfVideoGrade.encodePixel709(developed)
                        EncodedVideoProfile.SLOG3 -> VfVideoGrade.encodePixelSlog3(developed)
                        EncodedVideoProfile.HLG -> VfVideoGrade.encodePixelHlg(developed)
                        EncodedVideoProfile.ACES -> VfVideoGrade.encodePixelAces(developed)
                    }
                    if(cube!=null && profile==EncodedVideoProfile.SLOG3) encoded=cube.apply(encoded)
                    val kr=if(profile==EncodedVideoProfile.HLG).2627f else .2126f
                    val kb=if(profile==EncodedVideoProfile.HLG).0593f else .0722f
                    val luma=kr*encoded[0]+(1-kr-kb)*encoded[1]+kb*encoded[2]
                    val expected=intArrayOf((64+876*luma).roundToInt(),
                        (64+896*((encoded[2]-luma)/(2*(1-kb))+.5f)).roundToInt(),
                        (64+896*((encoded[0]-luma)/(2*(1-kr))+.5f)).roundToInt())
                    for(i in grid.indices) assertTrue("$profile packed sample $i: ${grid[i] shr 6} vs ${expected[i%3]}",abs((grid[i] shr 6)-expected[i%3])<=2)
                    Log.i("PhotoRcdCorrectness","profile=$profile lut=${cube!=null} P010=${grid.joinToString()}")
                } finally { output.close() }
            }
            // Previous grade's original fd gates reuse of slot zero. Validate
            // post-reconstruction LSC and pre-LSC sensor-white protection.
            var rgb=renderer.renderRawBuffer(raw,sw,sh,rowBytes,4,0,1,1,w,h,intArrayOf(0,1,2,3),
                FloatArray(4),4096f,floatArrayOf(1f,1f,1f),intArrayOf(1,1,0,0,sw,sh),ShortArray(4) { 0x4000.toShort() })
            var pixels=checkNotNull(VfEglImport.dumpFp16(rgb,w,h))
            for(i in pixels.indices)assertEquals("post-RCD LSC",if(i%4==3)1f else .5f,pixels[i],.0005f)
            repeat(sh) { y -> repeat(sw) { x -> raw.putShort(y*rowBytes+x*4,4096.toShort()) } }
            rgb=renderer.renderRawBuffer(raw,sw,sh,rowBytes,4,0,1,1,w,h,intArrayOf(0,1,2,3),
                FloatArray(4),4096f,floatArrayOf(2f,1f,4f),null,null)
            pixels=checkNotNull(VfEglImport.dumpFp16(rgb,w,h))
            val neutral=floatArrayOf(.5f,1f,.25f,1f)
            for(i in pixels.indices) assertEquals("sensor-white before WB",neutral[i%4],pixels[i],.0005f)
            try {
                renderer.renderRawBuffer(raw,sw,sh,1,4,0,1,1,w,h,intArrayOf(0,1,2,3),
                    FloatArray(4),4096f,floatArrayOf(1f,1f,1f),null,null)
                fail("invalid RAW stride accepted")
            } catch (_: IllegalStateException) { }
        }
        VfVulkan.resetNative()
    }
}
