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
//   - 投影包围盒固定，模型不会因头动而整体缩放（标准语义也是固定取景）；
//   - 不做物理/自动眨眼：本版本 Purism Core 的 csmUpdateModel 只做参数求值，
//     不含 physics3/pose3 的求解器。头发裙摆的二次运动需要另实现物理，
//     超出本阶段（pose3 已按下面的 P4 做了最小支持）。
//
//  P4 增补（姿势 pose3 / 动作 motion3 / 表情 exp3 / FPS）：
//   - **「4 只手」的根因是缺 pose3**：Sample 的 pose3.json 把手臂拆成两组互斥
//     部件（ArmRA/ArmRB、ArmLA/ArmLB），moc3 里这 4 个部件的默认不透明度
//     全是 1，动作文件的 PartOpacity 曲线也没给它们做互斥。标准渲染器靠
//     PoseManager 每帧后处理「每组只留一个可见」，我们此前完全没做，
//     于是两套手臂同时画出来 —— 这就是真机上看到的 4 只手。现在
//     applyPoseLocked() 在状态重建的最后一步执行。
//   - **可见集合不再在 init 定死**：drawFrame 逐帧读 csmIsVisible 决定画不画。
//     init 快照只代表默认姿态，动作/表情/pose 都能改变可见性；把「画不画」
//     写死在 init 会永久丢失后来才变可见的图层（手臂就是这一类）。
//   - **绘制顺序逐帧按 csmGetRenderOrders 重排**：动作会让 draw order 变化，
//     固定顺序在换动作后会出现透明部件叠加错乱。
//   - 逐帧状态重建顺序：复位默认值 → 动作 → 表情 → 面捕 → pose → update。
//     面捕放在 pose 之前是为了让「说话/眨眼」始终跟人，不被 idle 动作压掉。
//   - 顶点 alpha 固定 1.0，drawable opacity 只走 uOpacity 一条路：
//     早期版本两处都乘，实际是 opacity²，动作的淡入淡出会变得很陡。
//   - 动作时间轴：motion3 的三种段类型（线性/阶跃/反阶跃/三次贝塞尔）全部
//     实现，循环按 Meta.Loop + Meta.Duration 处理。没有实现淡入淡出权重
//     （FadeInTime/FadeOutTime），换动作是硬切。
//   - 表情：exp3 的 Overwrite/Add/Multiply 三种 Blend 都支持；只支持单个
//     表情同时生效（标准允许叠加多个）。
//   - FPS 由 GL 线程以 0.5s 窗口统计，Kotlin 侧轮询 nativeFps 显示。
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
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
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

// 极简只读 JSON：解析 model3.json / motion3.json / exp3.json / pose3.json。
// 自带实现而不是引 nlohmann/json —— 后者会给 .so 增加几百 KB。
#include "mini_json.h"

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
 * 顶点。P1 起带 UV，颜色改由纹理提供。
 *
 * V 轴在**上传前**就翻掉（而不是在 shader 里）：Live2D 的 UV 原点在图片
 * 左下角（V 向上为增），PNG 的行序是从上到下，而 glTexImage2D 默认把
 * 第一行当作 v=0（即图像底部）。三者叠加会让直接透传的 v 上下颠倒
 * （实测眼睛跑到手上）。这里选「上传前 1-v」，比在 shader 里再加一行
 * 更容易被 grep 到，也省一次逐像素减法。
 *
 * `a` 恒为 1.0，**不再承载 drawable 的 opacity**（P4 修正）：
 * 早期版本把 opacity 同时写进逐顶点 `a` 和 uOpacity，片段着色器里
 * `texel.a * vAlpha * uOpacity` 于是把同一个 opacity 乘了两遍（等价于平方）。
 * 满不透明时看不出问题，但动作的淡入淡出会被压成一条陡峭的曲线。
 * 现在逐 drawable 的 opacity 只走 uOpacity 一条路。
 */
struct Vertex {
    float x, y;     // 模型空间坐标（Purism Core 直接给出，单位 = canvas / ppu）
    float u, v;     // 纹理坐标；v 已按上述原因翻转为 1-v
    float a;        // 逐顶点 alpha，恒 1.0（opacity 走 uOpacity）
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

// ---------------------------------------------------------------------------
//  动作 / 表情 / 姿势（P4）
// ---------------------------------------------------------------------------
//
//  为什么姿势（pose3）是必需而不是可选：
//   Sample 的 pose3.json 把手臂拆成两组互斥部件：
//     [Part01ArmRA001, Part01ArmRB001]   ← 交叉抱臂 / 自然下垂 二选一
//     [Part01ArmLA001, Part01ArmLB001]
//   moc3 里这 4 个部件的默认不透明度全是 1，**动作文件里的 PartOpacity 曲线
//   也全是 1**。标准渲染器靠 PoseManager 在每帧后处理：每组只保留第一个
//   达到可见阈值的部件，其余归零。我们此前完全没做这一步，于是两套手臂
//   同时出现 —— 真机上的「4 只手」就是这个原因，不是批次或顶点出了问题。
//
//  动作（motion3.json）：解析为「参数曲线 + 部件不透明度曲线」。段格式为
//   [t0, v0, type, ...]；type 0=线性、1=三次贝塞尔、2=阶跃、3=反阶跃。
//  表达式（exp3.json）：{Id, Value, Blend}，Blend ∈ Overwrite/Add/Multiply。
//
//  所有权：这些结构在 nativeInit 里一次性构建，之后只读；播放状态在锁内写。

/** 一条动作曲线：目标 + 在 MotionData.segments 里的区间。 */
struct MotionCurve {
    int paramIndex = -1;     // Target=Parameter 时的参数下标
    int partIndex = -1;      // Target=PartOpacity 时的部件下标
    bool modelOpacity = false; // Target=Model / Id=Opacity：整体淡入淡出
    int first = 0;           // 在 MotionData.segments 中的起点
    int count = 0;           // 段值个数
};

struct MotionData {
    std::string name;                  // 文件名（诊断用）
    float duration = 0.0f;             // 秒
    bool loop = false;
    std::vector<MotionCurve> curves;
    std::vector<float> segments;       // 该动作全部段值拍平
};

struct MotionGroup {
    std::string name;                  // model3.json 的键，如 "Idle" / "Tap"
    std::vector<MotionData> motions;
};

std::vector<MotionGroup> g_motionGroups;

struct ExpressionParam {
    int paramIndex = -1;
    float value = 0.0f;
    int blend = 0; // 0=Overwrite, 1=Add, 2=Multiply
};

struct Expression {
    std::string name;
    std::vector<ExpressionParam> params;
};

std::vector<Expression> g_expressions;

/** 姿势组：每组内互斥，只保留一个可见部件（csm 部件下标）。 */
std::vector<std::vector<int>> g_poseGroups;

/** 部件 id -> csm 部件下标（初始化时建立，构造姿势组用）。 */
std::unordered_map<std::string, int> g_partIndexById;

/** 部件默认不透明度快照（init 后立刻拷贝，逐帧复位用）。 */
std::vector<float> g_partDefaults;

/** 参数默认值快照（逐帧复位用）。 */
std::vector<float> g_paramDefaults;

/** 当前激活的动作 / 表情（-1 = 无）。 */
int g_activeGroup = -1;
int g_activeMotion = -1;
int g_activeExpression = -1;

/** 动作已播放时长（秒，未取模）；循环播放时按 duration 取模。 */
double g_motionTime = 0.0;
bool g_motionActive = false;

/** 上一次推进动作时钟的 steady_clock 秒数（GL 线程独占）。 */
double g_lastTickSec = 0.0;

/** 整体不透明度（动作里 Target=Model/Opacity 的曲线），乘到每个批次上。 */
float g_modelOpacity = 1.0f;

/** 面捕推来的参数值（NaN = 该参数本帧不覆盖）。下标 = 参数下标。 */
std::vector<float> g_faceValues;
bool g_faceDirty = false;

/** 模型状态需要重算（参数/动作/表情/姿势任一变化）。 */
bool g_modelDirty = true;

// 前置声明：loadModel 末尾要立刻应用一次初始状态（动作 / pose），
// 而这两个函数的实现放在它后面（依赖运动数据结构 + 顶点刷新）。
void applyModelStateLocked();
void refreshVertexPositionsLocked();

/**
 * 最近一次统计的渲染帧率（GL 线程写，任意线程读）。
 *
 * 统计窗口 0.5s：比 1s 窗口响应快，又足够平掉单帧抖动；用 atomic<float>
 * 而非普通 float，是因为 Kotlin 侧轮询频率与 GL 线程完全无关。
 */
std::atomic<float> g_fps{0.0f};

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
    int firstIndex = 0;     // 在 g_indices 中的起始位置
    int indexCount = 0;     // 索引个数
    int textureIndex = 0;   // 对应 g_textures 的下标
    int drawableIndex = -1; // csm 的 drawable 下标（逐帧查可见性 / render order / opacity）

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
//  动作 / 表情 / 姿势资源加载（P4）
// ---------------------------------------------------------------------------
//  全部在 nativeInit 的锁内、GL 侧还没有资源时执行；解析失败的条目单独跳过，
//  绝不因为某个 .motion3.json 损坏就让模型整体加载失败。

/** 把 assets 里的文本文件读成 string（失败返回 false，不区分原因）。 */
bool readAssetText(AAssetManager *manager, const std::string &path, std::string *out) {
    std::vector<uint8_t> bytes;
    if (!readAssetBytes(manager, path, &bytes)) return false;
    out->assign(reinterpret_cast<const char *>(bytes.data()), bytes.size());
    return true;
}

/**
 * 解析一条 motion3 曲线的 Segments 到 [flat] 尾部，追加一个 MotionCurve。
 *
 * 段值布局（标准 Motion3 格式）：第 0/1 个是起点 t0/v0，之后每个段先是一个
 * type，再按类型带 2 或 6 个值：
 *   type 0（线性）/ 2（阶跃）/ 3（反阶跃）→ t, v
 *   type 1（三次贝塞尔）                 → c1t, c1v, c2t, c2v, t, v
 */
void appendCurve(const mjson::Value &segments, int paramIndex, int partIndex,
                 bool modelOpacity, MotionData *motion) {
    if (!segments.isArray() || segments.items.size() < 2) return;

    MotionCurve curve;
    curve.paramIndex = paramIndex;
    curve.partIndex = partIndex;
    curve.modelOpacity = modelOpacity;
    curve.first = static_cast<int>(motion->segments.size());

    for (const mjson::Value &v : segments.items) {
        motion->segments.push_back(static_cast<float>(v.numberOr(0.0)));
    }
    curve.count = static_cast<int>(motion->segments.size()) - curve.first;
    if (curve.count >= 2) motion->curves.push_back(curve);
}

/**
 * 解析一份 motion3.json。
 *
 * 参数 / 部件名 -> 下标的映射在 model 初始化完成后才能建立，因此这里只在
 * 名字能查到索引时才建曲线；查不到的曲线直接丢弃（该动作只对别的模型有意义）。
 */
bool parseMotionFile(AAssetManager *manager, const std::string &path, MotionData *out) {
    std::string text;
    if (!readAssetText(manager, path, &text)) {
        LOGW("动作文件读取失败: %s", path.c_str());
        return false;
    }

    mjson::Value root;
    if (!mjson::parse(text, &root)) {
        LOGW("动作文件 JSON 解析失败: %s", path.c_str());
        return false;
    }

    out->name = path;
    if (const mjson::Value *meta = root.find("Meta")) {
        if (const mjson::Value *duration = meta->find("Duration")) {
            out->duration = static_cast<float>(duration->numberOr(0.0));
        }
        if (const mjson::Value *loop = meta->find("Loop")) {
            out->loop = loop->boolOr(false);
        }
    }
    if (out->duration <= 0.0f) out->duration = 1.0f;

    const mjson::Value *curves = root.find("Curves");
    if (!curves || !curves->isArray()) {
        LOGW("动作文件没有 Curves: %s", path.c_str());
        return false;
    }

    for (const mjson::Value &curve : curves->items) {
        const mjson::Value *target = curve.find("Target");
        const mjson::Value *id = curve.find("Id");
        const mjson::Value *segments = curve.find("Segments");
        if (!target || !id || !segments) continue;

        const std::string &targetName = target->stringOrEmpty();
        const std::string &idName = id->stringOrEmpty();

        if (targetName == "Model" && idName == "Opacity") {
            appendCurve(*segments, -1, -1, /*modelOpacity=*/true, out);
            continue;
        }
        if (targetName == "Parameter") {
            const auto it = g_paramIndexById.find(idName);
            if (it == g_paramIndexById.end()) continue;
            appendCurve(*segments, it->second, -1, false, out);
            continue;
        }
        if (targetName == "PartOpacity") {
            const auto it = g_partIndexById.find(idName);
            if (it == g_partIndexById.end()) continue;
            appendCurve(*segments, -1, it->second, false, out);
            continue;
        }
        // Target=Part（部件可见性）在 Sample 里未出现，忽略
    }

    return true;
}

/** 解析 model3.json 的 Motions 段：{ 组名: [ {File}, ... ] }。 */
void loadMotions(AAssetManager *manager, const std::string &jsonPath,
                 const std::string &dir) {
    std::string text;
    if (!readAssetText(manager, jsonPath, &text)) {
        LOGW("model3.json 读取失败，动作不可用: %s", jsonPath.c_str());
        return;
    }

    mjson::Value root;
    if (!mjson::parse(text, &root)) {
        LOGW("model3.json JSON 解析失败: %s", jsonPath.c_str());
        return;
    }

    const mjson::Value *fileRefs = root.find("FileReferences");
    const mjson::Value *motions = fileRefs ? fileRefs->find("Motions") : nullptr;
    if (!motions || !motions->isObject()) {
        LOGW("model3.json 没有 Motions 段");
        return;
    }

    int loaded = 0;
    for (size_t gi = 0; gi < motions->keys.size(); ++gi) {
        const mjson::Value &list = motions->items[gi];
        if (!list.isArray()) continue;

        MotionGroup group;
        group.name = motions->keys[gi];
        for (const mjson::Value &entry : list.items) {
            const mjson::Value *file = entry.find("File");
            if (!file) continue;
            MotionData motion;
            if (parseMotionFile(manager, dir + file->stringOrEmpty(), &motion)) {
                group.motions.push_back(std::move(motion));
                ++loaded;
            }
        }
        if (!group.motions.empty()) {
            LOGI("动作组就绪: %s (%zu 个)", group.name.c_str(), group.motions.size());
            g_motionGroups.push_back(std::move(group));
        }
    }
    LOGI("动作加载完成: %d 个文件 / %zu 组", loaded, g_motionGroups.size());
}

/** 解析一条 exp3.json 的参数表。 */
bool parseExpressionFile(AAssetManager *manager, const std::string &path,
                         const std::string &name, Expression *out) {
    std::string text;
    if (!readAssetText(manager, path, &text)) {
        LOGW("表情文件读取失败: %s", path.c_str());
        return false;
    }

    mjson::Value root;
    if (!mjson::parse(text, &root)) {
        LOGW("表情文件 JSON 解析失败: %s", path.c_str());
        return false;
    }

    const mjson::Value *params = root.find("Parameters");
    if (!params || !params->isArray()) return false;

    out->name = name;
    for (const mjson::Value &p : params->items) {
        const mjson::Value *id = p.find("Id");
        const mjson::Value *value = p.find("Value");
        if (!id || !value) continue;

        const auto it = g_paramIndexById.find(id->stringOrEmpty());
        if (it == g_paramIndexById.end()) continue;

        ExpressionParam ep;
        ep.paramIndex = it->second;
        ep.value = static_cast<float>(value->numberOr(0.0));

        const mjson::Value *blend = p.find("Blend");
        const std::string &blendName = blend ? blend->stringOrEmpty() : std::string();
        if (blendName == "Add") ep.blend = 1;
        else if (blendName == "Multiply") ep.blend = 2;
        else ep.blend = 0;

        out->params.push_back(ep);
    }
    return !out->params.empty();
}

/** 解析 model3.json 的 Expressions 段。 */
void loadExpressions(AAssetManager *manager, const std::string &jsonPath,
                     const std::string &dir) {
    std::string text;
    if (!readAssetText(manager, jsonPath, &text)) return;

    mjson::Value root;
    if (!mjson::parse(text, &root)) return;

    const mjson::Value *fileRefs = root.find("FileReferences");
    const mjson::Value *list = fileRefs ? fileRefs->find("Expressions") : nullptr;
    if (!list || !list->isArray()) {
        LOGW("model3.json 没有 Expressions 段");
        return;
    }

    for (const mjson::Value &entry : list->items) {
        const mjson::Value *name = entry.find("Name");
        const mjson::Value *file = entry.find("File");
        if (!name || !file) continue;

        Expression exp;
        if (parseExpressionFile(manager, dir + file->stringOrEmpty(),
                                name->stringOrEmpty(), &exp)) {
            g_expressions.push_back(std::move(exp));
        }
    }
    LOGI("表情加载完成: %zu 个", g_expressions.size());
}

/**
 * 解析 pose3.json 的 Groups。
 *
 * 结构：{ "Groups": [ [ { "Id": "Part01ArmRA001", "Link": [] }, ... ], ... ] }
 * 每组是互斥的部件候选；Link 里列出的组会跟随本组一起切换（Sample 未使用）。
 *
 * 这里把每个 Id 解析成 csm 部件下标；查不到就跳过该项，整组不足 2 项时
 * 也不丢弃（单项组等价于「强制该部件可见」）。
 */
void loadPose(AAssetManager *manager, const std::string &jsonPath) {
    std::string text;
    if (!readAssetText(manager, jsonPath, &text)) {
        LOGW("pose3.json 读取失败，手臂互斥不生效: %s", jsonPath.c_str());
        return;
    }

    mjson::Value root;
    if (!mjson::parse(text, &root)) {
        LOGW("pose3.json JSON 解析失败: %s", jsonPath.c_str());
        return;
    }

    const mjson::Value *groups = root.find("Groups");
    if (!groups || !groups->isArray()) return;

    for (const mjson::Value &group : groups->items) {
        if (!group.isArray()) continue;
        std::vector<int> parts;
        for (const mjson::Value &item : group.items) {
            const mjson::Value *id = item.find("Id");
            if (!id) continue;
            const auto it = g_partIndexById.find(id->stringOrEmpty());
            if (it != g_partIndexById.end()) parts.push_back(it->second);
        }
        if (!parts.empty()) g_poseGroups.push_back(std::move(parts));
    }
    LOGI("姿势组加载完成: %zu 组", g_poseGroups.size());
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

    // ---- P4：参数 / 部件 id 表与默认值快照 ----
    // 参数表供动作曲线查下标；部件表供 pose3 与 PartOpacity 曲线查下标。
    // 两张表都在 csmUpdateModel 之前建立，因此读到的 values 就是模型默认值。
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

        const float *values = csmGetParameterValues(g_model);
        g_paramDefaults.assign(static_cast<size_t>(std::max(0, g_paramCount)), 0.0f);
        if (values) {
            for (int i = 0; i < g_paramCount; ++i) g_paramDefaults[static_cast<size_t>(i)] = values[i];
        }
        // 面捕覆盖数组：NaN = 该参数本帧无输入，因此初始化为全 NaN
        g_faceValues.assign(static_cast<size_t>(std::max(0, g_paramCount)),
                            std::numeric_limits<float>::quiet_NaN());
    }
    {
        const int partCount = csmGetPartCount(g_model);
        const char **partIds = csmGetPartIds(g_model);
        const float *partOpacities = csmGetPartOpacities(g_model);
        g_partIndexById.clear();
        g_partDefaults.assign(static_cast<size_t>(std::max(0, partCount)), 1.0f);
        if (partIds && partCount > 0) {
            g_partIndexById.reserve(static_cast<size_t>(partCount) * 2);
            for (int i = 0; i < partCount; ++i) {
                if (partIds[i]) g_partIndexById.emplace(partIds[i], i);
            }
        }
        if (partOpacities) {
            for (int i = 0; i < partCount; ++i) {
                g_partDefaults[static_cast<size_t>(i)] = partOpacities[i];
            }
        }
        LOGI("部件表: count=%d（pose 用）", partCount);
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
            // a 恒 1.0：opacity 只走 uOpacity，避免二次相乘（见 Vertex 注释）
            g_vertices.push_back(Vertex{x, y, u, flippedV, 1.0f});
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
        // ⚠️ 这里**刻意不再按 dynamicFlags/csmIsVisible 过滤**（P4 修正）：
        // 可见性是逐帧量，动作 / 表情 / pose 都能让一个初始不可见的 drawable
        // 之后变可见（Sample 的 Part01ArmRB001 就属于这一类）。init 时按当时的
        // 可见性把它们剔出批次表，等于永远补不回来 —— 之前「4 只手」里被
        // 多画出来的两只手，正是这种可见性快照与真实状态脱节的表现。
        // 现在的做法：所有有几何的 drawable 都进批次表，逐帧在 drawFrame 里
        // 用 csmGetDrawableDynamicFlags 判定 csmIsVisible，必要时直接跳过。
        // 内容批次与遮罩批次的拓扑仍然在 init 定死（顶点数不随参数变），
        // 但「画不画」完全交给逐帧状态。

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
        batch.drawableIndex = idx;
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

    // ---- P4：动作 / 表情 / 姿势 ----
    // 必须在参数表与部件表建好之后解析：二者的曲线都要把名字翻成下标。
    {
        const std::string jsonPath = modelJsonPathFor(mocPath);
        const std::string dir = dirOf(mocPath);
        g_motionGroups.clear();
        g_expressions.clear();
        g_poseGroups.clear();
        if (!jsonPath.empty()) {
            loadMotions(manager, jsonPath, dir);
            loadExpressions(manager, jsonPath, dir);
            // pose3 路径不像 Motions/Expressions 那样由我们自己解析：
            // 直接在 model3.json 里找 Physics/Pose 同级的 "Pose" 字段。
            std::string jsonText;
            if (readAssetText(manager, jsonPath, &jsonText)) {
                mjson::Value root;
                if (mjson::parse(jsonText, &root)) {
                    const mjson::Value *refs = root.find("FileReferences");
                    const mjson::Value *pose = refs ? refs->find("Pose") : nullptr;
                    if (pose) loadPose(manager, dir + pose->stringOrEmpty());
                }
            }
        }

        // 默认播第一组动作的第 0 个（Sample 是 Idle），并立刻应用一次
        g_activeGroup = g_motionGroups.empty() ? -1 : 0;
        g_activeMotion = (g_activeGroup >= 0 && !g_motionGroups[0].motions.empty()) ? 0 : -1;
        g_motionActive = g_activeMotion >= 0;
        g_motionTime = 0.0;
        g_lastTickSec = 0.0;
        g_activeExpression = -1;
        g_modelDirty = true;

        applyModelStateLocked();
        csmUpdateModel(g_model);
        refreshVertexPositionsLocked();
        LOGI("初始状态: motion=%s/%d exp=-1 pose=%zu modelOpacity=%.2f",
             g_activeGroup >= 0 ? g_motionGroups[0].name.c_str() : "none",
             g_activeMotion, g_poseGroups.size(), g_modelOpacity);
    }
    return true;
}

// ---------------------------------------------------------------------------
//  动作曲线求值（P4）
// ---------------------------------------------------------------------------
/**
 * 在一条已解析的曲线上按时间采样。
 *
 * 段布局回顾（见 appendCurve）：flat[0..1] = 起点 (t0, v0)，之后每段
 * 先读 type，再按 0/2/3 → (t, v)、1 → (c1t, c1v, c2t, c2v, t, v) 取数。
 *
 * 三种插值的语义与标准一致：
 *  - 0 线性：在当前段首尾之间线性插值（贝塞尔控制点退化时也走这里）；
 *  - 2 阶跃：保持段首值，到段尾瞬间跳到新值；
 *  - 3 反阶跃：先在段内保持段尾值，到最后一点才退回段首值。
 *
 * @param base  曲线在 flat 里的起点（= MotionCurve.first）
 * @param count 该曲线的值个数
 * @param t     采样时间（秒）
 */
float sampleCurve(const float *flat, int count, float t) {
    if (count < 2) return 0.0f;

    const float t0 = flat[0];
    const float v0 = flat[1];
    if (t <= t0) return v0;

    int i = 2;
    float segStartT = t0;
    float segStartV = v0;
    while (i < count) {
        const int type = static_cast<int>(flat[i++]);
        if (type == 1) {
            // 三次贝塞尔：起点(segStartT,segStartV) 控制点1 控制点2 终点
            if (i + 5 >= count) break;
            const float c1t = flat[i], c1v = flat[i + 1];
            const float c2t = flat[i + 2], c2v = flat[i + 3];
            const float endT = flat[i + 4], endV = flat[i + 5];
            i += 6;

            if (t <= endT) {
                // 时间轴是单调的，用二分求参数 u 使 x(u) = t
                float lo = 0.0f, hi = 1.0f, u = 0.0f;
                for (int k = 0; k < 24; ++k) {
                    u = (lo + hi) * 0.5f;
                    const float mt = 1.0f - u;
                    const float x = mt * mt * mt * segStartT +
                                    3.0f * mt * mt * u * c1t +
                                    3.0f * mt * u * u * c2t +
                                    u * u * u * endT;
                    if (x < t) lo = u; else hi = u;
                }
                const float mt = 1.0f - u;
                return mt * mt * mt * segStartV +
                       3.0f * mt * mt * u * c1v +
                       3.0f * mt * u * u * c2v +
                       u * u * u * endV;
            }
            segStartT = endT;
            segStartV = endV;
        } else {
            if (i + 1 >= count) break;
            const float endT = flat[i], endV = flat[i + 1];
            i += 2;

            if (t <= endT) {
                if (type == 2) return segStartV;       // 阶跃：段内保持段首
                if (type == 3) return (t >= endT) ? segStartV : endV; // 反阶跃
                const float span = endT - segStartT;
                if (span <= 1e-6f) return endV;
                const float u = (t - segStartT) / span;
                return segStartV + (endV - segStartV) * u;
            }
            segStartT = endT;
            segStartV = endV;
        }
    }
    return segStartV; // 已过末点：保持末值
}

/**
 * 应用姿势（pose3）互斥语义到部件不透明度。
 *
 * 标准 Pose 后处理 的做法：每组按顺序检查候选部件，第一个「输入不透明度
 * 高于阈值」的部件视为当前姿态，其余候选归零。这样 m14/m05 这类手势动作
 * 把 Part01ArmRB001 设为 1 时，Part01ArmRA001 被压成 0，手臂就不会重叠。
 *
 * 可见的那个部件**保留自己的输入值**而不是硬写 1.0：动作可以给部件做
 * 淡入淡出（PartOpacity 曲线从 0 渐增），硬写 1 会让渐入变成瞬间弹出。
 */
void applyPoseLocked() {
    if (g_poseGroups.empty() || !g_model) return;

    float *partOpacities = csmGetPartOpacities(g_model);
    if (!partOpacities) return;

    constexpr float kVisibleThreshold = 0.001f;

    for (const std::vector<int> &group : g_poseGroups) {
        int visible = -1;
        for (int partIndex : group) {
            if (partIndex < 0 || partIndex >= static_cast<int>(g_partDefaults.size())) {
                continue;
            }
            // 用「动作/默认」写进来的输入值判定，而不是被 pose 改过的输出值
            if (partOpacities[partIndex] > kVisibleThreshold) {
                visible = partIndex;
                break;
            }
        }
        // 一个都不亮时把第一个候选设为可见，避免整只手消失
        if (visible < 0 && !group.empty()) visible = group.front();

        for (int partIndex : group) {
            if (partIndex < 0 || partIndex >= static_cast<int>(g_partDefaults.size())) {
                continue;
            }
            // 可见者保留输入值（支持动作淡入），其余归零
            if (partIndex != visible) partOpacities[partIndex] = 0.0f;
        }
    }
}

/** 把参数 / 部件不透明度复位到模型默认值（每帧重建状态的起点）。 */
void resetParametersLocked() {
    if (!g_model) return;

    float *values = csmGetParameterValues(g_model);
    if (values) {
        for (size_t i = 0; i < g_paramDefaults.size(); ++i) values[i] = g_paramDefaults[i];
    }
    float *partOpacities = csmGetPartOpacities(g_model);
    if (partOpacities) {
        for (size_t i = 0; i < g_partDefaults.size(); ++i) partOpacities[i] = g_partDefaults[i];
    }
}

/** 应用当前激活表情（叠加到已复位的默认参数上）。 */
void applyExpressionLocked() {
    if (g_activeExpression < 0 ||
        g_activeExpression >= static_cast<int>(g_expressions.size())) {
        return;
    }
    float *values = csmGetParameterValues(g_model);
    if (!values) return;

    for (const ExpressionParam &p : g_expressions[static_cast<size_t>(g_activeExpression)].params) {
        if (p.paramIndex < 0 || p.paramIndex >= g_paramCount) continue;
        float &dst = values[p.paramIndex];
        switch (p.blend) {
            case 1: dst += p.value; break;
            case 2: dst *= p.value; break;
            default: dst = p.value; break;
        }
    }
}

/**
 * 应用当前动作到参数 / 部件不透明度，并返回整体不透明度。
 *
 * 时间由调用方推进（g_motionTime）。Target=Model/Opacity 的曲线决定整体
 * 不透明度（Sample 的 idle 会 0→1→0 淡入淡出）。
 */
void applyMotionLocked(float *modelOpacity) {
    *modelOpacity = 1.0f;
    if (!g_motionActive || g_activeGroup < 0 ||
        g_activeGroup >= static_cast<int>(g_motionGroups.size())) {
        return;
    }

    const MotionGroup &group = g_motionGroups[static_cast<size_t>(g_activeGroup)];
    if (g_activeMotion < 0 || g_activeMotion >= static_cast<int>(group.motions.size())) return;
    const MotionData &motion = group.motions[static_cast<size_t>(g_activeMotion)];

    float t = static_cast<float>(g_motionTime);
    if (motion.loop && motion.duration > 0.0f) {
        t = std::fmod(t, motion.duration);
    } else if (t > motion.duration) {
        t = motion.duration;
    }

    float *values = csmGetParameterValues(g_model);
    float *partOpacities = csmGetPartOpacities(g_model);

    for (const MotionCurve &curve : motion.curves) {
        const float v = sampleCurve(motion.segments.data() + curve.first, curve.count, t);
        if (curve.modelOpacity) {
            *modelOpacity = std::max(0.0f, std::min(1.0f, v));
        } else if (curve.paramIndex >= 0 && values && curve.paramIndex < g_paramCount) {
            values[curve.paramIndex] = v;
        } else if (curve.partIndex >= 0 && partOpacities &&
                   curve.partIndex < static_cast<int>(g_partDefaults.size())) {
            partOpacities[curve.partIndex] = v;
        }
    }
}

/** 面捕参数覆盖（最高优先级：手臂/身体等由动作驱动，面部由人脸驱动）。 */
void applyFaceValuesLocked() {
    if (g_faceValues.empty()) return;
    float *values = csmGetParameterValues(g_model);
    if (!values) return;

    for (size_t i = 0; i < g_faceValues.size(); ++i) {
        const float v = g_faceValues[i];
        if (std::isnan(v)) continue; // 该参数本帧无输入
        values[i] = v;
    }
}

/**
 * 重建整帧模型状态：复位 → 动作 → 表情 → 面捕 → 姿势 → csmUpdateModel。
 *
 * 顺序是有意的：
 *  1) 复位成模型默认值（动作曲线不是每个参数都覆盖）；
 *  2) 动作写参数与部件不透明度；
 *  3) 表情叠加在动作结果之上（标准也是 Expression 在 Motion 之后）；
 *  4) 面捕覆盖面部参数 —— 保证「说话/眨眼」始终跟人，不被 idle 动作压掉；
 *  5) pose 在最后判定互斥，避免动作写进来的 1 又被覆盖回去。
 *
 * 调用约束：**必须持 g_mutex** 且 g_model 非空。
 */
void applyModelStateLocked() {
    if (!g_model) return;

    resetParametersLocked();
    float modelOpacity = 1.0f;
    applyMotionLocked(&modelOpacity);
    applyExpressionLocked();
    applyFaceValuesLocked();
    applyPoseLocked();

    g_modelOpacity = modelOpacity;
    g_modelDirty = false;
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
    if (!positions) return;

    const int vertexTotal = static_cast<int>(g_vertices.size());
    for (const VertexSpan &span : g_vertexSpans) {
        const int di = span.drawableIndex;
        if (di < 0 || di >= drawableCount) continue;
        const csmVector2 *pos = positions[di];
        if (!pos) continue;

        // 防御：Purism Core 万一改了顶点数，按两者较小值抄，
        // 绝不越过本 drawable 的区间写到邻居上
        const int n = std::min(span.vertexCount, std::max(0, vertexTotal - span.firstVertex));
        Vertex *dst = g_vertices.data() + span.firstVertex;
        for (int v = 0; v < n; ++v) {
            dst[v].x = pos[v].X;
            dst[v].y = pos[v].Y;
            // a 恒 1.0：逐 drawable 的 opacity 由 drawFrame 的 uOpacity 提供
            dst[v].a = 1.0f;
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
        g_paramDefaults.clear();
        g_partIndexById.clear();
        g_partDefaults.clear();
        g_motionGroups.clear();
        g_expressions.clear();
        g_poseGroups.clear();
        g_faceValues.clear();
        g_faceDirty = false;
        g_activeGroup = -1;
        g_activeMotion = -1;
        g_activeExpression = -1;
        g_motionActive = false;
        g_motionTime = 0.0;
        g_lastTickSec = 0.0;
        g_modelOpacity = 1.0f;
        g_modelDirty = true;
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
 * 参数驱动入口（P3/P4）：写入一帧面捕参数。
 *
 * @param params [id, value, id, value, ...] 的扁平浮点数组（长度必须为偶数）。
 *               id 是 Purism Core 参数下标（Kotlin 侧经 nativeResolveParameterIds
 *               一次性解析并缓存，避免逐帧字符串查找）。
 *
 * P4 语义变化：这里**不再直接写 csmGetParameterValues**，而是写进
 * g_faceValues 缓冲并置脏。原因是逐帧状态重建（reset → motion → expression
 * → face → pose → csmUpdateModel）会把参数先复位成默认值；如果面捕直接写
 * values，下一次重建就被冲掉了。现在面捕是重建流程里优先级最高的一层。
 *
 * 线程：可由任意线程调用（Kotlin 推流协程）。只改 CPU 侧缓冲，真正的求值与
 * VBO 重传发生在 GL 线程（EGL context 只在那条线程上有效）。
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
    if (g_faceValues.empty()) return -1;

    // 直通数组：避免 30Hz × N 参数的 GetFloatArrayRegion 额外拷贝
    jfloat *raw = env->GetFloatArrayElements(params, nullptr);
    if (!raw) return -1;

    int written = 0;
    int unknown = 0;
    for (jsize i = 0; i + 1 < length; i += 2) {
        const int index = static_cast<int>(raw[i]);
        if (index < 0 || index >= g_paramCount ||
            index >= static_cast<int>(g_faceValues.size())) {
            ++unknown;
            continue;
        }
        g_faceValues[static_cast<size_t>(index)] = raw[i + 1];
        ++written;
    }
    env->ReleaseFloatArrayElements(params, raw, JNI_ABORT);

    g_paramWritesTotal += written;
    g_paramUnknownTotal += unknown;

    if (written > 0) g_faceDirty = true;
    return written;
}

/**
 * 播放动作（P4）。
 *
 * @param group 动作组下标（对应 model3.json Motions 的键顺序，见 nativeListMotions）
 * @param index 组内动作下标；-1 = 该组第 0 个
 * @return 实际播放的动作下标（负数 = 失败）
 */
JNIEXPORT jint JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativePlayMotion(
        JNIEnv * /*env*/, jobject /*thiz*/, jint group, jint index) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (group < 0 || group >= static_cast<jint>(g_motionGroups.size())) return -1;

    const MotionGroup &g = g_motionGroups[static_cast<size_t>(group)];
    if (g.motions.empty()) return -1;

    jint target = index;
    if (target < 0 || target >= static_cast<jint>(g.motions.size())) target = 0;

    g_activeGroup = group;
    g_activeMotion = target;
    g_motionTime = 0.0;
    g_lastTickSec = 0.0;
    g_motionActive = true;
    g_modelDirty = true;

    LOGI("播放动作: %s[%d] (%s, %.2fs, loop=%d)", g.name.c_str(), target,
         g.motions[static_cast<size_t>(target)].name.c_str(),
         g.motions[static_cast<size_t>(target)].duration,
         g.motions[static_cast<size_t>(target)].loop ? 1 : 0);
    return target;
}

/**
 * 设置表情（P4）。
 *
 * @param index 表情下标（model3.json Expressions 顺序）；-1 = 取消表情
 * @return 当前表情下标（-1 = 无）
 */
JNIEXPORT jint JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetExpression(
        JNIEnv * /*env*/, jobject /*thiz*/, jint index) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (index < 0) {
        g_activeExpression = -1;
        g_modelDirty = true;
        LOGI("取消表情");
        return -1;
    }
    if (index >= static_cast<jint>(g_expressions.size())) return g_activeExpression;

    g_activeExpression = index;
    g_modelDirty = true;
    LOGI("设置表情: %s (idx=%d)", g_expressions[static_cast<size_t>(index)].name.c_str(), index);
    return index;
}

/**
 * 列出动作组与表情清单（P4），供 Kotlin 侧建按钮。
 *
 * 返回格式（每项一行，字段以 '\t' 分隔）：
 *   motion\t<组下标>\t<组名>\t<动作个数>
 *   expression\t<下标>\t<表情名>
 *
 * 用字符串而不是数组：这里的清单只在页面初始化时读一次，解析成本无关紧要，
 * 而字符串能避免再定义一套 JNI 对象构造代码。
 */
JNIEXPORT jstring JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeListActions(
        JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);

    std::string out;
    char line[256];
    for (size_t i = 0; i < g_motionGroups.size(); ++i) {
        std::snprintf(line, sizeof(line), "motion\t%zu\t%s\t%zu\n", i,
                      g_motionGroups[i].name.c_str(), g_motionGroups[i].motions.size());
        out += line;
    }
    for (size_t i = 0; i < g_expressions.size(); ++i) {
        std::snprintf(line, sizeof(line), "expression\t%zu\t%s\n", i,
                      g_expressions[i].name.c_str());
        out += line;
    }
    return env->NewStringUTF(out.c_str());
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
 * 每帧绘制：推进动作 → 重建状态 → 按逐帧可见性/顺序绘制。
 * GL 线程调用。
 *
 * P4 的逐帧状态重建（顺序很重要）：
 *   1) 推进动作时钟（steady_clock，与渲染帧率解耦）；
 *   2) 若有变化（动作在播 / 面捕有新数据 / 动作或表情被切换），
 *      走 applyModelStateLocked() → csmUpdateModel → 抄顶点；
 *   3) 用 csmGetDrawableDynamicFlags 逐帧判断 csmIsVisible，
 *      不可见的 drawable 直接跳过（「4 只手」的另一半修复：
 *      pose 让 Part01ArmRB001 的输入 opacity 归零后，这里的可见性判定
 *      才会真正把它挡在绘制之外）；
 *   4) 按 csmGetRenderOrders 逐帧重排绘制顺序，防止透明部件叠加错乱。
 *
 * 为什么可见性与顺序必须逐帧算：
 *   init 时的快照只反映「默认姿态」，动作/表情/pose 都会改变二者。
 *   把「画不画」和「先画谁」写死在 init，是这类 Live2D 渲染器最常见的错。
 *
 * 锁的边界：
 *   csm* 求值与 nativeUpdateParameters / nativeInit 必须互斥，因此状态重建和
 *   绘制计划（顺序 + 可见性 + opacity）在 g_mutex 内完成；glDraw* 放到锁外，
 *   不让 GPU 调用阻塞 Kotlin 侧 30Hz 的参数推流。
 *   绘制计划用函数级 static 复用缓冲，避免每帧重新分配三个 vector。
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

    // 绘制计划缓冲：函数级 static 复用，避免每帧分配（大小恒定，几十项）
    static std::vector<Batch> s_batches;
    static std::vector<MaskBatch> s_maskBatches;
    static std::vector<int> s_maskRefs;
    static std::vector<GLuint> s_textures;
    static std::vector<int> s_order;
    static std::vector<float> s_opacity;

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
    float modelOpacity;
    float mvp[16];

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_program == 0 || g_vbo == 0 || g_ibo == 0 || g_batches.empty()) return;

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
        std::memcpy(mvp, g_mvp, sizeof(mvp));

        // ---- 1) 推进动作时钟 ----
        const double nowSec = std::chrono::duration<double>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        if (g_lastTickSec <= 0.0) g_lastTickSec = nowSec;
        const double dt = std::min(0.25, std::max(0.0, nowSec - g_lastTickSec));
        g_lastTickSec = nowSec;

        if (g_motionActive) {
            g_motionTime += dt;
            g_modelDirty = true; // 动作每帧都在变，需要重新求值
        }

        // ---- 2) 状态重建（动作 / 表情 / 面捕 / pose 全部应用后重算顶点）----
        if (g_model && (g_modelDirty || g_faceDirty)) {
            applyModelStateLocked();
            csmUpdateModel(g_model);
            refreshVertexPositionsLocked();
            g_faceDirty = false;
        }

        // ---- 3) 顶点重传 ----
        // 常规路径：拓扑（索引、批次数、顶点总数）不变，整块 glBufferSubData。
        // 兜底路径：VBO 容量与 CPU 顶点数不一致（nativeInit 在 surfaceCreated
        // 之后重跑过），改走 glBufferData 重新分配，避免越界写显存。
        if (g_verticesDirty && !g_vertices.empty()) {
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

        // ---- 4) 生成绘制计划：逐帧可见性 + 逐帧 opacity + render order 排序 ----
        const int drawableCount = g_model ? csmGetDrawableCount(g_model) : 0;
        const csmFlags *dynamicFlags =
                g_model ? csmGetDrawableDynamicFlags(g_model) : nullptr;
        const int *renderOrders = g_model ? csmGetRenderOrders(g_model) : nullptr;
        const float *drawableOpacities = g_model ? csmGetDrawableOpacities(g_model) : nullptr;

        modelOpacity = g_modelOpacity;
        s_batches = g_batches;
        s_maskBatches = g_maskBatches;
        s_maskRefs = g_maskRefs;
        s_textures = g_textures;

        // s_opacity 同时承担「可见性」：0 = 不画（不可见或完全透明），
        // 省掉一个并行的可见性数组。
        s_opacity.assign(s_batches.size(), 0.0f);
        s_order.resize(s_batches.size());
        for (size_t i = 0; i < s_batches.size(); ++i) s_order[i] = static_cast<int>(i);

        for (size_t i = 0; i < s_batches.size(); ++i) {
            const int di = s_batches[i].drawableIndex;
            if (!dynamicFlags || di < 0 || di >= drawableCount) continue;
            if ((dynamicFlags[di] & csmIsVisible) == 0) continue;

            float opacity = drawableOpacities ? drawableOpacities[di] : 1.0f;
            if (opacity < 0.0f) opacity = 0.0f;
            if (opacity > 1.0f) opacity = 1.0f;
            s_opacity[i] = opacity;
        }

        if (renderOrders) {
            std::stable_sort(s_order.begin(), s_order.end(), [&](int a, int b) {
                const int da = s_batches[static_cast<size_t>(a)].drawableIndex;
                const int db = s_batches[static_cast<size_t>(b)].drawableIndex;
                const int ra = (da >= 0 && da < drawableCount) ? renderOrders[da] : 0;
                const int rb = (db >= 0 && db < drawableCount) ? renderOrders[db] : 0;
                return ra < rb;
            });
        }
    }

    // ================= 锁外：GPU 绘制 =================
    // 没有 8-bit 模板（EGL 配置选错）或遮罩 program 编译失败：
    // 退回「不裁剪直接画」，画面仍能看，只是圆弧切口还在。
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
    const auto textureOf = [](int index) -> GLuint {
        if (index < 0 || index >= static_cast<int>(s_textures.size())) return 0;
        return s_textures[static_cast<size_t>(index)];
    };

    // 模板缓冲的写入/清除都受 glStencilMask 影响，统一置满
    glStencilMask(0xFF);

    int drawnBatches = 0;
    int skippedInvisible = 0;
    for (int bi : s_order) {
        const size_t index = static_cast<size_t>(bi);
        // 整体不透明度（动作 Target=Model/Opacity）乘在每个批次上
        const float opacity = s_opacity[index] * modelOpacity;
        if (opacity <= 0.001f) { ++skippedInvisible; continue; }

        const Batch &batch = s_batches[index];
        const GLuint tex = textureOf(batch.textureIndex);
        if (tex == 0) continue;
        // ---- 无遮罩：完全沿用 P1 路径 ----
        if (!stencilUsable || batch.maskCount <= 0) {
            glBindTexture(GL_TEXTURE_2D, tex);
            glUniform1f(uniformOpacity, opacity);
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
            if (ref < 0 || ref >= static_cast<int>(s_maskRefs.size())) continue;
            const int maskBatchIndex = s_maskRefs[static_cast<size_t>(ref)];
            if (maskBatchIndex < 0 ||
                maskBatchIndex >= static_cast<int>(s_maskBatches.size())) {
                continue;
            }
            const MaskBatch &mb = s_maskBatches[static_cast<size_t>(maskBatchIndex)];
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
            glUniform1f(uniformOpacity, opacity);
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
        glUniform1f(uniformOpacity, opacity);
        drawRange(batch.firstIndex, batch.indexCount);

        // ---- 第三步：交还状态 ----
        glDisable(GL_STENCIL_TEST);
        ++drawnBatches;
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    glDisableVertexAttribArray(static_cast<GLuint>(attrPos));
    glDisableVertexAttribArray(static_cast<GLuint>(attrUv));
    glDisableVertexAttribArray(static_cast<GLuint>(attrAlpha));
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glUseProgram(0);

    // 每帧都打日志会刷屏（RENDERMODE_CONTINUOUSLY 下是 60Hz），
    // 这里只在第一次绘制时确认一次链路是否通
    static bool loggedOnce = false;
    if (!loggedOnce) {
        loggedOnce = true;
        LOGI("首帧绘制完成: batches=%d/%zu skippedInvisible=%d stencilBits=%d usable=%d",
             drawnBatches, s_batches.size(), skippedInvisible, stencilBits,
             stencilUsable ? 1 : 0);
    }

    // ---- FPS 统计（GL 线程独占，Kotlin 侧轮询 nativeFps）----
    // 0.5s 窗口：比 1s 响应快，又足够平掉单帧抖动。
    static double fpsWindowStart = 0.0;
    static int fpsFrames = 0;
    const double now = std::chrono::duration<double>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    if (fpsWindowStart <= 0.0) fpsWindowStart = now;
    ++fpsFrames;
    const double elapsed = now - fpsWindowStart;
    if (elapsed >= 0.5) {
        g_fps.store(static_cast<float>(fpsFrames / elapsed), std::memory_order_relaxed);
        fpsWindowStart = now;
        fpsFrames = 0;
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
    g_paramDefaults.clear();
    g_partIndexById.clear();
    g_partDefaults.clear();
    g_motionGroups.clear();
    g_expressions.clear();
    g_poseGroups.clear();
    g_faceValues.clear();
    g_faceDirty = false;
    g_activeGroup = -1;
    g_activeMotion = -1;
    g_activeExpression = -1;
    g_motionActive = false;
    g_motionTime = 0.0;
    g_lastTickSec = 0.0;
    g_modelOpacity = 1.0f;
    g_modelDirty = true;
    g_fps.store(0.0f, std::memory_order_relaxed);
    g_verticesDirty = false;
    g_vboVertexCount = 0;
    g_textureImages.clear();
    g_vertices.shrink_to_fit();
    g_indices.shrink_to_fit();
    g_batches.shrink_to_fit();
    g_maskBatches.shrink_to_fit();
    g_maskRefs.shrink_to_fit();
    g_vertexSpans.shrink_to_fit();
    g_paramDefaults.shrink_to_fit();
    g_partDefaults.shrink_to_fit();
    g_faceValues.shrink_to_fit();
    g_motionGroups.shrink_to_fit();
    g_expressions.shrink_to_fit();
    g_poseGroups.shrink_to_fit();
    g_textureImages.shrink_to_fit();
    g_model = nullptr;
    g_moc = nullptr;
    g_modelBuffer.free();
    g_mocBuffer.free();
    LOGI("nativeRelease: 资源已释放 (glThread=%d)", onGlThread ? 1 : 0);
}

/** 最近一轮统计的渲染帧率（无有效窗口时返回 0）。 */
JNIEXPORT jfloat JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeFps(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_fps.load(std::memory_order_relaxed);
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
    char buffer[512];
    std::snprintf(buffer, sizeof(buffer),
                  "drawables=%d drawn=%d batches=%zu masked=%d maskGeoms=%zu "
                  "textures=%d/%zu verts=%zu idx=%zu stencil=%d "
                  "params=%d writes=%ld unknown=%ld "
                  "motions=%zu exprs=%zu pose=%zu motion=%d/%d exp=%d op=%.2f fps=%.1f "
                  "bounds=[%.3f,%.3f]x[%.3f,%.3f]",
                  g_drawableTotal, g_drawableDrawn, g_batches.size(), maskedBatches,
                  g_maskBatches.size(), uploadedTextures, g_textureImages.size(),
                  g_vertices.size(), g_indices.size(), g_stencilBits,
                  g_paramCount, g_paramWritesTotal, g_paramUnknownTotal,
                  g_motionGroups.size(), g_expressions.size(), g_poseGroups.size(),
                  g_activeGroup, g_activeMotion, g_activeExpression, g_modelOpacity,
                  g_fps.load(std::memory_order_relaxed),
                  g_minX, g_maxX, g_minY, g_maxY);
    return env->NewStringUTF(buffer);
}

}  // extern "C"
