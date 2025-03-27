cmake_minimum_required(VERSION 3.10)

project(MyProject)
message(**********${CMAKE_ANDROID_NDK})
add_subdirectory(jni)
add_subdirectory(cpp)
# 添加 YOLO 模块
# 添加 SVM 模块
