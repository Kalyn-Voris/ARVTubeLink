# ============================================================================
#  ARVTubeLink - P0 静态渲染原型（Sakura2D Purism Core + 原生 GLES2）
# ============================================================================
#
# 产出：liblive2d_jni.so（ABI 由 AGP 按 splits.abi 传入：arm64-v8a / armeabi-v7a）
#
# Purism Core 以单文件头 PurismCoreBundle.h 形式内联编译：
#   - 只有一个翻译单元定义 PURISM_CORE_IMPLEMENTATION（见 live2d_jni.cpp），
#     否则所有 csm* 符号会重复定义；
#   - 头文件是 C99 写的，但标准声明支持 C++ 编译（README「Purism Core does build
#     with a C++ compiler」），故不拆 C/C++ 两个目标，避免跨语言链接开销。
#
# 依赖库只有三个，均为 NDK 自带的系统库：
#   android  -> AAssetManager（从 APK assets 读 .moc3）
#   log      -> __android_log_print
#   GLESv2   -> glDrawElements 等 GLES 2.0 入口
# ============================================================================

cmake_minimum_required(VERSION 3.22.1)

project(live2d_jni LANGUAGES CXX)

# Purism Core 的正则/浮点代码对严格别名与 fast-math 敏感；保持默认优化等级，
# 只额外关掉「未使用参数」这类噪音告警（第三方头文件不归本项目维护）。
add_library(live2d_jni SHARED live2d_jni.cpp)

target_compile_features(live2d_jni PRIVATE cxx_std_17)

target_compile_options(live2d_jni PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        # 单文件头里的 visibility 属性在部分 NDK 配置下会刷警告，屏蔽之
        -Wno-attributes
        # 第三方头文件的隐式类型转换较多，不作为本项目的编译门槛
        -Wno-sign-compare
        # Purism Core 是单文件实现头，未用到的 csm* API 会触发几十条
        # -Wunused-function（第三方代码，不由本项目维护）
        -Wno-unused-function
)

# Purism Core 默认 V6 ABI；显式声明 STATIC 让 PSMDEF 走 static 分支，
# 避免导出上百个 csm* 符号污染 .so 的动态符号表（只有 JNI 入口需要可见）。
target_compile_definitions(live2d_jni PRIVATE
        PURISM_CORE_STATIC
)

find_library(log-lib log)
find_library(android-lib android)
find_library(gles-lib GLESv2)

target_link_libraries(live2d_jni
        ${android-lib}
        ${log-lib}
        ${gles-lib}
        # Purism Core 用到 powf/sinf/sqrtf（<math.h>）；NDK 的 libm 需显式链接
        m
)
