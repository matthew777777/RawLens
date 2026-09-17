// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.media.Image

/**
 * One owned burst frame with everything frozen before asynchronous work:
 * the [Image], its [RawFrameMetadata], exposure/ISO/timing primitives, and
 * the xyz gyro window covering exposure plus rolling-shutter readout.
 * No live `CaptureResult` lookup is required (or performed) after creation.
 */
internal data class BurstFrameSnapshot(
    val image: Image,
    val metadata: RawFrameMetadata,
    val timestampNanos: Long,
    val exposureNanos: Long,
    val sensitivityIso: Int,
    val rollingShutterSkewNanos: Long,
    val gyroSamples: List<GyroSample>
)

/**
 * Builds owned same-exposure burst jobs from ZSL candidates without enabling
 * reconstruction or changing normal capture output. Ownership rule: every
 * selected `Image` is closed exactly once on success, fallback,
 * cancellation, or exception — including construction failure. Geometry,
 * CFA phase, strides, and exposure completeness are validated before any
 * asynchronous work starts. Rotational homography is a later prompt.
 */
internal object RawBurstJob {
    const val MIN_FRAMES = 2
    const val MAX_FRAMES = 30

    /**
     * @param frames candidate frames in any order; returned snapshots are
     *   sorted by timestamp.
     * @param metadataFor freezes one [RawFrameMetadata] per frame. Called
     *   exactly once per frame, synchronously, before ownership transfers.
     * @param gyroFor xyz window for one frame interval; called once per frame.
     * @param work receives the snapshots; must not touch `CaptureResult`.
     */
    fun create(
        frames: List<BufferedRawFrame>,
        metadataFor: (BufferedRawFrame) -> RawFrameMetadata,
        gyroFor: (timestampNanos: Long, exposureNanos: Long, skewNanos: Long) -> List<GyroSample>,
        work: (List<BurstFrameSnapshot>) -> Unit
    ): OwnedCaptureJob {
        if (frames.size !in MIN_FRAMES..MAX_FRAMES) {
            frames.forEach { runCatching { it.image.close() } }
            throw IllegalArgumentException(
                "burst requires $MIN_FRAMES..$MAX_FRAMES frames, got ${frames.size}"
            )
        }
        val snapshots: List<BurstFrameSnapshot> = try {
            frames.map { frame ->
                val metadata = metadataFor(frame)
                BurstFrameSnapshot(
                    image = frame.image,
                    metadata = metadata,
                    timestampNanos = frame.timestampNanos,
                    exposureNanos = frame.exposureNanos,
                    sensitivityIso = frame.exposureIsoOrThrow(metadata),
                    rollingShutterSkewNanos = frame.rollingShutterSkewNanos,
                    gyroSamples = gyroFor(
                        frame.timestampNanos,
                        frame.exposureNanos,
                        frame.rollingShutterSkewNanos
                    )
                )
            }.also(::validate)
        } catch (failure: Throwable) {
            frames.forEach { runCatching { it.image.close() } }
            throw failure
        }
        val ordered = snapshots.sortedBy { it.timestampNanos }
        val owner = CloseOnceOwner(ordered.map { it.image }, Image::close)
        return OwnedCaptureJob(owner) { work(ordered) }
    }

    private fun BufferedRawFrame.exposureIsoOrThrow(metadata: RawFrameMetadata): Int {
        val exposure = metadata.exposureTimeNanos
        require(exposure != null && exposure > 0L) {
            "frame $timestampNanos has incomplete exposure metadata"
        }
        val iso = metadata.sensitivityIso
        require(iso != null && iso > 0) {
            "frame $timestampNanos has incomplete sensitivity metadata"
        }
        return iso
    }

    private fun validate(snapshots: List<BurstFrameSnapshot>) {
        val timestamps = snapshots.map { it.timestampNanos }
        require(timestamps.toSet().size == timestamps.size) {
            "duplicate frame timestamps $timestamps"
        }
        val reference = snapshots.first().metadata
        require(reference.cfaPattern != null) { "reference frame has unknown CFA pattern" }
        val refGeometry = reference.bufferGeometry as? RawBufferGeometry.Supported
            ?: throw IllegalArgumentException("reference frame has unsupported buffer geometry")
        for (snapshot in snapshots) {
            val metadata = snapshot.metadata
            require(metadata.cameraId == reference.cameraId) {
                "frame ${snapshot.timestampNanos} camera ${metadata.cameraId} != ${reference.cameraId}"
            }
            require(metadata.imageWidth == reference.imageWidth &&
                metadata.imageHeight == reference.imageHeight) {
                "frame ${snapshot.timestampNanos} dimensions " +
                    "${metadata.imageWidth}x${metadata.imageHeight} != " +
                    "${reference.imageWidth}x${reference.imageHeight}"
            }
            require(metadata.imageCrop == reference.imageCrop) {
                "frame ${snapshot.timestampNanos} crop ${metadata.imageCrop} != ${reference.imageCrop}"
            }
            require(metadata.cfaPattern != null && metadata.cfaPattern == reference.cfaPattern) {
                "frame ${snapshot.timestampNanos} CFA ${metadata.cfaPattern} != ${reference.cfaPattern}"
            }
            require(metadata.rawPlaneRowStride == reference.rawPlaneRowStride &&
                metadata.rawPlanePixelStride == reference.rawPlanePixelStride) {
                "frame ${snapshot.timestampNanos} strides " +
                    "${metadata.rawPlaneRowStride}/${metadata.rawPlanePixelStride} != " +
                    "${reference.rawPlaneRowStride}/${reference.rawPlanePixelStride}"
            }
            val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
                ?: throw IllegalArgumentException(
                    "frame ${snapshot.timestampNanos} has unsupported buffer geometry"
                )
            require(geometry.sensorOriginX == refGeometry.sensorOriginX &&
                geometry.sensorOriginY == refGeometry.sensorOriginY) {
                "frame ${snapshot.timestampNanos} sensor origin " +
                    "${geometry.sensorOriginX},${geometry.sensorOriginY} != " +
                    "${refGeometry.sensorOriginX},${refGeometry.sensorOriginY}"
            }
        }
    }
}
