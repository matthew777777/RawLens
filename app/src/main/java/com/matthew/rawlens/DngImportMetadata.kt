// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.exifinterface.media.ExifInterface

internal fun DngImportReader.Image.frameMetadata(exif: ExifInterface): RawFrameMetadata {
    val full=IntRectSnapshot(0,0,width,height)
    return RawFrameMetadata(
        cameraId="imported-linear-dng",timestampNanos=0L,frameNumber=0L,
        imageWidth=width,imageHeight=height,imageCrop=full,rawPlaneCount=1,
        rawPlaneRowStride=width*6,rawPlanePixelStride=6,exifOrientation=orientation,
        sensorOrientationDegrees=0,sensitivityIso=exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,0).takeIf { it>0 },
        exposureTimeNanos=exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME,0.0).takeIf { it>0 }?.let { (it*1e9).toLong() },
        frameDurationNanos=null,rollingShutterSkewNanos=null,cfaPattern=pattern ?: BayerPattern.RGGB,
        rawDevelopmentUnsupportedReason=null,blackLevels=ImmutableFloatValues(floatArrayOf(0f,0f,0f,0f)),
        blackLevelSource=BlackLevelSource.STATIC,whiteLevel=1f,whiteLevelSource=WhiteLevelSource.STATIC,
        pixelArraySize=width to height,activeArray=full,preCorrectionActiveArray=full,rawCropRegion=null,
        bufferGeometry=RawBufferGeometry.Supported(0,0,RawCrop(0,0,width,height),"imported-linear-dng"),
        lensShadingAlreadyApplied=true,lensShadingMap=null,hotPixels=emptyList(),wbGains=null,
        neutralColorPoint=tags[50728]?.let { require(it.size==3); ImmutableDoubleValues(it) },
        colorCorrectionTransform=null,colorMatrix1=matrix(50721),colorMatrix2=matrix(50722),
        cameraCalibration1=matrix(50723),cameraCalibration2=matrix(50724),
        forwardMatrix1=matrix(50964),forwardMatrix2=matrix(50965),
        referenceIlluminant1=tags[50778]?.first()?.toInt(),referenceIlluminant2=tags[50779]?.first()?.toInt(),
        noiseProfile=null,sensorPixelMode=null,rawBinningFactorUsed=false,activePhysicalCameraId=null,
        aperture=exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER,0.0).takeIf { it>0 }?.toFloat(),
        focalLengthMm=exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH,0.0).takeIf { it>0 }?.toFloat())
}
