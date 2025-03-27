package com.hzcu.potholeDetection;

public class SVMPredictor {
    // 这里你可以选择通过 JNI 调用 native 方法，也可以把 SVM 模型逻辑封装在 Java 里（如果可能）
    public native double doubleFromJNI(double[] x);

    static {
        System.loadLibrary("main"); // 假设 native 库名为 main
    }
}
