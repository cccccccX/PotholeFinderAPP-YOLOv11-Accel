cmake_minimum_required(VERSION 3.10)
# 强制启用 RTTI 和异常
#set(ANDROID_CPP_FEATURES "rtti exceptions" CACHE STRING "Android C++ features" FORCE)
project(yolov11ncnn)
# 启用 RTTI 和异常
#set(ANDROID_CPP_FEATURES "rtti exceptions")
# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 11)

#string(REPLACE "-fno-rtti" "" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
#set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -frtti -fexceptions")
#message("CMAKE_CXX_FLAGS: ${CMAKE_CXX_FLAGS}")


# 设置 OpenCV 路径
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile-4.5.1-android/sdk/native/jni)
#set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile-4.5.2-android/sdk/native/jni)
find_package(OpenCV REQUIRED core imgproc)

# 设置 ncnn 路径
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20240410-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
#set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20240820-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
#set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20241226-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

# 添加源文件
add_library(yolov11ncnn SHARED yolov11ncnn.cpp ndkcamera.cpp yolov11.cpp)
target_compile_options(yolov11ncnn PRIVATE -frtti)

# 链接库
target_link_libraries(yolov11ncnn ${OpenCV_LIBS} ncnn camera2ndk mediandk)