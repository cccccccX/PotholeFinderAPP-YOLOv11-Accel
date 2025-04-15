cmake_minimum_required(VERSION 3.4.1)

include_directories(${CMAKE_ANDROID_NDK}/sysroot/usr/include)

# 只编译必要的文件
set(SOURCES
        main.c
        rtGetInf.c
        rt_nonfinite.c
        svmPredict.c
        rtGetNaN.c
)

add_library(main SHARED ${SOURCES})

find_library(log-lib log)
target_link_libraries(main ${log-lib})
