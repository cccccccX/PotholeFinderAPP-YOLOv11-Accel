#include "rt_nonfinite.h"
#include "svmPredict.h"
#include "svmPredict_terminate.h"
#include <jni.h>

JNIEXPORT jdouble JNICALL Java_com_hzcu_potholeDetection_SVMPredictor_doubleFromJNI(
        JNIEnv* env,
        jclass cls /* this */,
        jdoubleArray x) {
         jdouble* ac =(*env)->GetDoubleArrayElements(env,x,0);
    jdouble y = svmPredict(ac);
    return y;
    //return env->NewStringUTF(hello.c_str());
}
