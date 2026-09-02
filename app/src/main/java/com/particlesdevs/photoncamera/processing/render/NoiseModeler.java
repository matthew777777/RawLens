package com.particlesdevs.photoncamera.processing.render;

import android.util.Pair;

/** RawLens metadata bridge supplying the fields consumed by PhotonCamera DngCreator.setParameters. */
public final class NoiseModeler {
    public Pair<Double, Double>[] baseModel;
    public Pair<Double, Double>[] computeModel;
}
