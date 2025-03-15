// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <android/log.h>

#include <jni.h>

#include <string>
#include <vector>

#include <platform.h>
#include <benchmark.h>

#include "yolov11.h"

#include "ndkcamera.h"

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#if __ARM_NEON
#include <arm_neon.h>
#endif // __ARM_NEON
#include <cstdio>
#include <cstring>
#include <ctime>
#include <sys/stat.h>
#include <sys/types.h>
#include <string>

// 定义三个文件夹路径（建议使用实际可写路径，下面仅为示例）
static std::string g_rawFolder         = "/storage/emulated/0/Android/data/com.hzcu.potholeDetection/files/RawImages/";
static std::string g_annotatedFolder   = "/storage/emulated/0/Android/data/com.hzcu.potholeDetection/files/AnnotatedImages/";
static std::string g_annotationFolder  = "/storage/emulated/0/Android/data/com.hzcu.potholeDetection/files/Annotations/";

// 控制采集图像的开关（由 Java 调用 setCollectionState 设置）
volatile bool g_isCollectingImages = false;
bool ensureDirExists(const std::string& path)
{
    struct stat st;
    if (stat(path.c_str(), &st) != 0)
    {
        // 不存在则创建
        if (mkdir(path.c_str(), 0777) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Failed to create directory: %s", path.c_str());
            return false;
        } else {
            __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Directory created: %s", path.c_str());
        }
    }
    else if (!S_ISDIR(st.st_mode))
    {
        __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Path exists but is not a directory: %s", path.c_str());
        return false;
    }
    else {
        __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Directory exists: %s", path.c_str());
    }
    return true;
}

//bool ensureDirExists(const std::string& path)
//{
//    struct stat st;
//    if (stat(path.c_str(), &st) != 0)
//    {
//        // 不存在则创建
//        if (mkdir(path.c_str(), 0777) != 0)
//            return false;
//    }
//    else if (!S_ISDIR(st.st_mode))
//    {
//        return false;
//    }
//    return true;
//}
bool saveMatAsBMP(const std::string& filename, const cv::Mat& mat)
{
    if (mat.empty() || mat.channels() != 3 || mat.depth() != CV_8U)
        return false;
    int width = mat.cols;
    int height = mat.rows;
    int rowSize = (width * 3 + 3) & (~3); // 行宽以4字节对齐
    int dataSize = rowSize * height;
    int fileSize = 54 + dataSize; // 54字节头

    FILE* f = fopen(filename.c_str(), "wb");
    if (!f)
        return false;

    unsigned char bmpFileHeader[14] = {
            'B','M',
            0,0,0,0, // 文件大小
            0,0,
            0,0,
            54,0,0,0
    };
    unsigned char bmpInfoHeader[40] = {
            40,0,0,0,
            0,0,0,0, // 宽度
            0,0,0,0, // 高度
            1,0,
            24,0,
            0,0,0,0,
            0,0,0,0,
            0,0,0,0,
            0,0,0,0
    };

    // 填充文件大小
    bmpFileHeader[2] = (unsigned char)(fileSize      );
    bmpFileHeader[3] = (unsigned char)(fileSize >> 8 );
    bmpFileHeader[4] = (unsigned char)(fileSize >> 16);
    bmpFileHeader[5] = (unsigned char)(fileSize >> 24);

    // 填充宽度、高度（注意 BMP 采用小端存储）
    bmpInfoHeader[4] = (unsigned char)(width      );
    bmpInfoHeader[5] = (unsigned char)(width >> 8 );
    bmpInfoHeader[6] = (unsigned char)(width >> 16);
    bmpInfoHeader[7] = (unsigned char)(width >> 24);
    bmpInfoHeader[8] = (unsigned char)(height      );
    bmpInfoHeader[9] = (unsigned char)(height >> 8 );
    bmpInfoHeader[10] = (unsigned char)(height >> 16);
    bmpInfoHeader[11] = (unsigned char)(height >> 24);

    fwrite(bmpFileHeader, 1, 14, f);
    fwrite(bmpInfoHeader, 1, 40, f);

    // BMP 像素数据：从下到上写入
    unsigned char* rowData = new unsigned char[rowSize];
    for (int i = height - 1; i >= 0; i--)
    {
        const unsigned char* rowPtr = mat.ptr<unsigned char>(i);
        memcpy(rowData, rowPtr, width * 3);
        // 填充对齐字节
        for (int j = width * 3; j < rowSize; j++)
            rowData[j] = 0;
        fwrite(rowData, 1, rowSize, f);
    }
    delete[] rowData;
    fclose(f);
    return true;
}

static int draw_unsupported(cv::Mat& rgb)
{
    const char text[] = "unsupported";

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 1.0, 1, &baseLine);

    int y = (rgb.rows - label_size.height) / 2;
    int x = (rgb.cols - label_size.width) / 2;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 0, 0));

    return 0;
}

static int draw_fps(cv::Mat& rgb)
{
    // resolve moving average
    float avg_fps = 0.f;
    {
        static double t0 = 0.f;
        static float fps_history[10] = {0.f};

        double t1 = ncnn::get_current_time();
        if (t0 == 0.f)
        {
            t0 = t1;
            return 0;
        }

        float fps = 1000.f / (t1 - t0);
        t0 = t1;

        for (int i = 9; i >= 1; i--)
        {
            fps_history[i] = fps_history[i - 1];
        }
        fps_history[0] = fps;

        if (fps_history[9] == 0.f)
        {
            return 0;
        }

        for (int i = 0; i < 10; i++)
        {
            avg_fps += fps_history[i];
        }
        avg_fps /= 10.f;
    }

    char text[32];
    sprintf(text, "FPS=%.2f", avg_fps);

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 0.5, 1, &baseLine);

    int y = 0;
    int x = rgb.cols - label_size.width;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 0.5, cv::Scalar(0, 0, 0));

    return 0;
}
//static Inference_det* g_yolo = 0;
static Inference* g_yolo = 0;
static ncnn::Mutex lock;

class MyNdkCamera : public NdkCameraWindow
{
public:
    virtual void on_image_render(cv::Mat& rgb) const;
};


void MyNdkCamera::on_image_render(cv::Mat& rgb) const
{
    // 先保存原始图像（检测前）
    cv::Mat rawCopy = rgb.clone();
    if (g_isCollectingImages)
    {
        if (!ensureDirExists(g_rawFolder)) {
            __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Raw folder check failed: %s", g_rawFolder.c_str());
        }

        using namespace std::chrono;
        auto now = system_clock::now();
        std::time_t now_time = system_clock::to_time_t(now);
        std::tm* tm_info = std::localtime(&now_time);
        char timestamp[80];
        std::strftime(timestamp, sizeof(timestamp), "%Y%m%d_%H%M%S", tm_info);
        auto ms = duration_cast<milliseconds>(now.time_since_epoch()) % 1000;
        sprintf(timestamp + std::strlen(timestamp), "_%03d", (int)ms.count());
        std::string rawFilename = g_rawFolder + timestamp + ".bmp";
//        char timestamp[64];
//        std::time_t now = std::time(nullptr);
//        std::tm* tm_info = std::localtime(&now);
//        std::strftime(timestamp, sizeof(timestamp), "%Y%m%d_%H%M%S", tm_info);
//        std::string rawFilename = g_rawFolder + timestamp + ".bmp";
        if (!saveMatAsBMP(rawFilename, rawCopy))
        {
            __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Failed to save raw image: %s", rawFilename.c_str());
        }
        else {
            __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Saved raw image: %s", rawFilename.c_str());
        }
    }

    // 运行目标检测并绘制检测框
    std::vector<Object> objects;
    {
        ncnn::MutexLockGuard g(lock);
        if (g_yolo)
        {
            objects = g_yolo->runInference(rgb);
            g_yolo->draw(rgb, objects);
        }
        else
        {
            draw_unsupported(rgb);
        }
    }
    draw_fps(rgb);

    // 如果检测到目标，则保存带检测框的图像和生成标注文件
    if (g_isCollectingImages && !objects.empty())
    {
        if (!ensureDirExists(g_annotatedFolder)) {
            __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Annotated folder check failed: %s", g_annotatedFolder.c_str());
        }
        if (!ensureDirExists(g_annotationFolder)) {
            __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Annotation folder check failed: %s", g_annotationFolder.c_str());
        }
//        char timestamp[64];
//        std::time_t now = std::time(nullptr);
//        std::tm* tm_info = std::localtime(&now);
//        std::strftime(timestamp, sizeof(timestamp), "%Y%m%d_%H%M%S", tm_info);
        using namespace std::chrono;
        auto now = system_clock::now();
        std::time_t now_time = system_clock::to_time_t(now);
        std::tm* tm_info = std::localtime(&now_time);
        char timestamp[80];
        std::strftime(timestamp, sizeof(timestamp), "%Y%m%d_%H%M%S", tm_info);
        auto ms = duration_cast<milliseconds>(now.time_since_epoch()) % 1000;
        sprintf(timestamp + std::strlen(timestamp), "_%03d", (int)ms.count());
        std::string annotatedFilename = g_annotatedFolder + timestamp + ".bmp";
        if (!saveMatAsBMP(annotatedFilename, rgb))
        {
            __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Failed to save annotated image: %s", annotatedFilename.c_str());
        }
        else {
            __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Saved annotated image: %s", annotatedFilename.c_str());
        }

        // 生成标注文件（格式：类别编号 center_x center_y norm_width norm_height）
        int imgWidth = rawCopy.cols;
        int imgHeight = rawCopy.rows;
        std::string annotationFilename = g_annotationFolder + timestamp + ".txt";
        FILE* f = fopen(annotationFilename.c_str(), "w");
        if (!f) {
            __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Failed to open annotation file for writing: %s", annotationFilename.c_str());
        } else {
            for (size_t i = 0; i < objects.size(); i++)
            {
                const Object& obj = objects[i];
                float centerX = (obj.rect.x + obj.rect.width / 2.0f) / imgWidth;
                float centerY = (obj.rect.y + obj.rect.height / 2.0f) / imgHeight;
                float normWidth = obj.rect.width / imgWidth;
                float normHeight = obj.rect.height / imgHeight;
                fprintf(f, "%d %.8f %.8f %.8f %.8f\n", obj.label, centerX, centerY, normWidth, normHeight);
            }
            fclose(f);
            __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "Saved annotation file: %s", annotationFilename.c_str());
        }
    }
}


//
//void MyNdkCamera::on_image_render(cv::Mat& rgb) const
//{
//    // 在进入检测前先保存一份原始图像
//    cv::Mat rawCopy = rgb.clone();
//    if (g_isCollectingImages)
//    {
//        ensureDirExists(g_rawFolder);
//        char timestamp[64];
//        std::time_t now = std::time(nullptr);
//        std::tm* tm_info = std::localtime(&now);
//        std::strftime(timestamp, sizeof(timestamp), "%Y%m%d_%H%M%S", tm_info);
//        std::string rawFilename = g_rawFolder + timestamp + ".bmp";
//        saveMatAsBMP(rawFilename, rawCopy);
//    }
//
//    // 运行检测并绘制检测框
//    std::vector<Object> objects;
//    {
//        ncnn::MutexLockGuard g(lock);
//        if (g_yolo)
//        {
//            objects = g_yolo->runInference(rgb);
//            g_yolo->draw(rgb, objects);
//        }
//        else
//        {
//            draw_unsupported(rgb);
//        }
//    }
//    draw_fps(rgb);
//
//    // 如果检测到目标，则保存带框图像及生成标注文件
//    if (g_isCollectingImages && !objects.empty())
//    {
//        ensureDirExists(g_annotatedFolder);
//        ensureDirExists(g_annotationFolder);
//        char timestamp[64];
//        std::time_t now = std::time(nullptr);
//        std::tm* tm_info = std::localtime(&now);
//        std::strftime(timestamp, sizeof(timestamp), "%Y%m%d_%H%M%S", tm_info);
//        std::string annotatedFilename = g_annotatedFolder + timestamp + ".bmp";
//        saveMatAsBMP(annotatedFilename, rgb);
//
//        // 保存标注文件（按照传统数据集标注格式：类别 id 和归一化的中心点、宽度、高度）
//        // 这里采用 rawCopy 的尺寸作为原始图像尺寸
//        int imgWidth = rawCopy.cols;
//        int imgHeight = rawCopy.rows;
//        std::string annotationFilename = g_annotationFolder + timestamp + ".txt";
//        FILE* f = fopen(annotationFilename.c_str(), "w");
//        if (f)
//        {
//            for (size_t i = 0; i < objects.size(); i++)
//            {
//                const Object& obj = objects[i];
//                float centerX = (obj.rect.x + obj.rect.width / 2.0f) / imgWidth;
//                float centerY = (obj.rect.y + obj.rect.height / 2.0f) / imgHeight;
//                float normWidth = obj.rect.width / imgWidth;
//                float normHeight = obj.rect.height / imgHeight;
//                fprintf(f, "%d %.8f %.8f %.8f %.8f\n", obj.label, centerX, centerY, normWidth, normHeight);
//            }
//            fclose(f);
//        }
//    }
//}


//
//class MyNdkCamera : public NdkCameraWindow
//{
//public:
//    virtual void on_image_render(cv::Mat& rgb) const;
//};
//
//void MyNdkCamera::on_image_render(cv::Mat& rgb) const
//{
//    // nanodet
//    {
//        ncnn::MutexLockGuard g(lock);
//        if (g_yolo)
//        {
//            std::vector<Object> objects;
//            objects = g_yolo->runInference(rgb);
//
//            g_yolo->draw(rgb, objects);
//        }
//        /*if (g_yolo)
//        {
//            std::vector<Detection> objects;
//            objects = g_yolo->runInference(rgb);
//
//            g_yolo->draw(rgb, objects);
//        }*/
//        else
//        {
//            draw_unsupported(rgb);
//        }
//    }
//
//    draw_fps(rgb);
//}

static MyNdkCamera* g_camera = 0;

extern "C" {
JNIEXPORT void JNICALL Java_com_hzcu_potholeDetection_Yolov11Ncnn_setCollectionState(JNIEnv* env, jobject thiz, jboolean state)
{
    g_isCollectingImages = (state == JNI_TRUE);
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI_OnLoad");

    g_camera = new MyNdkCamera;

    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI_OnUnload");

    {
        ncnn::MutexLockGuard g(lock);

        delete g_yolo;
        g_yolo = 0;
    }

    delete g_camera;
    g_camera = 0;
}

// public native boolean loadModel(AssetManager mgr, int modelid, int cpugpu);
JNIEXPORT jboolean JNICALL Java_com_hzcu_potholeDetection_Yolov11Ncnn_loadModel(JNIEnv* env, jobject thiz, jobject assetManager, jint modelid, jint cpugpu)
{
    if (modelid < 0 || modelid > 6 || cpugpu < 0 || cpugpu > 1)
    {
        return JNI_FALSE;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "loadModel %p", mgr);

    const char* modeltypes[] =
    {
        "yolov11",
//        "s",
    };

    const int target_sizes[] =
    {
        320,
//        640,
    };

    const float mean_vals[][3] =
    {
        {0.0f, 0.0f, 0.0f},
//        {0.0f, 0.0f, 0.0f},
    };

    const float norm_vals[][3] =
    {
        { 1 / 255.f, 1 / 255.f, 1 / 255.f },
//        { 1 / 255.f, 1 / 255.f, 1 / 255.f },
    };

    const char* modeltype = modeltypes[(int)modelid];
    int target_size = target_sizes[(int)modelid];
    bool use_gpu = (int)cpugpu == 1;

    // reload
    {
        ncnn::MutexLockGuard g(lock);

        if (use_gpu && ncnn::get_gpu_count() == 0)
        {
            // no gpu
            delete g_yolo;
            g_yolo = 0;
        }
        else
        {
            if (!g_yolo)
                //g_yolo = new Inference_det;
                g_yolo = new Inference;
                g_yolo->loadNcnnNetwork(mgr, modeltype, target_size, mean_vals[(int)modelid], norm_vals[(int)modelid], use_gpu);
        }
    }

    return JNI_TRUE;
}

// public native boolean openCamera(int facing);
JNIEXPORT jboolean JNICALL Java_com_hzcu_potholeDetection_Yolov11Ncnn_openCamera(JNIEnv* env, jobject thiz, jint facing)
{
    if (facing < 0 || facing > 1)
        return JNI_FALSE;

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "openCamera %d", facing);

    g_camera->open((int)facing);

    return JNI_TRUE;
}

// public native boolean closeCamera();
JNIEXPORT jboolean JNICALL Java_com_hzcu_potholeDetection_Yolov11Ncnn_closeCamera(JNIEnv* env, jobject thiz)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "closeCamera");

    g_camera->close();

    return JNI_TRUE;
}

// public native boolean setOutputWindow(Surface surface);
JNIEXPORT jboolean JNICALL Java_com_hzcu_potholeDetection_Yolov11Ncnn_setOutputWindow(JNIEnv* env, jobject thiz, jobject surface)
{
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "setOutputWindow %p", win);

    g_camera->set_window(win);

    return JNI_TRUE;
}

}
