// ============================================================================
//  live2d_jni.cpp - P0 静态渲染原型（Purism Core -> GLES 2.0）
// ============================================================================
//
//  职责边界：
//   - 只做「从 assets 读 .moc3 → Purism Core 求值 → 贴图绘制」；
//   - 不碰 BlendShapeMapper / FaceRenderer 的任何逻辑（P3 只读取它们产出的
//     VTS 参数快照，映射与算法全在 Kotlin 侧）；
//   - 不做模型导入、不做动作播放、不做表情预设。
//
//  坐标与数据流：
//   nativeInit（任意线程，只调一次）
//     AAssetManager 读整份 .moc3 → 64 字节对齐缓冲区
//     csmHasMocConsistency 校验 → csmReviveMocInPlace → csmInitializeModelInPlace
//     csmUpdateModel（跑一遍完整管线，得到 t=0 的顶点）
//     按 render order 收集所有「直接可画」的 drawable，拼成
//     一个交错 CPU 顶点数组（x, y, r, g, b, a）+ 一个索引数组
//   nativeSurfaceCreated（GL 线程）
//     编译 shader → 建 VBO/IBO → glBufferData 上传
//   nativeDrawFrame（GL 线程）
//     glDrawElements 一次画完全部三角形
//
//  为什么合并成一个 VBO / 一次 draw call：
//   P0 只验证「C++ 能加载并画出人形」，3248 顶点 / 14088 索引的量级合并后
//   仍然是单次绘制；drawable 之间的前后关系靠「按 render order 顺序拼接索引」
//   保证（GL 对同一 draw call 内的图元按顺序做 alpha 混合）。
//
//  为什么要剔除部分 drawable（P0 简化，非最终形态）：
//   1. 被其它 drawable 当作 clipping mask 的网格（csmGetDrawableMasks 里出现过
//      的下标）在真实渲染器里只用于模板缓冲，绝不直接画——直接画会糊成一坨；
//   2. 顶点数 == 4 的矩形是 HitArea（命中检测用，model3.json 的 HitAreas），
//      不是画面内容，画出来会是一个盖住全身的大方块。
//  ⚠️ P2 实测修正：第 1 条是错的。Sample 的前发 D_PSD_04 既被别人当遮罩、
//     自身又是一个正常可见图层（op=1 vis=1）。按第 1 条整片剔除后前发消失，
//     头顶只剩后发的硬边几何，真机上看起来就是生硬的「平切」。
//     现在的做法：这类图层按普通内容绘制，同时几何也用于模板写入。
//
//  P1 增补（纹理与 UV）：
//   - stb_image.h 解 PNG；UV 取 csmGetDrawableVertexUvs，**V 轴要翻转**（见 Vertex）；
//   - 纹理张数不写死：解析 model3.json 的 FileReferences.Textures，按
//     csmGetDrawableTextureIndices 分配到对应纹理单元（Sample 有 2 张，51:19）；
//   - 逐 drawable 一个 draw call（纹理与 uOpacity 都随 drawable 变化）；
//   - 纹理是 straight alpha，而混合用预乘公式（GL_ONE, GL_ONE_MINUS_SRC_ALPHA），
//     因此 fragment shader 里先乘 a 再输出（否则边缘会发黑）；
//   - 被 stencil mask 裁剪的 10 个 drawable 在 P1 仍按「不裁剪直接画」处理：
//     实测这比直接丢弃它们的观感好得多（丢弃会丢胸前内容），真正的 stencil
//     裁剪留给 P2。
//
//  P2 增补（Clipping Mask / Stencil 裁剪）：
//   - 遮罩 drawable 的几何也写进同一份 VBO/IBO，但单独记成 g_maskBatches，
//     内容绘制时不再剔除它们（见上：「是遮罩」≠「不该显示」）；
//   - 每个内容批次记录自己的遮罩批次区间（Batch.maskFirst/maskCount）；
//   - 绘制一个带遮罩的批次：清 stencil → 关颜色写入 → 逐个遮罩 GL_REPLACE 写 1
//     → 开颜色写入 → GL_EQUAL 1 画内容 → 关 stencil。多个遮罩是**并集**
//     （连续 REPLACE 到同一位），不是交集；
//   - 遮罩 shader **只按纹理 alpha** 决定覆盖范围，不乘 opacity：
//     vis=0/op=0 的遮罩（如眼睛的 D_PSD_35/36）表示「自身不显示」，
//     但仍要按其形状裁剪别人，乘了 opacity 会把眼睛整片裁没；
//   - 依赖 EGL 配置里至少 8-bit stencil（Kotlin 侧 setEGLConfigChooser 显式指定），
//     没有时自动退回「不裁剪直接画」并打警告；
//   - 嵌套遮罩（遮罩自身又被遮罩）P2 按单层处理，只记警告；反转遮罩
//     （csmIsInvertedMask）已用 GL_NOTEQUAL 实现，但 Sample 未用到，未经真机验证。
//
//  P3 增补（参数绑定与面捕驱动）：
//   - 参数写入走 csmGetParameterValues 返回的**可写数组**：本版本 Purism Core
//     不提供 csmSetParameterValueById，语义等价的做法是按 id 查下标后直接写
//     values[index]，再由 csmUpdateModel 重算顶点（见 nativeUpdateParameters）；
//   - 下标缓存 g_paramIndexById 在 loadModel 里一次性建立（string -> int），
//     帧内不再做字符串比较；
//   - **拓扑不变**：批次/遮罩/索引在 init 时定死，逐帧只刷新顶点位置与 alpha
//     （g_vertexSpans 记录每个 drawable 在 g_vertices 里的区间）。参数改变
//     drawable 的顶点数是 Live2D 不支持的情形，改图集也不在本阶段范围内；
//   - 投影包围盒**不随参数重算**：否则头部摆动会导致画面跟着缩放抖动，
//     与标准渲染器的固定取景语义一致；
//   - 跨线程：nativeUpdateParameters 可能来自任意线程（Kotlin 推流协程），
//     只改 CPU 侧模型与顶点并置脏标记；VBO 重传在 GL 线程的 nativeDrawFrame
//     里完成（EREADY GL context 只在 GL 线程有效）。两者都持 g_mutex。
//
//  P3 已知边界（有意为之，不是遗漏）：
//   - **可见集合在 init 时定死**：init 阶段被判定为不可见（vis=0 / opacity≈0）
//     或顶点数=4（HitArea）的 drawable 不进入批次表。若某个参数后来让它变可见，
//     本阶段不会补画——补画意味着要动态增删批次、重建索引与掩码区间。
//     Sample 的常规表情（睁眼/闭嘴/转头）不触发这类变化，实测不受影响；
//   - 投影包围盒固定，模型不会因头动而整体缩放（标准语义也是固定取景）；
//   - 不做物理/自动眨眼：本版本 Purism Core 的 csmUpdateModel 只做参数求值，
//     不含 physics3/pose3。头发裙摆的二次运动需要另实现物理求解器，超出本阶段。
//
//  Purism Core 的使用约束（API 规范）：
//   - MOC 缓冲区必须 64 字节对齐（csmAlignofMoc），且必须活得比 model 久；
//   - model 缓冲区必须 16 字节对齐（csmAlignofModel），大小取 csmGetSizeofModel；
//   - 库不做任何堆分配，两块缓冲区都由调用方持有（本文件的 Buffer 结构）；
//   - 同一 model 不允许跨线程并发访问（本文件：init 线程写、GL 线程只读 CPU 副本）。
// ============================================================================

#include <jni.h>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <GLES2/gl2.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

// Purism Core 单文件头：整个实现只允许在这一个翻译单元里展开。
// PURISM_CORE_STATIC 由 CMakeLists.txt 的 target_compile_definitions 统一注入
// （让 PSMDEF 走 static 分支，只导出 JNI 入口），此处不重复定义以免 -Wmacro-redefined。
#define PURISM_CORE_IMPLEMENTATION
#include "PurismCoreBundle.h"

// stb_image：单文件 PNG/JPEG 解码器。同样只允许一个翻译单元定义实现。
// STBI_ONLY_PNG / STBI_NO_STDIO：本工程只解 assets 里的 PNG，且走内存接口
// （AAsset_read 到缓冲区再 stbi_load_from_memory），裁掉 JPEG/文件 IO 能显著减小 .so。
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#define STBI_NO_HDR
#define STBI_NO_GIF
#include "stb_image.h"

#define LOG_TAG "Live2DJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
//  错误传递
// ---------------------------------------------------------------------------
//  JNI 入口不抛 Java 异常（GL 线程抛异常无人能接），统一把失败原因写进
//  g_lastError 并由 nativeInit 以 jstring 返回（null = 成功）。
std::string g_lastError;

// ---------------------------------------------------------------------------
//  对齐缓冲区
// ---------------------------------------------------------------------------
// Posix memalign 在 NDK 上可用；用 RAII 保证 moc/model 缓冲区不泄漏。
struct AlignedBuffer {
    void *ptr = nullptr;
    size_t size = 0;

    bool alloc(size_t alignment, size_t bytes) {
        free();
        // posix_memalign 要求 size 无特殊限制，但对齐必须是 2 的幂且为 sizeof(void*) 倍数
        if (posix_memalign(&ptr, alignment, bytes) != 0) {
            ptr = nullptr;
            return false;
        }
        size = bytes;
        return true;
    }

    void free() {
        if (ptr) {
            ::free(ptr);
            ptr = nullptr;
        }
        size = 0;
    }

    ~AlignedBuffer() { free(); }
};

// ---------------------------------------------------------------------------
//  渲染侧全局状态
// ---------------------------------------------------------------------------
/**
 * 顶点。P1 起带 UV，颜色改由纹理提供，只保留 alpha（逐 drawable 的 opacity）。
 *
 * V 轴为什么要在这里就翻掉（而不是在 shader 里）：
 * Live2D 的 UV 原点在**图片左下角**（V 向上为增），而 PNG 的行序是**从上到下**、
 * OpenGL 的 glTexImage2D 又默认把第一行当作纹理的 v=0（即图像底部）。
 * 三者叠加的结果是：直接把 Purism Core 给出的 v 传给 texture2D 会上下颠倒
 * （实测眼睛跑到手上）。两种修法等价，这里选「上传前 1-v」，
 * 因为比在 shader 里再加一行更容易在本文件里被 grep 到、也更省一次逐像素减法。
 */
struct Vertex {
    float x, y;     // 模型空间坐标（Purism Core 直接给出，单位 = canvas / ppu）
    float u, v;     // 纹理坐标；v 已按上述原因翻转为 1-v
    float a;        // 逐顶点 alpha（= 该 drawable 的 opacity，播纹理时再乘一次）
};

// 模型生命周期（init 阶段写，之后只读）
AlignedBuffer g_mocBuffer;
AlignedBuffer g_modelBuffer;
csmMoc *g_moc = nullptr;
csmModel *g_model = nullptr;

// CPU 几何（init 组装，surfaceCreated 上传）
//
// 线程约束：nativeInit 在主线程写、nativeSurfaceCreated/nativeDrawFrame 在 GL 线程读，
// nativeRelease 可能来自主线程（组合销毁）而 GL 线程仍在跑 —— 三处都必须持 g_mutex。
// 不加锁会出现「GL 线程正读 g_indices.data() 时主线程 clear()」的悬垂指针崩溃。
std::mutex g_mutex;
std::vector<Vertex> g_vertices;
std::vector<uint16_t> g_indices;

/**
 * 一个 drawable 在 g_vertices 里占据的区间（P3）。
 *
 * 为什么要单独记：参数一变，Purism Core 只更新 csmGetDrawableVertexPositions
 * 返回的坐标数组，不会替我们重排 CPU 侧的合并顶点缓冲。有了区间就能只按
 * drawable 局部刷新，不必每帧重建整份几何（也保住了 VBO 的静态拓扑）。
 *
 * 内容批次与遮罩批次都会登记（遮罩的顶点位置同样随参数变化，如头发遮罩）。
 */
struct VertexSpan {
    int drawableIndex = 0; // csm 的 drawable 下标（不是批次下标）
    int firstVertex = 0;   // 在 g_vertices 中的起始下标
    int vertexCount = 0;
};

std::vector<VertexSpan> g_vertexSpans;

/** 顶点数据自上次上传后是否被参数更新改脏（GL 线程据此决定是否 glBufferSubData）。 */
bool g_verticesDirty = false;

/**
 * VBO 当前分配的顶点数（GL 线程写）。
 *
 * 用于 glBufferSubData 前的尺寸校验：若 nativeInit 在上传之后又跑过（模型换了），
 * CPU 顶点数可能与 VBO 容量不一致，此时**跳过**本次局部更新而不是越界写显存。
 */
int g_vboVertexCount = 0;

/**
 * 参数 id -> 参数下标缓存（P3）。
 *
 * 本版本 Purism Core 没有 csmSetParameterValueById，只能写
 * csmGetParameterValues 返回的数组；ID 是字符串，逐帧线性查找参数名会成为
 * 30Hz 下实实在在的开销。init 时建一次表，之后 O(1) 落位。
 */
std::unordered_map<std::string, int> g_paramIndexById;
int g_paramCount = 0;

/** 缓存命中计数，用于诊断「Kotlin 传的 id 是否在模型里」（仅累计，不打日志）。 */
long g_paramWritesTotal = 0;
long g_paramUnknownTotal = 0;

/**
 * 一次绘制批次：顶点/索引数组里的一段（一个 drawable 一段）。
 *
 * 为什么必须按 drawable 拆批次而不是像 P0 那样一次画完：
 *  - 纹理随 drawable 变化（纹理单元不同）；
 *  - opacity 随 drawable 变化（uOpacity uniform 不同）。
 * P0 合并成单次 draw call 是为了省事，P1 有了逐 drawable 状态就没法再合并了。
 * 顺序严格按 render order 升序，靠绘制顺序保证前后遮挡关系。
 */
struct Batch {
    int firstIndex = 0;   // 在 g_indices 中的起始位置
    int indexCount = 0;   // 索引个数
    int textureIndex = 0; // 对应 g_textures 的下标
    float opacity = 1.0f; // uOpacity

    // ---- P2：Clipping Mask ----
    int maskFirst = 0;      // 本批次引用的遮罩在 g_maskRefs 中的起点
    int maskCount = 0;      // 引用的遮罩个数（0 = 不裁剪）
    bool invertedMask = false; // csmIsInvertedMask：绘制遮罩之外的部分
};

std::vector<Batch> g_batches;

/**
 * 一个遮罩几何批次（P2）。
 *
 * 遮罩 drawable 不进 g_batches（正常内容绘制时跳过），几何单独记在这里，
 * 只在 stencil 写入阶段用纯色（颜色写入关闭）画一遍。
 *
 * textureIndex 是必需的：遮罩的实际覆盖范围由**纹理 alpha**决定
 * （网格矩形里可能有大量透明区域，不能只按几何三角形写模板）。
 * 这里刻意不保存 opacity —— 遮罩的显示透明度不参与模板写入，原因见
 * kMaskFragmentShader 的注释。
 */
struct MaskBatch {
    int firstIndex = 0;
    int indexCount = 0;
    int textureIndex = 0;
};

std::vector<MaskBatch> g_maskBatches;

/**
 * 扁平化的「批次 → 遮罩批次」引用表。
 *
 * Batch.maskFirst/maskCount 指向这里的区间。用扁平数组而不是
 * vector<vector<int>>，是为了 drawFrame 里整份 memcpy 快照更省事。
 */
std::vector<int> g_maskRefs;

/** 一张已解码的纹理（CPU 侧像素，surfaceCreated 时上传 GPU）。 */
struct TextureImage {
    std::string name;              // model3.json 里的相对路径（诊断用）
    std::vector<uint8_t> rgba;     // RGBA8，自上而下的行序（PNG 原生）
    int width = 0;
    int height = 0;
};

/** 所有纹理（按 model3.json 的 Textures 数组顺序，下标 = csm 的 texture index）。 */
std::vector<TextureImage> g_textureImages;

/** GPU 纹理对象（surfaceCreated 创建，与 g_textureImages 一一对应）。 */
std::vector<GLuint> g_textures;

float g_minX = 0, g_maxX = 0, g_minY = 0, g_maxY = 0;
int g_drawableTotal = 0;
int g_drawableDrawn = 0;

// GPU 资源（只在 GL 线程创建/销毁）
GLuint g_program = 0;
GLuint g_vbo = 0;
GLuint g_ibo = 0;
GLint g_attrPos = -1;
GLint g_attrUv = -1;
GLint g_attrAlpha = -1;
GLint g_uniformMvp = -1;
GLint g_uniformOpacity = -1;
GLint g_uniformTex = -1;

// 遮罩写入 program（P2）：与 g_program 共用顶点阶段，只换 fragment 阶段
GLuint g_maskProgram = 0;
GLint g_maskUniformMvp = -1;
GLint g_maskUniformTex = -1;

// 视口与投影（surfaceChanged 写，drawFrame 读；两者同在 GL 线程）
int g_viewW = 0;
int g_viewH = 0;
float g_mvp[16] = {0};

/**
 * 当前 EGL 配置的模板缓冲位数（P2）。
 *
 * 取自 glGetIntegerv(GL_STENCIL_BITS)，在 surfaceCreated 里读一次。
 * 0 表示 Surface 根本没有模板缓冲——此时所有 stencil 调用都是空操作，
 * drawFrame 会退回「不裁剪直接画」而不是把整块内容裁没。
 */
int g_stencilBits = 0;

// GL 线程标识：nativeRelease 据此判断能否安全调用 glDelete*
std::thread::id g_glThreadId{};

// ---------------------------------------------------------------------------
//  Purism Core 日志桥接
// ---------------------------------------------------------------------------
void coreLog(const char *message) {
    if (message) __android_log_print(ANDROID_LOG_DEBUG, "PurismCore", "%s", message);
}

// ---------------------------------------------------------------------------
//  纹理路径解析（极简 model3.json 扫描）
// ---------------------------------------------------------------------------
//  为什么不引 JSON 库：这里只需要 FileReferences.Textures 一个字符串数组，
//  引 nlohmann/json 会给 .so 增加几百 KB，还要为异常处理做额外约定。
//  做法是纯文本定位 —— 找到 "Textures" 键，再把它后面第一个 '[' 到 ']' 之间
//  的所有 "..." 字符串抽出来。
//
//  已知边界（P1 可接受，模型文件是随 APK 打包的可信资源）：
//   - 不处理字符串里的转义（模型路径不会是 "a\"b.png" 这种）；
//   - 不处理注释（JSON 标准不允许）；
//   - 若 model3.json 里有多个 "Textures" 键，取第一个。
std::vector<std::string> parseTextureList(const std::string &json) {
    std::vector<std::string> result;

    const std::string key = "\"Textures\"";
    size_t keyPos = json.find(key);
    if (keyPos == std::string::npos) return result;

    size_t bracket = json.find('[', keyPos + key.size());
    if (bracket == std::string::npos) return result;
    const size_t bracketEnd = json.find(']', bracket);
    if (bracketEnd == std::string::npos) return result;

    size_t pos = bracket + 1;
    while (pos < bracketEnd) {
        const size_t quoteOpen = json.find('"', pos);
        if (quoteOpen == std::string::npos || quoteOpen >= bracketEnd) break;
        const size_t quoteClose = json.find('"', quoteOpen + 1);
        if (quoteClose == std::string::npos || quoteClose >= bracketEnd) break;
        result.push_back(json.substr(quoteOpen + 1, quoteClose - quoteOpen - 1));
        pos = quoteClose + 1;
    }
    return result;
}

/** 取 model3.json 所在目录（带结尾 '/'），用于把 Moc/Textures 的相对路径拼成 assets 路径。 */
std::string dirOf(const std::string &path) {
    const size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) return std::string();
    return path.substr(0, slash + 1);
}

/** 把 .moc3 路径换成同目录下的 .model3.json 路径（Sample 的命名恰好一一对应）。 */
std::string modelJsonPathFor(const std::string &mocPath) {
    const std::string suffix = ".moc3";
    if (mocPath.size() > suffix.size() &&
        mocPath.compare(mocPath.size() - suffix.size(), suffix.size(), suffix) == 0) {
        return mocPath.substr(0, mocPath.size() - suffix.size()) + ".model3.json";
    }
    // 非 .moc3 结尾：退回「同目录下第一个 model3.json」不可行（要列目录），
    // 直接返回空串让调用方走退化路径
    return std::string();
}

/** 把整个 asset 读成字节数组（纹理/model3.json 用，不需要对齐）。 */
bool readAssetBytes(AAssetManager *manager, const std::string &path, std::vector<uint8_t> *out) {
    AAsset *asset = AAssetManager_open(manager, path.c_str(), AASSET_MODE_BUFFER);
    if (!asset) return false;

    const off64_t length = AAsset_getLength64(asset);
    if (length <= 0) {
        AAsset_close(asset);
        return false;
    }

    out->resize(static_cast<size_t>(length));
    const int read = AAsset_read(asset, out->data(), static_cast<size_t>(length));
    AAsset_close(asset);
    return read == static_cast<int>(length);
}

/**
 * 解码一张 PNG 到 [TextureImage]。
 *
 * stb_image 的接口选择：用 stbi_load_from_memory 而不是 stbi_load，
 * 因为 Android 上 assets 不是普通文件路径（APK 内的压缩条目没有文件系统路径），
 * 我们已经用 AAssetManager 把字节读进内存了。
 *
 * 强制 4 通道：Purism Core 的 UV/取样假设 RGBA，缺 alpha 通道的 PNG 若按 3 通道
 * 上传会被 GL 补成 alpha=1，看似没问题但容易在后续加遮罩时埋坑。
 */
bool loadTexture(AAssetManager *manager, const std::string &path, TextureImage *out) {
    std::vector<uint8_t> encoded;
    if (!readAssetBytes(manager, path, &encoded)) {
        LOGW("纹理读取失败: %s", path.c_str());
        return false;
    }

    int width = 0;
    int height = 0;
    int channels = 0;
    unsigned char *pixels = stbi_load_from_memory(
            encoded.data(), static_cast<int>(encoded.size()),
            &width, &height, &channels, 4);
    if (!pixels) {
        LOGW("纹理解码失败: %s (%s)", path.c_str(), stbi_failure_reason());
        return false;
    }

    out->name = path;
    out->width = width;
    out->height = height;
    out->rgba.assign(pixels, pixels + static_cast<size_t>(width) * height * 4);
    stbi_image_free(pixels);

    LOGI("纹理就绪: %s %dx%d (源通道数=%d) %zu KB",
         path.c_str(), width, height, channels, out->rgba.size() / 1024);
    return true;
}

/**
 * 加载模型引用的全部纹理。
 *
 * @param mocPath  .moc3 的 assets 路径，用来推导同目录的 .model3.json
 * @param err      失败原因（纹理加载失败不致命，只记日志；完全没纹理时在调用方报错）
 * @return 至少成功加载一张纹理
 */
bool loadTextures(AAssetManager *manager, const std::string &mocPath, std::string *err) {
    g_textureImages.clear();

    const std::string jsonPath = modelJsonPathFor(mocPath);
    std::vector<std::string> texturePaths;

    if (!jsonPath.empty()) {
        std::vector<uint8_t> jsonBytes;
        if (readAssetBytes(manager, jsonPath, &jsonBytes)) {
            const std::string json(jsonBytes.begin(), jsonBytes.end());
            texturePaths = parseTextureList(json);
            LOGI("model3.json 解析到 %zu 张纹理 (%s)",
                 texturePaths.size(), jsonPath.c_str());
        } else {
            LOGW("model3.json 读取失败，退化到硬编码纹理列表: %s", jsonPath.c_str());
        }
    }

    // 退化路径：解析不出就从 .moc3 同目录按约定尝试 texture_00 / texture_01。
    // 这样即使 model3.json 缺失或格式变动，P1 也不会整个白屏。
    if (texturePaths.empty()) {
        const std::string dir = dirOf(mocPath);
        texturePaths = {
            dir + "textures/texture_00.png",
            dir + "textures/texture_01.png",
        };
        LOGW("使用退化纹理列表（%zu 项）", texturePaths.size());
    }

    const std::string dir = dirOf(mocPath);
    for (const std::string &relative : texturePaths) {
        // Textures 数组里的路径相对 model3.json 所在目录
        TextureImage image;
        if (loadTexture(manager, dir + relative, &image)) {
            g_textureImages.push_back(std::move(image));
        } else {
            // 占位：保持下标与 csm 的 texture index 对齐，避免后续 drawable 取错纹理
            TextureImage placeholder;
            placeholder.name = dir + relative;
            g_textureImages.push_back(std::move(placeholder));
        }
    }

    int loaded = 0;
    for (const TextureImage &img : g_textureImages) {
        if (img.width > 0) ++loaded;
    }
    if (loaded == 0) {
        *err = "没有任何纹理加载成功（检查 assets 下的 textures 目录）";
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
//  MOC3 读取（AAssetManager）
// ---------------------------------------------------------------------------
bool readAsset(AAssetManager *manager, const char *path, AlignedBuffer *out, std::string *err) {
    AAsset *asset = AAssetManager_open(manager, path, AASSET_MODE_BUFFER);
    if (!asset) {
        *err = std::string("无法打开 asset: ") + path;
        return false;
    }

    const off64_t length = AAsset_getLength64(asset);
    if (length <= 0) {
        AAsset_close(asset);
        *err = std::string("asset 长度非法: ") + path;
        return false;
    }

    // 标准要求 MOC 缓冲区 64 字节对齐；AAsset_read 允许直接读进任意缓冲区
    if (!out->alloc(csmAlignofMoc, static_cast<size_t>(length))) {
        AAsset_close(asset);
        *err = "MOC 缓冲区分配失败（内存不足？）";
        return false;
    }

    const int read = AAsset_read(asset, out->ptr, static_cast<size_t>(length));
    AAsset_close(asset);

    if (read != static_cast<int>(length)) {
        *err = "asset 读取不完整";
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
//  模型加载 + CPU 几何组装
// ---------------------------------------------------------------------------
bool loadModel(AAssetManager *manager, const char *mocPath, std::string *err) {
    if (!readAsset(manager, mocPath, &g_mocBuffer, err)) return false;

    const auto mocSize = static_cast<unsigned int>(g_mocBuffer.size);

    // 不可信输入的先决校验（API 规范 明确要求先 consistency 再 revive）
    const csmMocVersion version = csmGetMocVersion(g_mocBuffer.ptr, mocSize);
    if (version == csmMocVersion_Unknown) {
        *err = "不是合法的 MOC3 文件（版本识别失败）";
        return false;
    }
    if (csmHasMocConsistency(g_mocBuffer.ptr, mocSize) != 1) {
        *err = "MOC3 结构校验失败";
        return false;
    }

    g_moc = csmReviveMocInPlace(g_mocBuffer.ptr, mocSize);
    if (!g_moc) {
        *err = std::string("csmReviveMocInPlace 失败: ") +
               csmGetErrorString(csmGetMocError(static_cast<const csmMoc *>(g_mocBuffer.ptr)));
        return false;
    }

    const unsigned int modelSize = csmGetSizeofModel(g_moc);
    if (modelSize == 0 ||
        !g_modelBuffer.alloc(csmAlignofModel, static_cast<size_t>(modelSize))) {
        *err = "model 缓冲区分配失败";
        return false;
    }

    g_model = csmInitializeModelInPlace(g_moc, g_modelBuffer.ptr, modelSize);
    if (!g_model) {
        *err = std::string("csmInitializeModelInPlace 失败: ") +
               csmGetErrorString(csmGetMocError(g_moc));
        return false;
    }

    // 标准说明初始化已跑过一次 update，但这里显式再跑一遍，确保顶点就是
    // 「全默认参数」下的静态姿态（P0 不需要任何参数驱动）。
    csmResetDrawableDynamicFlags(g_model);
    csmUpdateModel(g_model);

    csmVector2 canvasSize{}, canvasOrigin{};
    float ppu = 0;
    csmReadCanvasInfo(g_model, &canvasSize, &canvasOrigin, &ppu);
    LOGI("canvas=%.1fx%.1f origin=(%.1f,%.1f) ppu=%.1f",
         canvasSize.X, canvasSize.Y, canvasOrigin.X, canvasOrigin.Y, ppu);

    // ---- 收集需要跳过的 drawable ----
    const int drawableCount = csmGetDrawableCount(g_model);
    g_drawableTotal = drawableCount;
    if (drawableCount <= 0) {
        *err = "模型没有 drawable";
        return false;
    }

    const int *maskCounts = csmGetDrawableMaskCounts(g_model);
    const int **masks = csmGetDrawableMasks(g_model);
    std::vector<uint8_t> isMask(static_cast<size_t>(drawableCount), 0);
    for (int i = 0; i < drawableCount; ++i) {
        for (int k = 0; k < maskCounts[i]; ++k) {
            const int target = masks[i][k];
            if (target >= 0 && target < drawableCount) isMask[static_cast<size_t>(target)] = 1;
        }
    }
    // 注意：isMask 只用于统计与说明，**不再用于过滤绘制**。
    // 「被别人当遮罩」与「自己也要显示」并不互斥（Sample 的前发 D_PSD_04 两者皆是）；
    // P0 曾把所有 isMask 图层整片剔除，结果前发缺失，头顶只剩后发的硬边几何，
    // 真机上表现为生硬的「平切」。P2 起这些图层按普通内容绘制，同时参与模板写入。

    const int *vertexCounts = csmGetDrawableVertexCounts(g_model);
    const int *indexCounts = csmGetDrawableIndexCounts(g_model);
    const csmVector2 **positions = csmGetDrawableVertexPositions(g_model);
    const csmVector2 **uvs = csmGetDrawableVertexUvs(g_model);
    const unsigned short **indices = csmGetDrawableIndices(g_model);
    const float *opacities = csmGetDrawableOpacities(g_model);
    const csmFlags *dynamicFlags = csmGetDrawableDynamicFlags(g_model);
    const csmFlags *constantFlags = csmGetDrawableConstantFlags(g_model);
    const int *renderOrders = csmGetRenderOrders(g_model);
    const int *textureIndices = csmGetDrawableTextureIndices(g_model);

    // ---- 按 render order 升序排列（先画的在后，后画的在前）----
    std::vector<int> order(static_cast<size_t>(drawableCount));
    for (int i = 0; i < drawableCount; ++i) order[static_cast<size_t>(i)] = i;
    std::stable_sort(order.begin(), order.end(), [renderOrders](int a, int b) {
        return renderOrders[a] < renderOrders[b];
    });

    g_vertices.clear();
    g_indices.clear();
    g_batches.clear();
    g_maskBatches.clear();
    g_maskRefs.clear();
    g_vertexSpans.clear();
    g_verticesDirty = false;
    // Sample 实测 2794 顶点 / 12174 索引；Mask 会额外带进几百个顶点，一并预留
    g_vertices.reserve(8192);
    g_indices.reserve(32768);
    g_batches.reserve(128);
    g_maskBatches.reserve(32);
    g_maskRefs.reserve(32);
    g_vertexSpans.reserve(128);

    // ---- P3：参数 id -> 下标缓存 ----
    // 本版本无 csmSetParameterValueById，参数只能通过可写 values 数组写入，
    // 因此这里把全部参数名与下标建成表，供 nativeUpdateParameters 做 O(1) 查找。
    {
        const char **paramIds = csmGetParameterIds(g_model);
        g_paramCount = csmGetParameterCount(g_model);
        g_paramIndexById.clear();
        if (paramIds && g_paramCount > 0) {
            g_paramIndexById.reserve(static_cast<size_t>(g_paramCount) * 2);
            for (int i = 0; i < g_paramCount; ++i) {
                if (paramIds[i]) g_paramIndexById.emplace(paramIds[i], i);
            }
            LOGI("参数表: count=%d（可写入）", g_paramCount);
        } else {
            LOGW("参数表为空（csmGetParameterIds=%p count=%d），参数驱动不可用",
                 static_cast<const void *>(paramIds), g_paramCount);
        }
    }

    g_minX = g_minY = 1e30f;
    g_maxX = g_maxY = -1e30f;
    int drawn = 0;
    int skippedQuad = 0;
    int skippedHidden = 0;
    int texturesMissing = 0;
    int maskedBatches = 0;
    int invertedBatches = 0;
    int nestedMaskMasks = 0;
    int hiddenMaskRefs = 0;
    int maskAlsoDrawn = 0;

    /**
     * 把一个 drawable 的几何追加进共享 VBO 数据，返回它在 g_indices 里的区间。
     *
     * 内容批次与掩码批次共用同一份顶点/索引缓冲：每帧只需要一次
     * glVertexAttribPointer，class 切换成本为零。
     *
     * @param trackBounds 是否参与投影包围盒。掩码几何不直接显示，若把它算进 bbox
     *                    会让「模型适配视口」的取景被一块看不见的网格带偏。
     */
    const auto appendGeometry = [&](int idx, bool trackBounds, int *firstIndex,
                                    int *indexCount) -> bool {
        const size_t di = static_cast<size_t>(idx);
        const int vcount = vertexCounts[di];
        const int icount = indexCounts[di];
        if (vcount <= 0 || icount <= 0) return false;
        if (g_vertices.size() + static_cast<size_t>(vcount) > 65535u) {
            // uint16 索引上限；Sample 实测 2794 顶点，留 65k 足够，真撞上就放弃该 drawable
            LOGW("顶点数将超过 65535，跳过 drawable（index=%d）", idx);
            return false;
        }

        const float opacity =
                opacities[di] < 0 ? 0.0f : (opacities[di] > 1 ? 1.0f : opacities[di]);
        const int spanFirst = static_cast<int>(g_vertices.size());
        const uint16_t base = static_cast<uint16_t>(spanFirst);
        const csmVector2 *pos = positions[di];
        const csmVector2 *uv = uvs[di];
        for (int v = 0; v < vcount; ++v) {
            const float x = pos[v].X;
            const float y = pos[v].Y;
            // V 轴翻转：Live2D UV 原点在图片左下，PNG 行序自上而下（见 Vertex 注释）
            const float u = uv[v].X;
            const float flippedV = 1.0f - uv[v].Y;
            g_vertices.push_back(Vertex{x, y, u, flippedV, opacity});
            if (!trackBounds) continue;
            if (x < g_minX) g_minX = x;
            if (x > g_maxX) g_maxX = x;
            if (y < g_minY) g_minY = y;
            if (y > g_maxY) g_maxY = y;
        }
        // 登记区间：P3 的参数更新按 drawable 局部刷新顶点位置
        g_vertexSpans.push_back(VertexSpan{idx, spanFirst, vcount});

        *firstIndex = static_cast<int>(g_indices.size());
        *indexCount = icount;
        const unsigned short *src = indices[di];
        for (int t = 0; t < icount; ++t) {
            g_indices.push_back(static_cast<uint16_t>(base + src[t]));
        }
        return true;
    };
    // 「drawable 下标 → 掩码批次下标」缓存：同一个掩码常被多个内容共用，
    // 几何只收集一次，避免重复顶点把 65535 的 uint16 空间吃掉。
    std::vector<int> maskBatchOf(static_cast<size_t>(drawableCount), -1);

    for (int idx : order) {
        const size_t di = static_cast<size_t>(idx);
        const int vcount = vertexCounts[di];
        const int icount = indexCounts[di];

        if (vcount <= 0 || icount <= 0) continue;
        // 顶点数 == 4 的矩形：HitArea（命中检测用），不是画面内容
        if (vcount == 4) { ++skippedQuad; continue; }
        if ((dynamicFlags[di] & csmIsVisible) == 0) { ++skippedHidden; continue; }
        if (opacities[di] <= 0.001f) { ++skippedHidden; continue; }

        // 纹理下标越界（模型引用了没加载成功的图集）：跳过而不是贴错图，
        // 错贴会让整块区域串色，比缺一块更难判断
        const int texIndex = textureIndices[di];
        if (texIndex < 0 || texIndex >= static_cast<int>(g_textureImages.size()) ||
            g_textureImages[static_cast<size_t>(texIndex)].width <= 0) {
            ++texturesMissing;
            continue;
        }

        // ---- P2：先算出这一批要用的掩码几何 ----
        const int ownMaskCount = maskCounts[di];
        const int maskFirst = static_cast<int>(g_maskRefs.size());
        for (int k = 0; k < ownMaskCount; ++k) {
            const int maskIdx = masks[di][k];
            // -1 = 该掩码当前不可见：Purism Core 明确用它表示「隐藏的掩码」
            if (maskIdx < 0 || maskIdx >= drawableCount) { ++hiddenMaskRefs; continue; }

            const size_t mi = static_cast<size_t>(maskIdx);
            // 掩码自身不可见 / 透明（如眼睛的 D_PSD_35/36，vis=0 op=0）：
            // **仍然要写进模板**。这类图层在模型里同时承担「遮罩源」和
            // 「本身不显示」两种角色，opacity=0 只表达后者。
            // 若因此跳过它，被它裁剪的内容（睫毛/眼白等 8 个零件）会整片消失。
            // 这也是遮罩 shader 不乘 opacity 的原因（见 kMaskFragmentShader）。
            if ((dynamicFlags[mi] & csmIsVisible) == 0 || opacities[mi] <= 0.001f) {
                ++hiddenMaskRefs;
            }

            // 嵌套遮罩（掩码自己又被掩码）P2 不递归处理，只记一条警告：
            // 该掩码会按完整几何写入模板，可能比预期多露出一圈
            if (maskCounts[mi] > 0) ++nestedMaskMasks;

            if (maskBatchOf[mi] < 0) {
                MaskBatch mb;
                if (!appendGeometry(maskIdx, /*trackBounds=*/false, &mb.firstIndex,
                                    &mb.indexCount)) continue;
                mb.textureIndex = textureIndices[mi];
                maskBatchOf[mi] = static_cast<int>(g_maskBatches.size());
                g_maskBatches.push_back(mb);
            }
            g_maskRefs.push_back(maskBatchOf[mi]);
        }

        const int usedMaskCount = static_cast<int>(g_maskRefs.size()) - maskFirst;
        if (ownMaskCount > 0 && usedMaskCount == 0) {
            // 有掩码声明但一个都不可用：Live2D 语义下这块内容应当完全不显示
            continue;
        }

        Batch batch;
        if (!appendGeometry(idx, /*trackBounds=*/true, &batch.firstIndex, &batch.indexCount)) {
            // 内容几何没进缓冲：刚才为它收集的掩码引用也一并丢弃，
            // 避免 maskRefs 里留下没有批次引用的孤儿区间
            g_maskRefs.resize(static_cast<size_t>(maskFirst));
            continue;
        }
        batch.textureIndex = texIndex;
        batch.opacity = opacities[di] < 0 ? 0.0f : (opacities[di] > 1 ? 1.0f : opacities[di]);
        batch.maskFirst = maskFirst;
        batch.maskCount = usedMaskCount;
        batch.invertedMask = (constantFlags[di] & csmIsInvertedMask) != 0;
        g_batches.push_back(batch);

        // 同时是遮罩源又自己可见的图层（如前发 D_PSD_04）：它既要按正常内容画，
        // 又要作为别人的遮罩。计入统计便于核对这类「双重身份」图层数量。
        if (isMask[di]) ++maskAlsoDrawn;

        if (usedMaskCount > 0) {
            ++maskedBatches;
            if (batch.invertedMask) ++invertedBatches;
        } else {
            // 没被裁剪的批次不应残留引用区间（保持 maskFirst/count 一致）
            g_maskRefs.resize(static_cast<size_t>(maskFirst));
        }
        ++drawn;
    }

    g_drawableDrawn = drawn;

    if (g_vertices.empty() || g_indices.empty()) {
        *err = "没有可绘制的 drawable（模型为空或全部被过滤）";
        return false;
    }

    LOGI("model ready: drawables=%d drawn=%d batches=%zu skip(quad=%d hidden=%d texMissing=%d) "
         "maskAlsoDrawn=%d verts=%zu idx=%zu",
         drawableCount, drawn, g_batches.size(), skippedQuad, skippedHidden, texturesMissing,
         maskAlsoDrawn, g_vertices.size(), g_indices.size());
    LOGI("mask ready: maskedBatches=%d maskGeoms=%zu maskRefs=%zu inverted=%d "
         "nested=%d hiddenRefs=%d",
         maskedBatches, g_maskBatches.size(), g_maskRefs.size(), invertedBatches,
         nestedMaskMasks, hiddenMaskRefs);
    LOGI("bounds: x[%.3f,%.3f] y[%.3f,%.3f]", g_minX, g_maxX, g_minY, g_maxY);
    return true;
}

// ---------------------------------------------------------------------------
//  参数驱动的顶点刷新（P3）
// ---------------------------------------------------------------------------
/**
 * 把所有已登记 drawable 的顶点位置与 alpha 从 Purism Core 抄回 g_vertices。
 *
 * 只改 x/y/a，不动 u/v 与索引：
 *  - UV 由模型静态决定，参数不会改它；
 *  - 索引与批次拓扑在 init 时定死（见文件头 P3 说明），参数更新不会改变
 *    drawable 的顶点数量，因此 VBO 大小恒定，GL 侧只需 glBufferSubData。
 *
 * 调用约束：**必须持 g_mutex**（调用方负责），且 g_model 非空。
 * 不重算 g_minX..g_maxY —— 投影取景固定，否则头动会让画面整体缩放。
 */
void refreshVertexPositionsLocked() {
    if (!g_model || g_vertexSpans.empty()) return;

    const int drawableCount = csmGetDrawableCount(g_model);
    if (drawableCount <= 0) return;
    const csmVector2 **positions = csmGetDrawableVertexPositions(g_model);
    const float *opacities = csmGetDrawableOpacities(g_model);
    if (!positions || !opacities) return;

    const int vertexTotal = static_cast<int>(g_vertices.size());
    for (const VertexSpan &span : g_vertexSpans) {
        const int di = span.drawableIndex;
        if (di < 0 || di >= drawableCount) continue;
        const csmVector2 *pos = positions[di];
        if (!pos) continue;

        // 防御：Purism Core 万一改了顶点数，按两者较小值抄，
        // 绝不越过本 drawable 的区间写到邻居上
        const int n = std::min(span.vertexCount, std::max(0, vertexTotal - span.firstVertex));
        const float opacity =
                opacities[di] < 0 ? 0.0f : (opacities[di] > 1 ? 1.0f : opacities[di]);
        Vertex *dst = g_vertices.data() + span.firstVertex;
        for (int v = 0; v < n; ++v) {
            dst[v].x = pos[v].X;
            dst[v].y = pos[v].Y;
            // opacity 也会被参数驱动（如眼睛开合时某些零件整体淡出）
            dst[v].a = opacity;
        }
    }
    g_verticesDirty = true;
}

// ---------------------------------------------------------------------------
//  GL 工具
// ---------------------------------------------------------------------------
GLuint compileShader(GLenum type, const char *source) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        GLint len = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
        std::string log(static_cast<size_t>(len > 1 ? len : 1), '\0');
        glGetShaderInfoLog(shader, len, nullptr, log.data());
        LOGE("shader 编译失败: %s", log.c_str());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

// GLES 2.0 程序：纹理 + 逐 drawable 的 opacity
//
// 关于预乘 alpha（重要，改混合公式前必读）：
//  混合配置是 glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)，这是**预乘 alpha**
//  的标准公式（它假设着色器输出的 RGB 已经乘过 alpha）。
//  而 PNG 纹理是 **straight alpha**（非预乘），所以这里必须在 fragment shader 里
//  显式做一次 `texel.rgb * texel.a` 再输出。若省略这一步，半透明边缘会因为
//  RGB 未被 alpha 压低而发光/发黑（头发边界最明显）。
//  两个方案二选一即可：
//   a) shader 里预乘 + glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)  ← 本实现
//   b) 不预乘 + glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
//  之所以选 a) 是因为 Live2D 标准渲染器与后续加「加法混合」图层时都用预乘管线，
//  保持同一套约定能省掉 P2 的返工。
const char *kVertexShader = R"(
attribute vec2 aPos;
attribute vec2 aUv;
attribute float aAlpha;
uniform mat4 uMvp;
varying vec2 vUv;
varying float vAlpha;

void main() {
    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
    vUv = aUv;
    vAlpha = aAlpha;
}
)";

const char *kFragmentShader = R"(
precision mediump float;
varying vec2 vUv;
varying float vAlpha;
uniform sampler2D uTex;
uniform float uOpacity;

void main() {
    vec4 texel = texture2D(uTex, vUv);
    float a = texel.a * vAlpha * uOpacity;
    // 预乘 alpha：输出 RGB 先乘 a，配合 GL_ONE / GL_ONE_MINUS_SRC_ALPHA
    gl_FragColor = vec4(texel.rgb * a, a);
}
)";

// 点/属性位置固定（P2）：内容 program 与遮罩 program 必须共用同一组属性下标，
// 否则每切一次 program 都要重设一次 glVertexAttribPointer。
const GLuint kAttrPos = 0;
const GLuint kAttrUv = 1;
const GLuint kAttrAlpha = 2;

/**
 * 遮罩写入用的 fragment shader（P2）。
 *
 * 两点关键，都与内容 shader 不同：
 *
 * 1) 只按**纹理 alpha** 决定覆盖范围。
 *    网格矩形里大量像素是透明的，若按几何三角形整片写模板，等于没裁。
 *    低于阈值的片元直接 discard，让 stencil 的 1 精确落在遮罩画面上。
 *
 * 2) **忽略顶点 alpha 与 uOpacity**（不乘 vAlpha / uOpacity）。
 *    Live2D 里「只作遮罩、不参与显示」的图层（如眼睛的遮罩网格 D_PSD_35/36）
 *    在模型数据里的 opacity 就是 0（真机日志实测 vis=0 op=0）。这**不是**要让它
 *    把内容全裁掉，而是标记「这层本身不画」。把 opacity 乘进来会让被它裁剪的
 *    8 个眼睛零件整片消失（实测眼睛变黑洞）。标准渲染器绘制遮罩时同样只用
 *    遮罩自身的形状与纹理透明度。
 *
 *    副作用（P2 已知边界）：遮罩不透明度做淡入淡出动画时不会跟随。
 *    当前阶段只渲染静态默认姿态，等接入参数驱动时再按需区分
 *    「遮罩形状」与「遮罩强度」。
 */
const char *kMaskFragmentShader = R"(
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;

void main() {
    // 阈值取 1/255 量级：低于它的像素视为完全透明，不写模板
    if (texture2D(uTex, vUv).a < 0.004) discard;
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
}
)";

/**
 * 编译并链接一个 program，属性下标在链接前用 glBindAttribLocation 固定。
 *
 * 为什么要显式绑：GLES 2.0 的 shader 源码里不能写 layout(location=N)，
 * 属性下标由链接器分配，两个 program 很可能不一样；而我们希望 stencil 阶段
 * 切换 program 时不用重设一遍描点指针。
 */
GLuint buildProgramWith(const char *vertexSrc, const char *fragmentSrc) {
    const GLuint vs = compileShader(GL_VERTEX_SHADER, vertexSrc);
    if (vs == 0) return 0;
    const GLuint fs = compileShader(GL_FRAGMENT_SHADER, fragmentSrc);
    if (fs == 0) {
        glDeleteShader(vs);
        return 0;
    }

    const GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glBindAttribLocation(program, kAttrPos, "aPos");
    glBindAttribLocation(program, kAttrUv, "aUv");
    glBindAttribLocation(program, kAttrAlpha, "aAlpha");
    glLinkProgram(program);

    // 链接后 shader 对象即可释放（program 已持有编译产物）
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint ok = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        GLint len = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &len);
        std::string log(static_cast<size_t>(len > 1 ? len : 1), '\0');
        glGetProgramInfoLog(program, len, nullptr, log.data());
        LOGE("program 链接失败: %s", log.c_str());
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

bool buildProgram() {
    const GLuint program = buildProgramWith(kVertexShader, kFragmentShader);
    if (program == 0) return false;

    g_program = program;
    g_attrPos = glGetAttribLocation(program, "aPos");
    g_attrUv = glGetAttribLocation(program, "aUv");
    g_attrAlpha = glGetAttribLocation(program, "aAlpha");
    g_uniformMvp = glGetUniformLocation(program, "uMvp");
    g_uniformOpacity = glGetUniformLocation(program, "uOpacity");
    g_uniformTex = glGetUniformLocation(program, "uTex");
    if (g_attrPos < 0 || g_attrUv < 0 || g_attrAlpha < 0 ||
        g_uniformMvp < 0 || g_uniformOpacity < 0 || g_uniformTex < 0) {
        LOGE("shader 变量定位失败 pos=%d uv=%d alpha=%d mvp=%d opacity=%d tex=%d",
             g_attrPos, g_attrUv, g_attrAlpha, g_uniformMvp, g_uniformOpacity, g_uniformTex);
        glDeleteProgram(g_program);
        g_program = 0;
        return false;
    }

    // 遮罩 program 只换 fragment 阶段，顶点阶段完全复用（属性下标一致）
    g_maskProgram = buildProgramWith(kVertexShader, kMaskFragmentShader);
    if (g_maskProgram == 0) {
        // 遮罩 program 失败不致命：退回「不裁剪直接画」，画面仍可用
        LOGW("遮罩 program 构建失败，本帧起所有 drawable 不裁剪");
        return true;
    }
    g_maskUniformMvp = glGetUniformLocation(g_maskProgram, "uMvp");
    g_maskUniformTex = glGetUniformLocation(g_maskProgram, "uTex");

    return true;
}

// 正交投影，按「模型 bbox 适配视口且保持宽高比」计算
void updateProjection() {
    if (g_viewW <= 0 || g_viewH <= 0 || g_vertices.empty()) return;

    const float modelW = g_maxX - g_minX;
    const float modelH = g_maxY - g_minY;
    if (modelW <= 0 || modelH <= 0) return;

    const float cx = (g_minX + g_maxX) * 0.5f;
    const float cy = (g_minY + g_maxY) * 0.5f;

    // 留 8% 边距，避免模型贴边
    const float margin = 1.08f;
    const float viewAspect = static_cast<float>(g_viewW) / static_cast<float>(g_viewH);
    const float modelAspect = modelW / modelH;

    float halfW;
    float halfH;
    if (viewAspect >= modelAspect) {
        // 视口更宽：以高度为准，左右留白
        halfH = modelH * 0.5f * margin;
        halfW = halfH * viewAspect;
    } else {
        // 视口更窄：以宽度为准，上下留白
        halfW = modelW * 0.5f * margin;
        halfH = halfW / viewAspect;
    }

    const float left = cx - halfW;
    const float right = cx + halfW;
    const float bottom = cy - halfH;
    const float top = cy + halfH;
    const float near = -1.0f;
    const float far = 1.0f;

    // 列主序（OpenGL 约定），glUniformMatrix4fv 不转置
    std::memset(g_mvp, 0, sizeof(g_mvp));
    g_mvp[0] = 2.0f / (right - left);
    g_mvp[5] = 2.0f / (top - bottom);
    g_mvp[10] = -2.0f / (far - near);
    g_mvp[12] = -(right + left) / (right - left);
    g_mvp[13] = -(top + bottom) / (top - bottom);
    g_mvp[14] = -(far + near) / (far - near);
    g_mvp[15] = 1.0f;

    LOGI("projection: view=%dx%d model=%.3fx%.3f half=%.3f/%.3f",
         g_viewW, g_viewH, modelW, modelH, halfW, halfH);
}

void releaseGl() {
    // 只能在「当前线程持有有效 EGL context」时调用（即 GL 线程），
    // 否则 glDelete* 作用不到原 context 的资源上，还会产生 GL_INVALID_OPERATION。
    if (g_vbo) { glDeleteBuffers(1, &g_vbo); g_vbo = 0; }
    if (g_ibo) { glDeleteBuffers(1, &g_ibo); g_ibo = 0; }
    if (!g_textures.empty()) {
        glDeleteTextures(static_cast<GLsizei>(g_textures.size()), g_textures.data());
        g_textures.clear();
    }
    if (g_program) { glDeleteProgram(g_program); g_program = 0; }
    if (g_maskProgram) { glDeleteProgram(g_maskProgram); g_maskProgram = 0; }
    g_attrPos = g_attrUv = g_attrAlpha = -1;
    g_uniformMvp = g_uniformOpacity = g_uniformTex = -1;
    g_maskUniformMvp = g_maskUniformTex = -1;
}

/**
 * 把 CPU 侧的 [TextureImage] 上传为 GL 纹理。GL 线程调用。
 *
 * @return 成功上传的张数
 */
int uploadTextures() {
    g_textures.assign(g_textureImages.size(), 0);
    int uploaded = 0;

    for (size_t i = 0; i < g_textureImages.size(); ++i) {
        const TextureImage &img = g_textureImages[i];
        if (img.width <= 0 || img.height <= 0 || img.rgba.empty()) {
            LOGW("跳过空纹理槽位 #%zu (%s)", i, img.name.c_str());
            continue;
        }

        GLuint tex = 0;
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);

        // GL_LINEAR：2D 立绘缩放时边缘更平滑（模型原图 2048²，屏幕远小于它，
        // 必须缩小采样，MIPMAP 在 P1 不做——静态单帧看不出差别，省一层生成开销）
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        // GL_CLAMP_TO_EDGE：图集里的 UV 恰好贴着 0/1 边界（实测 v 最大 1.0006），
        // REPEAT 会让边界像素从对面卷回来，出现一条错的细边
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // 行对齐：RGBA8 每行 4 字节对齐，无需 glPixelStorei 特殊设置（默认 4）
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, img.width, img.height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, img.rgba.data());

        const GLenum glError = glGetError();
        if (glError != GL_NO_ERROR) {
            LOGE("纹理上传失败: %s glError=0x%04x", img.name.c_str(), glError);
            glDeleteTextures(1, &tex);
            continue;
        }

        g_textures[i] = tex;
        ++uploaded;
        LOGI("纹理上传 #%zu: %s (%dx%d) -> GL id=%u",
             i, img.name.c_str(), img.width, img.height, tex);
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    return uploaded;
}

}  // namespace

// ===========================================================================
//  JNI 入口
// ===========================================================================
// 对应 Kotlin: com.exarry.arvtubelink.ui.live2d.Live2DRenderer
// 全部 external fun 都是**实例方法**（声明在 class 体内，非 companion object），
// 故第二个参数是 jobject（this），不是 jclass。
// ===========================================================================
extern "C" {

/**
 * 加载 assets 里的 .moc3 并组装 CPU 几何。
 *
 * @param assetManager 由 Kotlin 传入（AssetManager 实例）
 * @param mocPath      assets 内的相对路径，如 "live2d_demo/model/sample/sample_greeter_t03.moc3"
 * @return null = 成功；非 null = 失败原因（直接可展示的中文串）
 */
JNIEXPORT jstring JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jobject assetManager, jstring mocPath) {
    g_lastError.clear();

    if (!assetManager || !mocPath) {
        return env->NewStringUTF("nativeInit 参数为空");
    }

    AAssetManager *manager = AAssetManager_fromJava(env, assetManager);
    if (!manager) {
        return env->NewStringUTF("AAssetManager 获取失败");
    }

    const char *pathChars = env->GetStringUTFChars(mocPath, nullptr);
    if (!pathChars) return env->NewStringUTF("mocPath 读取失败");
    const std::string path(pathChars);
    env->ReleaseStringUTFChars(mocPath, pathChars);

    // 重复 init（例如重建 Renderer）先释放上一份模型
    g_model = nullptr;
    g_moc = nullptr;
    g_modelBuffer.free();
    g_mocBuffer.free();
    g_vertices.clear();
    g_indices.clear();

    csmSetLogFunction(&coreLog);
    csmSetLogLevel(2);  // 2 = warning 及以上

    // 与 GL 线程互斥：GL 线程可能正在绘制上一帧几何
    std::string err;
    bool ok;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        // 重复 init（例如重建 Renderer）先释放上一份模型。
        // 注意：这里不碰 GL 句柄——GL 资源属于 GL 线程，累积的旧 buffer 会随
        // GLSurfaceView 销毁（EGL context teardown）一起被系统回收。
        g_model = nullptr;
        g_moc = nullptr;
        g_modelBuffer.free();
        g_mocBuffer.free();
        g_vertices.clear();
        g_indices.clear();
        g_batches.clear();
        g_maskBatches.clear();
        g_maskRefs.clear();
        g_vertexSpans.clear();
        g_paramIndexById.clear();
        g_paramCount = 0;
        g_verticesDirty = false;
        g_vboVertexCount = 0;
        g_textureImages.clear();
        g_drawableTotal = 0;
        g_drawableDrawn = 0;

        csmSetLogFunction(&coreLog);
        csmSetLogLevel(2);  // 2 = warning 及以上

        // 先加载纹理：loadModel 需要知道哪些纹理槽位是有效的，
        // 才能把引用了空槽位的 drawable 踢掉（而不是贴错图）
        g_textureImages.clear();
        if (!loadTextures(manager, path, &err)) {
            ok = false;
        } else {
            ok = loadModel(manager, path.c_str(), &err);
        }
    }
    if (!ok) {
        g_lastError = err;
        LOGE("nativeInit 失败: %s", err.c_str());
        return env->NewStringUTF(err.c_str());
    }

    return nullptr;
}

/**
 * 参数驱动入口（P3）：写入一帧参数并让模型重算顶点。
 *
 * @param params [id, value, id, value, ...] 的扁平浮点数组（长度必须为偶数）。
 *               id 是 Purism Core 参数下标（Kotlin 侧经 nativeResolveParameterIds
 *               一次性解析并缓存，避免逐帧字符串查找），value 会被
 *               csmUpdateModel 按参数量程钳制。
 *
 * 线程：可由任意线程调用（Kotlin 推流协程）。只在锁内改 CPU 侧模型与顶点，
 * 置 g_verticesDirty；VBO 重传留给 GL 线程的 nativeDrawFrame —— EGL context
 * 只在那条线程上有效，跨线程调 glBufferSubData 会静默失效。
 *
 * 返回值：本帧实际写入的参数个数（负数 = 失败：-1 模型未就绪，
 * -2 数组长度为奇数）。
 */
JNIEXPORT jint JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeUpdateParameters(
        JNIEnv *env, jobject /*thiz*/, jfloatArray params) {
    if (!params) return -2;

    const jsize length = env->GetArrayLength(params);
    if (length <= 0 || (length % 2) != 0) return -2;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return -1;

    float *values = csmGetParameterValues(g_model);
    if (!values || g_paramCount <= 0) return -1;

    // 直通数组：避免 30Hz × N 参数的 GetFloatArrayRegion 额外拷贝
    jfloat *raw = env->GetFloatArrayElements(params, nullptr);
    if (!raw) return -1;

    int written = 0;
    int unknown = 0;
    for (jsize i = 0; i + 1 < length; i += 2) {
        const int index = static_cast<int>(raw[i]);
        if (index < 0 || index >= g_paramCount) {
            ++unknown;
            continue;
        }
        values[index] = raw[i + 1];
        ++written;
    }
    env->ReleaseFloatArrayElements(params, raw, JNI_ABORT);

    g_paramWritesTotal += written;
    g_paramUnknownTotal += unknown;

    if (written > 0) {
        // 先清动态标记再 update：Purism Core 用动态标记判断哪些 drawable 变了
        csmResetDrawableDynamicFlags(g_model);
        csmUpdateModel(g_model);
        refreshVertexPositionsLocked();
    }
    return written;
}

/**
 * 把参数名解析成下标，供 Kotlin 建缓存（P3）。
 *
 * @param names 参数名数组（如 "ParamEyeLOpen"）
 * @return 与 names 等长的下标数组；模型里不存在的参数返回 -1
 */
JNIEXPORT jintArray JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeResolveParameterIds(
        JNIEnv *env, jobject /*thiz*/, jobjectArray names) {
    if (!names) return nullptr;
    const jsize count = env->GetArrayLength(names);

    std::lock_guard<std::mutex> lock(g_mutex);
    std::vector<jint> out(static_cast<size_t>(count), -1);
    for (jsize i = 0; i < count; ++i) {
        auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        if (!name) continue;
        const char *chars = env->GetStringUTFChars(name, nullptr);
        if (chars) {
            const auto it = g_paramIndexById.find(chars);
            if (it != g_paramIndexById.end()) out[static_cast<size_t>(i)] = it->second;
            env->ReleaseStringUTFChars(name, chars);
        }
        env->DeleteLocalRef(name);
    }

    jintArray result = env->NewIntArray(count);
    if (result) {
        env->SetIntArrayRegion(result, 0, count, out.data());
    }
    return result;
}

/** 初始化 GL 资源：编译 shader、上传纹理、创建并填充 VBO/IBO。GL 线程调用。 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSurfaceCreated(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_glThreadId = std::this_thread::get_id();

    releaseGl();

    if (!buildProgram()) {
        LOGE("nativeSurfaceCreated: shader program 构建失败");
        return;
    }

    if (g_vertices.empty() || g_indices.empty()) {
        LOGW("nativeSurfaceCreated: 没有顶点数据（nativeInit 是否成功？）");
        return;
    }

    // 纹理必须在有 GL context 的线程上传（P1 的核心改动）
    const int uploaded = uploadTextures();
    if (uploaded == 0) {
        LOGE("nativeSurfaceCreated: 没有任何纹理上传成功，画面将全黑");
    }

    glGenBuffers(1, &g_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(g_vertices.size() * sizeof(Vertex)),
                 g_vertices.data(),
                 GL_STATIC_DRAW);
    g_vboVertexCount = static_cast<int>(g_vertices.size());
    // 上传即最新：清掉可能残留的脏标记，避免下一帧重复上传
    g_verticesDirty = false;

    glGenBuffers(1, &g_ibo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g_ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(g_indices.size() * sizeof(uint16_t)),
                 g_indices.data(),
                 GL_STATIC_DRAW);

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

    // 2D 静态画面：关深度、不开背面剔除（部分 artmesh 是 double-sided），
    // 打开预乘 alpha 混合；背景色与 App 深色主题一致
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    // Live2D 标准预乘混合：着色器已把 RGB 乘过 alpha（见 kFragmentShader）
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glClearColor(0.043f, 0.043f, 0.063f, 1.0f);

    // ---- P2：模板缓冲 ----
    // 先读实际位数再决定要不要清：没有模板缓冲时 glClear(GL_STENCIL_BUFFER_BIT)
    // 只是空操作，但清一次能保证「有缓冲但未定义」的初值为 0。
    GLint stencilBits = 0;
    glGetIntegerv(GL_STENCIL_BITS, &stencilBits);
    g_stencilBits = stencilBits;
    if (stencilBits > 0) {
        // 清模板必须先把 stencil 写掩码置满，否则沿用上一帧可能为 0 的掩码
        glStencilMask(0xFF);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);
    } else {
        LOGW("EGL 配置没有模板缓冲（GL_STENCIL_BITS=0）：Clipping Mask 不生效，"
             "检查 Live2DScreen 的 setEGLConfigChooser");
    }
    glDisable(GL_STENCIL_TEST);

    LOGI("nativeSurfaceCreated: vbo=%u ibo=%u verts=%zu idx=%zu batches=%zu textures=%d/%zu "
         "maskGeoms=%zu stencilBits=%d",
         g_vbo, g_ibo, g_vertices.size(), g_indices.size(), g_batches.size(),
         uploaded, g_textureImages.size(), g_maskBatches.size(), stencilBits);
}

/** 视口与投影矩阵。GL 线程调用。 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSurfaceChanged(
        JNIEnv * /*env*/, jobject /*thiz*/, jint width, jint height) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_viewW = width;
    g_viewH = height;
    glViewport(0, 0, width, height);
    updateProjection();
}

/**
 * 每帧绘制：清屏 + 逐 drawable 一个 draw call（纹理/opacity 随 drawable 变化）。
 * GL 线程调用。
 *
 * 为什么不能像 P0 那样一次画完：
 *  - 每张纹理要绑到独立的纹理单元；
 *  - uOpacity 是逐 drawable 的 uniform。
 * 70 个批次 = 70 次 draw call，对一张静态立绘完全可接受（真机实测远低于 1ms）。
 *
 * P2 的 stencil 流程（每个带遮罩的批次独立走一遍）：
 *   1) glStencilMask(0xFF) + glClear(GL_STENCIL_BUFFER_BIT)    清成 0；
 *   2) glColorMask(false×4)：只写模板不写颜色；
 *      glStencilFunc(GL_ALWAYS, 1, 0xFF) + glStencilOp(KEEP,KEEP,REPLACE)
 *      逐个遮罩画几何（片元 alpha < 阈值就 discard，不写 1）；
 *   3) glColorMask(true×4) + glStencilFunc(GL_EQUAL, 1, 0xFF) + glStencilOp(KEEP×3)
 *      画目标 drawable，只有模板为 1 的像素通过 → 圆弧切口；
 *   4) glDisable(GL_STENCIL_TEST)，交还后续批次。
 *
 * 多个遮罩取**并集**（连续 REPLACE 到同一个位），这是 Live2D 标准渲染器的语义；
 * 取交集会让「多个遮罩共同裁剪」的图层只剩极小一块。
 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeDrawFrame(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    glClear(GL_COLOR_BUFFER_BIT);

    // 锁内只做快照：批次表可能被主线程的 nativeInit 清空，
    // 一旦解锁后仍读 g_batches 就有悬垂风险，所以拷一份出来。
    // 批次表本身很小（70 项 × 32 字节 ≈ 2.2KB），拷贝开销可忽略。
    GLuint program;
    GLuint maskProgram;
    GLuint vbo;
    GLuint ibo;
    GLint attrPos;
    GLint attrUv;
    GLint attrAlpha;
    GLint uniformMvp;
    GLint uniformOpacity;
    GLint uniformTex;
    GLint maskUniformMvp;
    GLint maskUniformTex;
    int stencilBits;
    bool verticesDirty;
    std::vector<Batch> batches;
    std::vector<MaskBatch> maskBatches;
    std::vector<int> maskRefs;
    std::vector<GLuint> textures;
    float mvp[16];
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        program = g_program;
        maskProgram = g_maskProgram;
        vbo = g_vbo;
        ibo = g_ibo;
        attrPos = g_attrPos;
        attrUv = g_attrUv;
        attrAlpha = g_attrAlpha;
        uniformMvp = g_uniformMvp;
        uniformOpacity = g_uniformOpacity;
        uniformTex = g_uniformTex;
        maskUniformMvp = g_maskUniformMvp;
        maskUniformTex = g_maskUniformTex;
        stencilBits = g_stencilBits;
        if (program == 0 || vbo == 0 || ibo == 0 || g_batches.empty()) return;
        batches = g_batches;
        maskBatches = g_maskBatches;
        maskRefs = g_maskRefs;
        textures = g_textures;
        std::memcpy(mvp, g_mvp, sizeof(mvp));
        verticesDirty = g_verticesDirty;
        if (verticesDirty && !g_vertices.empty()) {
            // ---- P3：参数更新后重传顶点 ----
            // 常规路径：拓扑（索引、批次数、顶点总数）不变，整块 glBufferSubData。
            // 兜底路径：VBO 容量与 CPU 顶点数不一致（nativeInit 在 surfaceCreated
            // 之后重跑过），改走 glBufferData 重新分配，避免越界写显存。
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            const GLsizeiptr bytes =
                    static_cast<GLsizeiptr>(g_vertices.size() * sizeof(Vertex));
            if (g_vboVertexCount == static_cast<int>(g_vertices.size())) {
                glBufferSubData(GL_ARRAY_BUFFER, 0, bytes, g_vertices.data());
            } else {
                LOGW("顶点数变化 (%d -> %zu)，重建 VBO", g_vboVertexCount, g_vertices.size());
                glBufferData(GL_ARRAY_BUFFER, bytes, g_vertices.data(), GL_STATIC_DRAW);
                g_vboVertexCount = static_cast<int>(g_vertices.size());
            }
            g_verticesDirty = false;
        }
    }

    // 没有 8-bit 模板（EGL 配置选错）或遮罩 program 编译失败：
    // 退回 P1 的「不裁剪直接画」，画面仍能看，只是圆弧切口还在
    const bool stencilUsable = stencilBits >= 8 && maskProgram != 0;

    glUseProgram(program);
    glUniformMatrix4fv(uniformMvp, 1, GL_FALSE, mvp);

    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);

    // 交错布局：x, y, u, v, a 共 5 个 float = 20 字节
    // 属性下标由 buildProgramWith 里的 glBindAttribLocation 固定，遮罩 program 同下标，
    // 因此切换 program 时无需重设。
    const GLsizei stride = sizeof(Vertex);
    glEnableVertexAttribArray(static_cast<GLuint>(attrPos));
    glVertexAttribPointer(static_cast<GLuint>(attrPos), 2, GL_FLOAT, GL_FALSE,
                          stride, reinterpret_cast<const void *>(0));
    glEnableVertexAttribArray(static_cast<GLuint>(attrUv));
    glVertexAttribPointer(static_cast<GLuint>(attrUv), 2, GL_FLOAT, GL_FALSE,
                          stride, reinterpret_cast<const void *>(sizeof(float) * 2));
    glEnableVertexAttribArray(static_cast<GLuint>(attrAlpha));
    glVertexAttribPointer(static_cast<GLuint>(attrAlpha), 1, GL_FLOAT, GL_FALSE,
                          stride, reinterpret_cast<const void *>(sizeof(float) * 4));

    // 纹理单元 0 固定给 uTex（两个 program 的名字相同，切 program 后要重设）
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(uniformTex, 0);

    // 索引指针计算：Batch.firstIndex 是 uint16 下标序号，不是字节偏移
    const auto drawRange = [](int firstIndex, int indexCount) {
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_SHORT,
                       reinterpret_cast<const void *>(
                               static_cast<uintptr_t>(firstIndex) * sizeof(uint16_t)));
    };
    const auto textureOf = [&textures](int index) -> GLuint {
        if (index < 0 || index >= static_cast<int>(textures.size())) return 0;
        return textures[static_cast<size_t>(index)];
    };

    // 模板缓冲的写入/清除都受 glStencilMask 影响，统一置满
    glStencilMask(0xFF);

    int drawnBatches = 0;
    int clippedBatches = 0;
    for (const Batch &batch : batches) {
        const GLuint tex = textureOf(batch.textureIndex);
        if (tex == 0) continue;
        // ---- 无遮罩：完全沿用 P1 路径 ----
        if (!stencilUsable || batch.maskCount <= 0) {
            glBindTexture(GL_TEXTURE_2D, tex);
            glUniform1f(uniformOpacity, batch.opacity);
            drawRange(batch.firstIndex, batch.indexCount);
            ++drawnBatches;
            continue;
        }

        // ---- 第一步：清模板 + 写遮罩 ----
        glEnable(GL_STENCIL_TEST);
        glClear(GL_STENCIL_BUFFER_BIT);

        // 只写模板，不写颜色（否则遮罩网格会被画成一坨黑块）
        glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        glStencilFunc(GL_ALWAYS, 1, 0xFF);
        // GL_REPLACE：片元通过就写参考值 1；stencil 失败/depth 失败都保持不动
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);

        glUseProgram(maskProgram);
        glUniformMatrix4fv(maskUniformMvp, 1, GL_FALSE, mvp);
        glUniform1i(maskUniformTex, 0);

        int drawnMasks = 0;
        for (int k = 0; k < batch.maskCount; ++k) {
            const int ref = batch.maskFirst + k;
            if (ref < 0 || ref >= static_cast<int>(maskRefs.size())) continue;
            const int maskBatchIndex = maskRefs[static_cast<size_t>(ref)];
            if (maskBatchIndex < 0 ||
                maskBatchIndex >= static_cast<int>(maskBatches.size())) {
                continue;
            }
            const MaskBatch &mb = maskBatches[static_cast<size_t>(maskBatchIndex)];
            const GLuint maskTex = textureOf(mb.textureIndex);
            if (maskTex == 0) continue;

            glBindTexture(GL_TEXTURE_2D, maskTex);
            drawRange(mb.firstIndex, mb.indexCount);
            ++drawnMasks;
        }

        // 一个遮罩几何都没能画出来（纹理缺失等）：模板里全是 0，
        // 继续按 GL_EQUAL 1 会把这块内容整片裁没。退回不裁剪直接画，
        // 宁可留着平切也不要凭空少一块。
        if (drawnMasks == 0) {
            glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
            glDisable(GL_STENCIL_TEST);
            glUseProgram(program);
            glUniformMatrix4fv(uniformMvp, 1, GL_FALSE, mvp);
            glUniform1i(uniformTex, 0);
            glBindTexture(GL_TEXTURE_2D, tex);
            glUniform1f(uniformOpacity, batch.opacity);
            drawRange(batch.firstIndex, batch.indexCount);
            ++drawnBatches;
            continue;
        }

        // ---- 第二步：恢复颜色写入，按模板裁剪目标 ----
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        // 反转遮罩：模板为 0 的区域才是内容（Live2D 的 InvertedMask 语义）
        glStencilFunc(batch.invertedMask ? GL_NOTEQUAL : GL_EQUAL, 1, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);

        glUseProgram(program);
        glUniformMatrix4fv(uniformMvp, 1, GL_FALSE, mvp);
        glUniform1i(uniformTex, 0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glUniform1f(uniformOpacity, batch.opacity);
        drawRange(batch.firstIndex, batch.indexCount);

        // ---- 第三步：交还状态 ----
        glDisable(GL_STENCIL_TEST);
        ++clippedBatches;
        ++drawnBatches;
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    glDisableVertexAttribArray(static_cast<GLuint>(attrPos));
    glDisableVertexAttribArray(static_cast<GLuint>(attrUv));
    glDisableVertexAttribArray(static_cast<GLuint>(attrAlpha));
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glUseProgram(0);

    // 每帧都打日志会刷屏（RENDERMODE_WHEN_DIRTY 下其实只有几帧），
    // 这里只在第一次绘制时确认一次链路是否通
    static bool loggedOnce = false;
    if (!loggedOnce) {
        loggedOnce = true;
        LOGI("首帧绘制完成: batches=%d/%zu clipped=%d stencilBits=%d usable=%d",
             drawnBatches, batches.size(), clippedBatches, stencilBits,
             stencilUsable ? 1 : 0);
    }
}

/**
 * 释放原生资源。
 *
 * 调用线程不确定（Kotlin 侧在组合销毁时调 → 主线程；也可能来自 GL 线程）：
 *  - CPU 侧的 MOC/模型缓冲区与几何数组：任何线程都可安全释放（持锁）；
 *  - GL 侧的 VBO/IBO/program：glDelete* 必须由持有该 EGL context 的 GL 线程执行。
 *    若当前不是 GL 线程，就不调用 glDelete*，把清理交给 GLSurfaceView 销毁时
 *    的 context teardown（GPU 内存随 context 一起回收），避免跨线程误删。
 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeRelease(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);

    const bool onGlThread = (g_glThreadId == std::this_thread::get_id());
    if (onGlThread) {
        releaseGl();
    } else {
        // 只丢弃句柄记录，不跨线程调 GL
        g_vbo = 0;
        g_ibo = 0;
        g_program = 0;
        g_maskProgram = 0;
        g_textures.clear();
        g_attrPos = g_attrUv = g_attrAlpha = -1;
        g_uniformMvp = g_uniformOpacity = g_uniformTex = -1;
        g_maskUniformMvp = g_maskUniformTex = -1;
    }

    g_vertices.clear();
    g_indices.clear();
    g_batches.clear();
    g_maskBatches.clear();
    g_maskRefs.clear();
    g_vertexSpans.clear();
    g_paramIndexById.clear();
    g_paramCount = 0;
    g_verticesDirty = false;
    g_vboVertexCount = 0;
    g_textureImages.clear();
    g_vertices.shrink_to_fit();
    g_indices.shrink_to_fit();
    g_batches.shrink_to_fit();
    g_maskBatches.shrink_to_fit();
    g_maskRefs.shrink_to_fit();
    g_textureImages.shrink_to_fit();
    g_model = nullptr;
    g_moc = nullptr;
    g_modelBuffer.free();
    g_mocBuffer.free();
    LOGI("nativeRelease: 资源已释放 (glThread=%d)", onGlThread ? 1 : 0);
}

/** 诊断用：返回一行统计（drawable 数/批次数/纹理数/顶点数/遮罩数/包围盒）。 */
JNIEXPORT jstring JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeStats(
        JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    int uploadedTextures = 0;
    for (const TextureImage &img : g_textureImages) {
        if (img.width > 0) ++uploadedTextures;
    }
    int maskedBatches = 0;
    for (const Batch &batch : g_batches) {
        if (batch.maskCount > 0) ++maskedBatches;
    }
    char buffer[384];
    std::snprintf(buffer, sizeof(buffer),
                  "drawables=%d drawn=%d batches=%zu masked=%d maskGeoms=%zu "
                  "textures=%d/%zu verts=%zu idx=%zu stencil=%d "
                  "params=%d writes=%ld unknown=%ld "
                  "bounds=[%.3f,%.3f]x[%.3f,%.3f]",
                  g_drawableTotal, g_drawableDrawn, g_batches.size(), maskedBatches,
                  g_maskBatches.size(), uploadedTextures, g_textureImages.size(),
                  g_vertices.size(), g_indices.size(), g_stencilBits,
                  g_paramCount, g_paramWritesTotal, g_paramUnknownTotal,
                  g_minX, g_maxX, g_minY, g_maxY);
    return env->NewStringUTF(buffer);
}

}  // extern "C"
