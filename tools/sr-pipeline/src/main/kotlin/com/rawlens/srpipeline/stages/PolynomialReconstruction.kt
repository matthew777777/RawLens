package com.rawlens.srpipeline.stages

import kotlin.math.*

/**
 * Experimental pooled local regression. Unlike Eq. 1, estimate the value at
 * the OUTPUT position, not the mean at the realised sample centroid.
 * Green fits a quadratic surface to actual Bayer samples across the burst;
 * WB colour differences fit planes, with a green range gate at luma edges.
 * Higher-order coefficients have ridge priors; insufficient/rank-deficient
 * support falls back to the ordinary merge. No display sharpening or noise
 * synthesis. CPU oracle first: this is deliberately not a shipping default.
 */
internal class PolynomialReconstruction(
    private val width: Int, private val height: Int,
    private val scale: Float, private val tile: Int,
    private val alpha: Float, private val beta: Float,
    private val outputOriginX: Float = 0f, private val outputOriginY: Float = 0f,
) {
    private class Fit(val order: Int, pixels: Int) {
        val triangle = order*(order+1)/2
        val data = Array(triangle+order+3) { FloatArray(pixels) }
        init {
            data[triangle+order+1].fill(Float.POSITIVE_INFINITY)
            data[triangle+order+2].fill(Float.NEGATIVE_INFINITY)
        }
        fun add(i: Int, b: FloatArray, w: Float, v: Float) {
            var k=0
            for(y in 0 until order) for(x in 0..y) data[k++][i]+=w*b[y]*b[x]
            for(x in 0 until order) data[triangle+x][i]+=w*b[x]*v
            data[triangle+order][i]+=w*w
            data[triangle+order+1][i]=min(data[triangle+order+1][i],v)
            data[triangle+order+2][i]=max(data[triangle+order+2][i],v)
        }
    }
    private val frameSupport = Array(2) { FloatArray(width*height) }
    private val fits = arrayOf(Fit(3,width*height),Fit(6,width*height),Fit(3,width*height))

    fun add(frame: Array<FloatArray>, flow: Array<Array<FloatArray>>, rob: Array<FloatArray>, lumaWeight: Float,
            inputOriginX: Int = 0, inputOriginY: Int = 0, robOriginX: Int = 0, robOriginY: Int = 0) {
        val h=frame.size; val w=frame[0].size
        fun green(y: Int,x: Int): Float {
            // CFA-preserving boundaries. Frame R/B already contain differences.
            fun at(yy:Int,xx:Int):Float {
                val yp=yy and 1;val xp=xx and 1
                return frame[yy.coerceIn(yp,h-2+yp)][xx.coerceIn(xp,w-2+xp)]
            }
            return if ((x+y) and 1 == 1) at(y,x) else (at(y,x-1)+at(y,x+1)+at(y-1,x)+at(y+1,x))*.25f
        }
        java.util.stream.IntStream.range(0,height).parallel().forEach { oy ->
            val basis=FloatArray(6)
            for(ox in 0 until width) {
                val rx=(ox+.5f)/scale+outputOriginX;val ry=(oy+.5f)/scale+outputOriginY
                val f=flow[min((ry/tile).toInt(),flow.lastIndex)][min((rx/tile).toInt(),flow[0].lastIndex)]
                val mx=rx+f[0]-inputOriginX;val my=ry+f[1]-inputOriginY
                if(mx<0 || my<0 || mx>=w || my>=h) continue
                val r=rob[((ry/2).toInt()-robOriginY).coerceIn(0,rob.lastIndex)][((rx/2).toInt()-robOriginX).coerceIn(0,rob[0].lastIndex)]
                if(r<=0) continue
                val cx=floor(mx).toInt();val cy=floor(my).toInt()
                val guide=green(cy,cx)
                // A colour-only edge need not appear in green. Anchor each
                // difference on its own CFA lattice too, so the range gate
                // does not borrow another hue merely because luma matches.
                fun colourAnchor(parity: Int): Float {
                    val gx=(mx-.5f-parity)/2f;val gy=(my-.5f-parity)/2f
                    val bx=floor(gx).toInt();val by=floor(gy).toInt()
                    val tx=gx-bx;val ty=gy-by
                    fun at(x:Int,y:Int)=frame[(2*y+parity).coerceIn(parity,h-2+parity)][(2*x+parity).coerceIn(parity,w-2+parity)]
                    val top=at(bx,by)*(1-tx)+at(bx+1,by)*tx
                    val bot=at(bx,by+1)*(1-tx)+at(bx+1,by+1)*tx
                    return top*(1-ty)+bot*ty
                }
                val anchorR=colourAnchor(0);val anchorB=colourAnchor(1)
                val rangeVariance=max(.0001f,16f*max(0f,alpha*max(guide,0f)+beta))
                val index=oy*width+ox
                frameSupport[0][index]+=r.coerceIn(0f,1f)
                if(lumaWeight>0f) frameSupport[1][index]+=r.coerceIn(0f,1f)
                for(dy in -3..3) for(dx in -3..3) {
                    val x=cx+dx;val y=cy+dy
                    if(x !in 0 until w || y !in 0 until h) continue
                    val ch=(x and 1)+(y and 1)
                    if(ch==1 && lumaWeight==0f) continue
                    val sx=x+.5f-mx;val sy=y+.5f-my
                    val sigma=if(ch==1) .9f else 1.35f
                    val nx=sx/sigma;val ny=sy/sigma
                    var weight=r*exp(-.5f*(nx*nx+ny*ny))
                    if(ch!=1) {
                        val diff=green(y,x)-guide
                        val dc=frame[y][x]-(if(ch==0) anchorR else anchorB)
                        weight*=exp(-.5f*diff*diff/rangeVariance-.5f*dc*dc/(4f*rangeVariance))
                    } else weight*=lumaWeight
                    if(weight<1e-8f) continue
                    basis[0]=1f;basis[1]=nx;basis[2]=ny
                    basis[3]=nx*nx;basis[4]=nx*ny;basis[5]=ny*ny
                    fits[ch].add(index,basis,weight,frame[y][x])
                }
            }
        }
    }

    fun finish(fallback: Array<Array<FloatArray>>) {
        java.util.stream.IntStream.range(0,height).parallel().forEach { y ->
            val a=Array(6) { DoubleArray(7) }
            for(x in 0 until width) for(ch in 0..2) {
                val fit=fits[ch];val n=fit.order;val d=fit.data;val i=y*width+x
                val frames=frameSupport[if(ch==1) 1 else 0][i]
                if(frames<3f) continue
                val mass=d[0][i].toDouble()
                if(mass<=1e-10) continue
                val neff=mass*mass/max(d[fit.triangle+n][i].toDouble(),1e-20)
                if(neff<2*n) continue
                var k=0
                for(row in 0 until n) for(col in 0..row) {
                    val v=d[k++][i].toDouble()/mass
                    a[row][col]=v;a[col][row]=v
                }
                val mean=d[fit.triangle][i]/mass
                for(row in 0 until n) {
                    a[row][n]=d[fit.triangle+row][i]/mass-mean*a[row][0]
                    // Shrink slopes and curvature toward a constant model.
                    if(row>0) a[row][row]+=if(row<3) .002 else .04
                }
                var valid=true
                for(col in 0 until n) {
                    var pivot=col
                    for(row in col+1 until n) if(abs(a[row][col])>abs(a[pivot][col])) pivot=row
                    if(abs(a[pivot][col])<1e-8) { valid=false;break }
                    val tmp=a[col];a[col]=a[pivot];a[pivot]=tmp
                    val q=a[col][col]
                    for(j in col..n) a[col][j]/=q
                    for(row in 0 until n) if(row!=col) {
                        val r=a[row][col]
                        for(j in col..n) a[row][j]-=r*a[col][j]
                    }
                }
                if(!valid) continue
                val value=(mean+a[0][n]).coerceIn(d[fit.triangle+n+1][i].toDouble(),d[fit.triangle+n+2][i].toDouble())
                if(value.isFinite()) {
                    val confidence=((frames-3f)/3f).coerceIn(0f,1f)
                    fallback[y][x][ch]+=confidence*(value.toFloat()-fallback[y][x][ch])
                }
            }
        }
    }
}
