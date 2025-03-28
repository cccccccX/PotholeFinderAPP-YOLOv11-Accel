package com.hzcu.potholeDetection;

public class SVMPredictor {
    public native double doubleFromJNI(double[] x);

    static {
        System.loadLibrary("main");
    }
}
