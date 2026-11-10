// ============================================================================
//  live2d_jni.cpp - P0 静态渲染原型（Purism Core -> GLES 2.0）
// ============================================================================
//
//  职责边界：
//   - 只做「从本地文件读 .moc3 → Purism Core 求值 → 贴图绘制」；
//   - 不碰 BlendShapeMapper / FaceRenderer 的任何逻辑（P3 只读取它们产出的
//     VTS 参数快照，映射与算法全在 Kotlin 侧）；
//   - 不做模型导入、不做动作播放、不做表情预设。
//
//  坐标与数据流：
//   nativeLoadModelFromPath（任意线程，加载/切换模型时调用）
//     读整份 .moc3 到 64 字节对齐缓冲区
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
//  ⚠️ P2 实测修正：第 1 条是错的。标准模型的前发图层既被别人当遮罩、
//     自身又是一个正常可见图层（op=1 vis=1）。按第 1 条整片剔除后前发消失，
//     头顶只剩后发的硬边几何，真机上看起来就是生硬的「平切」。
//     现在的做法：这类图层按普通内容绘制，同时几何也用于模板写入。
//
//  P1 增补（纹理与 UV）：
//   - stb_image.h 解 PNG；UV 取 csmGetDrawableVertexUvs，**V 轴要翻转**（见 Vertex）；
//   - 纹理张数不写死：解析 model3.json 的 FileReferences.Textures，按
//     csmGetDrawableTextureIndices 分配到对应纹理单元（实测 2 张，51:19）；
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
//     （csmIsInvertedMask）已用 GL_NOTEQUAL 实现，但示例模型未用到，未经真机验证。
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
//     与标准固定取景语义一致；
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
//   - **「4 只手」的根因是缺 pose3**：标准示例模型的 pose3.json 把手臂拆成两组互斥
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
//  P5 增补（生命周期恢复 / 原生手势 / 自研物理）：
//   - **切后台丢部件**：EGL context 被销毁后 onSurfaceCreated 重来一遍，GPU 资源
//     全部重建，但 csm 模型状态与 CPU 顶点仍停留在停渲染前。旧代码只重传
//     g_vertices（可能是「pose 把手臂压掉」的那个中间态）就开画，于是脸/手臂
//     这些依赖 pose + stencil 的部件只剩空壳。现在 surfaceCreated 里先强制
//     走一次完整状态重建（复位 → 动作 → 表情 → 面捕 → 物理 → pose → update），
//     再上传顶点，保证「回到前台」与「首次进入」是同一条路径。
//   - **手势变换**：nativeSetTransform(dx, dy, scale) 接收 NDC 偏移与缩放，
//     在投影矩阵之后再做一次 S/T 复合（正交矩阵无旋转，可直接缩放 0/5/12/13 行），
//     偏移与缩放都在 C++ 侧钳制。平移边界按**模型包围盒**计算：只要还有
//     80px 可见就允许拖（nativePanLimits 供 Kotlin 同步），因此放大后可以把
//     头部拖到屏幕中心；旧版「中心点必须在屏幕内」是放大拖不动的根因。
//   - **物理**：XPBD 求解器（见 PhysicsChain / xpbd::step）。只借鉴「输入→
//     分段→输出」的数据约定，求解器按 Macklin「Small Steps in Physics Simulation」
//     方案独立实现：固定子步长 + 每子步一次约束迭代，距离（刚性杆）+ 目标方向
//     （输入驱动，柔度=Delay/Acceleration）+ 弯曲（单边防对折）三类约束，
//     速度由位移回推并加指数阻尼。输出取链尾相对姿态偏角，写回 ParamHair*。
//
//  P7 增补（v1.3.0 首发回归修复）：
//   - **特定模型手部缺失**：旧实现把「有掩码声明但所有掩码下标都是 -1」的
//     drawable 整块丢弃。Live2D 里 -1 表示「该掩码 drawable 当前不可见」，
//     标准语义是**退回不裁剪**而不是把被裁剪的内容也删掉 —— 手部常被袖子/
//     衣物遮挡，掩码一旦隐藏，整只手就跟着消失。现在改为按无掩码绘制。
//   - **pose3 互斥判定改成「最大不透明度获胜」**：旧实现取「第一个高于阈值」
//     的部件，默认值全为 1 时永远选中第一个候选，动作把另一只手抬起来也无效。
//     标准 Pose 后处理取组内最大值（并列取靠前者）；全组都不亮时**保持隐藏**，
//     不再强行点亮第一个（那会把本来不该出现的部件按 0 透明度复活）。
//   - pose3 的 Link 联动部件现在会跟随所属部件一起归零（示例模型未用到，但
//     自定义模型常用）。
//   - **切页黑块 / 面捕抽风**：surfaceCreated 无条件置 g_glModelDirty 并重建
//     全部 GL 资源（program / 纹理 / VBO / IBO）；drawFrame 入口连 stencil
//     一起清，避免旧模板污染；±Inf 参数降级为「本帧无输入」，物理链在上下文
//     重建后复位，顶点抄写拒绝非有限值。
//
//  P8 增补（v1.4.0 真机回归修复：丢手 / 四只手 / 头发抽风）：
//   - **Link 下标错位（四只手 / 丢手）**：旧 applyPoseLocked 把「获胜部件的
//     csm 下标」当成「组内候选序号」去索引 Link 表（links[winner]），两者是
//     不同下标空间，只要组内候选序号与部件下标不相等就会取到错误候选的联动
//     列表（越界时直接跳过）。现在显式区分 winnerOrdinal / winnerPart。
//   - **pose3 名字匹配大小写不敏感**：自定义模型的 pose3.json 与 moc3 部件名
//     常大小写不一致。旧实现按字节严格匹配，解析后组内只剩 1 个成员，而单项组
//     被当作「强制该部件可见」，于是两套手臂同时被点亮 ——「四只手」的另一半
//     成因。现在回退到小写表匹配，且**解析后不足 2 个成员的组直接丢弃**（宁可
//     少一条互斥规则，也不凭空点亮一只手臂）。
//   - **获胜者强制 1.0、全组为 0 时退化为恰好一个可见**：严格对齐标准
//     Pose 后处理语义。旧实现让获胜者保留输入值（可能是 0.3，仍然看不见），并且
//     全组为 0 时保持整组隐藏 —— 后者会让「默认 visible=0、只靠 pose 点亮」
//     的部件永久消失，即真机「模型 A 没手」。互斥组只会切换、不会整组消失。
//   - **物理防抖（P8）**：①输入增益 0.25（阈值任务要求的 0.1~0.3）；
//     ②输入低通 + 速度钳制，消除 30Hz 面捕推流造成的逐帧交替冲击（抽风的
//     真正来源：两帧里一帧速度 0、一帧速度尖峰）；③速度低于 1e-3 强制归零
//     （Movement Threshold）；④基础阻尼比 0.08 → 0.22 并封顶 0.95；
//     ⑤终端速度（空气阻力）兜底；⑥输出死区 + 输出低通，滤掉链尾残余抖动；
//     ⑦全局开关 g_physicsEnabled（JNI nativeSetPhysicsEnabled），关闭时输出
//     参数写回默认值，用于真机二分定位「是不是物理在抖」。
//
//  Purism Core 的使用约束（API.md）：
//   - MOC 缓冲区必须 64 字节对齐（csmAlignofMoc），且必须活得比 model 久；
//   - model 缓冲区必须 16 字节对齐（csmAlignofModel），大小取 csmGetSizeofModel；
//   - 库不做任何堆分配，两块缓冲区都由调用方持有（本文件的 Buffer 结构）；
//   - 同一 model 不允许跨线程并发访问（本文件：init 线程写、GL 线程只读 CPU 副本）。
// ============================================================================

#include <jni.h>

#include <android/log.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <unistd.h>
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
#include "xpbd_solver.h"

// stb_image：单文件 PNG/JPEG 解码器。同样只允许一个翻译单元定义实现。
// STBI_ONLY_PNG / STBI_NO_STDIO：本工程只解 PNG，且统一走内存接口
// （fread 到缓冲区再 stbi_load_from_memory），裁掉 JPEG/文件 IO 能显著减小 .so。
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
//  g_lastError 并由 nativeLoadModelFromPath 以 jstring 返回（null = 成功）。
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
// 线程约束：nativeLoadModelFromPath 在主线程写、nativeSurfaceCreated/nativeDrawFrame 在 GL 线程读，
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
 * 本次改脏的顶点区间 [first, last]（含端点；first > last = 无变化）。
 *
 * P14：参数更新与物理只影响「跟着这条参数链动」的少数 drawable（头发、裙摆、
 * 眼睛…），整块 glBufferSubData 会把几万顶点的显存流量摊到每一帧。
 * [refreshVertexPositionsLocked] 在抄顶点时顺手比较新旧坐标，只记下真正变了的
 * 连续区间，drawFrame 按它做区间上传。比较成本（一次 float 相等）远小于一次
 * 全量 DMA，且判据来自数据本身，不会漏传。
 */
int g_dirtyVertexFirst = -1;
int g_dirtyVertexLast = -1;

/** 清掉顶点脏标记与脏区间（上传完成 / 模型复位 / VBO 重建后调用）。 */
inline void clearVertexDirty() {
    g_verticesDirty = false;
    g_dirtyVertexFirst = -1;
    g_dirtyVertexLast = -1;
}

/**
 * VBO 当前分配的顶点数（GL 线程写）。
 *
 * 用于 glBufferSubData 前的尺寸校验：若模型在上传之后又换过（nativeLoadModelFromPath），
 * CPU 顶点数可能与 VBO 容量不一致，此时**跳过**本次局部更新而不是越界写显存。
 */
int g_vboVertexCount = 0;

/**
 * 模型已换、GL 侧资源必须整体重建（P6 自定义模型导入）。
 *
 * 为什么不能像首次加载那样「什么都不做、等 surfaceCreated」：
 * 导入新模型时 EGL context 是**活着**的，surfaceCreated 不会再次触发，
 * 旧 VBO/IBO 仍是上一个模型的拓扑，纹理句柄也指向旧图集。
 * drawFrame 看到这个标记就重新上传纹理、重建 VBO/IBO（GL 线程内完成）。
 */
bool g_glModelDirty = false;

/**
 * 参数 id -> 参数下标缓存（P3）。
 *
 * 本版本 Purism Core 没有 csmSetParameterValueById，只能写
 * csmGetParameterValues 返回的数组；ID 是字符串，逐帧线性查找参数名会成为
 * 30Hz 下实实在在的开销。init 时建一次表，之后 O(1) 落位。
 */
std::unordered_map<std::string, int> g_paramIndexById;
int g_paramCount = 0;

/**
 * 参数名「归一化」表（P14）：同一参数在不同模型里的写法不统一时兜底命中。
 *
 * 实证（Big_fat_fish）：model3.json 的 EyeBlink 组写的是 `EyeLOpen` / `EyeROpen`，
 * 没有 `Param` 前缀；而 App 的推流表固定用 Cubism 标准名 `ParamEyeLOpen`。
 * 精确查表必然 miss → 参数绑定失败 → 面捕/眨眼数据整条链路写不进去。
 *
 * 归一化规则：小写、丢弃非字母数字、去前导 "param"。于是
 *   ParamEyeLOpen / EyeLOpen / eyE_l_open → "eyelopen"
 *   ParamEyeBallX / EyeBallX            → "eyeballx"
 * 只在精确匹配失败时兜底，且一参多写时保留 moc3 里先出现的那个（确定性）。
 */
std::unordered_map<std::string, int> g_paramIndexByNorm;

/** 参数名归一化键（别名兜底用）。 */
std::string normalizeParamName(const std::string &name) {
    std::string out;
    out.reserve(name.size());
    for (char c : name) {
        if (c >= 'A' && c <= 'Z') {
            out.push_back(static_cast<char>(c - 'A' + 'a'));
        } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
            out.push_back(c);
        }
    }
    if (out.size() > 5 && out.compare(0, 5, "param") == 0) out.erase(0, 5);
    return out;
}

/**
 * 参数名 → 下标（精确优先，归一化兜底）。找不到返回 -1。
 *
 * 所有「按名字取参数」的路径（物理输入/输出、表情、眨眼组、Kotlin 绑定）都必须
 * 走这里，否则同一份数据在不同命名风格的模型上会出现「有的模型生效、有的不生效」。
 */
int resolveParamIndex(const std::string &name) {
    const auto exact = g_paramIndexById.find(name);
    if (exact != g_paramIndexById.end()) return exact->second;
    const auto alias = g_paramIndexByNorm.find(normalizeParamName(name));
    if (alias != g_paramIndexByNorm.end()) return alias->second;
    return -1;
}

/** 缓存命中计数，用于诊断「Kotlin 传的 id 是否在模型里」（仅累计，不打日志）。 */
long g_paramWritesTotal = 0;
long g_paramUnknownTotal = 0;
/** 被降级的非有限值计数（P7：±Inf → NaN 的「本帧无输入」）。 */
long g_paramSanitizedTotal = 0;

// ---------------------------------------------------------------------------
//  动作 / 表情 / 姿势（P4）
// ---------------------------------------------------------------------------
//
//  为什么姿势（pose3）是必需而不是可选：
//   典型 pose3.json 把手臂拆成两组互斥部件：
//     [Part01ArmRA001, Part01ArmRB001]   ← 交叉抱臂 / 自然下垂 二选一
//     [Part01ArmLA001, Part01ArmLB001]
//   moc3 里这 4 个部件的默认不透明度全是 1，**动作文件里的 PartOpacity 曲线
//   也全是 1**。标准渲染器靠 Pose 后处理：每组只保留第一个
//   达到可见阈值的部件，其余归零。我们此前完全没做这一步，于是两套手臂
//   同时出现 —— 真机上的「4 只手」就是这个原因，不是批次或顶点出了问题。
//
//  动作（motion3.json）：解析为「参数曲线 + 部件不透明度曲线」。段格式为
//   [t0, v0, type, ...]；type 0=线性、1=三次贝塞尔、2=阶跃、3=反阶跃。
//  表达式（exp3.json）：{Id, Value, Blend}，Blend ∈ Overwrite/Add/Multiply。
//
//  所有权：这些结构在模型加载时一次性构建，之后只读；播放状态在锁内写。

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

/**
 * 姿势组的 Link 联动部件（P7）。
 *
 * 下标与 [g_poseGroups] 对齐：g_poseGroupLinks[组][候选序] = 跟随该候选
 * 一起切换的部件下标列表。pose3.json 里每个候选部件的 "Link" 字段表示
 * 「选中我时，这些部件也一起显示」。示例模型未用到，但自定义模型（尤其是
 * 多套服装/手臂换装）常用，漏掉会让联动部件残留成第二只手。
 *
 * ⚠️ P8：候选序（ordinal）与 csm 部件下标是**两个不同的下标空间**。
 * 旧 applyPoseLocked 直接拿部件下标去索引本表（links[winner]），越界时静默
 * 跳过、不越界时取到**别的候选**的联动列表 —— 这是自定义模型「四只手 /
 * 丢手」的直接成因之一。现在两者显式区分。
 */
std::vector<std::vector<std::vector<int>>> g_poseGroupLinks;

/**
 * 无 pose3.json 时的 A/B 成对部件兜底互斥（P9）。
 *
 * 背景见 local_debug/repro/FINDINGS.md §2.1：hiyori_vts 这类模型既没有
 * pose3.json，也没有 Motions 段，moc3 里却把两套互斥的手臂部件
 * （PartArmA / PartArmB）的默认不透明度都导出成 1.0。applyPoseLocked()
 * 在 g_poseGroups 为空时直接 return，于是两套手臂同时绘制 —— 真机上的「四只手」。
 *
 * 兜底规则刻意保守，避免误伤正常模型：
 *  1) 只有 **完全没有 pose3 组** 时才启用（有 pose3 一律以作者意图为准）；
 *  2) 只在检测到「同名 + 后缀 A/B 成对」的部件时才建组
 *     （PartArmA/PartArmB、PartSleeveA/PartSleeveB …），单独一个 A 或 B 不建组；
 *  3) 两端都必须是「默认可显示」（默认不透明度 > 阈值）—— 真正会叠画的才算；
 *     默认就隐藏的一端说明作者已经用不透明度表达过意图，兜底不介入；
 *  4) 两端都必须至少归属一个 drawable（否则归零也画不掉任何东西，纯属噪音）；
 *  5) 由 prefs `pose_fallback_ab`（nativeSetPoseFallback）随时可关，关闭后
 *     立即恢复成「不互斥」的原始行为，方便真机 A/B 对比。
 *
 * 组内顺序固定为 [A, B]，获胜者因此在默认值相同时落到 A（applyPoseLocked
 * 按「最大不透明度、同值取靠前候选」选择）。若动作曲线显式驱动了其中一个，
 * 动作值会通过同一条获胜者规则生效。
 */
std::atomic<bool> g_poseFallbackEnabled{true};

/**
 * 物理逐帧曲线追踪（P9 与基准参考实现对照用）。
 *
 * 打开后每帧打一行 `trace:` 到 logcat，内容是该帧的时间、dt 与
 * ParamHairFront 的物理输出值。格式与 host 基准实现输出的 CSV 一致，
 * 因此可以把两边曲线逐帧对齐比数值，
 * 而不是靠肉眼或单一统计量判断「物理是不是抽风」。
 *
 * 由 prefs `physics_trace` 驱动（nativeSetPhysicsTrace），默认关闭 ——
 * 每帧一行日志开销不小，只在取证时需要。
 */
std::atomic<bool> g_physicsTrace{false};

/** 追踪用的帧序号（drawFrame 递增）。 */
long g_traceFrame = 0;

/**
 * 参考模式开关（P9 与基准实现对照用）—— **P12 起退役**。
 *
 * 方向跟踪粒子模型成为唯一路径后，「旁路抑制项」失去了对象：P8 加入的全部抑制项
 * （输入增益/低通、输出死区/低通、速度死区、静止吸附）已随旧伺服模型删除。
 * 保留这个 atomic 只为 nativeSetPhysicsReferenceMode 的 JNI ABI 不变
 * （Kotlin 侧仍会按旧 prefs 调用），写入它不再产生任何行为差异。
 */
std::atomic<bool> g_physicsReferenceMode{false};

/** 本次加载是否真的读到了 pose3 组（true = 兜底必须让位）。 */
bool g_poseFromFile = false;

/** 前 g_poseFileGroupCount 组来自 pose3.json，其后是兜底推断组（运行时开关回滚用）。 */
size_t g_poseFileGroupCount = 0;

/** 兜底推断出的互斥组数（诊断/日志用）。 */
int g_poseFallbackPairs = 0;

/** 部件 id -> csm 部件下标（初始化时建立，构造姿势组用）。 */
std::unordered_map<std::string, int> g_partIndexById;

/**
 * 部件 id（小写）-> csm 部件下标（P8）。
 *
 * 自定义模型的 pose3.json 与 moc3 里的部件名大小写经常不一致（导出工具
 * 版本差异）。标准 Pose 后处理用**字符串全等**匹配，而用户导入的模型
 * 不能指望这一点：全等匹配失败会让组内成员骤减，剩下单项组
 * 被当作「强制该部件可见」，于是两套手臂同时点亮 —— 另一种「四只手」。
 * 这里加一层小写回退，仍然以精确匹配优先。
 */
std::unordered_map<std::string, int> g_partIndexByLower;

/** 部件默认不透明度快照（init 后立刻拷贝，逐帧复位用）。 */
std::vector<float> g_partDefaults;

/** 每个部件归属的 drawable 数量（兜底互斥判定「是否真的会画出来」用）。 */
std::vector<int> g_partDrawableCounts;

/**
 * 本帧被**动作 PartOpacity 曲线显式驱动**的部件标记（P8）。
 *
 * 语义：动画自己对某个部件的不透明度有明确意图时，pose 后处理不该跟它抢。
 * 这正对应任务里的「PartOpacity 写入优先级低于动画参数」：
 *  - 组内成员：pose3 的互斥语义优先（这是「四只手」的唯一治法，动画若把
 *    两套手臂都拉到 1.0，必须由 pose 压掉落选者）；
 *  - 组外被动画驱动的部件：一律不动；
 *  - 被动画驱动的 **Link 联动部件**：不归零、不跟随获胜者改值。
 *
 * 每帧在 resetParametersLocked 里清零，applyMotionLocked 里置位。
 */
std::vector<uint8_t> g_partAnimDriven;

/** 参数默认值快照（逐帧复位用）。 */
std::vector<float> g_paramDefaults;

/** 参数量程（P5：物理输入归一化用），与 g_paramCount 等长。 */
std::vector<float> g_paramMin;
std::vector<float> g_paramMax;

// ---------------------------------------------------------------------------
//  物理引擎（XPBD 重写）：位置动力学约束求解器（法律安全的独立实现）
// ---------------------------------------------------------------------------
//
//  目标：头发 / 裙摆 / 围巾跟随 ParamAngleX/Y/Z 产生**惯性拖尾**摆动。
//
//  前两代实现：P5 弹簧伺服（稳态输入产生恒定 DC 响应，动态被低通化，靠一串
//  抑制项压制发散与残差）→ P12 方向跟踪粒子模型（高通、稳定，但一阶方向
//  跟踪没有真正的惯性过冲，摆动的幅度只能靠附加项凑出来）。
//  本代按 Macklin 等「Small Steps in Physics Simulation」（SCR'19）的 XPBD
//  方案从零实现：
//
//    每渲染帧 → 累加时钟 → 固定子步（xpbd::tuning().substepDt = 1/120s，与渲染帧率
//    解耦）→ 每个子步：预测（阻尼 + 前推）→ **一次**约束求解（Gauss-Seidel
//    顺序投影）→ 速度回推。「多子步 × 单次迭代」替代「大步长 + 多次迭代」，
//    求解误差与阻尼误差都随子步数下降（Macklin 论文核心结论）。
//
//  三类约束（算法层面参考 Blender 4.x 细杆 XPBD 的「以距离代角度」弯曲处理
//  与 UE5 Chaos Groom 的 Advance → Solve → Update 流程组织，未复制任何实现
//  代码）：
//    1) 距离约束（等式，柔度 0）：相邻粒子钳回 Radius —— 刚性杆；
//    2) 目标方向约束（等式，柔度 α = 基值 × Delay / Acceleration）：把粒子
//       拉向「父粒子 + 输入目标方向 × Radius」。Angle 类输入映射目标方向，
//       X/Y 类输入通过根粒子平移注入动量。稳态时链与目标方向平行、输出 0
//       —— 与参考模型相同的高通结构，但动态是二阶惯性系统（过冲 + 回摆），
//       不是一阶方向跟踪的纯迟滞；
//    3) 弯曲约束（单边不等式）：|x_{i+1} − x_{i−1}| ≥ ratio × (r_i + r_{i+1})，
//       防止大幅甩动时链对折。
//
//  求解器主体与稳定性兜底的完整说明见 xpbd_solver.h 与 applyPhysicsLocked。

// 求解器本体在 xpbd_solver.h（header-only）：同一份代码既进 NDK 构建，也能被
// local_debug/repro/host/xpbd_host.exe 在桌面端回放调参。这里只做类型别名，
// 让本文件的历史命名（PhysicsChain 等）继续可用。
using PhysicsInput = xpbd::Input;
using PhysicsOutput = xpbd::Output;
using PhysicsVertex = xpbd::Vertex;
using PhysicsChain = xpbd::Chain;

std::vector<PhysicsChain> g_physicsChains;

// ---------------------------------------------------------------------------
//  XPBD 物理常量与 rig 级状态
// ---------------------------------------------------------------------------

/** 输入/输出 Weight 的满值（json 语义，解析侧使用）。 */
constexpr float kPhysicsMaxWeight = 100.0f;
/** 累加时钟上限（超过即丢弃；正常渲染帧永远碰不到）。 */
constexpr float kPhysicsMaxDeltaTime = 5.0f;

// 求解器常量（子步长、柔度、阻尼、弯曲阈值…）全部在 xpbd_solver.h 的
// xpbd::tuning() 里，App 侧只读默认值；宿主回放台可覆盖同一份结构体。

/** 物理累加时钟（整个 rig 共用一个）。 */
float g_physicsRemainTime = 0.0f;

/**
 * physics3.json 的 Meta.Fps（0 = 缺失或不可信）。
 *
 * XPBD 起不再驱动步长（子步长固定 xpbd::tuning().substepDt，与渲染帧率/json 都解耦），
 * 仅保留解析用于加载日志与模型诊断。
 */
float g_physicsFps = 0.0f;

/**
 * 参数缓存与输入缓存（尺寸 = 参数总数）。
 *
 * caches 承担两个职责：步进时刻的输入时间对齐载体 + 组间传播通道
 * （前一条链的输出写进 caches，后一条链的输入就能读到，链间传播按声明顺序）。
 */
std::vector<float> g_paramCaches;
std::vector<float> g_paramInputCaches;

/** 物理累加时钟的帧进水量（drawFrame 每帧写入，applyPhysicsLocked 读取）。 */
float g_physicsDt = 0.0f;

/**
 * 上一帧真正喂给 csmUpdateModel 的参数快照（P14 渲染门控）。
 *
 * 为什么用「整份快照比较」而不是各阶段的脏标记：物理/眨眼写回的最终值与
 * 「复位后的默认值」相比几乎每帧都不同（复位把参数打回默认，物理又写回输出），
 * 那种比较恒为 true，门控就失效了。只有比较「本帧最终参数 vs 上一帧最终参数」
 * 才能回答真正的问题：模型的输入状态有没有变。263 个 float 的逐项比较在
 * 微秒量级，远小于一次 csmUpdateModel（整模型插值）与全顶点遍历。
 */
std::vector<float> g_lastAppliedParams;

/**
 * 物理总开关（P8 调试闸）。
 *
 * false 时 applyPhysicsLocked 直接把所有输出参数写回**默认值**并跳过模拟：
 * 参数完全静止，用来在真机上二分定位「抖动到底来自物理求解器，还是来自
 * 面捕/动作参数推流本身」。默认开启，由 JNI nativeSetPhysicsEnabled 切换
 * （Kotlin 侧未接 UI 时保持 true，不影响发布行为）。
 */
std::atomic<bool> g_physicsEnabled{true};

/**
 * 物理抖动度量（P8 真机验证用）。
 *
 * 「头发抽风」是高频量，截图/录屏采样率都不够（这台设备的 screenrecord 被
 * 厂商移除、screencap 单次 200ms ≈ 5fps），根本抓不到 60Hz 的逐帧抖动。
 * 唯一可靠的做法是在**求解器内部**直接统计：
 *
 *  - inputJitter：相邻两帧物理输入（归一化后）的变化量；
 *  - outputJitter：相邻两帧写回参数的变化量（这是屏幕上真正看到的量）；
 *  - residualSpeed：所有质点速度的均值（抖动 = 静止时速度不为 0）；
 *  - restFrames：判定为静止的帧数占比。
 *
 * 每 5 秒由 nativeStats 汇总输出一次，真机上抓 logcat 即可量化。
 * 注意：这些只在**物理启用**时更新，关闭物理时清空（避免误导）。
 */
struct PhysicsJitterStats {
    double outputAbsDeltaSum = 0.0;   // Σ|本帧输出 - 上帧输出|（每输出参数）
    long outputDeltaCount = 0;        // 参与统计的样本数
    double outputAbsMax = 0.0;        // 单帧最大变化
    long outputFlipCount = 0;         // 方向反转次数（= 抖动/颤的直接证据）

    /**
     * 每个**具体输出参数**上一次的符号（-1/0/+1）。
     *
     * ⚠️ 为什么不能用一个全局 lastSign：输出是按「链 → 输出序」固定顺序遍历的，
     * 相邻两个不同参数的符号天然可能相反。用全局变量跨参数比较，会把
     * 「正常的多参数运动」全部计成方向反转 —— 实测就是这样得到了
     * flipsPerFrame≈0.78 的假象（满幅 1Hz 正弦本来毫无抖动）。
     * 只有跟踪同一个参数在相邻两帧之间是否反向，才是抖动的真实度量。
     */
    std::vector<int> lastSignByOutput;

    double residualSpeedSum = 0.0;    // （P12 退役：恒 0，保留字段仅为日志格式兼容）
    long residualSpeedCount = 0;

    long frames = 0;
    long restFrames = 0;              // （P12 退役：resting 判定已随伺服模型删除）

    void reset() { *this = PhysicsJitterStats(); }

    double meanOutputDelta() const {
        return outputDeltaCount ? outputAbsDeltaSum / outputDeltaCount : 0.0;
    }
    double meanResidualSpeed() const {
        return residualSpeedCount ? residualSpeedSum / residualSpeedCount : 0.0;
    }
    double restRatio() const {
        return frames ? static_cast<double>(restFrames) / static_cast<double>(frames) : 0.0;
    }
    double flipsPerFrame() const {
        return frames ? static_cast<double>(outputFlipCount) / static_cast<double>(frames) : 0.0;
    }
};

PhysicsJitterStats g_physicsJitter;

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

// ---------------------------------------------------------------------------
//  自动眨眼（P14）
// ---------------------------------------------------------------------------
//
//  背景：本渲染器一直没有自动眨眼，睁眼只能靠面捕推 ParamEyeLOpen/ROpen。
//  没有摄像头时 Kotlin 推的是 NaN（语义 = 本帧交给动作/表情），眼睛于是恒停在
//  模型默认值上 —— 用户看到的就是「这个模型不会眨眼」。
//
//  Big_fat_fish 更特殊：它的 model3.json 用 EyeBlink 组声明自己需要眨眼，
//  而且眼皮/眼球位移是通过 physics3.json（PhysicsSetting19/20/22/23，
//  输入 EyeLOpen/EyeROpen）驱动的。也就是说眨眼必须写在**物理之前**，
//  否则物理读到的永远是上一帧的眼睛状态，眼皮不动。
//
//  优先级：真实面捕 > 自动眨眼 > 动作/表情。判据是 g_faceValues 是否有限。
//  P16 起「让位」是**渐变交接**（g_blinkYield，0.12s）：旧实现看到有限值就当场
//  返回，若自动眨眼正处闭眼保持（closed=1）而面捕给的是睁眼值，眼皮会一帧内
//  从 0 跳到满睁 —— 这正是用户反馈的「眼皮闪跳」。交接期间把两者线性混合。

/** model3.json EyeBlink 组解析出的参数下标（空 = 该模型不眨眼）。 */
std::vector<int> g_eyeBlinkParams;

/** 眨眼时序状态（GL 线程独占）。 */
struct BlinkState {
    double nextAtSec = 0.0;   // 下一次眨眼开始的时刻；<=0 = 尚未排期
    double startedAtSec = 0.0;
    bool active = false;
    /** 眨眼曲线各段时长（秒）：闭合 / 保持 / 张开。 */
    double closeSec = 0.09;
    double holdSec = 0.04;
    double openSec = 0.14;
    long totalBlinks = 0;
};
BlinkState g_blink;

/**
 * 自动眨眼 → 真实面捕的交接权重（0 = 完全交给真实数据，1 = 自动眨眼全权）。
 *
 * 为什么需要它（P16）：旧逻辑是「只要看到任一眼睛参数有限就立刻 return」。
 * 若此刻自动眨眼正处于闭眼保持（hold，closed=1），真实面捕推来的却是睁眼值，
 * 眼皮会在一帧内从 0 跳到满睁 —— 正是用户反馈的「眼皮闪跳」。
 * 现在改为按固定斜率淡出：交接期间把「真实值」与「自动眨眼值」线性混合，
 * 约 kBlinkHandoverSec 内平滑过渡，两个来源不再在帧间硬切。
 */
float g_blinkYield = 0.0f;

/** 上一次更新交接权重的时间（秒）；0 = 尚无历史。 */
double g_blinkYieldSec = 0.0;

/** 交接淡出/淡入的完整时长（秒）：≈0.12s，比一次眨眼快、又足够盖住一帧硬切。 */
constexpr double kBlinkHandoverSec = 0.12;

/** P14 诊断：最近一次眨眼写进眼睛参数的值，以及写入次数（物理侧据此确认读到的是新值）。 */
float g_blinkEyeWritten = -1.0f;
long g_blinkEyeWrittenSerial = 0;

/** 闭眼程度 0（睁开）~1（完全闭合）随时间的变化（三次缓动，手感自然）。 */
float blinkClosedness(const BlinkState &b, double nowSec) {
    const double t = nowSec - b.startedAtSec;
    if (t < 0.0) return 0.0f;
    const auto ease = [](double x) -> float {
        const double c = std::max(0.0, std::min(1.0, x));
        return static_cast<float>(c * c * (3.0 - 2.0 * c));   // smoothstep
    };
    if (t < b.closeSec) return ease(t / b.closeSec);
    if (t < b.closeSec + b.holdSec) return 1.0f;
    if (t < b.closeSec + b.holdSec + b.openSec) {
        return 1.0f - ease((t - b.closeSec - b.holdSec) / b.openSec);
    }
    return 0.0f;
}

/**
 * 眨眼诊断日志开关（`/data/local/tmp/arvt_blink_log` 存在 = 开）。
 *
 * 为什么不是只读一次的 static const bool：调试时希望在 App 已经跑起来之后
 * `adb shell touch` 就能立刻看到日志，不必重装重启。这里 0.5s 才 stat 一次，
 * 相对每帧而言成本可忽略，又足够即时。
 */
bool blinkLogEnabled(double nowSec) {
    static bool s_enabled = false;
    static double s_nextCheckSec = 0.0;
    if (nowSec >= s_nextCheckSec) {
        s_nextCheckSec = nowSec + 0.5;
        s_enabled = access("/data/local/tmp/arvt_blink_log", F_OK) == 0;
    }
    return s_enabled;
}

/** 解析 model3.json 的 Groups 里 EyeBlink 组（Target=Parameter）。
 *  实现放在资源读取工具之后（依赖 ResourceSource），此处先声明。 */
struct ResourceSource;
void loadEyeBlinkGroup(const ResourceSource &src, const std::string &jsonPath);

/**
 * 自动眨眼：在面捕没有提供眼睛值的时间段里生成周期性眨眼。
 *
 * 调用约束：持 g_mutex、在 applyFaceValuesLocked 之后、applyPhysicsLocked 之前。
 * 顺序是硬要求 —— Big_fat_fish 的眼皮由 EyeLOpen 经物理驱动（PhysicsSetting19/
 * 20/22/23 的输入就是 EyeLOpen/EyeROpen），晚于物理写就等于让物理看到上一帧的
 * 眼睛，眼皮不会跟着闭。
 */
void applyAutoBlinkLocked(double nowSec) {
    if (g_eyeBlinkParams.empty() || !g_model) return;

    // ---- 面捕在场判定 + 交接权重渐变更（P16）----
    //
    // 旧实现「看到有限值就当场 return」会在眨眼保持阶段造成一帧闪跳。现在把
    // 「让位」做成有时间常数的渐变：权重从 1 平滑降到 0，交接期间把自动眨眼值
    // 与真实值混合，眼皮就不会硬切。
    bool facePresent = false;
    for (int pi : g_eyeBlinkParams) {
        if (pi < 0 || pi >= static_cast<int>(g_faceValues.size())) continue;
        if (std::isfinite(g_faceValues[static_cast<size_t>(pi)])) {
            facePresent = true;
            break;
        }
    }
    {
        const float target = facePresent ? 0.0f : 1.0f;
        double dt = (g_blinkYieldSec > 0.0) ? (nowSec - g_blinkYieldSec) : 0.0;
        if (g_blinkYieldSec <= 0.0) {
            // 首帧/复位后：直接取目标值。若从 0 起渐变，无摄像头时自动眨眼会被
            // 「淡入」卡在 0.1 权重以下好几帧，进页面后头 0.12s 眼睛状态是错的。
            g_blinkYield = target;
        } else {
            if (dt < 0.0) dt = 0.0;
            if (dt > 0.25) dt = 0.25;   // 掉帧/后台回来时不要一步跳完
            if (dt > 0.0) {
                // 线性斜率：权重变化率固定，交接时长与帧率无关（90fps 约 11 帧）
                const float step = static_cast<float>(dt / kBlinkHandoverSec);
                if (g_blinkYield < target) {
                    g_blinkYield = std::min(target, g_blinkYield + step);
                } else if (g_blinkYield > target) {
                    g_blinkYield = std::max(target, g_blinkYield - step);
                }
            }
        }
        g_blinkYieldSec = nowSec;
    }

    // 权重已完全交给真实数据：撤销待触发的眨眼排期，等面捕消失后重新排期。
    // 注意必须等权重真正到 0 才清 —— 交接途中还要靠 g_blink 的 closed 曲线做混合。
    if (g_blinkYield <= 0.0f) {
        g_blink.active = false;
        g_blink.nextAtSec = 0.0;
        return;
    }

    // 排期：0.8s 启动延迟（避免进入页面立刻闭眼），之后 3~6s 随机一次
    if (g_blink.nextAtSec <= 0.0) {
        g_blink.nextAtSec = nowSec + 0.8;
    }
    if (!g_blink.active && nowSec >= g_blink.nextAtSec) {
        g_blink.active = true;
        g_blink.startedAtSec = nowSec;
        ++g_blink.totalBlinks;
        // 间隔用确定性伪随机（避免引入 <random> 的状态与体积）
        const double jitter = 3.0 + static_cast<double>((g_blink.totalBlinks * 2654435761u) % 3000) / 1000.0;
        g_blink.nextAtSec = nowSec + jitter;
    }

    float closed = 0.0f;
    if (g_blink.active) {
        closed = blinkClosedness(g_blink, nowSec);
        if (closed <= 0.0f &&
            nowSec - g_blink.startedAtSec >
                    g_blink.closeSec + g_blink.holdSec + g_blink.openSec + 0.02) {
            g_blink.active = false;
        }
    }
    // 睁眼阶段、或权重已完全让位给真实数据 → 不写参数：
    // 保留动作/表情/面捕的眼睛值（权重为 0 时写进去只是无意义的自赋值）。
    if (closed <= 0.0f || g_blinkYield <= 0.0f) return;

    float *values = csmGetParameterValues(g_model);
    if (!values) return;
    const float w = g_blinkYield;
    for (int pi : g_eyeBlinkParams) {
        if (pi < 0 || pi >= g_paramCount) continue;
        const float base = g_paramDefaults[static_cast<size_t>(pi)];
        const float open = (base > 0.0f) ? base : 1.0f;   // 默认 0 的模型按 1 处理
        const float autoValue = open * (1.0f - closed);
        // 交接混合：w=1 用自动眨眼，w=0 保留真实数据（此分支已被上面 return 挡住）
        values[pi] = values[pi] * (1.0f - w) + autoValue * w;
    }

    // P14 诊断（限频）：只在真正写眼睛参数的帧打一行，用来证实眨眼时序真的在跑。
    // 50ms 节流 ≈ 每次眨眼 5~6 行，足以看出 closed 的升/降形状。
    if (!blinkLogEnabled(nowSec)) return;
    g_blinkEyeWritten = values[g_eyeBlinkParams.front()];
    ++g_blinkEyeWrittenSerial;
    static double s_lastBlinkLogSec = 0.0;
    if (nowSec - s_lastBlinkLogSec >= 0.05) {
        s_lastBlinkLogSec = nowSec;
        LOGI("blink: closed=%.2f n=%ld eye=%.3f t=%.3f",
             static_cast<double>(closed), g_blink.totalBlinks,
             static_cast<double>(g_blinkEyeWritten),
             static_cast<double>(nowSec - g_blink.startedAtSec));
    }
}

/** 参数 id（下标 = 参数下标），仅用于诊断日志。 */
std::vector<std::string> g_paramNames;

/**
 * 合成面捕注入（真机无摄像头时复现物理抽风）。
 *
 * 为什么必须在 native 层注入：Kotlin 侧推流协程在「人脸丢失」时推的是 NaN
 * （语义 = 本帧交给动作/表情），没有摄像头就永远是 NaN，物理链输入恒为 0，
 * 抖动根本不会发生（实测 rest=100%）。这里按 1Hz 正弦直接写
 * ParamAngleY（摇头）与 ParamMouthOpenY（张嘴），并把 g_faceValues 的对应项
 * 覆盖成有限值，物理输入就变成真实的周期激励。
 *
 * 由 JNI nativeSetSyntheticFace 开关，默认关闭（发布行为不变）。
 */
std::atomic<bool> g_syntheticFace{false};
double g_syntheticStartSec = 0.0;

/** 帧时间戳（drawFrame 每帧写，供合成面捕/诊断使用）。 */
double g_frameNowSec = 0.0;

/**
 * 物理 dt 统计（真机诊断「dt 是否算错」）。
 *
 * 面捕 30Hz、渲染 60/90Hz 时，若把「面捕帧间隔」当成物理步长，或把累计时间
 * 一次性灌进积分器，就会出现幅度随帧率变化的抖动。这里逐帧记录实际喂给
 * 物理累加时钟的 dt，抓 logcat 即可看到它是否稳定在 1/渲染帧率。
 */
/**
 * 单帧最大时间步（秒）。**动作时钟与物理时钟共用这一个值（P9）**。
 *
 * 必须唯一：两条时间轴各钳各的就会脱钩（旧实现动作钳 0.25s、物理钳 0.05s，
 * 掉帧时物理被限速而动作照跑，头发慢半拍）。
 *
 * 取 0.05s 的理由：
 *  - 半隐式欧拉在 k≈200 的刚性链上，h 超过 ~0.05s 开始失稳；
 *  - 20fps 以上的掉帧完全落在范围内，头发不会为了补帧而瞬移；
 *  - 更长的间隔（切后台、卡顿数秒）本就该丢弃，不该一次性灌进积分器。
 *
 * 定义在物理统计结构体之前，因为 drawFrame（动作时钟）与
 * applyPhysicsLocked 都要引用它。
 */
constexpr float kPhysicsMaxDt = 0.05f;

struct PhysicsDtStats {
    double minDt = 1e9;
    double maxDt = 0.0;
    double sumDt = 0.0;
    long frames = 0;
    /** dt<=0 的帧数（P9：这些帧不步进物理，见 simulateChain 的注释）。 */
    long zeroDtFrames = 0;
    /** dt 撞上 kMaxFrameDt 钳制的帧数（诊断时间轴是否被限速）。 */
    long clampedFrames = 0;
    void reset() { *this = PhysicsDtStats(); }
    void add(double dt) {
        if (dt < minDt) minDt = dt;
        if (dt > maxDt) maxDt = dt;
        sumDt += dt;
        ++frames;
        if (!(dt > 0.0)) ++zeroDtFrames;
        if (dt >= static_cast<double>(kPhysicsMaxDt) - 1e-9) ++clampedFrames;
    }
    double meanDt() const { return frames ? sumDt / static_cast<double>(frames) : 0.0; }
};

PhysicsDtStats g_physicsDtStats;

/** 模型状态需要重算（参数/动作/表情/姿势任一变化）。 */
bool g_modelDirty = true;

// 前置声明：loadModel 末尾要立刻应用一次初始状态（动作 / pose），
// 而这两个函数的实现放在它后面（依赖运动数据结构 + 顶点刷新）。
void applyModelStateLocked();
void refreshVertexPositionsLocked();
void reportJitterPeriodicallyLocked(double nowSec);
/**
 * trace 追踪的目标输出参数下标（-2 = 未解析，-1 = 该模型没有可追踪的物理输出）。
 *
 * 为什么是文件级变量而不是函数内 static：它在**模型切换时必须复位**。
 * loadPhysics 会清空并重建链，旧的参数下标在新模型里指向完全不同的参数，
 * 继续复用会追踪到一条与物理无关的列（P13 实测：miku 的 ParamHairFront 恒 1.0）。
 */
int g_traceOutParam = -2;

/** 模型切换时复位 trace 绑定（下一次 trace 会重新解析）。 */
void resetPhysicsTraceBinding() { g_traceOutParam = -2; }

/** 物理链初始化（实现紧跟在求解器一节，loadPhysics 复位时也要用）。 */
inline void initializeChain(PhysicsChain &chain) { xpbd::initialize(chain); }

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
    /** 本批次在 g_vertices 里占据的连续区间（P15：scissor 清除要按顶点求包围盒）。 */
    int firstVertex = 0;
    int vertexCount = 0;
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
    /** 本遮罩在 g_vertices 里占据的连续区间（P15：scissor 清除要按顶点求包围盒）。 */
    int firstVertex = 0;
    int vertexCount = 0;
    int textureIndex = 0;
    /** 遮罩自身的 drawable 下标（诊断用）。 */
    int drawableIndex = -1;
    /**
     * 遮罩所属的部件下标（P7）。
     *
     * 逐帧判定「这个遮罩此刻还该不该生效」的唯一正确依据。
     * 为什么不用 drawable 的可见性/不透明度：Live2D 里有一类「只当遮罩、
     * 自身永不显示」的图层（眼睛的 D_PSD_35/36 就是 vis=0 op=0），
     * 按可见性跳过它们会把被裁剪的 8 个眼睛零件整片裁没。
     *
     * 而**部件**不透明度是另一回事：pose3 把某个互斥部件压成 0 时，
     * 该部件下的所有图层（包括遮罩）都应当整体失效。典型场景是袖子遮挡手部：
     * 动作把「抬起的袖子」部件隐藏后，袖子不再显示，但旧实现仍按它的形状
     * 去裁剪手 —— 手就被一块看不见的袖子切掉了。这正是「特定模型手部
     * 渲染不出来」的成因。按部件不透明度判定可以同时满足眼睛与袖子两种情形。
     */
    int partIndex = -1;
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

// ---------------------------------------------------------------------------
//  手势变换（P5）
// ---------------------------------------------------------------------------
//  用户拖拽 / 双指缩放作用在「投影之后的屏幕空间」：
//    p' = scale * p + offset
//  正交投影矩阵没有旋转分量，所以直接在投影矩阵的行 0/5 上乘 scale、
//  行 12/13 上改偏移即可（见 buildGestureMvp），不必真的做矩阵乘法。
//
//  offset 以**屏幕可视范围的归一化单位**表达（±1 = 屏幕半宽/半高），
//  这样 Kotlin 侧只要把像素位移除以视图尺寸就能传过来，与分辨率无关。
struct GestureTransform {
    float dx = 0.0f;     // 水平偏移（±1 = 屏幕半宽）
    float dy = 0.0f;     // 垂直偏移（±1 = 屏幕半高；向上为正）
    float scale = 1.0f;  // 缩放倍数
};

GestureTransform g_gesture;

/** 缩放边界（P5 要求 0.5~3.0）。 */
constexpr float kScaleMin = 0.5f;
constexpr float kScaleMax = 3.0f;

/**
 * 平移边界的兜底值：仅在「视口尺寸未知」时使用（surfaceChanged 之前）。
 *
 * 正常路径不再用固定值，而是按**模型包围盒**算允许范围（见 computePanLimits）。
 */
constexpr float kPanFallback = 2.0f;

/**
 * 允许滑出屏幕后仍须保留的最小可见像素（P6）。
 *
 * 语义：无论拖到多远，模型包围盒与视口的交集在 X/Y 上都不小于这个像素数，
 * 保证用户永远能看见（并重新抓住）模型。
 */
constexpr float kMinVisiblePx = 80.0f;

/**
 * 按包围盒计算当前缩放下允许的平移范围（P6，取代旧的「中心点必须在屏幕内」）。
 *
 * ## 为什么废除旧规则
 * 旧实现把 |dx| ≤ 0.9 当作边界，等价于「模型 bbox 中心必须留在屏幕内」。
 * 但放大后要看清头部，用户需要把**头部**拖到屏幕中心——此时 bbox 中心
 * （腰/躯干附近）会跑到屏幕外，旧规则会硬顶住不让拖，正是「放大后拖不动」的根因。
 *
 * ## 新规则
 * 把模型 bbox 经手势变换（p' = scale·p + d）后的范围与视口 [-1,1]² 求交，
 * 要求交集在 X 与 Y 上都不小于 [kMinVisiblePx] 像素。解出 d 的可行区间：
 *   - 模型滑到左边：bboxRight + 1 ≥ minNdc  → d ≥ minNdc - 1 - s·half
 *   - 模型滑到右边：1 - bboxLeft ≥ minNdc   → d ≤ 1 + s·half - minNdc
 *
 * bbox 的半宽/半高在 NDC 里是「投影矩阵对角元 × 模型半尺寸」——投影以 bbox
 * 中心为原点，所以变换前 bbox 恒为 [-half, +half]。
 *
 * @param scale 缩放倍数（已钳制）
 * @param outMinX/outMaxX/outMinY/outMaxY 输出允许区间
 * @return false = 视口或包围盒未知，调用方退回 [kPanFallback]
 */
bool computePanLimits(float scale,
                      float *outMinX, float *outMaxX,
                      float *outMinY, float *outMaxY) {
    if (g_viewW <= 0 || g_viewH <= 0 || g_vertices.empty()) return false;

    const float modelW = g_maxX - g_minX;
    const float modelH = g_maxY - g_minY;
    if (!(modelW > 0.0f) || !(modelH > 0.0f)) return false;

    // 投影以 bbox 中心为原点：变换前 bbox = [-halfX, +halfX] × [-halfY, +halfY]
    const float halfX = std::fabs(g_mvp[0]) * modelW * 0.5f;
    const float halfY = std::fabs(g_mvp[5]) * modelH * 0.5f;
    if (!(halfX > 0.0f) || !(halfY > 0.0f)) return false;

    // 80px 对应的 NDC 长度（NDC 跨度 2 = 视口边长）
    const float minNdcX = std::min(2.0f, 2.0f * kMinVisiblePx / static_cast<float>(g_viewW));
    const float minNdcY = std::min(2.0f, 2.0f * kMinVisiblePx / static_cast<float>(g_viewH));

    const float scaledHalfX = scale * halfX;
    const float scaledHalfY = scale * halfY;

    *outMinX = minNdcX - 1.0f - scaledHalfX;
    *outMaxX = 1.0f + scaledHalfX - minNdcX;
    *outMinY = minNdcY - 1.0f - scaledHalfY;
    *outMaxY = 1.0f + scaledHalfY - minNdcY;

    // 数值兜底：区间反向时（理论上不会，除非模型比可见阈值还小）取中点
    if (*outMinX > *outMaxX) *outMinX = *outMaxX = 0.0f;
    if (*outMinY > *outMaxY) *outMinY = *outMaxY = 0.0f;
    return true;
}

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

/**
 * 当前 GL 资源所属的 EGL context（P5 修复「切后台丢部件」的真正根因）。
 *
 * 背景：切后台再回前台时 EGL context 被销毁重建，但 g_program / g_vbo 等句柄
 * 变量里仍是**旧 context 的编号**。新 context 里这些编号要么无效、要么指向
 * 完全不同的对象。旧代码在 surfaceCreated 里无条件 releaseGl()，于是：
 *   glDeleteProgram(旧句柄) → GL_INVALID_VALUE 被压进错误队列；
 * 紧接着 uploadTextures() 对第一张纹理做 glGetError()，读到的是这个**遗留错误**，
 * 判定「texture_00 上传失败」并把它删掉；第二张才拿到干净的错误状态从而成功。
 * 结果是所有引用 0 号纹理的部件（脸、手臂）全部因 tex==0 被跳过，
 * 只剩用 1 号纹理的衣服 —— 这正是用户看到的「只剩衣服」。
 *
 * 修复：记住句柄所属的 context，只有同一个 context 才敢删；换 context 时
 * 直接丢弃句柄记录（旧 GPU 资源随旧 context 一起被系统回收）。
 */
EGLContext g_glContext = EGL_NO_CONTEXT;

/** 读当前线程的 EGL context（无 context 时返回 EGL_NO_CONTEXT）。 */
EGLContext currentEglContext() {
    return eglGetCurrentContext();
}

/** 清空 GL 错误队列，避免上一次操作的遗留错误污染后续判定。 */
void drainGlErrors() {
    // 上限只是防御：正常情况下 1~2 次就清空了
    for (int i = 0; i < 32; ++i) {
        if (glGetError() == GL_NO_ERROR) break;
    }
}

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

/** 取 model3.json 所在目录（带结尾 '/'），用于拼接 Moc/Textures 的相对路径。 */
std::string dirOf(const std::string &path) {
    const size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) return std::string();
    return path.substr(0, slash + 1);
}

/** 把 .moc3 路径换成同目录下的 .model3.json 路径（两者同名前缀）。 */
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

/**
 * 资源来源：设备本地目录。
 *
 * 为什么需要这层抽象：模型全部由用户从 .zip 导入到 filesDir 下（普通文件路径），
 * 应用不携带任何随包模型资源。上层 model3/motion3/physics3 解析、纹理解码、MOC 校验
 * 只有一份实现，共享同一套读取原语。
 *
 * 安全性：path 全部由 Kotlin 侧的安全解压目录拼出（Zip Slip 已在解压阶段拦掉
 * `..` / 绝对路径条目），这里不再做路径穿越检查，但会拒绝空路径与非普通文件，
 * 避免把目录/设备节点当模型读。
 */
struct ResourceSource {
    // 目前只作为「资源来源」的类型标记：读取原语已统一为本地文件，无额外状态。
};

/** 把整个资源读成字节数组（纹理 / json / moc3 共用，不需要对齐）。 */
bool readResource(const ResourceSource & /*src*/, const std::string &path,
                  std::vector<uint8_t> *out) {
    if (path.empty()) return false;

    FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) return false;

    // 先 seek 到末尾拿长度：导入的模型文件大小不可信，按实际长度读，
    // 避免「读到一半才发现比预期短」的半截缓冲区。
    if (std::fseek(file, 0, SEEK_END) != 0) {
        std::fclose(file);
        return false;
    }
    const long length = std::ftell(file);
    if (length <= 0) {
        std::fclose(file);
        return false;
    }
    std::rewind(file);

    out->resize(static_cast<size_t>(length));
    const size_t read = std::fread(out->data(), 1, static_cast<size_t>(length), file);
    std::fclose(file);
    return read == static_cast<size_t>(length);
}

/** 把资源读成文本（json 用，失败返回 false，不区分原因）。 */
bool readResourceText(const ResourceSource &src, const std::string &path, std::string *out) {
    std::vector<uint8_t> bytes;
    if (!readResource(src, path, &bytes)) return false;
    out->assign(reinterpret_cast<const char *>(bytes.data()), bytes.size());
    return true;
}

/**
 * 解码一张 PNG 到 [TextureImage]。
 *
 * stb_image 的接口选择：用 stbi_load_from_memory 而不是 stbi_load，
 * 因为资源都已读成字节数组，统一走内存接口
 * 可以完全避开 STBI_NO_STDIO（本工程裁掉了 stb 的文件 IO 以减小 .so）。
 *
 * 强制 4 通道：Purism Core 的 UV/取样假设 RGBA，缺 alpha 通道的 PNG 若按 3 通道
 * 上传会被 GL 补成 alpha=1，看似没问题但容易在后续加遮罩时埋坑。
 */
bool loadTexture(const ResourceSource &src, const std::string &path, TextureImage *out) {
    std::vector<uint8_t> encoded;
    if (!readResource(src, path, &encoded)) {
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
 * @param mocPath  .moc3 的绝对路径，用来推导同目录的 .model3.json
 * @param err      失败原因（纹理加载失败不致命，只记日志；完全没纹理时在调用方报错）
 * @return 至少成功加载一张纹理
 */
bool loadTextures(const ResourceSource &src, const std::string &mocPath, std::string *err) {
    g_textureImages.clear();

    const std::string jsonPath = modelJsonPathFor(mocPath);
    std::vector<std::string> texturePaths;

    if (!jsonPath.empty()) {
        std::vector<uint8_t> jsonBytes;
        if (readResource(src, jsonPath, &jsonBytes)) {
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
        if (loadTexture(src, dir + relative, &image)) {
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
        *err = "没有任何纹理加载成功（检查模型目录下的 textures）";
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
//  动作 / 表情 / 姿势资源加载（P4）
// ---------------------------------------------------------------------------
//  全部在模型加载的锁内、GL 侧还没有资源时执行；解析失败的条目单独跳过，
//  绝不因为某个 .motion3.json 损坏就让模型整体加载失败。

/**
 * 解析一条 motion3 曲线的 Segments 到 [flat] 尾部，追加一个 MotionCurve。
 *
 * 段值布局（Motion3 格式）：第 0/1 个是起点 t0/v0，之后每个段先是一个
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
bool parseMotionFile(const ResourceSource &src, const std::string &path, MotionData *out) {
    std::string text;
    if (!readResourceText(src, path, &text)) {
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
            const int paramIndex = resolveParamIndex(idName);
            if (paramIndex < 0) continue;
            appendCurve(*segments, paramIndex, -1, false, out);
            continue;
        }
        if (targetName == "PartOpacity") {
            const auto it = g_partIndexById.find(idName);
            if (it == g_partIndexById.end()) continue;
            appendCurve(*segments, -1, it->second, false, out);
            continue;
        }
        // Target=Part（部件可见性）在示例模型里未出现，忽略
    }

    return true;
}

/** 解析 model3.json 的 Motions 段：{ 组名: [ {File}, ... ] }。 */
void loadMotions(const ResourceSource &src, const std::string &jsonPath,
                 const std::string &dir) {
    std::string text;
    if (!readResourceText(src, jsonPath, &text)) {
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
            if (parseMotionFile(src, dir + file->stringOrEmpty(), &motion)) {
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

/**
 * P12：VTS 约定的 idle 兜底加载（model3.json 没有 Motions 段时调用）。
 *
 * 动机（P12 实证）：hiyori_vts 这类 VTS 打包模型没有 pose3.json，model3.json
 * 也不带 Motions 段，但它的互斥（PartArmA=1 / PartArmB=0 的 PartOpacity 常量
 * 曲线）写在 VTS 约定的 idle 动作里——vtube.json 的
 * FileReferences.IdleAnimation 指向 animations/ 下的 motion3.json。VTube
 * Studio 会自动循环播放该动作，所以显示一对手；我们此前不播，PartArmB 停在
 * moc3 默认 op=1.0，于是渲染出两对手。标准 Core 对同一 moc3 的求值与
 * Purism 逐位一致（基准对照记录），「四只手」不是渲染器或
 * Core 的分歧，缺的是这条 VTS 约定的播放链。
 *
 * 查找顺序：
 *   1) 模型目录下任一 .vtube.json 的 FileReferences.IdleAnimation（相对目录）；
 *   2) 兜底：animations/ 子目录里字典序第一个 .motion3.json 文件。
 * 加载成功则 push 一个 "VTS Idle" 组，随后的「默认播第一组第 0 个」逻辑
 * 会自动循环它，无需任何 Kotlin 改动。
 */
void loadVtsIdleMotion(const ResourceSource &src, const std::string &dir) {
    std::string vtubePath, idleFile;
    {
        DIR *d = opendir(dir.c_str());
        if (d) {
            struct dirent *e;
            while ((e = readdir(d)) != nullptr) {
                const size_t len = strlen(e->d_name);
                if (len > 11 && strcmp(e->d_name + len - 11, ".vtube.json") == 0) {
                    vtubePath = dir + e->d_name;
                    break;
                }
            }
            closedir(d);
        }
    }
    if (!vtubePath.empty()) {
        std::string text;
        if (readResourceText(src, vtubePath, &text)) {
            mjson::Value root;
            if (mjson::parse(text, &root)) {
                const mjson::Value *fr = root.find("FileReferences");
                const mjson::Value *idle = fr ? fr->find("IdleAnimation") : nullptr;
                if (idle) idleFile = idle->stringOrEmpty();
            }
        }
    }
    if (idleFile.empty()) {
        // 兜底：vtube.json 缺失或没写 IdleAnimation 时，取 animations/ 的第一个
        const std::string animDir = dir + "animations";
        DIR *d = opendir(animDir.c_str());
        if (d) {
            struct dirent *e;
            std::string best;
            while ((e = readdir(d)) != nullptr) {
                const size_t len = strlen(e->d_name);
                if (len > 13 && strcmp(e->d_name + len - 13, ".motion3.json") == 0) {
                    if (best.empty() || strcmp(e->d_name, best.c_str()) < 0) best = e->d_name;
                }
            }
            closedir(d);
            if (!best.empty()) idleFile = "animations/" + best;
        }
    }
    if (idleFile.empty()) {
        LOGI("VTS idle 兜底: 目录里没有 vtube.json/animations，动作保持为空");
        return;
    }
    // VTS 写入的 IdleAnimation 值是相对 animations/ 目录的文件名（真机实测
    // "hiyori_m02.motion3.json" 实际位于 <模型目录>/animations/ 下），但也兼容
    // 直接写了相对路径的写法：先按模型根目录试，失败再按 animations/ 试。
    MotionData motion;
    if (!parseMotionFile(src, dir + idleFile, &motion) &&
        !parseMotionFile(src, dir + "animations/" + idleFile, &motion)) {
        LOGW("VTS idle 兜底: %s 解析失败", idleFile.c_str());
        return;
    }
    MotionGroup group;
    group.name = "VTS Idle (待机)";
    group.motions.push_back(std::move(motion));
    g_motionGroups.push_back(std::move(group));
    LOGI("VTS idle 兜底: 已加载 %s 作为自动循环 idle", idleFile.c_str());
}

/** 解析一条 exp3.json 的参数表。 */
bool parseExpressionFile(const ResourceSource &src, const std::string &path,
                         const std::string &name, Expression *out) {
    std::string text;
    if (!readResourceText(src, path, &text)) {
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

        const int paramIndex = resolveParamIndex(id->stringOrEmpty());
        if (paramIndex < 0) continue;

        ExpressionParam ep;
        ep.paramIndex = paramIndex;
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
void loadExpressions(const ResourceSource &src, const std::string &jsonPath,
                     const std::string &dir) {
    std::string text;
    if (!readResourceText(src, jsonPath, &text)) return;

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
        if (parseExpressionFile(src, dir + file->stringOrEmpty(),
                                name->stringOrEmpty(), &exp)) {
            g_expressions.push_back(std::move(exp));
        }
    }
    LOGI("表情加载完成: %zu 个", g_expressions.size());
}

/**
 * P14：从 vtube.json 的 Hotkeys 兜底加载表情（VTS 打包模型的唯一来源）。
 *
 * 实证背景（Big_fat_fish / 樱花miku）：两个模型的 model3.json 里**都没有
 * Expressions 段**，几乎所有 .exp3.json 只被 vtube.json 的
 * `Hotkeys[].Action == "ToggleExpression"` 引用。旧实现只读 model3.json，
 * 于是表情列表恒为空 —— 界面上根本没有按钮可点，表现为「表情切换失效」。
 * 而 exp3 的参数（biaoqing8/9、Param125…）与物理输出集合无关，也不存在
 * 「被 csmUpdateModel 前覆盖」的问题；真正断掉的就是这份清单。
 *
 * 读取顺序：Hotkeys 里 File 非空的项（保持作者顺序）→ 去重 →
 * 目录里剩余未被引用的 *.exp3.json 追加在后（用户可能自己往目录里丢表情）。
 *
 * 只在 g_expressions 为空时调用（model3.json 的 Expressions 优先），
 * 保证标准模型的行为完全不变。
 */
void loadVtsHotkeyExpressions(const ResourceSource &src, const std::string &dir) {
    if (!g_expressions.empty()) return;

    std::vector<std::string> files;     // 相对模型目录的文件名
    std::vector<std::string> names;     // 显示名（Hotkey Name，缺失时用文件名）

    // 1) vtube.json 的 Hotkeys（作者意图：顺序 + 名字）
    std::string vtubePath;
    {
        DIR *d = opendir(dir.c_str());
        if (d) {
            struct dirent *e;
            while ((e = readdir(d)) != nullptr) {
                const size_t len = strlen(e->d_name);
                if (len > 11 && strcmp(e->d_name + len - 11, ".vtube.json") == 0) {
                    vtubePath = dir + e->d_name;
                    break;
                }
            }
            closedir(d);
        }
    }
    if (!vtubePath.empty()) {
        std::string text;
        if (readResourceText(src, vtubePath, &text)) {
            mjson::Value root;
            if (mjson::parse(text, &root)) {
                const mjson::Value *hotkeys = root.find("Hotkeys");
                if (hotkeys && hotkeys->isArray()) {
                    for (const mjson::Value &h : hotkeys->items) {
                        const mjson::Value *action = h.find("Action");
                        const mjson::Value *file = h.find("File");
                        if (!action || !file) continue;
                        if (action->stringOrEmpty() != "ToggleExpression") continue;
                        const std::string fileName = file->stringOrEmpty();
                        if (fileName.empty()) continue;

                        // 去重：同一表情文件被多个热键引用时只保留第一次
                        bool duplicate = false;
                        for (const std::string &existing : files) {
                            if (existing == fileName) { duplicate = true; break; }
                        }
                        if (duplicate) continue;

                        const mjson::Value *name = h.find("Name");
                        files.push_back(fileName);
                        names.push_back(name ? name->stringOrEmpty() : std::string());
                    }
                }
            }
        }
    }

    // 2) 目录里未被引用的 .exp3.json（作者没建热键、或用户自己加的）
    //    .vtube.json 里可能带 Folder 子目录，这里只扫模型根目录 —— 够用且无递归风险。
    {
        DIR *d = opendir(dir.c_str());
        if (d) {
            struct dirent *e;
            while ((e = readdir(d)) != nullptr) {
                const size_t len = strlen(e->d_name);
                if (len <= 11 || strcmp(e->d_name + len - 11, ".exp3.json") != 0) continue;
                const std::string fileName(e->d_name);
                bool known = false;
                for (const std::string &existing : files) {
                    if (existing == fileName) { known = true; break; }
                }
                if (known) continue;
                files.push_back(fileName);
                names.emplace_back();   // 空名字 → 用文件名（去掉 .exp3.json）
            }
            closedir(d);
        }
    }

    for (size_t i = 0; i < files.size(); ++i) {
        std::string display = names[i];
        if (display.empty()) {
            display = files[i];
            const size_t suffix = display.size() > 11 ? display.size() - 11 : 0;
            if (suffix > 0) display.erase(suffix);   // 去掉 ".exp3.json"
        }
        // VTS 的 Folder 字段可能是子目录：先按根目录试，失败再按 Folder/skel 组合试
        Expression exp;
        if (parseExpressionFile(src, dir + files[i], display, &exp)) {
            g_expressions.push_back(std::move(exp));
        }
    }
    LOGI("表情加载完成（vtube 热键兜底）: %zu 个", g_expressions.size());
}

/** 解析 model3.json 的 Groups 里 EyeBlink 组（Target=Parameter）。 */
void loadEyeBlinkGroup(const ResourceSource &src, const std::string &jsonPath) {
    g_eyeBlinkParams.clear();
    std::string text;
    if (!readResourceText(src, jsonPath, &text)) return;

    mjson::Value root;
    if (!mjson::parse(text, &root)) return;

    const mjson::Value *groups = root.find("Groups");
    if (groups && groups->isArray()) {
        for (const mjson::Value &g : groups->items) {
            const mjson::Value *target = g.find("Target");
            const mjson::Value *name = g.find("Name");
            if (!target || !name) continue;
            if (target->stringOrEmpty() != "Parameter") continue;
            if (name->stringOrEmpty() != "EyeBlink") continue;

            const mjson::Value *ids = g.find("Ids");
            if (!ids || !ids->isArray()) continue;
            for (const mjson::Value &id : ids->items) {
                const int paramIndex = resolveParamIndex(id.stringOrEmpty());
                if (paramIndex >= 0) g_eyeBlinkParams.push_back(paramIndex);
            }
        }
    }

    // 兜底：Groups 缺失（VTS 打包件偶尔如此）但参数表里有标准名时仍然眨眼。
    // 别名解析已经把 Big_fat_fish 的 EyeLOpen 映射到同一参数，因此用标准名
    // 取到的就是模型自己的眼睛参数。
    if (g_eyeBlinkParams.empty()) {
        for (const char *name : {"ParamEyeLOpen", "ParamEyeROpen"}) {
            const int index = resolveParamIndex(name);
            if (index >= 0) g_eyeBlinkParams.push_back(index);
        }
    }

    if (!g_eyeBlinkParams.empty()) {
        const int first = g_eyeBlinkParams.front();
        LOGI("自动眨眼就绪: %zu 个参数（首个=%s）", g_eyeBlinkParams.size(),
             (first >= 0 && first < static_cast<int>(g_paramNames.size()))
                     ? g_paramNames[static_cast<size_t>(first)].c_str()
                     : "?");
    }
}

/**
 * 解析 pose3.json 的 Groups。
 *
 * 结构：{ "Groups": [ [ { "Id": "Part01ArmRA001", "Link": ["PartXxx"] }, ... ], ... ] }
 * 每组是互斥的部件候选；Link 里列出的部件跟随本组当前选中的候选一起切换。
 *
 * P8 三处修正：
 *  1) 部件名匹配失败时回退到小写表（自定义模型大小写不一致）；
 *  2) **解析后不足 2 个成员的组直接丢弃**。单项组不是「强制可见」：标准
 *     Pose 后处理对单成员组的行为是「它永远获胜」，等于恒点亮 —— 一旦
 *     精确匹配失败把双成员组削成单项，就会凭空多出一只手。丢弃只是少一条
 *     互斥规则，剩余成员回到「跟着动作/默认值走」，比硬点亮安全。
 *  3) 解析失败的组打 WARN 并带上原始成员数，便于真机定位是哪个模型没生效。
 */
void loadPose(const ResourceSource &src, const std::string &jsonPath) {
    std::string text;
    if (!readResourceText(src, jsonPath, &text)) {
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

    // 部件名查下标：精确匹配优先，失败再走小写回退。
    const auto lookupPart = [](const std::string &name) -> int {
        const auto exact = g_partIndexById.find(name);
        if (exact != g_partIndexById.end()) return exact->second;
        std::string lower = name;
        for (char &c : lower) {
            if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
        }
        const auto loose = g_partIndexByLower.find(lower);
        return loose != g_partIndexByLower.end() ? loose->second : -1;
    };

    int linkCount = 0;
    int droppedGroups = 0;
    int relaxedLinks = 0;
    for (const mjson::Value &group : groups->items) {
        if (!group.isArray()) continue;
        const size_t declared = group.items.size();
        std::vector<int> parts;
        std::vector<std::vector<int>> links;
        for (const mjson::Value &item : group.items) {
            const mjson::Value *id = item.find("Id");
            if (!id) continue;
            const int partIndex = lookupPart(id->stringOrEmpty());
            if (partIndex < 0) {
                LOGW("pose3 部件名在 moc3 里查不到，跳过: %s", id->stringOrEmpty().c_str());
                continue;
            }
            // 同一组内重复登记同一部件没有意义，反而会干扰获胜者判定
            if (std::find(parts.begin(), parts.end(), partIndex) != parts.end()) continue;
            parts.push_back(partIndex);

            // Link 可以是单个部件名（旧格式）或名字数组（P7 支持的两种写法）。
            // 解析失败/查不到一律当空，不影响互斥本身。
            std::vector<int> partLinks;
            const mjson::Value *link = item.find("Link");
            if (link && link->isArray()) {
                for (const mjson::Value &name : link->items) {
                    const int linkPart = lookupPart(name.stringOrEmpty());
                    if (linkPart >= 0) partLinks.push_back(linkPart);
                }
            } else if (link && link->type == mjson::Type::String) {
                const int linkPart = lookupPart(link->stringOrEmpty());
                if (linkPart >= 0) partLinks.push_back(linkPart);
            }
            linkCount += static_cast<int>(partLinks.size());
            links.push_back(std::move(partLinks));
        }

        if (parts.size() < 2) {
            // 见上面第 2 条：单项组会被当成「恒获胜 = 恒点亮」，宁可丢弃
            ++droppedGroups;
            LOGW("pose3 组被丢弃（声明 %zu 项，仅解析出 %zu 项）",
                 declared, parts.size());
            continue;
        }
        if (parts.size() != declared) ++relaxedLinks;
        g_poseGroups.push_back(std::move(parts));
        g_poseGroupLinks.push_back(std::move(links));
    }
    LOGI("姿势组加载完成: %zu 组（联动部件 %d 个，丢弃 %d 组，降级 %d 组）",
         g_poseGroups.size(), linkCount, droppedGroups, relaxedLinks);
    g_poseFromFile = !g_poseGroups.empty();
}

/**
 * 兜底互斥：无 pose3 时按「同名 + A/B 后缀」推断互斥部件对（P9）。
 *
 * 详见 g_poseFallbackEnabled 的注释。这里只**追加**组，不修改已从 pose3
 * 读到的组；调用方负责保证「只在 g_poseGroups 为空时调用」。
 *
 * 匹配逻辑：部件名去掉末尾的 'A'/'B' 后必须完全相同，两端同时存在才成对。
 * 大小写敏感（moc3 里的部件名是稳定的），但允许两端大小写一致的前缀差异，
 * 因为去后缀后直接做字符串比较即可。
 */
void buildFallbackPoseGroupsLocked() {
    g_poseFallbackPairs = 0;
    if (!g_poseFallbackEnabled.load(std::memory_order_relaxed)) return;
    if (!g_poseGroups.empty()) return;
    const int partCount = static_cast<int>(g_partDefaults.size());
    if (partCount <= 0) return;

    // 只考虑「默认可显示 + 至少一个 drawable」的部件：否则归零没有画面收益
    constexpr float kVisibleThreshold = 0.001f;
    std::vector<const std::string *> namesByPart(static_cast<size_t>(partCount), nullptr);
    for (const auto &entry : g_partIndexById) {
        if (entry.second >= 0 && entry.second < partCount) {
            namesByPart[static_cast<size_t>(entry.second)] = &entry.first;
        }
    }
    std::unordered_map<std::string, int> pendingA; // 基名 -> 部件下标（...A）
    std::vector<std::vector<int>> pairs;
    for (int i = 0; i < partCount; ++i) {
        const float def = g_partDefaults[static_cast<size_t>(i)];
        if (!std::isfinite(def) || def <= kVisibleThreshold) continue;
        if (i < static_cast<int>(g_partDrawableCounts.size()) &&
            g_partDrawableCounts[static_cast<size_t>(i)] <= 0) {
            continue;
        }
        const std::string *name = namesByPart[static_cast<size_t>(i)];
        if (!name || name->size() < 2) continue;
        const char tail = name->back();
        if (tail != 'A' && tail != 'B') continue;
        const std::string base = name->substr(0, name->size() - 1);
        if (tail == 'A') {
            pendingA.emplace(base, i);
        } else {
            const auto it = pendingA.find(base);
            if (it == pendingA.end()) continue;
            pairs.push_back({it->second, i});
            pendingA.erase(it);
        }
    }

    if (pairs.empty()) return;
    for (std::vector<int> &pair : pairs) {
        g_poseGroups.push_back(std::move(pair));
        // 无 Link 信息：兜底组的两端各自独立，联动列表留空（与 pose3 语义一致）
        g_poseGroupLinks.push_back(
                std::vector<std::vector<int>>(2, std::vector<int>()));
    }
    g_poseFallbackPairs = static_cast<int>(pairs.size());
    LOGI("pose 兜底: 无 pose3，按 A/B 后缀推断出 %d 组互斥（开关=%s）",
         g_poseFallbackPairs,
         g_poseFallbackEnabled.load(std::memory_order_relaxed) ? "on" : "off");
}

// ---------------------------------------------------------------------------
//  物理配置解析（P5）
// ---------------------------------------------------------------------------
//
//  只读 physics3.json 的「输入 / 输出 / 分段质点 / 归一化区间」四类信息；
//  Meta.EffectiveForces 的 Gravity 是全局的，拷到每条链上。
//  （Wind 字段此前被解析并保存但从未参与受力计算，属死代码，已移除。）

/** 读一个 Vector2 字段（缺失时用 fallback）。 */
void readVector2(const mjson::Value *node, float fallbackX, float fallbackY,
                 float *outX, float *outY) {
    *outX = fallbackX;
    *outY = fallbackY;
    if (!node) return;
    if (const mjson::Value *x = node->find("X")) *outX = static_cast<float>(x->numberOr(fallbackX));
    if (const mjson::Value *y = node->find("Y")) *outY = static_cast<float>(y->numberOr(fallbackY));
}

/** 读一个数字字段（缺失或类型不符时用 fallback），避免对 find() 结果直接解引用。 */
float numberField(const mjson::Value *node, const char *key, float fallback) {
    if (!node) return fallback;
    const mjson::Value *value = node->find(key);
    return value ? static_cast<float>(value->numberOr(fallback)) : fallback;
}

/** 物理项的类型：0=X、1=Y、2=Angle。 */
int physicsTypeOf(const mjson::Value *node) {
    if (!node) return 0;
    const mjson::Value *type = node->find("Type");
    if (!type) return 0;
    const std::string &name = type->stringOrEmpty();
    if (name == "Y") return 1;
    if (name == "Angle") return 2;
    return 0;
}

/** 读 Normalization 里的 Position/Angle 三段区间。 */
void readNormalization(const mjson::Value *norm, const char *key,
                       float *minV, float *defV, float *maxV) {
    if (!norm) return;
    const mjson::Value *range = norm->find(key);
    if (!range) return;
    if (const mjson::Value *v = range->find("Minimum")) *minV = static_cast<float>(v->numberOr(*minV));
    if (const mjson::Value *v = range->find("Default")) *defV = static_cast<float>(v->numberOr(*defV));
    if (const mjson::Value *v = range->find("Maximum")) *maxV = static_cast<float>(v->numberOr(*maxV));
}

/**
 * 解析 physics3.json 到 g_physicsChains。
 *
 * 失败的任何单条 setting 只跳过自身：物理是增强效果，不能让模型加载失败。
 */
void loadPhysics(const ResourceSource &src, const std::string &jsonPath) {
    g_physicsChains.clear();
    // 模型切换：累加时钟与参数缓存一并作废（尺寸与语义都跟参数表走）
    g_physicsRemainTime = 0.0f;
    g_paramCaches.clear();
    g_paramInputCaches.clear();
    g_physicsFps = 0.0f;
    // trace 的目标输出参数绑定的是「上一个模型」的参数下标，换模型后必须重解析，
    // 否则会追踪到新模型里语义完全不同的同名下标（P13 实测：miku 上追到的列恒 1.0）。
    resetPhysicsTraceBinding();

    std::string text;
    if (!readResourceText(src, jsonPath, &text)) {
        LOGW("physics3.json 读取失败，物理不生效: %s", jsonPath.c_str());
        return;
    }

    mjson::Value root;
    if (!mjson::parse(text, &root)) {
        LOGW("physics3.json JSON 解析失败: %s", jsonPath.c_str());
        return;
    }

    float gravityX = 0.0f, gravityY = -1.0f;
    if (const mjson::Value *meta = root.find("Meta")) {
        if (const mjson::Value *forces = meta->find("EffectiveForces")) {
            readVector2(forces->find("Gravity"), 0.0f, -1.0f, &gravityX, &gravityY);
        }
        // Meta.Fps（可选）：仅解析备查（加载日志用）。XPBD 起子步长固定
        // xpbd::tuning().substepDt，不再随 json Fps 变化；仍按 json 消毒惯例钳掉离谱值。
        g_physicsFps = numberField(meta, "Fps", 0.0f);
        if (!(g_physicsFps > 0.0f) || g_physicsFps > 240.0f) g_physicsFps = 0.0f;
    }

    const mjson::Value *settings = root.find("PhysicsSettings");
    if (!settings || !settings->isArray()) {
        LOGW("physics3.json 没有 PhysicsSettings 段");
        return;
    }

    for (const mjson::Value &setting : settings->items) {
        if (!setting.isObject()) continue;

        PhysicsChain chain;
        chain.gravityX = gravityX;
        chain.gravityY = gravityY;
        if (const mjson::Value *id = setting.find("Id")) chain.id = id->stringOrEmpty();
        readNormalization(setting.find("Normalization"), "Position",
                          &chain.posMin, &chain.posDef, &chain.posMax);
        readNormalization(setting.find("Normalization"), "Angle",
                          &chain.angMin, &chain.angDef, &chain.angMax);

        if (const mjson::Value *inputs = setting.find("Input")) {
            for (const mjson::Value &item : inputs->items) {
                const mjson::Value *source = item.find("Source");
                const mjson::Value *id = source ? source->find("Id") : nullptr;
                if (!id) continue;
                const int paramIndex = resolveParamIndex(id->stringOrEmpty());
                if (paramIndex < 0) continue;

                PhysicsInput input;
                input.paramIndex = paramIndex;
                input.weight = numberField(&item, "Weight", 100.0f) / kPhysicsMaxWeight;
                input.type = physicsTypeOf(&item);
                input.reflect = item.find("Reflect") && item.find("Reflect")->boolOr(false);
                chain.inputs.push_back(input);
            }
        }

        if (const mjson::Value *outputs = setting.find("Output")) {
            for (const mjson::Value &item : outputs->items) {
                const mjson::Value *dest = item.find("Destination");
                const mjson::Value *id = dest ? dest->find("Id") : nullptr;
                if (!id) continue;
                const int paramIndex = resolveParamIndex(id->stringOrEmpty());
                if (paramIndex < 0) continue;

                PhysicsOutput output;
                output.paramIndex = paramIndex;
                output.vertexIndex = static_cast<int>(numberField(&item, "VertexIndex", 1.0f));
                output.scale = numberField(&item, "Scale", 1.0f);
                output.weight = numberField(&item, "Weight", 100.0f) / kPhysicsMaxWeight;
                output.type = physicsTypeOf(&item);
                output.reflect = item.find("Reflect") && item.find("Reflect")->boolOr(false);
                chain.outputs.push_back(output);
            }
        }

        if (const mjson::Value *vertices = setting.find("Vertices")) {
            for (const mjson::Value &item : vertices->items) {
                PhysicsVertex vertex;
                readVector2(item.find("Position"), 0.0f, 0.0f, &vertex.restX, &vertex.restY);
                if (const mjson::Value *v = item.find("Mobility")) vertex.mobility = static_cast<float>(v->numberOr(1.0));
                if (const mjson::Value *v = item.find("Delay")) vertex.delay = static_cast<float>(v->numberOr(1.0));
                if (const mjson::Value *v = item.find("Acceleration")) vertex.acceleration = static_cast<float>(v->numberOr(1.0));
                if (const mjson::Value *v = item.find("Radius")) vertex.radius = static_cast<float>(v->numberOr(0.0));
                chain.vertices.push_back(vertex);
            }
        }

        // json 消毒（P1 审计保留）：Acceleration/Delay 钳到有限区间；
        // Radius ≤0 或非有限时退回 json Position 差（直接算会出 NaN，这里兜底）。
        for (size_t i = 0; i < chain.vertices.size(); ++i) {
            PhysicsVertex &v = chain.vertices[i];
            v.acceleration = std::max(0.0f, std::min(100.0f, v.acceleration));
            v.delay = std::max(0.0f, std::min(20.0f, v.delay));
            if (i == 0) continue;
            const PhysicsVertex &p = chain.vertices[i - 1];
            const float spanX = v.restX - p.restX;
            const float spanY = v.restY - p.restY;
            const float spanLen = std::sqrt(spanX * spanX + spanY * spanY);
            if (!(v.radius > 1e-6f) || !std::isfinite(v.radius)) {
                v.radius = (spanLen > 1e-6f) ? spanLen : 1e-3f;
                LOGW("物理链 %s 第 %d 节 Radius 无效，退回 Position 差 %.4f",
                     chain.id.c_str(), static_cast<int>(i), v.radius);
            }
        }

        if (chain.vertices.size() < 2 || chain.outputs.empty()) continue;
        LOGI("物理链就绪: %s (in=%zu out=%zu verts=%zu fps=%.1f)",
             chain.id.c_str(), chain.inputs.size(), chain.outputs.size(),
             chain.vertices.size(), g_physicsFps);
        initializeChain(chain);
        g_physicsChains.push_back(std::move(chain));
    }

    LOGI("物理加载完成: %zu 条链", g_physicsChains.size());
}

// ---------------------------------------------------------------------------
//  MOC3 读取
// ---------------------------------------------------------------------------
bool readMoc(const ResourceSource &src, const std::string &path,
             AlignedBuffer *out, std::string *err) {
    std::vector<uint8_t> bytes;
    if (!readResource(src, path, &bytes)) {
        *err = std::string("无法打开模型文件: ") + path;
        return false;
    }

    // MOC 缓冲区要求 64 字节对齐：先读进临时缓冲再拷进对齐块
    if (!out->alloc(csmAlignofMoc, bytes.size())) {
        *err = "MOC 缓冲区分配失败（内存不足？）";
        return false;
    }
    std::memcpy(out->ptr, bytes.data(), bytes.size());
    return true;
}

// ---------------------------------------------------------------------------
//  模型加载 + CPU 几何组装
// ---------------------------------------------------------------------------
bool loadModel(const ResourceSource &src, const std::string &mocPath, std::string *err) {
    if (!readMoc(src, mocPath, &g_mocBuffer, err)) return false;

    const auto mocSize = static_cast<unsigned int>(g_mocBuffer.size);

    // 不可信输入的先决校验（API.md 要求先 consistency 再 revive）
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

    // 文档说明初始化已跑过一次 update，但这里显式再跑一遍，确保顶点就是
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
    // 「被别人当遮罩」与「自己也要显示」并不互斥（前发图层两者皆是）；
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
    const int *drawableParentParts = csmGetDrawableParentPartIndices(g_model);

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
    clearVertexDirty();
    // 实测 2794 顶点 / 12174 索引；Mask 会额外带进几百个顶点，一并预留
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
        g_paramIndexByNorm.clear();
        g_paramNames.assign(static_cast<size_t>(std::max(0, g_paramCount)), std::string());
        if (paramIds && g_paramCount > 0) {
            g_paramIndexById.reserve(static_cast<size_t>(g_paramCount) * 2);
            g_paramIndexByNorm.reserve(static_cast<size_t>(g_paramCount) * 2);
            for (int i = 0; i < g_paramCount; ++i) {
                if (!paramIds[i]) continue;
                g_paramIndexById.emplace(paramIds[i], i);
                g_paramNames[static_cast<size_t>(i)] = paramIds[i];
                // 归一化别名表（见 normalizeParamName 的注释）。同名归一化键
                // 只保留先出现的参数，避免「Param X」与「X」两份都存在于 moc3 时
                // 出现非确定的覆盖顺序。
                g_paramIndexByNorm.emplace(normalizeParamName(paramIds[i]), i);
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

        // P5：量程快照（物理输入归一化 + 输出映射都要用）
        const float *minValues = csmGetParameterMinimumValues(g_model);
        const float *maxValues = csmGetParameterMaximumValues(g_model);
        g_paramMin.assign(static_cast<size_t>(std::max(0, g_paramCount)), -1.0f);
        g_paramMax.assign(static_cast<size_t>(std::max(0, g_paramCount)), 1.0f);
        if (minValues && maxValues) {
            for (int i = 0; i < g_paramCount; ++i) {
                g_paramMin[static_cast<size_t>(i)] = minValues[i];
                g_paramMax[static_cast<size_t>(i)] = maxValues[i];
            }
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
        g_partIndexByLower.clear();
        g_partDefaults.assign(static_cast<size_t>(std::max(0, partCount)), 1.0f);
        if (partIds && partCount > 0) {
            g_partIndexById.reserve(static_cast<size_t>(partCount) * 2);
            g_partIndexByLower.reserve(static_cast<size_t>(partCount) * 2);
            for (int i = 0; i < partCount; ++i) {
                if (!partIds[i]) continue;
                const std::string name(partIds[i]);
                g_partIndexById.emplace(name, i);
                // P8：小写回退表。同一个部件的名字只有大小写差异时不会冲突
                // （模型内的部件名是唯一的，只可能大小写不同后缀不同）。
                if (g_partIndexByLower.find(name) == g_partIndexByLower.end()) {
                    g_partIndexByLower.emplace(name, i);
                }
                std::string lower = name;
                for (char &c : lower) {
                    if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
                }
                if (lower != name) g_partIndexByLower.emplace(lower, i);
            }
        }
        if (partOpacities) {
            for (int i = 0; i < partCount; ++i) {
                g_partDefaults[static_cast<size_t>(i)] = partOpacities[i];
            }
        }
        // 每个部件归属的 drawable 数（兜底互斥只在「归零能真的少画东西」时介入）
        g_partDrawableCounts.assign(static_cast<size_t>(std::max(0, partCount)), 0);
        if (drawableParentParts) {
            for (int i = 0; i < drawableCount; ++i) {
                const int pp = drawableParentParts[i];
                if (pp >= 0 && pp < partCount) {
                    ++g_partDrawableCounts[static_cast<size_t>(pp)];
                }
            }
        }
        LOGI("部件表: count=%d（pose 用）", partCount);
        // 诊断：把部件名与默认不透明度全量打出来。真机上「手不见了」必须能一眼
        // 分清是「部件默认就 0」还是「被后续代码改成 0」，没有这份 dump 就只能猜。
        if (partOpacities && partCount > 0) {
            for (int i = 0; i < partCount; ++i) {
                LOGI("part[%d] %s def=%.3f", i, partIds && partIds[i] ? partIds[i] : "?",
                     partOpacities[i]);
            }
        }
    }
    // 诊断：把「手臂/手」相关 drawable 的静态归属打出来（pose 与遮罩都按部件走）。
    if (drawableParentParts) {
        const char **drawableIds = csmGetDrawableIds(g_model);
        for (int i = 0; i < drawableCount; ++i) {
            const char *id = drawableIds ? drawableIds[i] : nullptr;
            if (!id) continue;
            std::string lower(id);
            for (char &c : lower) {
                if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
            }
            if (lower.find("arm") == std::string::npos &&
                lower.find("hand") == std::string::npos) {
                continue;
            }
            const int pp = drawableParentParts[i];
            LOGI("drawable[%d] %s parentPart=%d op=%.3f visFlags=0x%02x",
                 i, id, pp, opacities ? opacities[i] : -1.0f,
                 dynamicFlags ? dynamicFlags[i] : 0);
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
    int masksUnavailable = 0;

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
                                    int *indexCount, int *outFirstVertex,
                                    int *outVertexCount) -> bool {
        const size_t di = static_cast<size_t>(idx);
        const int vcount = vertexCounts[di];
        const int icount = indexCounts[di];
        if (vcount <= 0 || icount <= 0) return false;
        if (g_vertices.size() + static_cast<size_t>(vcount) > 65535u) {
            // uint16 索引上限；实测 2794 顶点，留 65k 足够，真撞上就放弃该 drawable
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
        if (outFirstVertex) *outFirstVertex = spanFirst;
        if (outVertexCount) *outVertexCount = vcount;
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
        // 之后变可见（手臂的 B 组部件就属于这一类）。init 时按当时的
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
            // -1 = 该掩码当前不可见：Purism Core 明确用它表示「隐藏的掩码」。
            // P7 修正：这里**只是跳过这个掩码**，绝不因为「声明了掩码却全都
            // 不可用」就把被裁剪的内容也丢掉。旧实现在下面 `usedMaskCount==0`
            // 时直接 continue，于是被袖子/衣物遮住的手在袖子隐藏后整只消失。
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
                                    &mb.indexCount, &mb.firstVertex, &mb.vertexCount)) {
                    continue;
                }
                mb.textureIndex = textureIndices[mi];
                mb.drawableIndex = maskIdx;
                mb.partIndex = drawableParentParts ? drawableParentParts[mi] : -1;
                maskBatchOf[mi] = static_cast<int>(g_maskBatches.size());
                g_maskBatches.push_back(mb);
            }
            g_maskRefs.push_back(maskBatchOf[mi]);
        }

        // P7：这里不再因为 usedMaskCount == 0 就丢弃内容。掩码全部隐藏
        // （或全是 -1）时按「无遮罩」绘制 —— 标准语义是掩码缺失
        // 等于不裁剪，而不是把被裁剪的对象一起删掉。
        const int usedMaskCount = static_cast<int>(g_maskRefs.size()) - maskFirst;
        if (ownMaskCount > 0 && usedMaskCount == 0) {
            ++masksUnavailable;
        }

        Batch batch;
        if (!appendGeometry(idx, /*trackBounds=*/true, &batch.firstIndex, &batch.indexCount,
                            &batch.firstVertex, &batch.vertexCount)) {
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
         "nested=%d hiddenRefs=%d masksUnavailable=%d",
         maskedBatches, g_maskBatches.size(), g_maskRefs.size(), invertedBatches,
         nestedMaskMasks, hiddenMaskRefs, masksUnavailable);
    LOGI("bounds: x[%.3f,%.3f] y[%.3f,%.3f]", g_minX, g_maxX, g_minY, g_maxY);

    // ---- P4：动作 / 表情 / 姿势 ----
    // 必须在参数表与部件表建好之后解析：二者的曲线都要把名字翻成下标。
    {
        const std::string jsonPath = modelJsonPathFor(mocPath);
        const std::string dir = dirOf(mocPath);
        g_motionGroups.clear();
        g_expressions.clear();
        g_poseGroups.clear();
        g_poseGroupLinks.clear();
        g_poseFromFile = false;
        g_poseFallbackPairs = 0;
        if (!jsonPath.empty()) {
            loadMotions(src, jsonPath, dir);
            // P12：model3.json 没有 Motions 段的模型（VTS 打包件常见），
            // 补一条 VTS 约定 idle，否则带 PartOpacity 互斥曲线的 idle
            // （hiyori 的 PartArmA=1/PartArmB=0）不会被播放。
            if (g_motionGroups.empty()) loadVtsIdleMotion(src, dir);
            loadExpressions(src, jsonPath, dir);
            // P14：VTS 打包模型（Big_fat_fish / 樱花miku）的 model3.json 没有
            // Expressions 段，表情清单只存在于 vtube.json 的 Hotkeys 里。
            // 没有这一步，界面上的表情条恒为空，表现为「表情切换完全失效」。
            loadVtsHotkeyExpressions(src, dir);
            // P14：眨眼组的参数下标要在解析完 model3.json 的 Groups 后建立，
            // 且必须在 physics3 之前（Big_fat_fish 的眼皮由 EyeLOpen 物理驱动）。
            loadEyeBlinkGroup(src, jsonPath);
            // pose3 / physics3 路径不像 Motions/Expressions 那样由我们自己解析：
            // 直接在 model3.json 的 FileReferences 里找 "Pose" / "Physics" 字段。
            std::string jsonText;
            if (readResourceText(src, jsonPath, &jsonText)) {
                mjson::Value root;
                if (mjson::parse(jsonText, &root)) {
                    const mjson::Value *refs = root.find("FileReferences");
                    const mjson::Value *pose = refs ? refs->find("Pose") : nullptr;
                    if (pose) loadPose(src, dir + pose->stringOrEmpty());
                    const mjson::Value *physics = refs ? refs->find("Physics") : nullptr;
                    if (physics) loadPhysics(src, dir + physics->stringOrEmpty());
                }
            }
        }

        // ---- P9：无 pose3 时的兜底互斥 ----
        // 只在「没有任何 pose3 组」时介入，规则与开关见 buildFallbackPoseGroupsLocked。
        g_poseFileGroupCount = g_poseGroups.size();
        if (!g_poseFromFile) buildFallbackPoseGroupsLocked();

        // 默认播第一组动作的第 0 个（通常是 Idle），并立刻应用一次
        g_activeGroup = g_motionGroups.empty() ? -1 : 0;
        g_activeMotion = (g_activeGroup >= 0 && !g_motionGroups[0].motions.empty()) ? 0 : -1;
        g_motionActive = g_activeMotion >= 0;
        g_motionTime = 0.0;
        g_lastTickSec = 0.0;
        g_activeExpression = -1;
        g_modelDirty = true;
        // 物理：复位全部链的运行时状态（粒子姿态、速度、输出历史），
        // 新模型从静息竖直姿态开始，不拖旧模型的任何历史
        g_physicsDt = 0.0f;
        for (PhysicsChain &chain : g_physicsChains) {
            initializeChain(chain);
        }

        applyModelStateLocked();
        csmUpdateModel(g_model);
        refreshVertexPositionsLocked();
        LOGI("初始状态: motion=%s/%d exp=-1 pose=%zu physics=%zu modelOpacity=%.2f",
             g_activeGroup >= 0 ? g_motionGroups[0].name.c_str() : "none",
             g_activeMotion, g_poseGroups.size(), g_physicsChains.size(), g_modelOpacity);
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
 * Pose 后处理的语义：
 *  1) 每组用**本帧输入不透明度**选出唯一获胜者（最大者，并列取靠前者）；
 *     判据用的是动作/面捕/默认值刚写进去、pose 尚未改动的值，所以本函数必须
 *     在所有写部件不透明度的步骤之后、csmUpdateModel 之前调用。
 *  2) 组内落选者归零，获胜者保留自己的值；获胜者的 Link 部件跟随获胜者，
 *     落选者的 Link 部件一并归零。
 *  3) 全组都不亮（最大值 ≤ 阈值）时按**默认不透明度**回退：挑默认值最大的候选。
 *     这一步专治「模型 A 没手」：手部部件可能被上一帧动作压成 0，而它的 moc3
 *     默认值是 1 —— 回退能把这只手放回来，同时不会误点亮默认值也是 0 的部件。
 *  4) 默认值也全为 0（整组都是 visible=0、只能靠 pose 点亮的部件）时，退化为
 *     「强制第一个候选按 1.0 显示」。互斥组的语义是**恰好一个可见**：整组消失
 *     就是真机上的「没手」；标准 Pose 后处理的淡入终点也是同样的强制可见。
 *
 * ⚠️ P8 关键修复：候选序（ordinal）与 csm 部件下标是两个不同的下标空间。
 * 旧实现拿部件下标去索引 g_poseGroupLinks[组]（形如 links[winner]），
 * 越界时静默跳过、不越界时取到**别的候选**的联动列表：真机表现就是
 * 联动部件（第二只手）没被清零 → 四只手；或获胜手被误零 → 没手。
 */
void applyPoseLocked() {
    if (g_poseGroups.empty() || !g_model) return;

    float *partOpacities = csmGetPartOpacities(g_model);
    if (!partOpacities) return;

    constexpr float kVisibleThreshold = 0.001f;
    const int partCount = static_cast<int>(g_partDefaults.size());
    const size_t groupCount = g_poseGroups.size();

    // ---- 1) 每组选出唯一获胜者：记录**候选序**与**部件下标** ----
    std::vector<int> winnerOrdinal(groupCount, -1);
    std::vector<int> winnerPart(groupCount, -1);
    int forcedVisibleGroups = 0;
    std::vector<uint8_t> isWinner(static_cast<size_t>(std::max(0, partCount)), 0);
    for (size_t gi = 0; gi < groupCount; ++gi) {
        const std::vector<int> &group = g_poseGroups[gi];
        int bestOrdinal = -1;
        int bestPart = -1;
        float bestOpacity = kVisibleThreshold;
        for (size_t oi = 0; oi < group.size(); ++oi) {
            const int partIndex = group[oi];
            if (partIndex < 0 || partIndex >= partCount) continue;
            const float opacity = partOpacities[partIndex];
            if (!std::isfinite(opacity)) continue;
            if (opacity > bestOpacity) {
                bestOpacity = opacity;
                bestOrdinal = static_cast<int>(oi);
                bestPart = partIndex;
            }
        }

        // 回退（见上面第 3 条）：按默认不透明度挑。
        //
        // 两次回退的用意不同：
        //  - 先按 moc3 默认值挑：默认值非 0 的候选说明作者本意是「可显示」，
        //    选它既能把手放回来，又不会点亮作者刻意隐藏的部件；
        //  - 默认值全为 0（整组都是 visible=0 的部件，只能靠 pose 点亮）时，
        //    强制选第一个候选并按 1.0 显示。互斥组的语义就是「必须恰好有
        //    一个可见」，整组消失就是真机上的「模型没手」；宁可显示一组手臂
        //    也不能让手凭空不见（这正是标准 Pose 后处理的淡入终点）。
        bool forcedVisible = false;
        if (bestPart < 0) {
            float bestDefault = kVisibleThreshold;
            for (size_t oi = 0; oi < group.size(); ++oi) {
                const int partIndex = group[oi];
                if (partIndex < 0 || partIndex >= partCount) continue;
                const float def = g_partDefaults[static_cast<size_t>(partIndex)];
                if (!std::isfinite(def)) continue;
                if (def > bestDefault) {
                    bestDefault = def;
                    bestOrdinal = static_cast<int>(oi);
                    bestPart = partIndex;
                }
            }
        }
        if (bestPart < 0) {
            for (size_t oi = 0; oi < group.size(); ++oi) {
                const int partIndex = group[oi];
                if (partIndex < 0 || partIndex >= partCount) continue;
                bestOrdinal = static_cast<int>(oi);
                bestPart = partIndex;
                forcedVisible = true;
                break;
            }
        }
        // 回退选出的获胜者若自身不透明度仍为 0，就按 1.0 点亮：
        // 否则「选出了获胜者」却保留 0 值，屏幕上依然没有这只手。
        // 注意：正常（按最大不透明度）选出的获胜者**不**强制 1.0 —— 那会截断
        // 动作对组内成员做的淡入淡出。
        if (bestPart >= 0 && partOpacities[bestPart] <= kVisibleThreshold) {
            partOpacities[bestPart] = 1.0f;
            forcedVisible = true;
        }

        winnerOrdinal[gi] = bestOrdinal;
        winnerPart[gi] = bestPart;
        if (forcedVisible) ++forcedVisibleGroups;
        if (bestPart >= 0) isWinner[static_cast<size_t>(bestPart)] = 1;
    }

    // ---- 2) 汇总「本帧必须保留」：各组获胜者 + 获胜者的联动部件 ----
    //
    // 必须先汇总再统一归零：同一个部件可能既是 A 组的获胜者、又是 B 组的落选者
    // 或某个落选者的联动部件，边遍历边写会把前面的判定结果冲掉。
    std::vector<uint8_t> keep = isWinner;
    for (size_t gi = 0; gi < groupCount; ++gi) {
        const int ordinal = winnerOrdinal[gi];
        if (ordinal < 0 || gi >= g_poseGroupLinks.size()) continue;
        const std::vector<std::vector<int>> &links = g_poseGroupLinks[gi];
        if (ordinal >= static_cast<int>(links.size())) continue;
        for (int linkPart : links[static_cast<size_t>(ordinal)]) {
            if (linkPart >= 0 && linkPart < partCount) {
                keep[static_cast<size_t>(linkPart)] = 1;
            }
        }
    }

    // ---- 3) 归零：组内落选者 + 落选者的联动部件 ----
    //
    // 组内成员无条件归零：pose3 的互斥语义是「只有一套手臂」，这是「四只手」
    // 唯一可靠的治法 —— 动画若把两套手臂都拉到 1.0，必须由这里压回一套。
    // 联动部件则尊重动画优先级（见 g_partAnimDriven）。
    for (size_t gi = 0; gi < groupCount; ++gi) {
        const std::vector<int> &group = g_poseGroups[gi];
        const int winner = winnerPart[gi];

        for (int partIndex : group) {
            if (partIndex < 0 || partIndex >= partCount) continue;
            if (partIndex != winner && !keep[static_cast<size_t>(partIndex)]) {
                partOpacities[partIndex] = 0.0f;
            }
        }

        if (gi >= g_poseGroupLinks.size()) continue;
        for (const std::vector<int> &partLinks : g_poseGroupLinks[gi]) {
            for (int linkPart : partLinks) {
                if (linkPart < 0 || linkPart >= partCount) continue;
                if (keep[static_cast<size_t>(linkPart)]) continue;
                // 动画显式驱动的联动部件不由 pose 改写（任务要求的优先级）
                if (linkPart < static_cast<int>(g_partAnimDriven.size()) &&
                    g_partAnimDriven[static_cast<size_t>(linkPart)]) {
                    continue;
                }
                partOpacities[linkPart] = 0.0f;
            }
        }
    }

    // ---- 4) 获胜者的联动部件跟随其不透明度（支持动作淡入）----
    for (size_t gi = 0; gi < groupCount; ++gi) {
        const int ordinal = winnerOrdinal[gi];
        const int winner = winnerPart[gi];
        if (ordinal < 0 || winner < 0 || gi >= g_poseGroupLinks.size()) continue;
        const std::vector<std::vector<int>> &links = g_poseGroupLinks[gi];
        if (ordinal >= static_cast<int>(links.size())) continue;

        const float opacity = partOpacities[winner];
        for (int linkPart : links[static_cast<size_t>(ordinal)]) {
            if (linkPart < 0 || linkPart >= partCount) continue;
            // 联动部件自己若也是别组的获胜者，则以它自己的值为准
            if (isWinner[static_cast<size_t>(linkPart)]) continue;
            // P8：动画显式驱动的联动部件保留动画值（优先级高于 pose）
            if (linkPart < static_cast<int>(g_partAnimDriven.size()) &&
                g_partAnimDriven[static_cast<size_t>(linkPart)]) {
                continue;
            }
            partOpacities[linkPart] = opacity;
        }
    }

    // 诊断：每 5 秒最多一次，报告「本帧有几个组是被强制点亮的」。
    // 真机上「四只手 / 没手」都表现为这里计数异常（恒为 0 = pose 没生效，
    // 恒等于组数 = 每个组都在强制点亮，说明动作/默认值把整组压成了 0）。
    if (forcedVisibleGroups > 0) {
        static double lastReportSec = 0.0;
        static int lastForced = -1;
        const double nowSec = std::chrono::duration<double>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        if (lastForced != forcedVisibleGroups || nowSec - lastReportSec > 5.0) {
            LOGI("pose: 强制点亮 %d/%zu 组（其余组按本帧最大不透明度选择）",
                 forcedVisibleGroups, groupCount);
            lastReportSec = nowSec;
            lastForced = forcedVisibleGroups;
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
    // P8：每帧重建「被动画显式驱动」的部件标记，保证 pose 读到的是本帧意图
    if (g_partAnimDriven.size() != g_partDefaults.size()) {
        g_partAnimDriven.assign(g_partDefaults.size(), 0);
    } else {
        std::fill(g_partAnimDriven.begin(), g_partAnimDriven.end(), 0);
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
 * 不透明度（idle 常常 0→1→0 淡入淡出）。
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
            // P8：标记「动画对它有明确意图」，pose 后处理只覆盖组内成员
            if (curve.partIndex < static_cast<int>(g_partAnimDriven.size())) {
                g_partAnimDriven[static_cast<size_t>(curve.partIndex)] = 1;
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  物理求解器（XPBD）：固定子步 + 三类约束 + 速度回推
// ---------------------------------------------------------------------------
//
//  前两代实现的教训：
//   - P5 弹簧伺服有 DC 通路（稳态输入产生恒定输出）且动态被低通化，靠抑制项
//     压制发散与残差，越压越迟滞；
//   - P12 方向跟踪是高通（稳态输出 0）、结构上稳定，但每步只跟 1/5 的
//     一阶跟踪没有真正的惯性 —— 大幅甩头时头发「贴着走」，过冲/回摆全靠
//     附加项凑，摆动的“惯性”并不来自动量本身。
//
//  本代按 XPBD（Extended Position-Based Dynamics）从零实现：
//
//    输入汇总（normalizeParameterValue + Type × Weight，与 json 数据约定
//    一致）→ 目标方向 d / 根粒子平移 → 子步循环（预测 → 一次约束求解 →
//    速度回推）→ 输出取相邻杆相对几何量 → ×Scale → 钳参数量程 → ×Weight
//    混合 → 写参数。
//
//  约束与柔度映射（α = 柔度，子步内缩放为 α̃ = α/h²）：
//   - 距离约束 α=0（刚性杆）；
//   - 目标方向约束 α = xpbd::Tuning::poseCompliance × Delay / Acceleration —— Angle
//     类输入的「目标方向」语义位，稳态平行 → 输出 0（高通结构不变），动态
//     由二阶惯性系统产生（拖尾 + 过冲）；
//   - 弯曲约束（单边）防对折。
//
//  稳定性兜底：
//   - 固定子步长 1/120（与渲染帧率、json Fps 双解耦）；
//   - 柔度语义 = 对刚度 1/α 的弹簧做隐式积分（无条件稳定），叠加速度指数
//     阻尼 + 逐粒子速度硬钳；
//   - json 消毒：Acceleration ≤ 100、Delay ≤ 20（解析侧钳制，P1 审计保留）；
//   - 非有限值 → 整链复位静息姿态 + 限频日志。

// 求解器数学（directionToRadian / normalizeParameterValue / initialize /
// step / evaluateOutput）全部由 xpbd_solver.h 提供，这里只保留 App 侧的
// 参数表适配与写回逻辑 —— 保证「桌面回放调参」与「真机运行」是同一份代码。

/** 参数表快照（求解器只读；每次调用重建，模型切换自动生效）。 */
xpbd::ParamTable makeParamTable() {
    xpbd::ParamTable table;
    table.count = g_paramCount;
    table.min = g_paramMin.data();
    table.max = g_paramMax.data();
    table.def = g_paramDefaults.data();
    return table;
}

/**
 * 输出写回单个参数槽。
 *
 * value = outputValue × Scale，钳到目标参数 [min, max]，再按 Weight 与参数
 * 当前值混合（Weight ≥ 100 直接覆盖）。与旧实现的差异：不做 default 居中的
 * 半程量程映射 —— 这里采用「满量程钳制 + 线性混合」。
 */
void physicsWriteOutputLocked(float *paramValue, float paramMin, float paramMax,
                              float outputValue, const PhysicsOutput &output) {
    float value = outputValue * output.scale;
    if (value < paramMin) {
        value = paramMin;
    } else if (value > paramMax) {
        value = paramMax;
    }
    if (output.weight >= 1.0f) {
        *paramValue = value;
    } else {
        *paramValue = *paramValue * (1.0f - output.weight) + value * output.weight;
    }
}

/**
 * 物理输出写回参数：rig 级调度（累加时钟 + 固定子步 + 帧间插值）。
 *
 * 本帧流程：
 *  1) 确认参数缓存容量（模型切换后参数变多时重建一次）；
 *  2) 累加时钟 += dt（超过 MaxDeltaTime 直接清零 —— 丢时间不补步）；
 *  3) 子步循环（remain ≥ substepDt）：输出槽前进一格、输入线性对齐到子步
 *     时刻、逐链 xpbd::step、输出写 curOut 与参数缓存（后者是组间传播通道：
 *     后一条链的输入能读到前一条链的输出）；
 *  4) 帧末按 remain/substepDt 的比例在「前一次输出」与「本次输出」之间插值。
 *     一阶保持：高刷新率渲染下靠它保证输出连续，只增加 ≤1 个子步的延迟，
 *     不衰减任何频段。即使本帧一步都没跑（remain < h），也照常执行 ——
 *     输出仍随比例平滑推进。
 *
 * 调用约束：**必须持 g_mutex**，且在动作/表情/面捕之后、pose 之前执行
 * （物理读的是本帧最终的头/身体角度）。
 */
void applyPhysicsLocked() {
    if (g_physicsChains.empty() || !g_model) return;
    float *values = csmGetParameterValues(g_model);
    if (!values) return;

    // P14 诊断（顺序证明）：眨眼写入之后物理才能读到闭眼值。若这里读到的
    // eye 值 == 眨眼刚写进去的值，就证明 applyAutoBlinkLocked 确实排在物理之前，
    // 眼皮链（PhysicsSetting19/20/22/23 输入 EyeLOpen/EyeROpen）本帧就是闭着的。
    // 同样只在 /data/local/tmp/arvt_blink_log 存在时开启。
    if (g_blinkEyeWrittenSerial != 0 && !g_eyeBlinkParams.empty() &&
        blinkLogEnabled(g_frameNowSec)) {
        static long s_lastSeenSerial = 0;
        if (g_blinkEyeWrittenSerial != s_lastSeenSerial) {
            s_lastSeenSerial = g_blinkEyeWrittenSerial;
            const int pi = g_eyeBlinkParams.front();
            if (pi >= 0 && pi < g_paramCount) {
                LOGI("blink->physics: eye=%.3f (written=%.3f, %s)",
                     static_cast<double>(values[pi]),
                     static_cast<double>(g_blinkEyeWritten),
                     values[pi] == g_blinkEyeWritten ? "same-value" : "MISMATCH");
            }
        }
    }

    // P12 实验开关（flag 文件，`adb shell touch ...` 即可控制，零 Kotlin 改动）：
    //   /data/local/tmp/arvt_physics_off → 全部输出写回默认值并跳过模拟。
    //   （arvt_physics_fixed_dt 已退役：固定步长兜底成为唯一路径。）
    const bool flagOff = access("/data/local/tmp/arvt_physics_off", F_OK) == 0;
    {
        static bool s_lastOff = false;
        if (flagOff != s_lastOff) {
            LOGI("物理实验开关: off=%d", flagOff ? 1 : 0);
            s_lastOff = flagOff;
        }
    }
    const bool enabled = g_physicsEnabled.load(std::memory_order_relaxed) && !flagOff;

    // P9：唯一的物理 dt 钳制点，与 drawFrame 里的 kMaxFrameDt 完全一致。
    // dt 是「累加时钟的进水量」；物理子步长 h 固定为 xpbd::tuning().substepDt，两者独立。
    const float dt = std::max(0.0f, std::min(kPhysicsMaxDt, g_physicsDt));
    if (enabled) g_physicsDtStats.add(dt);

    if (!enabled) {
        // 调试开关关闭：输出参数写回默认值（动作/表情可能已写过这些参数，
        // 不覆盖就分不清抖的是物理还是动作），并复位全部运行时状态。
        for (PhysicsChain &chain : g_physicsChains) {
            for (const PhysicsOutput &output : chain.outputs) {
                const int pi = output.paramIndex;
                if (pi < 0 || pi >= g_paramCount) continue;
                values[pi] = g_paramDefaults[static_cast<size_t>(pi)];
            }
            initializeChain(chain);
        }
        g_physicsRemainTime = 0.0f;
        g_paramCaches.clear();
        g_paramInputCaches.clear();
        return;
    }

    if (!(dt > 0.0f)) return;   // 无时间流逝（dt<=0）不做任何演化

    // ---- 1) 参数缓存容量：按当前参数总数分配一次，并把实时值作为初值。
    // 只关心「容量够不够」，模型切换后参数变多时自然触发重建。
    const size_t paramCount = static_cast<size_t>(g_paramCount);
    const bool cacheNeedsInit = g_paramCaches.size() < paramCount;
    if (cacheNeedsInit) {
        g_paramCaches.assign(paramCount, 0.0f);
        g_paramInputCaches.assign(paramCount, 0.0f);
        for (size_t i = 0; i < paramCount; ++i) {
            g_paramInputCaches[i] = values[i];
        }
    }

    // ---- 2) 累加时钟 ----
    g_physicsRemainTime += dt;
    if (g_physicsRemainTime > kPhysicsMaxDeltaTime) g_physicsRemainTime = 0.0f;

    const float h = xpbd::tuning().substepDt;   // 固定子步长：与渲染帧率、json Meta.Fps 双解耦

    // ---- 3) 固定子步补步（每子步一次 XPBD 约束求解，Macklin small steps）----
    int steps = 0;
    while (g_physicsRemainTime >= h) {
        // 输入时间对齐：把实时值线性推进到本子步时刻。
        // alpha 是「本子步占用累加时钟的比例」，允许 >1（外插），保持原有语义。
        const float alpha = h / g_physicsRemainTime;
        const float keep = 1.0f - alpha;
        for (int j = 0; j < g_paramCount; ++j) {
            const size_t uj = static_cast<size_t>(j);
            g_paramCaches[uj] = g_paramInputCaches[uj] * keep + values[uj] * alpha;
            g_paramInputCaches[uj] = g_paramCaches[uj];
        }

        // 输出槽位轮转：把上一轮的输出整体推进「前一次」槽，
        // 这样帧末插值两端分别是本次与上次求解的结果。
        for (PhysicsChain &chain : g_physicsChains) {
            chain.prevOut = chain.curOut;
        }

        for (PhysicsChain &chain : g_physicsChains) {
            if (!chain.initialized) initializeChain(chain);
            if (!xpbd::step(chain, makeParamTable(), g_paramCaches.data(), h)) {
                // 求解器检出非有限值并已复位该链：限频记日志（1s 一次，防刷屏）
                static double s_lastWarnSec = 0.0;
                if (g_frameNowSec - s_lastWarnSec > 1.0) {
                    LOGW("物理链 %s 数值异常（XPBD），已复位到静息姿态", chain.id.c_str());
                    s_lastWarnSec = g_frameNowSec;
                }
            }

            for (size_t oi = 0; oi < chain.outputs.size(); ++oi) {
                const PhysicsOutput &output = chain.outputs[oi];
                // 越界 vertexIndex / 参数下标：整条输出跳过，不做任何写回；
                // 几何取法在 xpbd::evaluateOutput 里，与桌面回放台同一份代码。
                if (output.paramIndex < 0 || output.paramIndex >= g_paramCount) continue;
                float outputValue = 0.0f;
                if (!xpbd::evaluateOutput(chain, output, &outputValue)) continue;

                chain.curOut[oi] = outputValue;
                const size_t pui = static_cast<size_t>(output.paramIndex);
                physicsWriteOutputLocked(&g_paramCaches[pui], g_paramMin[pui],
                                         g_paramMax[pui], outputValue, output);
            }
        }

        g_physicsRemainTime -= h;
        // 防御时钟跳变（正常 60fps ≤ 2 子步、掉帧到 0.05s 钳制 ≤ 6 子步）。
        if (++steps > xpbd::tuning().maxSubstepsPerFrame) {
            g_physicsRemainTime = 0.0f;
            break;
        }
    }

    // ---- 3) 帧间插值：真正写参数的只有这一处 ----
    const float frameT = g_physicsRemainTime / h;
    for (PhysicsChain &chain : g_physicsChains) {
        ++g_physicsJitter.frames;
        const size_t count = chain.vertices.size();
        for (size_t oi = 0; oi < chain.outputs.size(); ++oi) {
            const PhysicsOutput &output = chain.outputs[oi];
            const int pi = output.paramIndex;
            if (pi < 0 || pi >= g_paramCount) continue;
            if (output.vertexIndex < 1 || output.vertexIndex >= static_cast<int>(count)) {
                continue;
            }
            const size_t pui = static_cast<size_t>(pi);
            const float blended = chain.prevOut[oi] + (chain.curOut[oi] - chain.prevOut[oi]) * frameT;
            const float before = values[pui];
            physicsWriteOutputLocked(&values[pui], g_paramMin[pui], g_paramMax[pui],
                                     blended, output);

            // 抖动度量：本帧写入相对上一帧参数值的变化量（屏幕上真正看到的量）。
            // 「高频抽风」在数值上就等于「这个差值持续不为 0 且符号反复翻转」。
            {
                const double d = static_cast<double>(values[pui]) -
                                 static_cast<double>(before);
                const double ad = std::fabs(d);
                g_physicsJitter.outputAbsDeltaSum += ad;
                ++g_physicsJitter.outputDeltaCount;
                if (ad > g_physicsJitter.outputAbsMax) g_physicsJitter.outputAbsMax = ad;
                // 方向反转按**参数下标**分别跟踪：相邻两个不同参数的符号天然
                // 可能相反，跨参数比较会把正常的多参数运动计成反转（假阳性）。
                if (static_cast<int>(g_physicsJitter.lastSignByOutput.size()) < g_paramCount) {
                    g_physicsJitter.lastSignByOutput.resize(
                            static_cast<size_t>(std::max(0, g_paramCount)), 0);
                }
                if (pi < static_cast<int>(g_physicsJitter.lastSignByOutput.size())) {
                    const int sign = (d > 1e-9) ? 1 : ((d < -1e-9) ? -1 : 0);
                    int &lastSign = g_physicsJitter.lastSignByOutput[static_cast<size_t>(pi)];
                    if (sign != 0) {
                        if (lastSign != 0 && sign != lastSign) {
                            ++g_physicsJitter.outputFlipCount;
                        }
                        lastSign = sign;
                    }
                }
            }
        }
    }

    // ---- P14：静止时跳过 ref 统计与逐帧追踪的前置（见 drawFrame 门控）----
    // 注意：这里不再做「本帧参数是否变化」的判定 —— 那个判定放在 drawFrame 里
    // 用「上一帧最终参数快照」做整份比较（见 g_lastAppliedParams 注释），
    // 在这里与复位后的默认值比会恒为真。

    // ---- 逐帧曲线追踪（与基准参考实现逐帧对照）----
    // 每帧一行，字段与 host/ref/refphysics 的输入/输出一一对应：
    //   trace: <frame> <timeSec> <dt> <inX> <inZ> <inBodyX> <inBodyZ> <out>
    // 记录**真实输入**而不是让参考实现自己重算正弦：合成面捕的相位起点与
    // 追踪起点不同，若两边各自生成输入，比出来的相位差会混进物理本身。
    //
    // <out> 选哪条物理输出：优先 ParamHairFront（hiyori 等有命名头发的模型），
    // 否则退回**第一条物理链的第一个输出参数**。樱花miku 的 83 条输出全叫
    // Param25/Param27… 这类无名参数，写死 ParamHairFront 会追踪到一个与其
    // 物理无关的常量列（P13 实测：该列恒 1.0，会误导分析）。
    if (g_physicsTrace.load(std::memory_order_relaxed)) {
        static double traceStartSec = 0.0;
        if (g_traceOutParam == -2) {
            // 只在**物理链的输出**里选，不能只看参数是否存在：樱花miku 有
            // ParamHairFront 参数，但它的 83 条输出全是 Param25/Param27… 这类
            // 无名参数，ParamHairFront 恒 1.0（P13 实测，会追到一条死列）。
            // 优先「链输出里叫 ParamHairFront 的」，没有就取第一条链的第一个输出。
            const int hairFront = resolveParamIndex("ParamHairFront");
            g_traceOutParam = -1;
            bool preferred = false;
            for (const PhysicsChain &chain : g_physicsChains) {
                for (const PhysicsOutput &output : chain.outputs) {
                    const int pi = output.paramIndex;
                    if (pi < 0 || pi >= g_paramCount) continue;
                    if (g_traceOutParam < 0) g_traceOutParam = pi;
                    if (pi == hairFront) {
                        g_traceOutParam = pi;
                        preferred = true;
                        break;
                    }
                }
                if (preferred) break;
            }
        }
        const double nowSec = g_frameNowSec;
        if (traceStartSec <= 0.0) traceStartSec = nowSec;
        const auto readParam = [&values](const char *name) -> float {
            const int index = resolveParamIndex(name);
            if (index < 0 || index >= g_paramCount) {
                return std::numeric_limits<float>::quiet_NaN();
            }
            return values[index];
        };
        const float traced = (g_traceOutParam >= 0 && g_traceOutParam < g_paramCount)
                                     ? values[g_traceOutParam]
                                     : std::numeric_limits<float>::quiet_NaN();
        LOGI("trace: %ld %.5f %.5f %.5f %.5f %.5f %.5f %.5f",
             g_traceFrame, nowSec - traceStartSec, static_cast<double>(dt),
             static_cast<double>(readParam("ParamAngleX")),
             static_cast<double>(readParam("ParamAngleZ")),
             static_cast<double>(readParam("ParamBodyAngleX")),
             static_cast<double>(readParam("ParamBodyAngleZ")),
             static_cast<double>(traced));
    }
}

/** 面捕参数覆盖（最高优先级：手臂/身体等由动作驱动，面部由人脸驱动）。 */
void applyFaceValuesLocked() {
    if (g_faceValues.empty()) return;
    float *values = csmGetParameterValues(g_model);
    if (!values) return;

    for (size_t i = 0; i < g_faceValues.size(); ++i) {
        const float v = g_faceValues[i];
        // NaN = 该参数本帧无输入（含被降级的 ±Inf），保留动作/表情的结果。
        if (!std::isfinite(v)) continue;
        // 防御：面捕值即使有限也可能远超参数量程（映射表改错、VTS 端异常），
        // 直接写进去会让插值把顶点甩出画布。按模型量程钳一次。
        const float minV = i < g_paramMin.size() ? g_paramMin[i] : -1.0f;
        const float maxV = i < g_paramMax.size() ? g_paramMax[i] : 1.0f;
        values[i] = (minV <= maxV) ? std::max(minV, std::min(maxV, v)) : v;
    }
}

/**
 * 合成面捕（诊断用，见 g_syntheticFace）。
 *
 * 每次调用写一帧「1Hz 正弦 + 半波张嘴」到 g_faceValues：
 *  - ParamAngleX / ParamAngleY / ParamAngleZ 按 1Hz 正弦扫 ±30°（三轴错相，
 *    避免整张脸只在一个平面里来回、掩盖其他轴的抖动）；
 *  - ParamBodyAngleX / ParamBodyAngleZ 跟随头部的一个固定比例（与
 *    Live2DParamMap 的 BODY_FOLLOW_* 一致，保持观感一致）；
 *  - ParamMouthOpenY 用 0.5Hz 半波（张嘴→闭合），幅值到 1.0。
 *
 * 参数名查不到（模型没有该参数）时跳过，因此对任何模型都能安全启用。
 *
 * 调用约束：GL 线程、持 g_mutex，且在 applyModelStateLocked 之前。
 */
void applySyntheticFaceLocked(double nowSec) {
    if (!g_syntheticFace.load(std::memory_order_relaxed)) return;
    if (g_faceValues.empty()) return;
    if (g_syntheticStartSec <= 0.0) g_syntheticStartSec = nowSec;

    const double t = nowSec - g_syntheticStartSec;
    constexpr double kTwoPi = 6.283185307179586;
    // P12 大幅验证模式（flag 文件 /data/local/tmp/arvt_synth_stress 控制，零
    // Kotlin 改动）：满量程快速正弦 —— X ±30°@1.5Hz 摇头、Y ±30°@1.1Hz 点头、
    // Z ±30°@0.8Hz 侧倾。用于验证粒子求解器在大幅高频输入下不发散、不 NaN、
    // 输出连续（多音激励的 ±17° 是为线性区测量设计的，不适合大幅验证）。
    const bool stress = access("/data/local/tmp/arvt_synth_stress", F_OK) == 0;
    {
        static bool s_lastStress = false;
        static bool s_first = true;
        if (s_first || stress != s_lastStress) {
            LOGI("合成面捕波形: %s", stress ? "大幅应力（±30° 满量程快摆）"
                                            : "多音激励（±17° 线性区）");
            s_lastStress = stress;
            s_first = false;
        }
    }
    float angleX;
    float angleY;
    float angleZ;
    if (stress) {
        angleX = static_cast<float>(30.0 * std::sin(t * kTwoPi * 1.5));
        angleY = static_cast<float>(30.0 * std::sin(t * kTwoPi * 1.1));
        angleZ = static_cast<float>(30.0 * std::sin(t * kTwoPi * 0.8));
    } else {
        // 多音激励（P9.1）：单一 1Hz 正弦让「滞后」只能确定到一个周期（1000ms）
        // 的整数倍，无法判断 200ms 与 1200ms 谁对。改用四个互不成整数比的分量
        // 叠加，波形无周期，互相关峰值唯一 —— 才能把相位滞后测到毫秒级。
        // 幅值合计控制在 ±17°（ParamAngleX 量程 ±30° 的一半左右）。
        // 不能更大：基准实现在 ±30° 多音激励下 ParamHairFront 有 28% 的帧
        // 顶到 ±1 钳制（2.51Hz 恰好落在链的自然频率上），饱和会同时破坏摆幅与
        // 相位的可比性。17° 时两侧都不饱和，测的才是线性响应。
        const auto tone = [&t](double hz, double phase, double amp) {
            return amp * std::sin(t * kTwoPi * hz + phase);
        };
        angleX = static_cast<float>(
                tone(0.53, 0.0, 7.0) + tone(1.13, 1.7, 5.0) +
                tone(1.79, 4.1, 3.0) + tone(2.51, 2.9, 2.0));
        angleY = static_cast<float>(
                tone(0.53, 2.1, 7.0) + tone(1.13, 3.8, 5.0) +
                tone(1.79, 0.6, 3.0) + tone(2.51, 5.1, 2.0));
        angleZ = static_cast<float>(
                tone(0.53, 4.2, 7.0) + tone(1.13, 5.9, 5.0) +
                tone(1.79, 2.7, 3.0) + tone(2.51, 1.1, 2.0));
    }
    // 0.5Hz 张嘴：半波（只张不合过头），幅值 1.0
    const float mouth = static_cast<float>(std::max(0.0, std::sin(t * kTwoPi * 0.5)));

    const auto setParam = [](const char *name, float value) {
        const int index = resolveParamIndex(name);
        if (index < 0) return;
        const size_t idx = static_cast<size_t>(index);
        if (idx >= g_faceValues.size()) return;
        g_faceValues[idx] = value;
    };

    setParam("ParamAngleX", angleX);
    setParam("ParamAngleY", angleY);
    setParam("ParamAngleZ", angleZ);
    setParam("ParamBodyAngleX", angleX * 0.30f);
    setParam("ParamBodyAngleZ", angleZ * 0.20f);
    setParam("ParamMouthOpenY", mouth);
    setParam("ParamMouthForm", 0.0f);
    // 眼睛保持睁开：合成面捕只关心物理输入，避免眨眼干扰对抖动的判断
    setParam("ParamEyeLOpen", 1.0f);
    setParam("ParamEyeROpen", 1.0f);

    g_faceDirty = true;
}

/**
 * 求值链路出口的统一消毒（P7）。
 *
 * 参数值可能来自三个不完全可信的来源：动作曲线的 JSON（自定义模型可写任意
 * 数字）、物理求解器（极端参数下的数值放大）、面捕（VTS 对端异常）。
 * csmUpdateModel 会把非有限值/越界值通过插值扩散到顶点，最终表现为
 * 「面捕一开模型就炸」。这里在 update 之前统一钳一遍：
 *  - 非有限值 → 该参数的模型默认值（比归零更安全：0 可能不在量程内）；
 *  - 越界值 → 钳到 [min, max]（模型自带的量程）；
 *  - 部件不透明度 → 钳到 [0, 1] 且拒绝非有限值。
 */
void sanitizeModelStateLocked() {
    if (!g_model) return;

    float *values = csmGetParameterValues(g_model);
    if (values) {
        for (int i = 0; i < g_paramCount; ++i) {
            const size_t idx = static_cast<size_t>(i);
            float v = values[i];
            if (!std::isfinite(v)) {
                v = idx < g_paramDefaults.size() ? g_paramDefaults[idx] : 0.0f;
            }
            const float minV = idx < g_paramMin.size() ? g_paramMin[idx] : v;
            const float maxV = idx < g_paramMax.size() ? g_paramMax[idx] : v;
            if (minV <= maxV) v = std::max(minV, std::min(maxV, v));
            values[i] = v;
        }
    }

    float *partOpacities = csmGetPartOpacities(g_model);
    if (partOpacities) {
        for (size_t i = 0; i < g_partDefaults.size(); ++i) {
            float o = partOpacities[i];
            if (!std::isfinite(o)) o = g_partDefaults[i];
            partOpacities[i] = std::max(0.0f, std::min(1.0f, o));
        }
    }
}

/**
 * 重建整帧模型状态：复位 → 动作 → 表情 → 面捕 → 姿势 → csmUpdateModel。
 *
 * 顺序是有意的：
 *  1) 复位成模型默认值（动作曲线不是每个参数都覆盖）；
 *  2) 动作写参数与部件不透明度；
 *  3) 表情叠加在动作结果之上（表达式的求值顺序在动作之后）；
 *  4) 面捕覆盖面部参数 —— 保证「说话/眨眼」始终跟人，不被 idle 动作压掉；
 *  5) 物理读本帧最终的头/身体角度，把链尾偏角写回头发/裙摆参数；
 *  6) pose 在最后判定互斥，避免动作写进来的 1 又被覆盖回去；
 *  7) 出口消毒（P7）：非有限/越界值在 update 之前统一收敛。
 *
 * 调用约束：**必须持 g_mutex** 且 g_model 非空。
 */
void applyModelStateLocked() {
    if (!g_model) return;

    resetParametersLocked();
    float modelOpacity = 1.0f;
    applyMotionLocked(&modelOpacity);
    applyExpressionLocked();
    // 合成面捕（诊断开关）先写 g_faceValues，再由 applyFaceValuesLocked 落到参数上
    applySyntheticFaceLocked(g_frameNowSec);
    applyFaceValuesLocked();
    // P14：自动眨眼必须在物理之前 —— Big_fat_fish 的眼皮由 EyeLOpen 经
    // physics3 驱动，晚于物理写就等于让物理本帧读到上一帧的眼睛状态。
    applyAutoBlinkLocked(g_frameNowSec);
    applyPhysicsLocked();
    applyPoseLocked();
    sanitizeModelStateLocked();

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

    // P14：只记录**真正变了**的顶点区间。判据是坐标是否逐位相等：
    //  - 静止部件（占绝大多数）、UV/alpha 都不动 → 一个字节都不用重传；
    //  - 参数驱动的几块网格（头发/眼/口）→ 只传它们所在的连续区间。
    // 区间取 min..max 的保守并集：即使变化的 drawable 分散，也只是多传中间
    // 那几段，仍比整块（big_fat_fish 上万个顶点）小一个量级。
    int dirtyFirst = -1;
    int dirtyLast = -1;
    bool anyChange = false;

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
        bool spanChanged = false;
        for (int v = 0; v < n; ++v) {
            // P7：非有限顶点会污染整块 VBO（GPU 侧表现为面片炸裂/整屏闪烁）。
            // 求值链路里任何一环吃到 NaN 都会传导到这里，因此出口处再拦一次：
            // 保留上一个有效坐标而不是写入 NaN。
            const float x = pos[v].X;
            const float y = pos[v].Y;
            if (std::isfinite(x) && std::isfinite(y)) {
                if (dst[v].x != x || dst[v].y != y) {
                    dst[v].x = x;
                    dst[v].y = y;
                    spanChanged = true;
                }
            }
            // a 恒 1.0：逐 drawable 的 opacity 由 drawFrame 的 uOpacity 提供
            dst[v].a = 1.0f;
        }
        if (spanChanged) {
            anyChange = true;
            const int first = span.firstVertex;
            const int last = span.firstVertex + n - 1;
            if (dirtyFirst < 0 || first < dirtyFirst) dirtyFirst = first;
            if (last > dirtyLast) dirtyLast = last;
        }
    }

    if (!anyChange) return;   // 全静止：不置脏，VBO 本帧零上传

    g_verticesDirty = true;
    if (g_dirtyVertexFirst < 0 || dirtyFirst < g_dirtyVertexFirst) {
        g_dirtyVertexFirst = dirtyFirst;
    }
    if (dirtyLast > g_dirtyVertexLast) g_dirtyVertexLast = dirtyLast;
    // 越界防御：区间始终钳在 [0, size-1]
    if (g_dirtyVertexFirst < 0) g_dirtyVertexFirst = 0;
    if (g_dirtyVertexLast >= vertexTotal) g_dirtyVertexLast = vertexTotal - 1;
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
//  之所以选 a) 是因为标准渲染器与后续加「加法混合」图层时都用预乘管线，
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

/**
 * 把投影矩阵与手势变换合成为最终 MVP（P5）。
 *
 * 变换语义：p_ndc' = scale * p_ndc + (dx, dy)。
 * 由于 g_mvp 是正交投影（只有对角线与非零平移列），复合结果可以原地得到：
 *   - 缩放：行 0 与行 5（列主序的 m[0] / m[5]）乘 scale；
 *   - 平移：m[12] / m[13] 乘 scale 后加上 dx / dy
 *     （必须先缩放原始平移再叠加偏移，否则放大时画面会整体飘走）。
 *
 * @param out 输出 16 个 float（列主序）
 */
void buildGestureMvp(float *out) {
    std::memcpy(out, g_mvp, sizeof(float) * 16);
    const float s = g_gesture.scale;
    out[0] *= s;
    out[5] *= s;
    out[12] = out[12] * s + g_gesture.dx;
    out[13] = out[13] * s + g_gesture.dy;
}

/**
 * 释放 GL 资源（GL 线程调用）。
 *
 * 关键约束：**只在句柄确实属于当前 EGL context 时才 glDelete**。
 * 跨 context 删旧句柄不仅无效，还会把 GL_INVALID_VALUE 压进错误队列，
 * 污染紧随其后的 uploadTextures() 的错误判定（见 g_glContext 注释）。
 * 不属于当前 context 的句柄直接丢弃记录，GPU 内存随旧 context 一起回收。
 */
void releaseGl() {
    const EGLContext current = currentEglContext();
    const bool sameContext = (g_glContext != EGL_NO_CONTEXT && g_glContext == current);

    if (sameContext) {
        if (g_vbo) { glDeleteBuffers(1, &g_vbo); }
        if (g_ibo) { glDeleteBuffers(1, &g_ibo); }
        if (!g_textures.empty()) {
            glDeleteTextures(static_cast<GLsizei>(g_textures.size()), g_textures.data());
        }
        if (g_program) { glDeleteProgram(g_program); }
        if (g_maskProgram) { glDeleteProgram(g_maskProgram); }
    } else if (g_vbo || g_ibo || g_program || g_maskProgram || !g_textures.empty()) {
        LOGW("releaseGl: 句柄属于旧 EGL context（旧=%p 当前=%p），只丢弃记录",
             reinterpret_cast<void *>(g_glContext), reinterpret_cast<void *>(current));
    }

    // 无论是否删除，句柄记录都必须清空：新 context 里它们已无意义
    g_vbo = 0;
    g_ibo = 0;
    g_program = 0;
    g_maskProgram = 0;
    g_textures.clear();
    g_attrPos = g_attrUv = g_attrAlpha = -1;
    g_uniformMvp = g_uniformOpacity = g_uniformTex = -1;
    g_maskUniformMvp = g_maskUniformTex = -1;

    // 丢弃可能残留的错误（旧 context 的操作、或上面的删除失败）
    drainGlErrors();
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

        // 每张纹理上传前清空错误队列：glGetError 是「粘性队列」，
        // 上一次（可能是别的模块或旧 context 遗留）的错误会被误算到本张头上。
        drainGlErrors();

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

        // 只信任「本次 glTexImage2D 之后」读到的错误（前面已 drain 过）
        const GLenum glError = glGetError();
        if (glError != GL_NO_ERROR) {
            // ⚠️ 失败时**不删纹理、也不留空槽**：留下一个已分配但内容为空的纹理，
            // 让引用它的 drawable 照常绘制（只是该图集区域透明）。
            // 早期版本在这里 glDeleteTextures + continue，把槽位置成 0，
            // 结果 drawFrame 里 tex==0 的批次整批跳过 —— 一张图集失败就会让
            // 一半部件凭空消失（「只剩衣服」就是这么来的）。宁可少一块内容，
            // 也不能让整片部件消失。
            LOGE("纹理上传异常: %s glError=0x%04x（保留空纹理，避免整批部件消失）",
                 img.name.c_str(), glError);
            g_textures[i] = tex;
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

/**
 * 配置固定不变的 GL 状态（P7 抽成公共函数）。
 *
 * 两处调用：surfaceCreated（首次 / 上下文重建）与 drawFrame 的模型切换路径。
 * 抽出来是为了保证「切页回来」与「首次进入」「换模型」三条路径的 GL 状态
 * 完全一致 —— 早期只有 surfaceCreated 里设，换模型时新 context 状态缺一半，
 * 表现为黑块/不透明背景。
 */
void configureGlState() {
    // 2D 静态画面：关深度、不开背面剔除（部分 artmesh 是 double-sided），
    // 打开预乘 alpha 混合；背景色与 App 深色主题一致
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    // Live2D 标准预乘混合：着色器已把 RGB 乘过 alpha（见 kFragmentShader）
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
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
}

/**
 * 重建全部 GL 资源：program → 纹理 → VBO/IBO → 固定状态。**调用方必须持 g_mutex**，
 * 且必须在持有目标 EGL context 的 GL 线程上调用。
 *
 * 这是「切页面黑块 / 面捕抽风」修复的核心（P7）：
 *  - 旧代码只在 surfaceCreated 里做一次，模型切换路径抄了一份近似的实现，
 *    两者漂移后出现「有 program 没状态」的半残 context；
 *  - 现在两条路径共用这一份实现，任何一处触发都得到完全相同的 GL 状态。
 *
 * @return false = program 构建失败或没有几何数据（此时不触碰旧资源记录）
 */
bool rebuildGlResourcesLocked() {
    // 先查几何：没有几何时不必编译 shader（否则 buildProgram 已分配的
    // program 会因提前 return 而泄漏，下一次重建又覆盖句柄）。
    if (g_vertices.empty() || g_indices.empty()) {
        LOGW("rebuildGlResources: 没有顶点数据（模型是否加载成功？）");
        return false;
    }

    if (!buildProgram()) {
        LOGE("rebuildGlResources: shader program 构建失败");
        return false;
    }

    // 纹理必须在有 GL context 的线程上传（P1 的核心改动）
    const int uploaded = uploadTextures();
    if (uploaded == 0) {
        LOGE("rebuildGlResources: 没有任何纹理上传成功，画面将全黑");
    }

    glGenBuffers(1, &g_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(g_vertices.size() * sizeof(Vertex)),
                 g_vertices.data(),
                 GL_STATIC_DRAW);
    g_vboVertexCount = static_cast<int>(g_vertices.size());
    // 上传即最新：清掉可能残留的脏标记，避免下一帧重复上传
    clearVertexDirty();

    glGenBuffers(1, &g_ibo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g_ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(g_indices.size() * sizeof(uint16_t)),
                 g_indices.data(),
                 GL_STATIC_DRAW);

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

    configureGlState();

    LOGI("rebuildGlResources: vbo=%u ibo=%u verts=%zu idx=%zu batches=%zu textures=%d/%zu "
         "maskGeoms=%zu stencilBits=%d",
         g_vbo, g_ibo, g_vertices.size(), g_indices.size(), g_batches.size(),
         uploaded, g_textureImages.size(), g_maskBatches.size(), g_stencilBits);
    return true;
}

/**
 * 复位物理链运行时状态（P7：EGL 上下文重建后必须调用）。
 *
 * 为什么必须复位：粒子位置/速度是连续量，而顶点坐标在上下文重建后会按
 * 「默认参数 + 当前动作」重新求值。若保留旧状态，重建那一帧会用「旧位置/
 * 旧速度」去驱动新姿态，表现为头发/裙摆瞬间炸开（面捕抽风的一半成因就是
 * 它）。整链回到静息竖直姿态，输出历史同时清零。
 */
void resetPhysicsStateLocked() {
    for (PhysicsChain &chain : g_physicsChains) {
        initializeChain(chain);
    }
    g_physicsRemainTime = 0.0f;
}

/**
 * 物理抖动自动上报（P8 真机验证）。
 *
 * 真机上不方便点按钮取数据，这里每 5 秒自动把滑动窗口的抖动统计打到 logcat
 * （TAG=Live2DJNI，关键字 "jitter"）。抓 `adb logcat -s Live2DJNI:V` 即可看到
 * 关闭物理前后的对比数据，无需任何 UI 操作。
 *
 * 调用约束：GL 线程、持 g_mutex（由 nativeDrawFrame 调用）。
 */
void reportJitterPeriodicallyLocked(double nowSec) {
    static double lastSec = 0.0;
    if (lastSec <= 0.0) { lastSec = nowSec; return; }
    if (nowSec - lastSec < 5.0) return;
    lastSec = nowSec;

    if (g_physicsJitter.frames <= 0) return;
    LOGI("jitter: frames=%ld enabled=%d outDelta=%.5f outMax=%.5f "
         "flipsPerFrame=%.3f residualSpeed=%.5f rest=%.1f%% chains=%zu",
         g_physicsJitter.frames,
         g_physicsEnabled.load(std::memory_order_relaxed) ? 1 : 0,
         g_physicsJitter.meanOutputDelta(), g_physicsJitter.outputAbsMax,
         g_physicsJitter.flipsPerFrame(), g_physicsJitter.meanResidualSpeed(),
         g_physicsJitter.restRatio() * 100.0, g_physicsChains.size());
    if (g_physicsDtStats.frames > 0) {
        LOGI("dt: frames=%ld min=%.5f mean=%.5f max=%.5f zero=%ld clamped=%ld "
             "syntheticFace=%d",
             g_physicsDtStats.frames, g_physicsDtStats.minDt, g_physicsDtStats.meanDt(),
             g_physicsDtStats.maxDt, g_physicsDtStats.zeroDtFrames,
             g_physicsDtStats.clampedFrames,
             g_syntheticFace.load(std::memory_order_relaxed) ? 1 : 0);
        g_physicsDtStats.reset();
    }
    g_physicsJitter.reset();
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
 * 清空上一份模型的全部 CPU 侧状态（调用方必须持 g_mutex）。
 *
 * 抽出来是因为「首次加载」与「切换模型」两条路径必须共享同一套复位
 * 语义：漏清任何一项（参数表、物理链、动作时钟、手势变换）都会让新模型带着
 * 旧模型的状态启动，表现为「导入后模型扭曲 / 头发炸开 / 位置偏移」。
 *
 * 不碰 GL 句柄：那属于 GL 线程，由 [g_glModelDirty] 通知它在下一帧重建。
 */
void resetModelStateLocked() {
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
    g_paramIndexByNorm.clear();
    g_paramCount = 0;
    g_paramNames.clear();
    g_paramDefaults.clear();
    g_paramMin.clear();
    g_paramMax.clear();
    g_partIndexById.clear();
    g_partIndexByLower.clear();
    g_partDefaults.clear();
    g_partAnimDriven.clear();
    g_partDrawableCounts.clear();
    g_motionGroups.clear();
    g_expressions.clear();
    g_poseGroups.clear();
    g_poseGroupLinks.clear();
    g_physicsChains.clear();
    g_eyeBlinkParams.clear();
    g_blink = BlinkState();
    g_blinkYield = 0.0f;
    g_blinkYieldSec = 0.0;
    g_blinkEyeWritten = -1.0f;
    g_blinkEyeWrittenSerial = 0;
    g_faceValues.clear();
    g_faceDirty = false;
    g_activeGroup = -1;
    g_activeMotion = -1;
    g_activeExpression = -1;
    g_motionActive = false;
    g_motionTime = 0.0;
    g_lastTickSec = 0.0;
    g_physicsDt = 0.0f;
    g_modelOpacity = 1.0f;
    g_modelDirty = true;
    g_lastAppliedParams.clear();
    clearVertexDirty();
    g_vboVertexCount = 0;
    g_textureImages.clear();
    g_drawableTotal = 0;
    g_drawableDrawn = 0;
    g_gesture.dx = 0.0f;
    g_gesture.dy = 0.0f;
    g_gesture.scale = 1.0f;

    // 新模型的纹理/几何与旧的完全不同，GL 侧必须重建（含纹理句柄）
    g_glModelDirty = true;
}

/**
 * 从任意 [ResourceSource] 加载模型（调用方必须持 g_mutex）。
 *
 * @return null = 成功；非 null = 失败原因
 */
const char *loadModelLocked(const ResourceSource &src, const std::string &path,
                            std::string *err) {
    resetModelStateLocked();

    csmSetLogFunction(&coreLog);
    csmSetLogLevel(2);  // 2 = warning 及以上

    // 先加载纹理：loadModel 需要知道哪些纹理槽位是有效的，
    // 才能把引用了空槽位的 drawable 踢掉（而不是贴错图）
    if (!loadTextures(src, path, err)) return err->c_str();
    if (!loadModel(src, path, err)) return err->c_str();
    return nullptr;
}

/**
 * 从设备本地文件加载模型（自定义模型导入）。
 *
 * @param mocPath  .moc3 的**绝对路径**，例如
 *                 `/data/data/com.exarry.arvtubelink/files/live2d_models/1730.../xxx.moc3`
 * @return null = 成功；非 null = 失败原因
 *
 * 这是**唯一**的模型加载入口：model3.json / motion3 / exp3 / pose3 / physics3
 * 的解析、几何组装、物理复位全部走这一套代码。
 *
 * 安全边界：路径由 Kotlin 侧的安全解压目录拼出（Zip Slip 已在解压阶段拦截）。
 * 这里只额外拒绝空路径与非 .moc3 后缀，防止误把目录当模型读。
 *
 * 线程：任意线程可调用；GL 资源由 [g_glModelDirty] 通知 GL 线程在下一帧重建。
 */
JNIEXPORT jstring JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeLoadModelFromPath(
        JNIEnv *env, jobject /*thiz*/, jstring mocPath) {
    g_lastError.clear();

    if (!mocPath) return env->NewStringUTF("模型路径为空");

    const char *pathChars = env->GetStringUTFChars(mocPath, nullptr);
    if (!pathChars) return env->NewStringUTF("模型路径读取失败");
    const std::string path(pathChars);
    env->ReleaseStringUTFChars(mocPath, pathChars);

    if (path.empty()) return env->NewStringUTF("模型路径为空");
    // 后缀校验：只接受 .moc3（大小写不敏感，兼容 Windows 上打包的 zip）
    const std::string lower = [](const std::string &s) {
        std::string out = s;
        for (char &c : out) {
            if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
        }
        return out;
    }(path);
    const std::string suffix = ".moc3";
    if (lower.size() <= suffix.size() ||
        lower.compare(lower.size() - suffix.size(), suffix.size(), suffix) != 0) {
        return env->NewStringUTF("模型路径必须以 .moc3 结尾");
    }

    ResourceSource src;

    std::string err;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        loadModelLocked(src, path, &err);
    }

    if (!err.empty()) {
        g_lastError = err;
        LOGE("nativeLoadModelFromPath 失败: %s (%s)", err.c_str(), path.c_str());
        return env->NewStringUTF(err.c_str());
    }

    LOGI("nativeLoadModelFromPath 成功: %s", path.c_str());
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
    int sanitized = 0;
    for (jsize i = 0; i + 1 < length; i += 2) {
        const int index = static_cast<int>(raw[i]);
        if (index < 0 || index >= g_paramCount ||
            index >= static_cast<int>(g_faceValues.size())) {
            ++unknown;
            continue;
        }

        // ★ P7 面捕防抽风：NaN 保留「本帧不覆盖」语义，±Inf 一律降级成 NaN。
        //
        // 为什么要分开处理：
        //  - NaN 在 C++ 侧是**有意义**的哨兵（人脸丢失时 Kotlin 推 NaN，表示
        //    「本帧该参数交给动作/表情决定」），不能把它当成坏数据丢弃；
        //  - ±Inf 则是纯粹的坏值：它一旦写进 values[]，csmUpdateModel 的插值与
        //    变形计算会把它扩散到相邻顶点，整张脸/整只手瞬间炸成碎片 ——
        //    这正是「开启面捕后模型抽风、顶点炸裂」的直接成因。
        //    降级成 NaN 等价于「本帧没有这个参数的输入」，模型退回动作驱动。
        float value = raw[i + 1];
        if (!std::isfinite(value)) {
            if (std::isnan(value)) {
                g_faceValues[static_cast<size_t>(index)] = value; // 哨兵，保持原样
                ++written;
            } else {
                g_faceValues[static_cast<size_t>(index)] =
                        std::numeric_limits<float>::quiet_NaN();
                ++sanitized;
                ++written;
            }
            continue;
        }

        g_faceValues[static_cast<size_t>(index)] = value;
        ++written;
    }
    env->ReleaseFloatArrayElements(params, raw, JNI_ABORT);

    g_paramWritesTotal += written;
    g_paramUnknownTotal += unknown;
    g_paramSanitizedTotal += sanitized;

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
            const int paramIndex = resolveParamIndex(chars);
            if (paramIndex >= 0) out[static_cast<size_t>(i)] = paramIndex;
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

    // ★ P7：**无条件**置脏（v1.3.0 切页黑块/面捕抽风的根因）。
    //
    // surfaceCreated 意味着「当前的 GL 句柄全部作废」：EGL context 可能被销毁
    // 重建过，program/VBO/IBO/纹理的编号在新 context 里要么无效、要么指向别的
    // 对象。旧代码在这里把脏标记清成 false，等于告诉 drawFrame「GL 资源是好的」，
    // 而实际可能只重建了一半 —— 画出来就是硬边黑块。
    // 现在：先置 true（保证任何失败路径下下一帧都会再试），重建成功后才清。
    g_glModelDirty = true;

    // releaseGl 必须看到「旧」context 才能判断句柄是否可删，因此先释放再更新记录。
    releaseGl();
    g_glContext = currentEglContext();
    LOGI("nativeSurfaceCreated: eglContext=%p（%s）",
         reinterpret_cast<void *>(g_glContext),
         g_glContext == EGL_NO_CONTEXT ? "无 context，GL 调用不可用" : "ok");

    // ---- P5/P7：GL 上下文重建后的状态恢复 ----
    // 切后台/切页再回来时 csm 模型状态仍停在「停渲染前的那一帧」。若直接把
    // 当时的 g_vertices 传上去，会把一个可能处于中间态（pose 把手臂压成 0、
    // 动作淡入到一半）的快照固化下来。
    // 这里强制走一次完整状态重建：复位默认值 → 动作 → 表情 → 面捕 → 物理 → pose
    // → csmUpdateModel → 抄顶点，与「重新进入页面」完全同一条路径。
    if (g_model) {
        // 时间基准清零：停渲染期间积累的 dt 不能一次灌进动作/物理积分
        g_lastTickSec = 0.0;
        g_physicsDt = 0.0f;
        // 物理链的绝对位置/速度属于旧 context 的旧姿态，必须整体复位，
        // 否则重建首帧会把旧速度灌进新姿态（头发/裙摆炸开）。
        resetPhysicsStateLocked();
        g_modelDirty = true;
        applyModelStateLocked();
        csmUpdateModel(g_model);
        refreshVertexPositionsLocked();
        LOGI("surfaceCreated 状态重建: drawables=%d pose=%zu physics=%zu verts=%zu",
             g_drawableTotal, g_poseGroups.size(), g_physicsChains.size(),
             g_vertices.size());
    }

    if (rebuildGlResourcesLocked()) {
        // 重建成功：脏标记到此已满足，避免下一帧再白跑一遍完整重建
        g_glModelDirty = false;
    } else {
        LOGE("nativeSurfaceCreated: GL 资源重建失败，保持脏标记等下一帧重试");
    }
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
 * 手势变换（P5/P6）：拖拽位移 + 双指缩放。
 *
 * @param dx    水平偏移，单位 = 屏幕半宽（±1 即贴到屏幕左右边缘）
 * @param dy    垂直偏移，单位 = 屏幕半高（向上为正）
 * @param scale 缩放倍数
 *
 * 边界在这里统一钳制（不信任调用方）：
 *  - scale 夹到 [0.5, 3.0]；
 *  - 平移按**包围盒**算可行区间（[computePanLimits]）：只要模型还有
 *    [kMinVisiblePx] 像素留在屏幕内就允许拖，因此放大后可以把头部拖到屏幕中心
 *    （旧版「中心点必须在屏幕内」会顶住，是放大拖不动的根因）。
 *
 * 线程：任意线程可调用（Kotlin 手势回调在主线程）。只写三个 float，
 * 真正的矩阵合成在 GL 线程的 drawFrame 里做。
 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetTransform(
        JNIEnv * /*env*/, jobject /*thiz*/, jfloat dx, jfloat dy, jfloat scale) {
    std::lock_guard<std::mutex> lock(g_mutex);

    float s = scale;
    if (!(s > 0.0f)) s = 1.0f; // 同时挡住 NaN
    s = std::max(kScaleMin, std::min(kScaleMax, s));
    g_gesture.scale = s;

    float x = dx;
    float y = dy;
    if (!std::isfinite(x)) x = 0.0f;
    if (!std::isfinite(y)) y = 0.0f;

    float minX = -kPanFallback, maxX = kPanFallback;
    float minY = -kPanFallback, maxY = kPanFallback;
    computePanLimits(s, &minX, &maxX, &minY, &maxY);

    g_gesture.dx = std::max(minX, std::min(maxX, x));
    g_gesture.dy = std::max(minY, std::min(maxY, y));
}

/** 重置手势变换（回到 1x、居中）。 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeResetTransform(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_gesture.dx = 0.0f;
    g_gesture.dy = 0.0f;
    g_gesture.scale = 1.0f;
}

/**
 * 查询指定缩放下的平移可行区间（P6），供 Kotlin 侧同步 UI 状态。
 *
 * 为什么需要这个接口：真正的钳制在 native（单一事实来源），但 Compose 侧
 * 的 panX/panY 是累加量。若 UI 不知道边界，手势会一直累加到「以为还能拖」
 * 的错误值，松手再拖时画面要「先追回差值」才开始动。
 *
 * @return `[minX, maxX, minY, maxY]`；视口/包围盒未知时返回 ±[kPanFallback]
 */
JNIEXPORT jfloatArray JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativePanLimits(
        JNIEnv *env, jobject /*thiz*/, jfloat scale) {
    std::lock_guard<std::mutex> lock(g_mutex);

    float s = scale;
    if (!(s > 0.0f)) s = 1.0f;
    s = std::max(kScaleMin, std::min(kScaleMax, s));

    float minX = -kPanFallback, maxX = kPanFallback;
    float minY = -kPanFallback, maxY = kPanFallback;
    computePanLimits(s, &minX, &maxX, &minY, &maxY);

    jfloat values[4] = {minX, maxX, minY, maxY};
    jfloatArray result = env->NewFloatArray(4);
    if (result) env->SetFloatArrayRegion(result, 0, 4, values);
    return result;
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
 *   csm* 求值与 nativeUpdateParameters / 模型加载必须互斥，因此状态重建和
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
 * 多个遮罩取**并集**（连续 REPLACE 到同一个位），这是标准渲染语义；
 * 取交集会让「多个遮罩共同裁剪」的图层只剩极小一块。
 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeDrawFrame(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    // ★ P7：入口就把 stencil 一起清掉（v1.3.0 切页黑块的一半成因）。
    //
    // 旧代码只清颜色，stencil 位残留着上一次绘制（甚至上一个 EGL context）
    // 写进去的 1。GL_EQUAL 1 的批次于是按一块「幽灵遮罩」裁剪，画出来就是
    // 硬边黑方块。这里在**任何绘制之前**把两者一起清掉，代价可忽略。
    //
    // glStencilMask 必须先置满：掩码为 0 时 glClear 清不动模板缓冲。
    // P15：同时确保 GL_SCISSOR_TEST 是关的 —— 上一次绘制若在 glScissor 之后
    // 异常离开（或换模型路径提前 return），残留的裁剪框会把这一次全屏清除
    // 限制在一个小矩形里，模板旧 1 清不掉 → 幽灵遮罩（P7 那种硬边黑块）。
    glDisable(GL_SCISSOR_TEST);
    glStencilMask(0xFF);
    glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

    // 绘制计划缓冲：函数级 static 复用，避免每帧分配（大小恒定，几十项）
    static std::vector<Batch> s_batches;
    static std::vector<MaskBatch> s_maskBatches;
    static std::vector<int> s_maskRefs;
    static std::vector<GLuint> s_textures;
    static std::vector<int> s_order;
    static std::vector<float> s_opacity;
    static std::vector<uint8_t> s_maskActive;

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

        // ---- 0) 模型已换 / GL 资源失效：整体重建（P6 自定义模型导入，P7 统一路径）----
        // 导入新模型时 EGL context 仍然活着，surfaceCreated 不会再触发，因此
        // 旧 VBO/IBO（上一个模型的拓扑）与旧纹理句柄必须在这里换掉。
        // 顺序：先释放旧 GL 资源 → 重建 program/纹理/VBO/IBO/固定状态。
        if (g_glModelDirty) {
            // 同一 context 下才真正 glDelete；跨 context 由 releaseGl 内部判定
            releaseGl();
            g_glContext = currentEglContext();

            if (g_vertices.empty() || g_indices.empty()) {
                // 还没有模型（或模型加载失败）：没有几何可重建。
                // 必须清脏标记，否则会在 60Hz 下每帧重编 shader 又立刻删掉。
                // 之后模型加载成功时 resetModelStateLocked() 会重新置脏。
                g_glModelDirty = false;
                return;
            }

            // 新模型的参数/动作/pose 全部就绪，重建一次完整状态再上传顶点
            g_lastTickSec = 0.0;
            g_physicsDt = 0.0f;
            resetPhysicsStateLocked();
            g_modelDirty = true;
            if (g_model) {
                applyModelStateLocked();
                csmUpdateModel(g_model);
                refreshVertexPositionsLocked();
            }

            if (rebuildGlResourcesLocked()) {
                // 新模型可能有不同尺寸的包围盒：重算投影
                updateProjection();
                // 重建成功才清脏标记；失败则保留，下一帧继续重试（避免永久黑屏）
                g_glModelDirty = false;
                LOGI("模型切换/GL 重建完成: verts=%zu idx=%zu batches=%zu textures=%zu",
                     g_vertices.size(), g_indices.size(), g_batches.size(),
                     g_textures.size());
            } else {
                LOGE("模型切换/GL 重建失败: verts=%zu idx=%zu（保持脏标记，下一帧重试）",
                     g_vertices.size(), g_indices.size());
            }
        }

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
        // 投影 + 手势（缩放/平移）合成最终 MVP
        buildGestureMvp(mvp);

        // ---- 1) 推进动作时钟 ----
        //
        // P9：动作与物理共用**同一个已钳制的 dt**（单一时间轴）。
        //
        // 旧实现两条时钟各钳各的：动作钳到 0.25s，物理在 applyPhysicsLocked 里
        // 钳到 0.05s。掉帧到 ~50ms 以上时物理被限速、动作照常推进，二者按不同
        // 速率走 —— 头发（物理输出）与骨架（动作曲线）就脱钩了，表现为「头已经
        // 转过去了、头发还慢半拍」。这里是唯一的时间源：先钳一次，再喂给两边。
        const double nowSec = std::chrono::duration<double>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        if (g_lastTickSec <= 0.0) g_lastTickSec = nowSec;
        const double dt = std::min(static_cast<double>(kPhysicsMaxDt),
                                   std::max(0.0, nowSec - g_lastTickSec));
        g_lastTickSec = nowSec;
        // 帧时间戳：合成面捕（1Hz 正弦）与诊断统计都以它为准，
        // 保证「输入相位」与「物理步长」来自同一条时间轴。
        g_frameNowSec = nowSec;

        if (g_motionActive && dt > 0.0) {
            g_motionTime += dt;
            g_modelDirty = true; // 动作每帧都在变，需要重新求值
        }

        // 物理是连续系统：即使动作停了、面捕没新数据，头发也在继续回弹，
        // 因此只要存在物理链就必须逐帧推进累加时钟（否则松手瞬间会僵在半空）。
        // dt=0 时这里传 0，物理累加时钟不走（无时间流逝就无演化）。
        g_physicsDt = static_cast<float>(dt);
        g_traceFrame = (g_physicsTrace.load(std::memory_order_relaxed) ? g_traceFrame + 1 : 0);
        const bool physicsRunning = !g_physicsChains.empty();

        // ---- 2) 状态重建（动作 / 表情 / 面捕 / 眨眼 / 物理 / pose）----
        //
        // P14 两级门控：状态应用必须每帧跑（物理累加时钟要推进），但
        // csmUpdateModel（整模型求值 + 插值）与顶点抄写只在**参数真的变了**时才做。
        // 判据来自各阶段的写回结果：
        //   - 动作在播 / 面捕有新数据 / 动作表情被切换 → 必然变；
        //   - 物理链全部收敛且输入没变 → g_physicsChangedThisFrame = false；
        //   - 眨眼处于睁眼间歇 → g_blinkChangedThisFrame = false。
        // 静止时（用户不动、无摄像头）这一条能省掉整帧的求值与顶点遍历。
        if (g_model && (g_modelDirty || g_faceDirty || physicsRunning)) {
            const bool externalDriven = g_modelDirty || g_faceDirty;
            applyModelStateLocked();

            // P14 渲染门控：比较「本帧最终参数」与「上一帧真正求值的那一份」。
            // 相等 → 模型输入零变化，csmUpdateModel（整模型插值）与顶点遍历
            // 都可以整帧跳过。静止的模型（无动作/无面捕/物理已收敛）占大多数
            // 时间，这一步是本轮性能优化的主要来源之一。
            bool paramsChanged = externalDriven;
            if (!paramsChanged) {
                const float *values = csmGetParameterValues(g_model);
                const size_t count = static_cast<size_t>(std::max(0, g_paramCount));
                if (values && g_lastAppliedParams.size() == count) {
                    for (size_t i = 0; i < count; ++i) {
                        if (g_lastAppliedParams[i] != values[i]) {
                            paramsChanged = true;
                            break;
                        }
                    }
                } else {
                    paramsChanged = true;   // 首次 / 换模型：必须求值一次
                }
            }

            if (paramsChanged) {
                csmUpdateModel(g_model);
                refreshVertexPositionsLocked();
                const float *values = csmGetParameterValues(g_model);
                if (values && g_paramCount > 0) {
                    g_lastAppliedParams.assign(values,
                                               values + static_cast<size_t>(g_paramCount));
                }
            }
            g_faceDirty = false;
        }

        // ---- 2b) 物理抖动自动上报（每 5s 一行 logcat，见 reportJitterPeriodicallyLocked）----
        reportJitterPeriodicallyLocked(nowSec);

        // ---- 3) 顶点重传（P14：只传本帧真正变化了的区间）----
        // 旧实现每帧整块 glBufferSubData：big_fat_fish 上万个顶点 × 20 字节
        // 每帧都要 DMA 一遍，是 44fps 的主要来源之一。
        // 现在 refreshVertexPositionsLocked 已经把「变化的连续区间」记下来，
        // 静止帧完全不传，动起来的帧只传头发/眼睛/口那几段。
        // 兜底路径：VBO 容量与 CPU 顶点数不一致（模型在 surfaceCreated
        // 之后重跑过），改走 glBufferData 重新分配，避免越界写显存。
        if (g_verticesDirty && !g_vertices.empty()) {
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            const GLsizeiptr bytes =
                    static_cast<GLsizeiptr>(g_vertices.size() * sizeof(Vertex));
            if (g_vboVertexCount == static_cast<int>(g_vertices.size())) {
                const int first = std::max(0, g_dirtyVertexFirst);
                const int last = std::min(static_cast<int>(g_vertices.size()) - 1,
                                          g_dirtyVertexLast);
                if (first <= last) {
                    // 区间上传：偏移与长度都按顶点计
                    glBufferSubData(GL_ARRAY_BUFFER,
                                    static_cast<GLintptr>(first * sizeof(Vertex)),
                                    static_cast<GLsizeiptr>((last - first + 1) * sizeof(Vertex)),
                                    g_vertices.data() + first);
                }
            } else {
                LOGW("顶点数变化 (%d -> %zu)，重建 VBO", g_vboVertexCount, g_vertices.size());
                glBufferData(GL_ARRAY_BUFFER, bytes, g_vertices.data(), GL_STATIC_DRAW);
                g_vboVertexCount = static_cast<int>(g_vertices.size());
            }
            g_verticesDirty = false;
            g_dirtyVertexFirst = -1;
            g_dirtyVertexLast = -1;
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

        // ---- P7：逐帧判定每个遮罩几何此刻是否仍然生效 ----
        //
        // 判据是**遮罩所属部件**的不透明度（不是遮罩 drawable 自己的可见性，
        // 理由见 MaskBatch::partIndex 的注释）。
        //
        // 关键细节：只在「默认可见、本帧被压成 0」时判定失效，而不是
        // 「本帧 opacity == 0」就失效。因为眼睛那类「只当遮罩、自身永不显示」
        // 的图层在**默认状态下**就是 op=0（见 kMaskFragmentShader 注释），
        // 若按绝对值为 0 判定，会把它们的遮罩一并关掉，眼睛变成没被裁切的
        // 大方块。用「相对默认值的变化」作为判据，两种情形都能正确区分：
        //   - 眼睛遮罩：默认 0 → 本帧 0，无变化，继续生效；
        //   - 袖子遮罩：默认 1 → pose 压成 0，判定失效，手不再被切。
        s_maskActive.assign(s_maskBatches.size(), 1);
        {
            const float *partOpacities = g_model ? csmGetPartOpacities(g_model) : nullptr;
            if (partOpacities) {
                const int partCount = static_cast<int>(g_partDefaults.size());
                for (size_t i = 0; i < s_maskBatches.size(); ++i) {
                    const int pi = s_maskBatches[i].partIndex;
                    if (pi < 0 || pi >= partCount) continue; // 无部件归属：永远生效
                    const float o = partOpacities[pi];
                    if (!std::isfinite(o)) continue;
                    const float def = g_partDefaults[static_cast<size_t>(pi)];
                    // 只有「默认可见」的部件被隐藏时才判定遮罩失效
                    if (def > 0.001f && o <= 0.001f) s_maskActive[i] = 0;
                }
            }
        }

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

    // ---- P15：模板清除改按包围盒 glScissor ----
    //
    // 旧实现在**每个**被遮罩批次开头都 glClear(GL_STENCIL_BUFFER_BIT) 一次，
    // big_fat_fish 有 69 个被遮罩批次 → 每帧最多 69 次全屏模板清除（入口还有
    // 一次），这是全屏填充量的最大头。
    //
    // 正确性约束：模板里上一批写进去的 1 必须在下一批之前清掉，否则
    // GL_EQUAL 1 会按一块「幽灵遮罩」放行。因此不能只清本批遮罩的包围盒，
    // 必须清「本批遮罩包围盒 ∪ 上一批残留的 1 的包围盒」（残留只在遮罩几何
    // 的包围盒内，内容绘制是 GL_KEEP 不写模板）。
    //
    // 帧入口已经全屏清过一次，所以 s_stencilDirty 从空集开始。
    // 真机验证开关：/data/local/tmp/arvt_stencil_full 存在 → 退回全屏清除，
    // 用于同版本 A/B 对比 FPS。
    const bool stencilFullClear = access("/data/local/tmp/arvt_stencil_full", F_OK) == 0;
    {
        static bool s_lastFull = false;
        if (stencilFullClear != s_lastFull) {
            LOGI("模板清除模式: %s", stencilFullClear ? "全屏(AB 对照)" : "scissor 包围盒");
            s_lastFull = stencilFullClear;
        }
    }

    const auto ndcToPixel = [&](float x, float y, int *px, int *py) {
        const float ndcX = mvp[0] * x + mvp[4] * y + mvp[12];
        const float ndcY = mvp[1] * x + mvp[5] * y + mvp[13];
        *px = static_cast<int>(std::floor((ndcX * 0.5f + 0.5f) * static_cast<float>(g_viewW)));
        *py = static_cast<int>(std::floor((ndcY * 0.5f + 0.5f) * static_cast<float>(g_viewH)));
    };

    /** 一组顶点在屏幕像素空间的包围盒（已钳到视口并加 2px 余量）。 */
    struct PixelRect {
        int x0 = 1, y0 = 1, x1 = -1, y1 = -1;   // 空集 = x1 < x0
        bool empty() const { return x1 < x0 || y1 < y0; }
        void add(int x, int y) {
            if (empty()) {
                x0 = x1 = x;
                y0 = y1 = y;
                return;
            }
            x0 = std::min(x0, x); x1 = std::max(x1, x);
            y0 = std::min(y0, y); y1 = std::max(y1, y);
        }
        void unite(const PixelRect &o) {
            if (o.empty()) return;
            if (empty()) { *this = o; return; }
            x0 = std::min(x0, o.x0); y0 = std::min(y0, o.y0);
            x1 = std::max(x1, o.x1); y1 = std::max(y1, o.y1);
        }
    };
    const auto vertexRect = [&](int firstVertex, int vertexCount) {
        PixelRect r;
        const int total = static_cast<int>(g_vertices.size());
        if (firstVertex < 0 || vertexCount <= 0 || firstVertex >= total) return r;
        const int last = std::min(total, firstVertex + vertexCount);
        for (int v = firstVertex; v < last; ++v) {
            int px, py;
            ndcToPixel(g_vertices[static_cast<size_t>(v)].x,
                       g_vertices[static_cast<size_t>(v)].y, &px, &py);
            r.add(px, py);
        }
        if (!r.empty()) {
            r.x0 = std::max(0, r.x0 - 2);
            r.y0 = std::max(0, r.y0 - 2);
            r.x1 = std::min(g_viewW - 1, r.x1 + 2);
            r.y1 = std::min(g_viewH - 1, r.y1 + 2);
        }
        return r;
    };
    // 上一批写入模板的 1 所在的包围盒（空 = 模板当前全 0）
    PixelRect stencilDirty;

    // 每个遮罩几何的屏幕包围盒，每帧只算一次（P15）。
    // 同一个遮罩常被多个内容批次共用（big_fat_fish：18 个遮罩 / 69 个被遮罩
    // 批次），逐批次重算是纯浪费。这里一次算完，循环里只做查表 + 并集。
    static std::vector<PixelRect> s_maskRects;
    s_maskRects.assign(s_maskBatches.size(), PixelRect());
    if (stencilUsable && !stencilFullClear) {
        for (size_t i = 0; i < s_maskBatches.size(); ++i) {
            if (!s_maskActive[i]) continue;
            s_maskRects[i] = vertexRect(s_maskBatches[i].firstVertex,
                                       s_maskBatches[i].vertexCount);
        }
    }
    const auto maskRectOf = [&](int mbi) -> const PixelRect & {
        static const PixelRect kEmpty;
        if (mbi < 0 || mbi >= static_cast<int>(s_maskRects.size())) return kEmpty;
        return s_maskRects[static_cast<size_t>(mbi)];
    };

    int drawnBatches = 0;
    int skippedInvisible = 0;
    int scissorClears = 0;
    int fullClears = 0;
    // 本帧模板清除覆盖的像素总面积（P15：对比 scissor 与全屏的真实带宽差）
    long long clearedPixels = 0;
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

        // P15：只清「本批遮罩包围盒 ∪ 上一批残留」这一块，而不是整屏。
        // 残留区域必须一起清：上一批写进去的 1 若留在模板里，本批的
        // GL_EQUAL 1 会在那里错误放行（幽灵遮罩）。
        PixelRect clearRect;
        PixelRect maskRect;
        {
            for (int k = 0; k < batch.maskCount; ++k) {
                const int ref = batch.maskFirst + k;
                if (ref < 0 || ref >= static_cast<int>(s_maskRefs.size())) continue;
                maskRect.unite(maskRectOf(s_maskRefs[static_cast<size_t>(ref)]));
            }
            clearRect.unite(maskRect);
            clearRect.unite(stencilDirty);
        }

        if (stencilFullClear) {
            glClear(GL_STENCIL_BUFFER_BIT);
            ++fullClears;
            clearedPixels += static_cast<long long>(g_viewW) * g_viewH;
        } else if (!clearRect.empty()) {
            const int w = clearRect.x1 - clearRect.x0 + 1;
            const int h = clearRect.y1 - clearRect.y0 + 1;
            // 覆盖大半屏时 scissor 省不了带宽，反而多两次状态切换 → 直接全屏清
            if (static_cast<long long>(w) * h * 2 > static_cast<long long>(g_viewW) * g_viewH) {
                glClear(GL_STENCIL_BUFFER_BIT);
                ++fullClears;
                clearedPixels += static_cast<long long>(g_viewW) * g_viewH;
            } else {
                glEnable(GL_SCISSOR_TEST);
                glScissor(clearRect.x0, clearRect.y0, w, h);
                glClear(GL_STENCIL_BUFFER_BIT);
                glDisable(GL_SCISSOR_TEST);
                ++scissorClears;
                clearedPixels += static_cast<long long>(w) * h;
            }
        }
        // 无遮罩可写（全部失效）：模板里该落的 1 落不下去，下面 drawnMasks==0
        // 分支会退回不裁剪绘制，因此这里不做任何清除也是安全的。
        stencilDirty = PixelRect();

        // 只写模板，不写颜色（否则遮罩网格会被画成一坨黑块）
        glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        glStencilFunc(GL_ALWAYS, 1, 0xFF);
        // GL_REPLACE：片元通过就写参考值 1；stencil 失败/depth 失败都保持不动
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);

        glUseProgram(maskProgram);
        glUniformMatrix4fv(maskUniformMvp, 1, GL_FALSE, mvp);
        glUniform1i(maskUniformTex, 0);

        int drawnMasks = 0;
        // P15：记下本批真正写进模板的 1 的包围盒，供下一批清除时并集使用。
        PixelRect written;
        for (int k = 0; k < batch.maskCount; ++k) {
            const int ref = batch.maskFirst + k;
            if (ref < 0 || ref >= static_cast<int>(s_maskRefs.size())) continue;
            const int maskBatchIndex = s_maskRefs[static_cast<size_t>(ref)];
            if (maskBatchIndex < 0 ||
                maskBatchIndex >= static_cast<int>(s_maskBatches.size())) {
                continue;
            }
            const MaskBatch &mb = s_maskBatches[static_cast<size_t>(maskBatchIndex)];
            // P7：遮罩所属部件本帧已被 pose/动作隐藏 → 该遮罩失效，跳过。
            // 若因此一个遮罩都没画出来，下面的 drawnMasks == 0 分支会退回
            // 「不裁剪直接画」，手就不会被一块看不见的袖子切掉。
            if (!s_maskActive[static_cast<size_t>(maskBatchIndex)]) continue;
            const GLuint maskTex = textureOf(mb.textureIndex);
            if (maskTex == 0) continue;

            glBindTexture(GL_TEXTURE_2D, maskTex);
            drawRange(mb.firstIndex, mb.indexCount);
            written.unite(maskRectOf(maskBatchIndex));
            ++drawnMasks;
        }
        stencilDirty = written;

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

    // P15：模板清除方式统计（5s 一行），用来核对 scissor 真的在生效、
    // 以及有多少批次因为覆盖太大退回全屏清除。
    {
        static double s_lastStencilLogSec = 0.0;
        static long s_scissorTotal = 0;
        static long s_fullTotal = 0;
        static long s_framesTotal = 0;
        static long long s_pixelsTotal = 0;
        s_scissorTotal += scissorClears;
        s_fullTotal += fullClears;
        s_pixelsTotal += clearedPixels;
        ++s_framesTotal;
        const double nowStencil = std::chrono::duration<double>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        if (s_lastStencilLogSec <= 0.0) s_lastStencilLogSec = nowStencil;
        if (nowStencil - s_lastStencilLogSec >= 5.0) {
            const double frames = static_cast<double>(s_framesTotal);
            // 等价全屏清除次数：本帧清掉的像素 / 一屏像素，直接量化填充量
            const double screenPx = static_cast<double>(g_viewW) * static_cast<double>(g_viewH);
            LOGI("stencil clears: scissor=%.1f/frame full=%.2f/frame screenEq=%.2f mode=%s",
                 static_cast<double>(s_scissorTotal) / frames,
                 static_cast<double>(s_fullTotal) / frames,
                 screenPx > 0.0 ? static_cast<double>(s_pixelsTotal) / frames / screenPx : 0.0,
                 stencilFullClear ? "full" : "scissor");
            s_lastStencilLogSec = nowStencil;
            s_scissorTotal = 0;
            s_fullTotal = 0;
            s_pixelsTotal = 0;
            s_framesTotal = 0;
        }
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
    // P15 诊断：与 Kotlin 侧 teardown 时间戳对照（判断停线程是否先于清模型）。
    LOGI("nativeRelease: enter onGlThread=%d",
         (g_glThreadId == std::this_thread::get_id()) ? 1 : 0);

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
    g_paramIndexByNorm.clear();
    g_paramCount = 0;
    g_paramNames.clear();
    g_paramDefaults.clear();
    g_paramMin.clear();
    g_paramMax.clear();
    g_partIndexById.clear();
    g_partIndexByLower.clear();
    g_partDefaults.clear();
    g_partAnimDriven.clear();
    g_partDrawableCounts.clear();
    g_motionGroups.clear();
    g_expressions.clear();
    g_poseGroups.clear();
    g_poseGroupLinks.clear();
    g_physicsChains.clear();
    g_faceValues.clear();
    g_faceDirty = false;
    g_activeGroup = -1;
    g_activeMotion = -1;
    g_activeExpression = -1;
    g_motionActive = false;
    g_motionTime = 0.0;
    g_lastTickSec = 0.0;
    g_physicsDt = 0.0f;
    g_modelOpacity = 1.0f;
    g_modelDirty = true;
    g_fps.store(0.0f, std::memory_order_relaxed);
    g_gesture.dx = 0.0f;
    g_gesture.dy = 0.0f;
    g_gesture.scale = 1.0f;
    g_glModelDirty = false;
    clearVertexDirty();
    g_vboVertexCount = 0;
    // P14：换模型 / 重置后参数快照失效，下一帧必须强制求值一次
    g_lastAppliedParams.clear();
    g_textureImages.clear();
    g_vertices.shrink_to_fit();
    g_indices.shrink_to_fit();
    g_batches.shrink_to_fit();
    g_maskBatches.shrink_to_fit();
    g_maskRefs.shrink_to_fit();
    g_vertexSpans.shrink_to_fit();
    g_paramDefaults.shrink_to_fit();
    g_paramMin.shrink_to_fit();
    g_paramMax.shrink_to_fit();
    g_partDefaults.shrink_to_fit();
    g_partAnimDriven.shrink_to_fit();
    g_partDrawableCounts.shrink_to_fit();
    g_faceValues.shrink_to_fit();
    g_motionGroups.shrink_to_fit();
    g_expressions.shrink_to_fit();
    g_poseGroups.shrink_to_fit();
    g_physicsChains.shrink_to_fit();
    g_eyeBlinkParams.clear();
    g_eyeBlinkParams.shrink_to_fit();
    g_lastAppliedParams.clear();
    g_lastAppliedParams.shrink_to_fit();
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
    char buffer[640];
    std::snprintf(buffer, sizeof(buffer),
                  "drawables=%d drawn=%d batches=%zu masked=%d maskGeoms=%zu "
                  "textures=%d/%zu verts=%zu idx=%zu stencil=%d "
                  "params=%d writes=%ld unknown=%ld "
                  "motions=%zu exprs=%zu pose=%zu motion=%d/%d exp=%d op=%.2f fps=%.1f "
                  "physics=%zu %s "
                  "bounds=[%.3f,%.3f]x[%.3f,%.3f]",
                  g_drawableTotal, g_drawableDrawn, g_batches.size(), maskedBatches,
                  g_maskBatches.size(), uploadedTextures, g_textureImages.size(),
                  g_vertices.size(), g_indices.size(), g_stencilBits,
                  g_paramCount, g_paramWritesTotal, g_paramUnknownTotal,
                  g_motionGroups.size(), g_expressions.size(), g_poseGroups.size(),
                  g_activeGroup, g_activeMotion, g_activeExpression, g_modelOpacity,
                  g_fps.load(std::memory_order_relaxed),
                  g_physicsChains.size(),
                  g_physicsEnabled.load(std::memory_order_relaxed) ? "on" : "off",
                  g_minX, g_maxX, g_minY, g_maxY);
    return env->NewStringUTF(buffer);
}

/**
 * 物理抖动度量（P8 真机验证）。
 *
 * 返回一行统计并**清零**（滑动窗口语义）：调用方每隔几秒取一次，
 * 每次拿到的都是「上一个窗口内」的抖动水平，不会越积越钝。
 *
 * 字段含义（都在物理链内部、60Hz 逐帧采样，不受截图/录屏采样率限制）：
 *  - frames       统计到的帧数；
 *  - outΔ         每帧每个输出参数的平均变化量（0.01 量级 = 看得见的微动）；
 *  - outMax       单帧最大变化；
 *  - flips/f      每帧方向反转次数（**抖动最直接的证据**：静止噪声会让它 >0）；
 *  - resSpeed     质点平均残余速度（P12 起恒 0，指标已退役）;
 *  - rest%        静止帧占比（P12 起恒 0，指标已退役）。
 */
JNIEXPORT jstring JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativePhysicsJitter(
        JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const PhysicsJitterStats s = g_physicsJitter;
    g_physicsJitter.reset();
    char buffer[320];
    std::snprintf(buffer, sizeof(buffer),
                  "frames=%ld enabled=%d outDelta=%.5f outMax=%.5f "
                  "flipsPerFrame=%.3f residualSpeed=%.5f rest=%.1f%%",
                  s.frames,
                  g_physicsEnabled.load(std::memory_order_relaxed) ? 1 : 0,
                  s.meanOutputDelta(), s.outputAbsMax, s.flipsPerFrame(),
                  s.meanResidualSpeed(), s.restRatio() * 100.0);
    return env->NewStringUTF(buffer);
}

/**
 * 物理开关（P8 调试闸）。
 *
 * 关闭后物理链不再模拟，所有输出参数（ParamHair* / ParamScarf 等）每帧被写回
 * 模型默认值 —— 参数完全静止。用于真机上二分定位抖动来源：
 *  - 关闭后仍抖 → 抖动来自动作/面捕参数推流或渲染链路，与物理求解器无关；
 *  - 关闭后不抖 → 抖动来自物理（输入增益/阻尼/阈值需要继续调）。
 * 任何时候可切回 true，链从静息姿态重新开始。
 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetPhysicsEnabled(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const bool on = enabled == JNI_TRUE;
    const bool wasOn = g_physicsEnabled.exchange(on, std::memory_order_relaxed);
    if (wasOn == on) return;
    if (!on) {
        // 关闭：粒子姿态与输出历史一并复位，重新打开时从静息姿态开始
        for (PhysicsChain &chain : g_physicsChains) {
            initializeChain(chain);
        }
        g_physicsRemainTime = 0.0f;
    }
    g_modelDirty = true;
    LOGI("物理开关: %s", on ? "on" : "off");
}

/** 物理是否启用（诊断用）。 */
JNIEXPORT jboolean JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeIsPhysicsEnabled(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_physicsEnabled.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

/**
 * 物理参考模式开关 —— **P12 起退役**（见 g_physicsReferenceMode 的注释）。
 *
 * 方向跟踪粒子模型成为唯一路径，本开关只保留 JNI ABI 兼容，无任何行为差异。
 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetPhysicsReferenceMode(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const bool on = enabled == JNI_TRUE;
    g_physicsReferenceMode.store(on, std::memory_order_relaxed);
    LOGI("物理参考模式: %s（已退役，无行为差异）", on ? "on" : "off");
}

/**
 * 物理逐帧曲线追踪开关（P9 与基准参考实现对照用）。
 *
 * 打开后每帧输出一行 `trace: <frame> <time> <dt> <ParamHairFront>`，字段与
 * host/ref/refphysics 的 CSV 对齐，可逐帧比对 app 自研求解器与基准
 * CubismNativeFramework 的偏差。默认关闭 —— 每帧一行日志只适合取证。
 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetPhysicsTrace(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const bool on = enabled == JNI_TRUE;
    g_physicsTrace.store(on, std::memory_order_relaxed);
    g_traceFrame = 0;
    LOGI("物理曲线追踪: %s", on ? "on" : "off");
}

/**
 * A/B 兜底互斥开关（P9 调试闸）。
 *
 * 关闭后立刻把兜底推断出的互斥组移除，恢复成「缺 pose3 时两套部件同时可见」
 * 的原始行为 —— 用于真机上同一段录屏做开/关 A/B 对比，证明画面差异确实来自
 * 这条兜底规则，而不是别的改动。
 *
 * 从 pose3.json 读到的组不受影响（兜底从不覆盖作者意图）。
 * 打开时若当前模型缺 pose3，会立即重新推断并让下一帧重建状态。
 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetPoseFallback(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const bool on = enabled == JNI_TRUE;
    const bool wasOn = g_poseFallbackEnabled.exchange(on, std::memory_order_relaxed);
    if (wasOn == on) return;

    const size_t fileGroups = std::min(g_poseFileGroupCount, g_poseGroups.size());
    if (on) {
        if (!g_poseFromFile) {
            g_poseGroups.resize(fileGroups);
            if (g_poseGroupLinks.size() > fileGroups) {
                g_poseGroupLinks.resize(fileGroups);
            }
            buildFallbackPoseGroupsLocked();
        }
    } else {
        g_poseGroups.resize(fileGroups);
        if (g_poseGroupLinks.size() > fileGroups) {
            g_poseGroupLinks.resize(fileGroups);
        }
        g_poseFallbackPairs = 0;
    }
    g_modelDirty = true;
    LOGI("pose 兜底开关: %s（pose3 组=%zu，兜底组=%d）", on ? "on" : "off",
         g_poseGroups.size(), g_poseFallbackPairs);
}

/**
 * 合成面捕开关（真机无摄像头时的复现工具）。
 *
 * 开启后每帧按 1Hz 正弦写 ParamAngleX/Y/Z + 0.5Hz 半波 ParamMouthOpenY，
 * 物理链因此得到与真实摇头一致的周期输入。用于在没有摄像头的模拟器/真机上
 * 稳定复现「头发抽风」并量化修复效果。
 *
 * 关闭时立刻把注入过的参数复位成 NaN（= 交回动作/表情），不会残留假数据。
 */
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetSyntheticFace(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const bool on = enabled == JNI_TRUE;
    const bool wasOn = g_syntheticFace.exchange(on, std::memory_order_relaxed);
    if (wasOn == on) return;
    g_syntheticStartSec = 0.0;
    if (!on) {
        // 复位所有被注入的参数：NaN 语义 = 本帧不覆盖
        const float nan = std::numeric_limits<float>::quiet_NaN();
        for (const char *name : {"ParamAngleX", "ParamAngleY", "ParamAngleZ",
                                 "ParamBodyAngleX", "ParamBodyAngleZ",
                                 "ParamMouthOpenY", "ParamMouthForm",
                                 "ParamEyeLOpen", "ParamEyeROpen"}) {
            const int index = resolveParamIndex(name);
            if (index < 0) continue;
            const size_t idx = static_cast<size_t>(index);
            if (idx < g_faceValues.size()) g_faceValues[idx] = nan;
        }
    }
    g_faceDirty = true;
    g_modelDirty = true;
    LOGI("合成面捕: %s", on ? "on" : "off");
}

}  // extern "C"
