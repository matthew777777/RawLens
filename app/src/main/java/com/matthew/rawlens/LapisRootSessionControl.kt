// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Serializes root leases across asynchronous Camera2 lifecycle generations.
 *
 * Switching is the expensive part (a camera HAL restart), so:
 * - a released lease lingers for [RELEASE_GRACE_MS]; a camera reopen in the same mode reuses it
 *   with no root work at all;
 * - a switch between two Redmi modes hands off directly (one HAL restart instead of
 *   restore-restart + apply-restart).
 */
internal object LapisRootSessionControl {
    private const val RELEASE_GRACE_MS = 1500L
    private val executor=Executors.newSingleThreadScheduledExecutor { work -> Thread(work,"lapis-sensor-lease").apply { isDaemon=true } }
    private var owner:String?=null
    private var session:LapisRootSession?=null
    private var pendingRelease:ScheduledFuture<*>?=null

    fun prepare(context:Context,key:String,mode:LapisRootProfile.Mode,complete:(Result<LapisRootSession.Spec?>)->Unit) {
        executor.execute {
            pendingRelease?.cancel(false);pendingRelease=null
            complete(runCatching {
                val current=session
                if(current!=null && current.mode==mode && current.alive) {
                    owner=key
                    return@runCatching current.spec
                }
                val handoff=current!=null && mode!=LapisRootProfile.Mode.NORMAL
                current?.close(handoff);session=null;owner=null
                owner=key
                if(mode!=LapisRootProfile.Mode.NORMAL) {
                    session=try { LapisRootSession.open(context.applicationContext,mode) } catch(failure:Throwable) {
                        if(handoff) runCatching { LapisRootSession.restartCamera() }
                        owner=null;throw failure
                    }
                } else LapisRootSession.awaitMainCamera(context.applicationContext)
                session?.spec
            })
        }
    }

    fun release(key:String) {
        executor.execute {
            if(owner!=key)return@execute
            pendingRelease?.cancel(false)
            pendingRelease=executor.schedule({
                if(owner==key) {
                    pendingRelease=null
                    runCatching { session?.close() }.onSuccess { session=null;owner=null }
                        .onFailure { Log.e("LapisRootSession","Sensor release failed; retaining lease for recovery",it) }
                }
            },RELEASE_GRACE_MS,TimeUnit.MILLISECONDS)
        }
    }
}

/** Transfers the existing lease across the preview-to-RAW-recorder camera handoff. */
internal class LapisRecordingLease(val key:String,val spec:LapisRootSession.Spec):AutoCloseable {
    private val closed=java.util.concurrent.atomic.AtomicBoolean()
    override fun close() { if(closed.compareAndSet(false,true))LapisRootSessionControl.release(key) }
}
