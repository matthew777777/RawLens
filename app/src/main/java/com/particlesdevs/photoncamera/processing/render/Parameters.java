package com.particlesdevs.photoncamera.processing.render;

import android.graphics.Point;
import android.graphics.Rect;

/** RawLens metadata bridge supplying the fields consumed by PhotonCamera DngCreator.setParameters. */
public final class Parameters {
    public int iso;
    public double exposureTime;
    public float focalLength;
    public float aperture;
    public int cameraRotation;
    public byte cfaPattern;
    public Point rawSize;
    public float[] blackLevel;
    public float[] whitePoint;
    public int whiteLevel;
    public int calibrationIlluminant1;
    public int calibrationIlluminant2;
    public float[] calibrationTransform1;
    public float[] calibrationTransform2;
    public float[] ForwardTransform1;
    public float[] ForwardTransform2;
    public float[] ColorMatrix1;
    public float[] ColorMatrix2;
    public float[] gainMap;
    public Point mapSize;
    public Rect sensorPix;
    public NoiseModeler noiseModeler;
}
