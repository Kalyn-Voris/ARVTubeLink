# 产出 liblive2d_jni.so；ABI 由 AGP 按 splits.abi 传入（arm64-v8a / armeabi-v7a）
# PURISM_CORE_IMPLEMENTATION 只能在一个翻译单元定义（见 live2d_jni.cpp），否则 csm* 符号重复
# PurismCoreBundle.h 是 C99 头，不拆 C/C++ 两个目标
# 依赖仅 NDK 系统库：android / log / GLESv2 / EGL 与 libm

cmake_minimum_required(VERSION 3.22.1)

project(live2d_jni LANGUAGES CXX)

add_library(live2d_jni SHARED live2d_jni.cpp)

target_compile_features(live2d_jni PRIVATE cxx_std_17)

target_compile_options(live2d_jni PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -Wno-attributes
        -Wno-sign-compare
        -Wno-unused-function
)

# PURISM_CORE_STATIC 让 PSMDEF 走 static 分支，不导出上百个 csm* 符号
target_compile_definitions(live2d_jni PRIVATE
        PURISM_CORE_STATIC
)

find_library(log-lib log)
find_library(android-lib android)
find_library(gles-lib GLESv2)
# eglGetCurrentContext 判断 GL 句柄属于哪个 EGL context；重建后的旧句柄 glDelete 会报 GL_INVALID_VALUE
find_library(egl-lib EGL)

target_link_libraries(live2d_jni
        ${android-lib}
        ${log-lib}
        ${gles-lib}
        ${egl-lib}
        m
)
