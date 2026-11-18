package com.rawlens.srpipeline.stages

import kotlin.math.*

/**
 * Mobile polynomial equivalent kernels on the complete quincunx green lattice.
 * This is a separable, per-frame regression, NOT the pooled 6x6 CPU fit.
 * Precomputed 1D normal inverses eliminate per-pixel matrix storage/solves.
 * The 257 x 8-float table is shared by CPU oracle and Vulkan (8 KiB).
 */
object MobilePolynomial {
    const val PHASES = 256
    const val STRIDE = 8
    const val SIGMA = .9
    val table: FloatArray by lazy {
        FloatArray((PHASES+1)*STRIDE).also { lut ->
            for (p in 0..PHASES) {
                val phase=p.toDouble()/PHASES-.5
                val moments=DoubleArray(5)
                val weights=DoubleArray(5)
                for (k in -2..2) {
                    val d=k-phase
                    val t=((abs(d)-1.5)/1.0).coerceIn(0.0,1.0)
                    val w=exp(-d*d/(SIGMA*SIGMA))*(1-t*t*(3-2*t))
                    weights[k+2]=w
                    var v=w
                    for (j in 0..4) { moments[j]+=v;v*=d }
                }
                val a=moments[0];val b=moments[1];val c=moments[2]
                val d=moments[2]+.002*a;val e=moments[3];val f=moments[4]+.04*a
                val aa=d*f-e*e;val ab=c*e-b*f;val ac=b*e-c*d
                val inv=1/(a*aa+b*ab+c*ac)
                for (k in -2..2) {
                    val x=k-phase
                    lut[p*STRIDE+k+2]=(weights[k+2]*(aa+ab*x+ac*x*x)*inv).toFloat()
                }
            }
        }
    }

    fun weights(phase: Float): FloatArray {
        val q=(phase+.5f).coerceIn(0f,1f)*PHASES
        val p=min(q.toInt(),PHASES-1);val t=q-p
        return FloatArray(5) { k -> table[p*STRIDE+k]*(1-t)+table[(p+1)*STRIDE+k]*t }
    }

    fun green(frame: Array<FloatArray>, x: Float, y: Float): Float {
        val u=(x+y-2f)*.5f;val v=(x-y-1f)*.5f
        val cu=floor(u+.5f).toInt();val cv=floor(v+.5f).toInt()
        val wx=weights(u-cu);val wy=weights(v-cv)
        var sum=0f;var low=Float.POSITIVE_INFINITY;var high=Float.NEGATIVE_INFINITY
        for (j in -2..2) for (i in -2..2) {
            val sx=cu+i+cv+j+1;val sy=cu+i-cv-j
            val xp=sx and 1;val yp=sy and 1
            val value=frame[sy.coerceIn(yp,frame.size-2+yp)][sx.coerceIn(xp,frame[0].size-2+xp)]
            sum+=wx[i+2]*wy[j+2]*value;low=min(low,value);high=max(high,value)
        }
        return sum.coerceIn(low,high)
    }

    fun colour(frame: Array<FloatArray>, x: Float, y: Float, parity: Int, alpha: Float, beta: Float, green: Float): Float {
        val qx=(x-.5f-parity)*.5f;val qy=(y-.5f-parity)*.5f
        val bx=floor(qx).toInt();val by=floor(qy).toInt();val tx=qx-bx;val ty=qy-by
        fun at(x:Int,y:Int)=frame[(2*y+parity).coerceIn(parity,frame.size-2+parity)][(2*x+parity).coerceIn(parity,frame[0].size-2+parity)]
        val anchor=(at(bx,by)*(1-tx)+at(bx+1,by)*tx)*(1-ty)+(at(bx,by+1)*(1-tx)+at(bx+1,by+1)*tx)*ty
        val variance=max(.0004f,64f*max(0f,alpha*max(green,0f)+beta))
        val cx=floor(qx+.5f).toInt();val cy=floor(qy+.5f).toInt()
        var num=0f;var den=0f
        for (j in -1..1) for (i in -1..1) {
            val dx=2f*(cx+i-qx);val dy=2f*(cy+j-qy)
            val c=at(cx+i,cy+j);val dc=c-anchor
            fun taper(d:Float):Float { val t=(abs(d)-2f).coerceIn(0f,1f);return 1-t*t*(3-2*t) }
            val w=exp(-.5f*((dx*dx+dy*dy)/(1.35f*1.35f)+dc*dc/variance))*taper(dx)*taper(dy)
            num+=w*c;den+=w
        }
        return if(den>1e-10f) num/den else anchor
    }
}
