cmake_minimum_required(VERSION 3.10)

project(MyProject)

# 添加 YOLO 模块
add_subdirectory(jni)
# 添加 SVM 模块
add_subdirectory(cpp)
