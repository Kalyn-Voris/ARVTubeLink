// live2d_jni.cpp - Purism Core 求值 -> GLES 2.0 绘制，含 JNI 入口
// MOC 缓冲区 64 字节对齐且须比 model 长寿；model 缓冲区 16 字节对齐，大小取 csmGetSizeofModel
// 库不堆分配，两块缓冲区由调用方持有；同一 model 不可跨线程并发访问
// UV 上传前把 V 翻成 1-v；纹理 straight alpha，片元着色器先乘 a 再配 GL_ONE/GL_ONE_MINUS_SRC_ALPHA
// 多个 clipping mask 取并集写同一 stencil 位；GL 句柄在 EGL context 重建后失效
// 顶点数 == 4 的矩形是 HitArea；drawable 拓扑在 init 定死，逐帧只刷新顶点位置，交叉由 render order 决定

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

#define PURISM_CORE_IMPLEMENTATION
#include "PurismCoreBundle.h"

#include "mini_json.h"
#include "xpbd_solver.h"

// stb_image：只解 PNG，走内存接口 stbi_load_from_memory，裁掉 JPEG/文件 IO 减小 .so
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

// JNI 入口不抛 Java 异常（GL 线程抛异常无人接），失败统一写 g_lastError，由 nativeLoadModelFromPath 返回 jstring（null = 成功）
std::string g_lastError;

struct AlignedBuffer {
    void *ptr = nullptr;
    size_t size = 0;

    bool alloc(size_t alignment, size_t bytes) {
        free();
        // 对齐必须是 2 的幂且为 sizeof(void*) 倍数
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

// moc3 模型空间顶点，x/y 单位 = canvas / ppu，v 已翻转 1-v，a 恒 1.0，opacity 走 uOpacity
struct Vertex {
    float x, y;     // 模型空间坐标（Purism Core 直接给出，单位 = canvas / ppu）
    float u, v;     // 纹理坐标；v 已按上述原因翻转为 1-v
    float a;        // 逐顶点 alpha，恒 1.0（opacity 走 uOpacity）
};

AlignedBuffer g_mocBuffer;
AlignedBuffer g_modelBuffer;
csmMoc *g_moc = nullptr;
csmModel *g_model = nullptr;

// 加载、surfaceCreated/drawFrame、release 三处都持 g_mutex，否则 GL 线程可能读到 clear() 的悬垂指针
std::mutex g_mutex;
std::vector<Vertex> g_vertices;
std::vector<uint16_t> g_indices;

struct VertexSpan {
    int drawableIndex = 0;
    int firstVertex = 0;   // 在 g_vertices 中的起始下标
    int vertexCount = 0;
};

std::vector<VertexSpan> g_vertexSpans;

// 顶点自上次上传后被参数更新改脏
bool g_verticesDirty = false;

// 本次改脏区间 [first, last]，first > last = 无变化
int g_dirtyVertexFirst = -1;
int g_dirtyVertexLast = -1;

inline void clearVertexDirty() {
    g_verticesDirty = false;
    g_dirtyVertexFirst = -1;
    g_dirtyVertexLast = -1;
}

// VBO 已分配顶点数，与 CPU 数不一致时跳过局部上传
int g_vboVertexCount = 0;

// 模型已换，GL 资源须整体重建；导入新模型时 surfaceCreated 不会再来
bool g_glModelDirty = false;

std::unordered_map<std::string, int> g_paramIndexById;
int g_paramCount = 0;

// 参数名归一化：小写、丢弃非字母数字、去前导 param，ParamEyeLOpen / EyeLOpen 都归一成 eyelopen
std::unordered_map<std::string, int> g_paramIndexByNorm;

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

int resolveParamIndex(const std::string &name) {
    const auto exact = g_paramIndexById.find(name);
    if (exact != g_paramIndexById.end()) return exact->second;
    const auto alias = g_paramIndexByNorm.find(normalizeParamName(name));
    if (alias != g_paramIndexByNorm.end()) return alias->second;
    return -1;
}

long g_paramWritesTotal = 0;
long g_paramUnknownTotal = 0;
long g_paramSanitizedTotal = 0;

// motion3 段格式 [t0, v0, type, ...]；type 0=线性、1=三次贝塞尔、2=阶跃、3=反阶跃
// exp3 {Id, Value, Blend}，Blend ∈ Overwrite/Add/Multiply；pose3 每组互斥只留一个部件

struct MotionCurve {
    int paramIndex = -1;
    int partIndex = -1;
    bool modelOpacity = false;
    int first = 0;
    int count = 0;
};

struct MotionData {
    std::string name;
    float duration = 0.0f;
    bool loop = false;
    std::vector<MotionCurve> curves;
    std::vector<float> segments;
};

struct MotionGroup {
    std::string name;
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

std::vector<std::vector<int>> g_poseGroups;

std::vector<std::vector<std::vector<int>>> g_poseGroupLinks;

std::atomic<bool> g_poseFallbackEnabled{true};

std::atomic<bool> g_physicsTrace{false};

// 追踪用的帧序号（drawFrame 递增）
long g_traceFrame = 0;

// 已退役，仅保留 nativeSetPhysicsReferenceMode 的 JNI ABI，写入无行为差异
std::atomic<bool> g_physicsReferenceMode{false};

bool g_poseFromFile = false;

size_t g_poseFileGroupCount = 0;

int g_poseFallbackPairs = 0;

std::unordered_map<std::string, int> g_partIndexById;

std::unordered_map<std::string, int> g_partIndexByLower;

std::vector<float> g_partDefaults;

std::vector<int> g_partDrawableCounts;

std::vector<uint8_t> g_partAnimDriven;

std::vector<float> g_paramDefaults;

std::vector<float> g_paramMin;
std::vector<float> g_paramMax;

// 每渲染帧累加时钟 → 固定子步 1/120s（xpbd::tuning().substepDt）→ 预测 + 一次约束求解 + 速度回推

// 求解器在 xpbd_solver.h，桌面回放共用，这里只做类型别名
using PhysicsInput = xpbd::Input;
using PhysicsOutput = xpbd::Output;
using PhysicsVertex = xpbd::Vertex;
using PhysicsChain = xpbd::Chain;

std::vector<PhysicsChain> g_physicsChains;

constexpr float kPhysicsMaxWeight = 100.0f;
// 累加时钟上限，超过即丢弃（正常渲染帧碰不到）
constexpr float kPhysicsMaxDeltaTime = 5.0f;

float g_physicsRemainTime = 0.0f;

float g_physicsFps = 0.0f;

// 参数缓存与输入缓存，尺寸 = 参数总数；caches 兼作子步输入对齐与链间传播
std::vector<float> g_paramCaches;
std::vector<float> g_paramInputCaches;

float g_physicsDt = 0.0f;

std::vector<float> g_lastAppliedParams;

// 物理总开关，false 时输出写回默认值并跳过模拟
std::atomic<bool> g_physicsEnabled{true};

struct PhysicsJitterStats {
    double outputAbsDeltaSum = 0.0;
    long outputDeltaCount = 0;        // 参与统计的样本数
    double outputAbsMax = 0.0;        // 单帧最大变化
    long outputFlipCount = 0;

    std::vector<int> lastSignByOutput;

    double residualSpeedSum = 0.0;
    long residualSpeedCount = 0;

    long frames = 0;
    long restFrames = 0;

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

int g_activeGroup = -1;
int g_activeMotion = -1;
int g_activeExpression = -1;

double g_motionTime = 0.0;
bool g_motionActive = false;

double g_lastTickSec = 0.0;

float g_modelOpacity = 1.0f;

std::vector<float> g_faceValues;
bool g_faceDirty = false;

// 自动眨眼：未接面捕时按 3~6s 随机间隔闭合，交接给真实数据用 g_blinkYield 淡出 0.12s
// 必须在 applyPhysicsLocked 之前写，眼皮由 EyeLOpen 经 physics3 驱动

// model3.json EyeBlink 组的参数下标（空 = 不眨眼）
std::vector<int> g_eyeBlinkParams;

// 眨眼时序状态，GL 线程独占
struct BlinkState {
    double nextAtSec = 0.0;   // 下一次眨眼开始的时刻；<=0 = 尚未排期
    double startedAtSec = 0.0;
    bool active = false;
    double closeSec = 0.09;
    double holdSec = 0.04;
    double openSec = 0.14;
    long totalBlinks = 0;
};
BlinkState g_blink;

// 自动眨眼 → 真实面捕的交接权重（0 = 全真实，1 = 全自动眨眼）
float g_blinkYield = 0.0f;

double g_blinkYieldSec = 0.0;

constexpr double kBlinkHandoverSec = 0.12;

float g_blinkEyeWritten = -1.0f;
long g_blinkEyeWrittenSerial = 0;

// 闭眼程度 0（睁开）~1（完全闭合），三次缓动
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

bool blinkLogEnabled(double nowSec) {
    static bool s_enabled = false;
    static double s_nextCheckSec = 0.0;
    if (nowSec >= s_nextCheckSec) {
        s_nextCheckSec = nowSec + 0.5;
        s_enabled = access("/data/local/tmp/arvt_blink_log", F_OK) == 0;
    }
    return s_enabled;
}

struct ResourceSource;
void loadEyeBlinkGroup(const ResourceSource &src, const std::string &jsonPath);

// 必须在 applyFaceValuesLocked 之后、applyPhysicsLocked 之前
void applyAutoBlinkLocked(double nowSec) {
    if (g_eyeBlinkParams.empty() || !g_model) return;

    // 面捕在场判定：任一眼睛参数为有限值即视为真实数据在场
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
            // 首帧/复位后直接取目标值，避免自动眨眼被淡入拖慢
            g_blinkYield = target;
        } else {
            if (dt < 0.0) dt = 0.0;
            if (dt > 0.25) dt = 0.25;   // 掉帧/后台回来时不要一步跳完
            if (dt > 0.0) {
                // 线性斜率：交接时长与帧率无关
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

    // 权重完全让位：撤销待触发的眨眼排期，等面捕消失后重新排期
    if (g_blinkYield <= 0.0f) {
        g_blink.active = false;
        g_blink.nextAtSec = 0.0;
        return;
    }

    // 0.8s 启动延迟，之后 3~6s 一次
    if (g_blink.nextAtSec <= 0.0) {
        g_blink.nextAtSec = nowSec + 0.8;
    }
    if (!g_blink.active && nowSec >= g_blink.nextAtSec) {
        g_blink.active = true;
        g_blink.startedAtSec = nowSec;
        ++g_blink.totalBlinks;
        // 确定性伪随机间隔 3~6s，避免引入 <random>
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
    if (closed <= 0.0f || g_blinkYield <= 0.0f) return;

    float *values = csmGetParameterValues(g_model);
    if (!values) return;
    const float w = g_blinkYield;
    for (int pi : g_eyeBlinkParams) {
        if (pi < 0 || pi >= g_paramCount) continue;
        const float base = g_paramDefaults[static_cast<size_t>(pi)];
        const float open = (base > 0.0f) ? base : 1.0f; // 默认 0 的模型按 1 处理
        const float autoValue = open * (1.0f - closed);
        values[pi] = values[pi] * (1.0f - w) + autoValue * w;
    }

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

std::vector<std::string> g_paramNames;

// 合成面捕：1Hz 正弦写 ParamAngleY / ParamMouthOpenY
std::atomic<bool> g_syntheticFace{false};
double g_syntheticStartSec = 0.0;

double g_frameNowSec = 0.0;

// 单帧最大时间步（秒）；半隐式欧拉在刚性链上 h 超 ~0.05s 开始失稳
constexpr float kPhysicsMaxDt = 0.05f;

struct PhysicsDtStats {
    double minDt = 1e9;
    double maxDt = 0.0;
    double sumDt = 0.0;
    long frames = 0;
    long zeroDtFrames = 0;
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

bool g_modelDirty = true;

void applyModelStateLocked();
void refreshVertexPositionsLocked();
void reportJitterPeriodicallyLocked(double nowSec);
// 一个 drawable 一段，纹理与 uOpacity 逐 drawable 变化，无法合并成单次 draw
int g_traceOutParam = -2;

void resetPhysicsTraceBinding() { g_traceOutParam = -2; }

inline void initializeChain(PhysicsChain &chain) { xpbd::initialize(chain); }

std::atomic<float> g_fps{0.0f};

// 一次绘制批次：一个 drawable 一段；纹理与 uOpacity 逐 drawable 变化，无法合并成单次 draw
struct Batch {
    int firstIndex = 0;
    int indexCount = 0;
    // 本批次在 g_vertices 的连续区间，scissor 清除按顶点求包围盒
    int firstVertex = 0;
    int vertexCount = 0;
    int textureIndex = 0;
    int drawableIndex = -1;

    // Clipping Mask：本批次引用的遮罩在 g_maskRefs 中的区间
    int maskFirst = 0;
    int maskCount = 0;
    bool invertedMask = false; // csmIsInvertedMask：绘制遮罩之外的部分
};

std::vector<Batch> g_batches;

// 遮罩几何批次，不进 g_batches，只在 stencil 写入阶段用纯色画一遍
struct MaskBatch {
    int firstIndex = 0;
    int indexCount = 0;
    int firstVertex = 0;
    int vertexCount = 0;
    int textureIndex = 0;
    int drawableIndex = -1;
    // 判定遮罩失效看部件而非 drawable 可见性
    int partIndex = -1;
};

std::vector<MaskBatch> g_maskBatches;

// 扁平化的「批次 → 遮罩批次」引用表，Batch.maskFirst/maskCount 指向这里的区间
std::vector<int> g_maskRefs;

struct TextureImage {
    std::string name;
    std::vector<uint8_t> rgba;
    int width = 0;
    int height = 0;
};

// 按 model3.json Textures 顺序，下标 = csm texture index
std::vector<TextureImage> g_textureImages;

// GPU 纹理对象（surfaceCreated 创建，与 g_textureImages 一一对应）
std::vector<GLuint> g_textures;

float g_minX = 0, g_maxX = 0, g_minY = 0, g_maxY = 0;
int g_drawableTotal = 0;
int g_drawableDrawn = 0;

// GPU 资源只在 GL 线程创建/销毁
GLuint g_program = 0;
GLuint g_vbo = 0;
GLuint g_ibo = 0;
GLint g_attrPos = -1;
GLint g_attrUv = -1;
GLint g_attrAlpha = -1;
GLint g_uniformMvp = -1;
GLint g_uniformOpacity = -1;
GLint g_uniformTex = -1;

// 遮罩写入 program，与 g_program 共用顶点阶段，只换 fragment
GLuint g_maskProgram = 0;
GLint g_maskUniformMvp = -1;
GLint g_maskUniformTex = -1;

int g_viewW = 0;
int g_viewH = 0;
float g_mvp[16] = {0};

// 手势作用在屏幕空间：p' = scale * p + offset，正交投影下直接改矩阵行 0/5 与 12/13，dx/dy 为 ±1 半屏归一化单位
struct GestureTransform {
    float dx = 0.0f;     // 水平偏移（±1 = 屏幕半宽）
    float dy = 0.0f;     // 垂直偏移（±1 = 屏幕半高；向上为正）
    float scale = 1.0f;  // 缩放倍数
};

GestureTransform g_gesture;

constexpr float kScaleMin = 0.5f;
constexpr float kScaleMax = 3.0f;

constexpr float kPanFallback = 2.0f;

constexpr float kMinVisiblePx = 80.0f;

// 按包围盒算当前缩放下允许的平移范围，视口或包围盒未知时返回 false
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

    if (*outMinX > *outMaxX) *outMinX = *outMaxX = 0.0f;
    if (*outMinY > *outMaxY) *outMinY = *outMaxY = 0.0f;
    return true;
}

// EGL 模板缓冲位数；0 = 无模板缓冲，stencil 调用全为空操作
int g_stencilBits = 0;

// GL 线程标识，nativeRelease 据此判断能否 glDelete*
std::thread::id g_glThreadId{};

// 当前 GL 资源所属 EGL context，同一 context 才敢 glDelete
EGLContext g_glContext = EGL_NO_CONTEXT;

EGLContext currentEglContext() {
    return eglGetCurrentContext();
}

void drainGlErrors() {
    for (int i = 0; i < 32; ++i) {
        if (glGetError() == GL_NO_ERROR) break;
    }
}

void coreLog(const char *message) {
    if (message) __android_log_print(ANDROID_LOG_DEBUG, "PurismCore", "%s", message);
}

// 纯文本定位 model3.json 的 FileReferences.Textures：[ 到 ] 之间的 "..." 字符串
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

std::string dirOf(const std::string &path) {
    const size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) return std::string();
    return path.substr(0, slash + 1);
}

// 把 .moc3 路径换成同目录下的 .model3.json 路径（两者同名前缀）；非 .moc3 结尾返回空串
std::string modelJsonPathFor(const std::string &mocPath) {
    const std::string suffix = ".moc3";
    if (mocPath.size() > suffix.size() &&
        mocPath.compare(mocPath.size() - suffix.size(), suffix.size(), suffix) == 0) {
        return mocPath.substr(0, mocPath.size() - suffix.size()) + ".model3.json";
    }
    return std::string();
}

// 资源来源：设备本地目录（模型由用户 zip 导入到 filesDir，不随包携带资源）
struct ResourceSource {
};

// 把整个资源读成字节数组（纹理 / json / moc3 共用，不需要对齐）
bool readResource(const ResourceSource & /*src*/, const std::string &path,
                  std::vector<uint8_t> *out) {
    if (path.empty()) return false;

    FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) return false;

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

bool readResourceText(const ResourceSource &src, const std::string &path, std::string *out) {
    std::vector<uint8_t> bytes;
    if (!readResource(src, path, &bytes)) return false;
    out->assign(reinterpret_cast<const char *>(bytes.data()), bytes.size());
    return true;
}

// 解码 PNG 到 TextureImage，强制 4 通道（UV/取样假设 RGBA）
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

// 加载全部纹理；缺失只记日志，false = 一张都没成功
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

    // 退化路径：解析不出就按约定试 texture_00 / texture_01
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
            // 占位：保持下标与 csm texture index 对齐
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

// 解析一条 motion3 曲线的 Segments 追加到 flat 尾部
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

// model3.json FileReferences.Motions：{ 组名: [ {File}, ... ] }
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

// idle 兜底：vtube.json IdleAnimation，否则 animations/ 下第一个 .motion3.json
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
    // IdleAnimation 为相对 animations/ 的文件名，根目录与 animations/ 都试
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

// exp3.json Parameters[].Id/Value/Blend（Add=1，Multiply=2，其余 0）
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

// model3.json FileReferences.Expressions[].Name/File
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

// 无 Expressions 时取 vtube.json Hotkeys 的 ToggleExpression，再追加目录剩余 .exp3.json
void loadVtsHotkeyExpressions(const ResourceSource &src, const std::string &dir) {
    if (!g_expressions.empty()) return;

    std::vector<std::string> files;
    std::vector<std::string> names;     // 显示名，缺失时用文件名

    // vtube.json Hotkeys：作者顺序 + 名字
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

    // 目录里未被引用的 .exp3.json 追加在后（只扫根目录）
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
                names.emplace_back();   // 空名字表示用文件名
            }
            closedir(d);
        }
    }

    for (size_t i = 0; i < files.size(); ++i) {
        std::string display = names[i];
        if (display.empty()) {
            display = files[i];
            const size_t suffix = display.size() > 11 ? display.size() - 11 : 0;
            if (suffix > 0) display.erase(suffix);
        }
        Expression exp;
        if (parseExpressionFile(src, dir + files[i], display, &exp)) {
            g_expressions.push_back(std::move(exp));
        }
    }
    LOGI("表情加载完成（vtube 热键兜底）: %zu 个", g_expressions.size());
}

// model3.json Groups[Target=Parameter, Name=EyeBlink].Ids
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

    // Groups 缺失时回退 ParamEyeLOpen/ParamEyeROpen
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

// pose3.json Groups：组内互斥，Link 部件随选中项切换；不足 2 成员丢弃
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

    // 部件名查下标：精确匹配优先，失败走小写回退
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
            // 同组内重复部件只记一次
            if (std::find(parts.begin(), parts.end(), partIndex) != parts.end()) continue;
            parts.push_back(partIndex);

            // Link 可为单个名字或名字数组，查不到当空
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
            // 单项组恒获胜即恒点亮，丢弃
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

// 无 pose3 时按同名 + A/B 后缀成对推断互斥组，只追加已有组
void buildFallbackPoseGroupsLocked() {
    g_poseFallbackPairs = 0;
    if (!g_poseFallbackEnabled.load(std::memory_order_relaxed)) return;
    if (!g_poseGroups.empty()) return;
    const int partCount = static_cast<int>(g_partDefaults.size());
    if (partCount <= 0) return;

    // 只取默认可见且至少一个 drawable 的部件
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
        // 兜底组无 Link，联动列表留空
        g_poseGroupLinks.push_back(
                std::vector<std::vector<int>>(2, std::vector<int>()));
    }
    g_poseFallbackPairs = static_cast<int>(pairs.size());
    LOGI("pose 兜底: 无 pose3，按 A/B 后缀推断出 %d 组互斥（开关=%s）",
         g_poseFallbackPairs,
         g_poseFallbackEnabled.load(std::memory_order_relaxed) ? "on" : "off");
}

// physics3.json：Input / Output / Vertices / Normalization；Meta.EffectiveForces.Gravity 拷到每条链

void readVector2(const mjson::Value *node, float fallbackX, float fallbackY,
                 float *outX, float *outY) {
    *outX = fallbackX;
    *outY = fallbackY;
    if (!node) return;
    if (const mjson::Value *x = node->find("X")) *outX = static_cast<float>(x->numberOr(fallbackX));
    if (const mjson::Value *y = node->find("Y")) *outY = static_cast<float>(y->numberOr(fallbackY));
}

float numberField(const mjson::Value *node, const char *key, float fallback) {
    if (!node) return fallback;
    const mjson::Value *value = node->find(key);
    return value ? static_cast<float>(value->numberOr(fallback)) : fallback;
}

// Type 字段：X=0，Y=1，Angle=2
int physicsTypeOf(const mjson::Value *node) {
    if (!node) return 0;
    const mjson::Value *type = node->find("Type");
    if (!type) return 0;
    const std::string &name = type->stringOrEmpty();
    if (name == "Y") return 1;
    if (name == "Angle") return 2;
    return 0;
}

// Normalization 的 Position/Angle 三段：Minimum/Default/Maximum
void readNormalization(const mjson::Value *norm, const char *key,
                       float *minV, float *defV, float *maxV) {
    if (!norm) return;
    const mjson::Value *range = norm->find(key);
    if (!range) return;
    if (const mjson::Value *v = range->find("Minimum")) *minV = static_cast<float>(v->numberOr(*minV));
    if (const mjson::Value *v = range->find("Default")) *defV = static_cast<float>(v->numberOr(*defV));
    if (const mjson::Value *v = range->find("Maximum")) *maxV = static_cast<float>(v->numberOr(*maxV));
}

// physics3.json → g_physicsChains，单条 setting 失败只跳过自身
void loadPhysics(const ResourceSource &src, const std::string &jsonPath) {
    g_physicsChains.clear();
    g_physicsRemainTime = 0.0f;
    g_paramCaches.clear();
    g_paramInputCaches.clear();
    g_physicsFps = 0.0f;
    // trace 绑定的是上一模型的参数下标，必须重解析
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
        // Meta.Fps 仅记录；子步长固定用 xpbd::tuning().substepDt，>240 视为无效
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
        // Position 与 Angle 各自独立区间，按 Type 选用
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
                // Weight 缺省 100，除以 kPhysicsMaxWeight 归一
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
                // VertexIndex 缺省 1
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
                // Mobility/Delay/Acceleration 缺省 1，Radius 缺省 0
                readVector2(item.find("Position"), 0.0f, 0.0f, &vertex.restX, &vertex.restY);
                if (const mjson::Value *v = item.find("Mobility")) vertex.mobility = static_cast<float>(v->numberOr(1.0));
                if (const mjson::Value *v = item.find("Delay")) vertex.delay = static_cast<float>(v->numberOr(1.0));
                if (const mjson::Value *v = item.find("Acceleration")) vertex.acceleration = static_cast<float>(v->numberOr(1.0));
                if (const mjson::Value *v = item.find("Radius")) vertex.radius = static_cast<float>(v->numberOr(0.0));
                chain.vertices.push_back(vertex);
            }
        }

        // Acceleration 钳 0..100，Delay 钳 0..20；Radius ≤0 或非有限时退回 Position 差
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

bool readMoc(const ResourceSource &src, const std::string &path,
             AlignedBuffer *out, std::string *err) {
    std::vector<uint8_t> bytes;
    if (!readResource(src, path, &bytes)) {
        *err = std::string("无法打开模型文件: ") + path;
        return false;
    }

    // MOC 需 64 字节对齐，先读临时缓冲再拷入对齐块
    if (!out->alloc(csmAlignofMoc, bytes.size())) {
        *err = "MOC 缓冲区分配失败（内存不足？）";
        return false;
    }
    std::memcpy(out->ptr, bytes.data(), bytes.size());
    return true;
}

bool loadModel(const ResourceSource &src, const std::string &mocPath, std::string *err) {
    if (!readMoc(src, mocPath, &g_mocBuffer, err)) return false;

    const auto mocSize = static_cast<unsigned int>(g_mocBuffer.size);

    // 不可信输入先校验：consistency 必须在 revive 之前
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

    // 初始化已跑过一次 update，再跑一遍取全默认参数下的静态姿态
    csmResetDrawableDynamicFlags(g_model);
    csmUpdateModel(g_model);

    csmVector2 canvasSize{}, canvasOrigin{};
    float ppu = 0;
    csmReadCanvasInfo(g_model, &canvasSize, &canvasOrigin, &ppu);
    LOGI("canvas=%.1fx%.1f origin=(%.1f,%.1f) ppu=%.1f",
         canvasSize.X, canvasSize.Y, canvasOrigin.X, canvasOrigin.Y, ppu);

    const int drawableCount = csmGetDrawableCount(g_model);
    g_drawableTotal = drawableCount;
    if (drawableCount <= 0) {
        *err = "模型没有 drawable";
        return false;
    }

    const int *maskCounts = csmGetDrawableMaskCounts(g_model);
    // masks 目标下标即被当遮罩的 drawable
    const int **masks = csmGetDrawableMasks(g_model);
    std::vector<uint8_t> isMask(static_cast<size_t>(drawableCount), 0);
    for (int i = 0; i < drawableCount; ++i) {
        for (int k = 0; k < maskCounts[i]; ++k) {
            const int target = masks[i][k];
            if (target >= 0 && target < drawableCount) isMask[static_cast<size_t>(target)] = 1;
        }
    }
    // isMask 只用于统计，不过滤绘制：被当遮罩的图层本身也要显示

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

    // 按 render order 升序绘制
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
    g_vertices.reserve(8192);
    g_indices.reserve(32768);
    g_batches.reserve(128);
    g_maskBatches.reserve(32);
    g_maskRefs.reserve(32);
    g_vertexSpans.reserve(128);

    // 参数表供动作曲线、部件表供 pose3/PartOpacity 查下标
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
                // 归一化别名表：同名键只保留先出现的参数
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

        // 量程快照，物理输入归一化与输出映射都用
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
        // NaN = 该参数本帧无输入
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
                // 小写回退表，仅在无同名项时插入
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
        // 每个部件的 drawable 数
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
        // 全量 dump 部件默认不透明度
        if (partOpacities && partCount > 0) {
            for (int i = 0; i < partCount; ++i) {
                LOGI("part[%d] %s def=%.3f", i, partIds && partIds[i] ? partIds[i] : "?",
                     partOpacities[i]);
            }
        }
    }
    // 打印手臂/手相关 drawable 的部件归属
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

// 把 drawable 几何追加进共享 VBO，返回 g_indices 区间；内容与掩码批次共用缓冲
    const auto appendGeometry = [&](int idx, bool trackBounds, int *firstIndex,
                                    int *indexCount, int *outFirstVertex,
                                    int *outVertexCount) -> bool {
        const size_t di = static_cast<size_t>(idx);
        const int vcount = vertexCounts[di];
        const int icount = indexCounts[di];
        if (vcount <= 0 || icount <= 0) return false;
        if (g_vertices.size() + static_cast<size_t>(vcount) > 65535u) {
            // uint16 索引上限 65535
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
            // V 轴翻转：Live2D UV 原点在左下，PNG 行序自上而下
            const float u = uv[v].X;
            const float flippedV = 1.0f - uv[v].Y;
            // a 恒 1.0，opacity 只走 uOpacity
            g_vertices.push_back(Vertex{x, y, u, flippedV, 1.0f});
            if (!trackBounds) continue;
            if (x < g_minX) g_minX = x;
            if (x > g_maxX) g_maxX = x;
            if (y < g_minY) g_minY = y;
            if (y > g_maxY) g_maxY = y;
        }
        // 登记区间供参数更新局部刷新
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
    // 同一掩码被多个内容共用，几何只收集一次
    std::vector<int> maskBatchOf(static_cast<size_t>(drawableCount), -1);

    for (int idx : order) {
        const size_t di = static_cast<size_t>(idx);
        const int vcount = vertexCounts[di];
        const int icount = indexCounts[di];

        if (vcount <= 0 || icount <= 0) continue;
        // 顶点数 == 4 的矩形是 HitArea，不是画面内容
        if (vcount == 4) { ++skippedQuad; continue; }
        // 可见性逐帧变化，不做 init 过滤；画不画由 drawFrame 查 csmIsVisible 决定

        // 纹理下标越界按缺图跳过，避免错贴串色
        const int texIndex = textureIndices[di];
        if (texIndex < 0 || texIndex >= static_cast<int>(g_textureImages.size()) ||
            g_textureImages[static_cast<size_t>(texIndex)].width <= 0) {
            ++texturesMissing;
            continue;
        }

        const int ownMaskCount = maskCounts[di];
        const int maskFirst = static_cast<int>(g_maskRefs.size());
        for (int k = 0; k < ownMaskCount; ++k) {
            const int maskIdx = masks[di][k];
            // -1 = 该掩码当前不可见，只跳过该掩码，不丢弃被裁剪的内容
            if (maskIdx < 0 || maskIdx >= drawableCount) { ++hiddenMaskRefs; continue; }

            const size_t mi = static_cast<size_t>(maskIdx);
            // 掩码自身不可见或透明仍写进模板：opacity=0 只表示它本身不显示
            if ((dynamicFlags[mi] & csmIsVisible) == 0 || opacities[mi] <= 0.001f) {
                ++hiddenMaskRefs;
            }

            // 嵌套遮罩不递归，按完整几何写模板，可能多露出一圈
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

        // 掩码全隐藏时按无遮罩绘制：掩码缺失等于不裁剪
        const int usedMaskCount = static_cast<int>(g_maskRefs.size()) - maskFirst;
        if (ownMaskCount > 0 && usedMaskCount == 0) {
            ++masksUnavailable;
        }

        Batch batch;
        if (!appendGeometry(idx, /*trackBounds=*/true, &batch.firstIndex, &batch.indexCount,
                            &batch.firstVertex, &batch.vertexCount)) {
            // 内容几何入缓冲失败，回滚刚收集的掩码引用
            g_maskRefs.resize(static_cast<size_t>(maskFirst));
            continue;
        }
        batch.textureIndex = texIndex;
        batch.drawableIndex = idx;
        batch.maskFirst = maskFirst;
        batch.maskCount = usedMaskCount;
        batch.invertedMask = (constantFlags[di] & csmIsInvertedMask) != 0;
        g_batches.push_back(batch);

        // 既是遮罩源又按内容绘制的图层
        if (isMask[di]) ++maskAlsoDrawn;

        if (usedMaskCount > 0) {
            ++maskedBatches;
            if (batch.invertedMask) ++invertedBatches;
        } else {
            // 无掩码批次清掉引用区间，保持 maskFirst/maskCount 一致
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

    // 须在参数表与部件表建好后解析：曲线要把名字翻成参数下标
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
            // 无 Motions 段的模型（VTS 打包件常见）补一条约定 idle，否则带 PartOpacity 互斥曲线的 idle 不播
            if (g_motionGroups.empty()) loadVtsIdleMotion(src, dir);
            loadExpressions(src, jsonPath, dir);
            // VTS 打包模型的表情清单只在 vtube.json 的 Hotkeys 里
            loadVtsHotkeyExpressions(src, dir);
            // 眨眼组参数下标须在 Groups 解析后、physics3 之前建立
            loadEyeBlinkGroup(src, jsonPath);
            // pose3 / physics3 路径直接取 model3.json FileReferences 的 Pose / Physics 字段
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

        // 无 pose3 组时按兜底互斥补齐
        g_poseFileGroupCount = g_poseGroups.size();
        if (!g_poseFromFile) buildFallbackPoseGroupsLocked();

        // 默认播第一组动作的第 0 个（通常 Idle），并立即应用一次
        g_activeGroup = g_motionGroups.empty() ? -1 : 0;
        g_activeMotion = (g_activeGroup >= 0 && !g_motionGroups[0].motions.empty()) ? 0 : -1;
        g_motionActive = g_activeMotion >= 0;
        g_motionTime = 0.0;
        g_lastTickSec = 0.0;
        g_activeExpression = -1;
        g_modelDirty = true;
        // 全部物理链复位到静息竖直姿态，不继承旧模型状态
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

// 曲线采样：type 0 线性、2 阶跃、3 反阶跃，1 为三次贝塞尔
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
            // 三次贝塞尔段：起点、控制点 1/2、终点
            if (i + 5 >= count) break;
            const float c1t = flat[i], c1v = flat[i + 1];
            const float c2t = flat[i + 2], c2v = flat[i + 3];
            const float endT = flat[i + 4], endV = flat[i + 5];
            i += 6;

            if (t <= endT) {
                // 二分 24 次求参数 u 使 x(u) = t（时间轴单调）
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
    return segStartV; // 已过末点保持末值
}

// pose3 互斥：本帧输入不透明度最大的候选胜出，组内其余归零
// 全组 ≤ 阈值按默认不透明度回退，默认也全 0 则强制首候选按 1.0
// 候选序与 csm 部件下标是两个下标空间，links 必须用候选序索引
void applyPoseLocked() {
    if (g_poseGroups.empty() || !g_model) return;

    float *partOpacities = csmGetPartOpacities(g_model);
    if (!partOpacities) return;

    constexpr float kVisibleThreshold = 0.001f;
    const int partCount = static_cast<int>(g_partDefaults.size());
    const size_t groupCount = g_poseGroups.size();

    // 每组选唯一获胜者，同时记候选序与部件下标
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
        // 回退获胜者仍为 0 则强制 1.0；正常选出的不强制，保留淡入淡出
        if (bestPart >= 0 && partOpacities[bestPart] <= kVisibleThreshold) {
            partOpacities[bestPart] = 1.0f;
            forcedVisible = true;
        }

        winnerOrdinal[gi] = bestOrdinal;
        winnerPart[gi] = bestPart;
        if (forcedVisible) ++forcedVisibleGroups;
        if (bestPart >= 0) isWinner[static_cast<size_t>(bestPart)] = 1;
    }

    // 先汇总保留集合再统一归零：同一部件可能跨组既是获胜者又是落选者
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

    // 组内落选者无条件归零；联动部件尊重动画优先级
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
                // 动画显式驱动的联动部件不由 pose 改写
                if (linkPart < static_cast<int>(g_partAnimDriven.size()) &&
                    g_partAnimDriven[static_cast<size_t>(linkPart)]) {
                    continue;
                }
                partOpacities[linkPart] = 0.0f;
            }
        }
    }

    // 获胜者的联动部件跟随其不透明度
    for (size_t gi = 0; gi < groupCount; ++gi) {
        const int ordinal = winnerOrdinal[gi];
        const int winner = winnerPart[gi];
        if (ordinal < 0 || winner < 0 || gi >= g_poseGroupLinks.size()) continue;
        const std::vector<std::vector<int>> &links = g_poseGroupLinks[gi];
        if (ordinal >= static_cast<int>(links.size())) continue;

        const float opacity = partOpacities[winner];
        for (int linkPart : links[static_cast<size_t>(ordinal)]) {
            if (linkPart < 0 || linkPart >= partCount) continue;
            // 联动部件自身若也是别组获胜者，以自己的值为准
            if (isWinner[static_cast<size_t>(linkPart)]) continue;
            if (linkPart < static_cast<int>(g_partAnimDriven.size()) &&
                g_partAnimDriven[static_cast<size_t>(linkPart)]) {
                continue;
            }
            partOpacities[linkPart] = opacity;
        }
    }

    // 每 5 秒最多一次，报告被强制点亮的组数
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

// 参数与部件不透明度复位到模型默认值
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
    // 每帧重建动画驱动标记，pose 读到的是本帧意图
    if (g_partAnimDriven.size() != g_partDefaults.size()) {
        g_partAnimDriven.assign(g_partDefaults.size(), 0);
    } else {
        std::fill(g_partAnimDriven.begin(), g_partAnimDriven.end(), 0);
    }
}

// 表情叠加到已复位的默认参数上
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

// 应用当前动作，返回整体不透明度（Target=Model/Opacity 曲线）
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
            // 标记动画对该部件有明确意图
            if (curve.partIndex < static_cast<int>(g_partAnimDriven.size())) {
                g_partAnimDriven[static_cast<size_t>(curve.partIndex)] = 1;
            }
        }
    }
}

// 固定子步 1/120，与渲染帧率、json Fps 双解耦；α 子步内缩放 α̃ = α/h²
// 非有限值 → 整链复位静息姿态 + 限频日志
// 求解器数学在 xpbd_solver.h，这里只做参数表适配与写回

// 参数表快照，求解器只读
xpbd::ParamTable makeParamTable() {
    xpbd::ParamTable table;
    table.count = g_paramCount;
    table.min = g_paramMin.data();
    table.max = g_paramMax.data();
    table.def = g_paramDefaults.data();
    return table;
}

// value = output × Scale，钳到参数 [min,max] 后按 Weight 混合，≥100 直接覆盖
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

// 须持 g_mutex，在动作/表情/面捕之后、pose 之前调用
// 累加时钟 += dt；超 kPhysicsMaxDeltaTime 清零，丢时间不补步
// 帧末按 remain/h 在前次与末次输出间插值，只增 ≤1 子步延迟
void applyPhysicsLocked() {
    if (g_physicsChains.empty() || !g_model) return;
    float *values = csmGetParameterValues(g_model);
    if (!values) return;

    // 读到眨眼刚写入的闭眼值即证明 applyAutoBlinkLocked 排在物理之前
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

    // flag 文件 arvt_physics_off 存在则输出写回默认值并跳过模拟
    const bool flagOff = access("/data/local/tmp/arvt_physics_off", F_OK) == 0;
    {
        static bool s_lastOff = false;
        if (flagOff != s_lastOff) {
            LOGI("物理实验开关: off=%d", flagOff ? 1 : 0);
            s_lastOff = flagOff;
        }
    }
    const bool enabled = g_physicsEnabled.load(std::memory_order_relaxed) && !flagOff;

    // dt 是累加时钟的进水量，子步长 h 固定，两者独立
    const float dt = std::max(0.0f, std::min(kPhysicsMaxDt, g_physicsDt));
    if (enabled) g_physicsDtStats.add(dt);

    if (!enabled) {
        // 关闭时参数写回默认值并复位全部链
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

    if (!(dt > 0.0f)) return;   // dt<=0 不演化

    // 容量不足（模型切换后参数变多）时重建，实时值作初值
    const size_t paramCount = static_cast<size_t>(g_paramCount);
    const bool cacheNeedsInit = g_paramCaches.size() < paramCount;
    if (cacheNeedsInit) {
        g_paramCaches.assign(paramCount, 0.0f);
        g_paramInputCaches.assign(paramCount, 0.0f);
        for (size_t i = 0; i < paramCount; ++i) {
            g_paramInputCaches[i] = values[i];
        }
    }

    g_physicsRemainTime += dt;
    if (g_physicsRemainTime > kPhysicsMaxDeltaTime) g_physicsRemainTime = 0.0f;

    const float h = xpbd::tuning().substepDt;   // 固定子步长

    int steps = 0;
    while (g_physicsRemainTime >= h) {
        // 输入线性推进到本子步时刻，alpha 允许 >1 外插
        const float alpha = h / g_physicsRemainTime;
        const float keep = 1.0f - alpha;
        for (int j = 0; j < g_paramCount; ++j) {
            const size_t uj = static_cast<size_t>(j);
            g_paramCaches[uj] = g_paramInputCaches[uj] * keep + values[uj] * alpha;
            g_paramInputCaches[uj] = g_paramCaches[uj];
        }

        // 输出槽轮转：prevOut ← curOut，供帧末插值
        for (PhysicsChain &chain : g_physicsChains) {
            chain.prevOut = chain.curOut;
        }

        for (PhysicsChain &chain : g_physicsChains) {
            if (!chain.initialized) initializeChain(chain);
            if (!xpbd::step(chain, makeParamTable(), g_paramCaches.data(), h)) {
                // 已复位该链，日志限频 1s
                static double s_lastWarnSec = 0.0;
                if (g_frameNowSec - s_lastWarnSec > 1.0) {
                    LOGW("物理链 %s 数值异常（XPBD），已复位到静息姿态", chain.id.c_str());
                    s_lastWarnSec = g_frameNowSec;
                }
            }

            for (size_t oi = 0; oi < chain.outputs.size(); ++oi) {
                const PhysicsOutput &output = chain.outputs[oi];
                // 越界参数下标或几何取法失败则跳过该输出
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
        // 防时钟跳变，单帧子步数上限
        if (++steps > xpbd::tuning().maxSubstepsPerFrame) {
            g_physicsRemainTime = 0.0f;
            break;
        }
    }

    // 帧间插值，真正写参数的只有这一处
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

            // 抖动度量：本帧写入相对上一帧值的变化量
            {
                const double d = static_cast<double>(values[pui]) -
                                 static_cast<double>(before);
                const double ad = std::fabs(d);
                g_physicsJitter.outputAbsDeltaSum += ad;
                ++g_physicsJitter.outputDeltaCount;
                if (ad > g_physicsJitter.outputAbsMax) g_physicsJitter.outputAbsMax = ad;
                // 符号反转按参数下标分别跟踪，跨参数比较会假阳性
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

    // trace 记录真实输入，参考实现自生成正弦会把相位差混进物理
    // <out> 优先链输出里的 ParamHairFront，否则取第一条链第一个输出
    if (g_physicsTrace.load(std::memory_order_relaxed)) {
        static double traceStartSec = 0.0;
        if (g_traceOutParam == -2) {
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

// 面捕覆盖：面部参数优先级最高
void applyFaceValuesLocked() {
    if (g_faceValues.empty()) return;
    float *values = csmGetParameterValues(g_model);
    if (!values) return;

    for (size_t i = 0; i < g_faceValues.size(); ++i) {
        const float v = g_faceValues[i];
        // NaN = 本帧无输入（含被降级的 ±Inf），保留动作/表情结果
        if (!std::isfinite(v)) continue;
        // 有限值也可能远超量程，按模型量程钳一次
        const float minV = i < g_paramMin.size() ? g_paramMin[i] : -1.0f;
        const float maxV = i < g_paramMax.size() ? g_paramMax[i] : 1.0f;
        values[i] = (minV <= maxV) ? std::max(minV, std::min(maxV, v)) : v;
    }
}

// 合成面捕：1Hz 正弦扫 ±30° 三轴错相，0.5Hz 半波张嘴
// ParamBodyAngleX/Z 按头部的 0.30 / 0.20 跟随
// 须在 applyModelStateLocked 之前调用
void applySyntheticFaceLocked(double nowSec) {
    if (!g_syntheticFace.load(std::memory_order_relaxed)) return;
    if (g_faceValues.empty()) return;
    if (g_syntheticStartSec <= 0.0) g_syntheticStartSec = nowSec;

    const double t = nowSec - g_syntheticStartSec;
    constexpr double kTwoPi = 6.283185307179586;
    // flag 文件 arvt_synth_stress：满量程快摆，验证大幅高频输入不发散
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
        // 四个互不成整数比的分量叠加，互相关峰值唯一，相位可测到毫秒；幅值合计 ±17° 不饱和
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
    // 0.5Hz 半波张嘴，幅值 1.0
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
    // 眼睛保持睁开，避免眨眼干扰抖动判断
    setParam("ParamEyeLOpen", 1.0f);
    setParam("ParamEyeROpen", 1.0f);

    g_faceDirty = true;
}

// update 前统一消毒：动作 / 物理 / 面捕三个来源都不可信
// 非有限值取模型默认值，越界钳到 [min,max]，部件不透明度钳到 [0,1]
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

// 整帧重建：复位 → 动作 → 表情 → 面捕 → 眨眼 → 物理 → 姿势 → 消毒，调用方持 g_mutex
void applyModelStateLocked() {
    if (!g_model) return;

    resetParametersLocked();
    float modelOpacity = 1.0f;
    applyMotionLocked(&modelOpacity);
    applyExpressionLocked();
    // 合成面捕先写 g_faceValues，再由 applyFaceValuesLocked 落到参数
    applySyntheticFaceLocked(g_frameNowSec);
    applyFaceValuesLocked();
    // 眨眼须在物理前：EyeLOpen 经 physics3 驱动眼皮，晚写会让物理读到上一帧眼睛状态
    applyAutoBlinkLocked(g_frameNowSec);
    applyPhysicsLocked();
    applyPoseLocked();
    sanitizeModelStateLocked();

    g_modelOpacity = modelOpacity;
    g_modelDirty = false;
}

// 只抄 x/y/a 回 g_vertices，UV 与索引不变，VBO 大小恒定只需 glBufferSubData
// 不重算 g_minX..g_maxY：投影取景固定，否则头动会让画面整体缩放
void refreshVertexPositionsLocked() {
    if (!g_model || g_vertexSpans.empty()) return;

    const int drawableCount = csmGetDrawableCount(g_model);
    if (drawableCount <= 0) return;
    const csmVector2 **positions = csmGetDrawableVertexPositions(g_model);
    if (!positions) return;

    // 只记录逐位变化的顶点区间：静止部件零上传，变化网格按 min..max 并集上传
    int dirtyFirst = -1;
    int dirtyLast = -1;
    bool anyChange = false;

    const int vertexTotal = static_cast<int>(g_vertices.size());
    for (const VertexSpan &span : g_vertexSpans) {
        const int di = span.drawableIndex;
        if (di < 0 || di >= drawableCount) continue;
        const csmVector2 *pos = positions[di];
        if (!pos) continue;

        // 顶点数按双方较小值抄，不越过本 drawable 区间
        const int n = std::min(span.vertexCount, std::max(0, vertexTotal - span.firstVertex));
        Vertex *dst = g_vertices.data() + span.firstVertex;
        bool spanChanged = false;
        for (int v = 0; v < n; ++v) {
            // 非有限坐标保留上一个有效值，写入 NaN 会让整块 VBO 炸裂
            const float x = pos[v].X;
            const float y = pos[v].Y;
            if (std::isfinite(x) && std::isfinite(y)) {
                if (dst[v].x != x || dst[v].y != y) {
                    dst[v].x = x;
                    dst[v].y = y;
                    spanChanged = true;
                }
            }
            // a 恒 1.0，逐 drawable opacity 由 drawFrame 的 uOpacity 提供
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

    if (!anyChange) return;   // 全静止：不置脏，本帧零上传

    g_verticesDirty = true;
    if (g_dirtyVertexFirst < 0 || dirtyFirst < g_dirtyVertexFirst) {
        g_dirtyVertexFirst = dirtyFirst;
    }
    if (dirtyLast > g_dirtyVertexLast) g_dirtyVertexLast = dirtyLast;
    // 区间钳在 [0, size-1]
    if (g_dirtyVertexFirst < 0) g_dirtyVertexFirst = 0;
    if (g_dirtyVertexLast >= vertexTotal) g_dirtyVertexLast = vertexTotal - 1;
}

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

// 预乘 alpha：纹理是 straight alpha，fragment 里 rgb 先乘 a 再配合 GL_ONE / GL_ONE_MINUS_SRC_ALPHA
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

// 属性下标固定：GLES 2.0 无 layout(location)，content 与 mask program 共用同一组，切 program 不必重设描点指针
const GLuint kAttrPos = 0;
const GLuint kAttrUv = 1;
const GLuint kAttrAlpha = 2;

// 遮罩 shader：只按纹理 alpha 决定覆盖，低于 0.004 的片元 discard
// 不乘 vAlpha / uOpacity：遮罩网格在模型里 opacity 为 0，乘进来会把被裁的眼睛零件整片裁掉
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

// GLES 2.0 shader 不能写 layout(location)，链接前用 glBindAttribLocation 固定属性下标
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

    // 链接后 shader 对象即可释放
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

    // 遮罩 program 只换 fragment，顶点阶段复用
    g_maskProgram = buildProgramWith(kVertexShader, kMaskFragmentShader);
    if (g_maskProgram == 0) {
        // 遮罩 program 失败退回不裁剪，画面仍可用
        LOGW("遮罩 program 构建失败，本帧起所有 drawable 不裁剪");
        return true;
    }
    g_maskUniformMvp = glGetUniformLocation(g_maskProgram, "uMvp");
    g_maskUniformTex = glGetUniformLocation(g_maskProgram, "uTex");

    return true;
}

// 正交投影：模型 bbox 适配视口并保持宽高比
void updateProjection() {
    if (g_viewW <= 0 || g_viewH <= 0 || g_vertices.empty()) return;

    const float modelW = g_maxX - g_minX;
    const float modelH = g_maxY - g_minY;
    if (modelW <= 0 || modelH <= 0) return;

    const float cx = (g_minX + g_maxX) * 0.5f;
    const float cy = (g_minY + g_maxY) * 0.5f;

    // 边距 1.08
    const float margin = 1.08f;
    const float viewAspect = static_cast<float>(g_viewW) / static_cast<float>(g_viewH);
    const float modelAspect = modelW / modelH;

    float halfW;
    float halfH;
    if (viewAspect >= modelAspect) {
        halfH = modelH * 0.5f * margin;
        halfW = halfH * viewAspect;
    } else {
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

// 手势复合：p_ndc' = scale * p_ndc + (dx, dy)；对角与平移列先乘 scale 再叠加偏移
void buildGestureMvp(float *out) {
    std::memcpy(out, g_mvp, sizeof(float) * 16);
    const float s = g_gesture.scale;
    out[0] *= s;
    out[5] *= s;
    out[12] = out[12] * s + g_gesture.dx;
    out[13] = out[13] * s + g_gesture.dy;
}

// 只在句柄属于当前 EGL context 时 glDelete，跨 context 删除会压入 GL_INVALID_VALUE
// 并污染后续 uploadTextures 的错误判定；旧 context 句柄只丢记录，内存随 context 回收
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

    // 句柄记录一律清空，新 context 里已无意义
    g_vbo = 0;
    g_ibo = 0;
    g_program = 0;
    g_maskProgram = 0;
    g_textures.clear();
    g_attrPos = g_attrUv = g_attrAlpha = -1;
    g_uniformMvp = g_uniformOpacity = g_uniformTex = -1;
    g_maskUniformMvp = g_maskUniformTex = -1;

    // 丢弃残留错误：旧 context 操作或上面的删除失败都会留下
    drainGlErrors();
}

// 上传 CPU 侧纹理，返回成功张数，GL 线程调用
int uploadTextures() {
    g_textures.assign(g_textureImages.size(), 0);
    int uploaded = 0;

    for (size_t i = 0; i < g_textureImages.size(); ++i) {
        const TextureImage &img = g_textureImages[i];
        if (img.width <= 0 || img.height <= 0 || img.rgba.empty()) {
            LOGW("跳过空纹理槽位 #%zu (%s)", i, img.name.c_str());
            continue;
        }

        // glGetError 是粘性队列，上传前清空，避免把上张的错误算到本张
        drainGlErrors();

        GLuint tex = 0;
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);

        // GL_LINEAR：原图 2048² 远大于屏幕必须缩小采样，不做 MIPMAP
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        // UV 贴 0/1 边界（v 最大 1.0006），REPEAT 会卷回对面像素，须 CLAMP_TO_EDGE
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // RGBA8 行宽天然 4 字节对齐，无需 glPixelStorei
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, img.width, img.height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, img.rgba.data());

        // 前面已 drain，此处错误只属于本次 glTexImage2D
        const GLenum glError = glGetError();
        if (glError != GL_NO_ERROR) {
            // 失败保留已分配的空纹理：置 0 会让 drawFrame 整批跳过，一半部件消失
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

// 固定 GL 状态：surfaceCreated 与换模型路径共用，保证三条路径状态一致
void configureGlState() {
    // 2D 静态画面：关深度与剔除（部分 artmesh 双面），开预乘 alpha 混合
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    // Live2D 预乘混合，着色器已把 RGB 乘过 alpha
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glClearColor(0.043f, 0.043f, 0.063f, 1.0f);

    // 先读实际位数：无模板缓冲时 glClear 是空操作，有则清成 0
    GLint stencilBits = 0;
    glGetIntegerv(GL_STENCIL_BITS, &stencilBits);
    g_stencilBits = stencilBits;
    if (stencilBits > 0) {
        // 清模板前先把写掩码置满，否则沿用上一帧可能为 0 的掩码
        glStencilMask(0xFF);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);
    } else {
        LOGW("EGL 配置没有模板缓冲（GL_STENCIL_BITS=0）：Clipping Mask 不生效，"
             "检查 Live2DScreen 的 setEGLConfigChooser");
    }
    glDisable(GL_STENCIL_TEST);
}

// 重建全部 GL 资源：program → 纹理 → VBO/IBO → 固定状态，须持 g_mutex 且在目标 context 的 GL 线程
// 返回 false = program 构建失败或无几何数据，此时不触碰旧资源记录
bool rebuildGlResourcesLocked() {
    // 先查几何：buildProgram 之后再提前返回会泄漏 program
    if (g_vertices.empty() || g_indices.empty()) {
        LOGW("rebuildGlResources: 没有顶点数据（模型是否加载成功？）");
        return false;
    }

    if (!buildProgram()) {
        LOGE("rebuildGlResources: shader program 构建失败");
        return false;
    }

    // 纹理上传必须在持有 EGL context 的 GL 线程
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
    // 上传即最新，清掉残留脏标记
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

// 上下文重建后须复位粒子位置/速度：旧状态配新姿态会让头发/裙摆瞬间炸开
void resetPhysicsStateLocked() {
    for (PhysicsChain &chain : g_physicsChains) {
        initializeChain(chain);
    }
    g_physicsRemainTime = 0.0f;
}

// 每 5 秒把抖动统计打到 logcat（TAG=Live2DJNI，关键字 jitter），GL 线程持 g_mutex
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

// JNI 入口：对应 Kotlin Live2DRenderer，external fun 均为实例方法，第二参数是 jobject
extern "C" {

// 清空 CPU 侧模型状态：首次加载与切换模型共用，不碰 GL 句柄，由 g_glModelDirty 通知重建
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

    // 新模型几何与纹理都变了，置 g_glModelDirty 让 GL 线程重建
    g_glModelDirty = true;
}

// 从 ResourceSource 加载模型，返回 null = 成功，非 null = 失败原因；调用方持 g_mutex
const char *loadModelLocked(const ResourceSource &src, const std::string &path,
                            std::string *err) {
    resetModelStateLocked();

    csmSetLogFunction(&coreLog);
    csmSetLogLevel(2);  // 2 = warning 及以上

    // 先加载纹理：loadModel 据此剔除引用空槽位的 drawable
    if (!loadTextures(src, path, err)) return err->c_str();
    if (!loadModel(src, path, err)) return err->c_str();
    return nullptr;
}

// 从设备本地文件加载模型，mocPath 为 .moc3 绝对路径，返回 null = 成功
// 唯一加载入口；路径由 Kotlin 安全解压目录拼出，这里只拒空路径与非 .moc3
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
    // 只接受 .moc3，大小写不敏感（兼容 Windows 打包的 zip）
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


// params 为 [id, value, ...] 扁平数组，id 是 Purism Core 参数下标
// 只写 g_faceValues 缓冲并置脏，不直写 csmGetParameterValues：逐帧重建会复位参数
// 任意线程可调，只改 CPU 侧；求值与 VBO 重传在 GL 线程
// 返回写入个数；-1 模型未就绪，-2 数组长度非偶
JNIEXPORT jint JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeUpdateParameters(
        JNIEnv *env, jobject /*thiz*/, jfloatArray params) {
    if (!params) return -2;

    const jsize length = env->GetArrayLength(params);
    if (length <= 0 || (length % 2) != 0) return -2;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return -1;
    if (g_faceValues.empty()) return -1;

    // 直通数组指针，省掉 30Hz × N 参数的额外拷贝
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

        // NaN 是「本帧不覆盖」哨兵，±Inf 降级成 NaN：Inf 会扩散到相邻顶点炸裂
        float value = raw[i + 1];
        if (!std::isfinite(value)) {
            if (std::isnan(value)) {
                g_faceValues[static_cast<size_t>(index)] = value; // 哨兵原样保留
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

// 播放动作：group 为 Motions 键顺序，index = -1 取组内第 0 个，返回实际下标
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

// 设置表情，index = -1 取消，返回当前表情下标
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

// 列出动作组与表情，每行一个 tab 分隔项，页面初始化时读一次
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

// 参数名 → 下标，模型里不存在的返回 -1，供 Kotlin 建缓存
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

// 初始化 GL 资源：编译 shader、上传纹理、建 VBO/IBO，GL 线程调用
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSurfaceCreated(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_glThreadId = std::this_thread::get_id();

    // 无条件置脏：surfaceCreated 说明旧 GL 句柄全部作废，重建成功后才清
    g_glModelDirty = true;

    // 先 releaseGl 再更新 g_glContext：它需看到旧 context 才能判断句柄可删
    releaseGl();
    g_glContext = currentEglContext();
    LOGI("nativeSurfaceCreated: eglContext=%p（%s）",
         reinterpret_cast<void *>(g_glContext),
         g_glContext == EGL_NO_CONTEXT ? "无 context，GL 调用不可用" : "ok");

    // GL 上下文重建：时间基准归零 → 物理复位 → 完整状态重建 → 顶点刷新
    if (g_model) {
        // 停渲染期间积累的 dt 不能灌进动作/物理积分
        g_lastTickSec = 0.0;
        g_physicsDt = 0.0f;
        // 物理链位置/速度属于旧 context，整体复位，否则重建首帧炸开
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
        g_glModelDirty = false;
    } else {
        LOGE("nativeSurfaceCreated: GL 资源重建失败，保持脏标记等下一帧重试");
    }
}

// 视口与投影矩阵，GL 线程调用
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSurfaceChanged(
        JNIEnv * /*env*/, jobject /*thiz*/, jint width, jint height) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_viewW = width;
    g_viewH = height;
    glViewport(0, 0, width, height);
    updateProjection();
}

// 手势变换：dx/dy 单位 = 屏幕半宽/半高，scale 夹到 [0.5, 3.0]，平移按包围盒钳制
// 任意线程可调，只写三个 float，MVP 合成在 GL 线程
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetTransform(
        JNIEnv * /*env*/, jobject /*thiz*/, jfloat dx, jfloat dy, jfloat scale) {
    std::lock_guard<std::mutex> lock(g_mutex);

    float s = scale;
    if (!(s > 0.0f)) s = 1.0f; // 挡 NaN
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

JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeResetTransform(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_gesture.dx = 0.0f;
    g_gesture.dy = 0.0f;
    g_gesture.scale = 1.0f;
}

// 指定 scale 下的平移区间 [minX,maxX,minY,maxY]，native 为单一事实来源，视口未知时 ±kPanFallback
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

// 每帧绘制，GL 线程调用：推进动作时钟 → 重建状态 → 按逐帧可见性/顺序绘制
// 锁内只做 csm 求值与绘制计划，glDraw* 在锁外；遮罩写模板 1 后按 GL_EQUAL 1 裁剪，多遮罩取并集
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeDrawFrame(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    // 入口先清 stencil 与颜色；glStencilMask 不置满时 glClear 清不动模板
    // 先关 SCISSOR_TEST，残留裁剪框会把全屏清除限制住
    glDisable(GL_SCISSOR_TEST);
    glStencilMask(0xFF);
    glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

    // 函数级 static 复用缓冲，避免每帧分配
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

        // 换模型时 EGL context 仍活着，旧 VBO/IBO 与纹理句柄必须在这里换掉
        if (g_glModelDirty) {
            // 同 context 才真正 glDelete，跨 context 由 releaseGl 判定
            releaseGl();
            g_glContext = currentEglContext();

            if (g_vertices.empty() || g_indices.empty()) {
                // 无几何可重建也要清脏标记，否则 60Hz 每帧重编 shader
                g_glModelDirty = false;
                return;
            }

            // 新模型就绪后重建一次完整状态再上传顶点
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
                updateProjection();
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
        buildGestureMvp(mvp);

        // 动作与物理共用同一个已钳到 kPhysicsMaxDt 的 dt，掉帧时不会脱钩
        const double nowSec = std::chrono::duration<double>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        if (g_lastTickSec <= 0.0) g_lastTickSec = nowSec;
        const double dt = std::min(static_cast<double>(kPhysicsMaxDt),
                                   std::max(0.0, nowSec - g_lastTickSec));
        g_lastTickSec = nowSec;
        // 帧时间戳：合成面捕与诊断统计共用这条时间轴
        g_frameNowSec = nowSec;

        if (g_motionActive && dt > 0.0) {
            g_motionTime += dt;
            g_modelDirty = true; // 动作每帧都在变，需要重新求值
        }

        // 物理是连续系统，有链就逐帧推进；dt=0 时累加时钟不走
        g_physicsDt = static_cast<float>(dt);
        g_traceFrame = (g_physicsTrace.load(std::memory_order_relaxed) ? g_traceFrame + 1 : 0);
        const bool physicsRunning = !g_physicsChains.empty();

        // 状态应用每帧跑（物理时钟要推进），csmUpdateModel 与顶点抄写只在参数真变时做
        if (g_model && (g_modelDirty || g_faceDirty || physicsRunning)) {
            const bool externalDriven = g_modelDirty || g_faceDirty;
            applyModelStateLocked();

            // 与上一帧实际求值的那份参数逐项比较，相等则整帧跳过求值
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

        // 每 5s 一行 logcat
        reportJitterPeriodicallyLocked(nowSec);

        // 只传本帧变化的连续区间；VBO 容量与顶点数不一致时改 glBufferData 重分配
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

        // 逐帧可见性 + opacity + renderOrder 排序
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

        // 遮罩是否生效看所属部件的 opacity：只在「默认可见、本帧压成 0」时失效
        // 眼睛类只当遮罩的图层默认就是 0，按绝对值判定会误关
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
                    if (def > 0.001f && o <= 0.001f) s_maskActive[i] = 0;
                }
            }
        }

        // s_opacity 兼作可见性：0 = 不画
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

    // 无 8-bit 模板或遮罩 program 编译失败：退回不裁剪直接画
    const bool stencilUsable = stencilBits >= 8 && maskProgram != 0;

    glUseProgram(program);
    glUniformMatrix4fv(uniformMvp, 1, GL_FALSE, mvp);

    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);

    // 交错布局 x,y,u,v,a = 5 float = 20 字节，属性下标由 glBindAttribLocation 固定
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

    // 纹理单元 0 固定给 uTex，切 program 后要重设
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(uniformTex, 0);

    // Batch.firstIndex 是 uint16 下标序号，不是字节偏移
    const auto drawRange = [](int firstIndex, int indexCount) {
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_SHORT,
                       reinterpret_cast<const void *>(
                               static_cast<uintptr_t>(firstIndex) * sizeof(uint16_t)));
    };
    const auto textureOf = [](int index) -> GLuint {
        if (index < 0 || index >= static_cast<int>(s_textures.size())) return 0;
        return s_textures[static_cast<size_t>(index)];
    };

    glStencilMask(0xFF);

    // 模板清除按「本批遮罩包围盒 ∪ 上一批残留」scissor，避免每批全屏清
    // 残留只在遮罩几何包围盒内；/data/local/tmp/arvt_stencil_full 存在则退回全屏清除
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

    // 顶点集在屏幕像素空间的包围盒，已钳到视口并加 2px 余量
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
    // 上一批写入模板的 1 的包围盒（空 = 模板全 0）
    PixelRect stencilDirty;

    // 每个遮罩几何的包围盒每帧只算一次，同一遮罩常被多个批次共用
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
    long long clearedPixels = 0;
    for (int bi : s_order) {
        const size_t index = static_cast<size_t>(bi);
        // 整体不透明度（动作 Target=Model/Opacity）乘到每个批次
        const float opacity = s_opacity[index] * modelOpacity;
        if (opacity <= 0.001f) { ++skippedInvisible; continue; }

        const Batch &batch = s_batches[index];
        const GLuint tex = textureOf(batch.textureIndex);
        if (tex == 0) continue;
        if (!stencilUsable || batch.maskCount <= 0) {
            glBindTexture(GL_TEXTURE_2D, tex);
            glUniform1f(uniformOpacity, opacity);
            drawRange(batch.firstIndex, batch.indexCount);
            ++drawnBatches;
            continue;
        }

        glEnable(GL_STENCIL_TEST);

        // 残留的 1 若不一起清，本批 GL_EQUAL 1 会幽灵放行
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
            // 覆盖过半屏时 scissor 省不了带宽，直接全屏清
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
        // 遮罩全失效时 drawnMasks==0 分支退回不裁剪，这里不清也安全
        stencilDirty = PixelRect();

        // 只写模板不写颜色
        glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        glStencilFunc(GL_ALWAYS, 1, 0xFF);
        // GL_REPLACE：通过就写 1
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);

        glUseProgram(maskProgram);
        glUniformMatrix4fv(maskUniformMvp, 1, GL_FALSE, mvp);
        glUniform1i(maskUniformTex, 0);

        int drawnMasks = 0;
        // 记下本批写进模板的 1 的包围盒，供下一批清除时并集
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
            // 遮罩所属部件被 pose/动作隐藏则跳过，一个都没画时退回不裁剪
            if (!s_maskActive[static_cast<size_t>(maskBatchIndex)]) continue;
            const GLuint maskTex = textureOf(mb.textureIndex);
            if (maskTex == 0) continue;

            glBindTexture(GL_TEXTURE_2D, maskTex);
            drawRange(mb.firstIndex, mb.indexCount);
            written.unite(maskRectOf(maskBatchIndex));
            ++drawnMasks;
        }
        stencilDirty = written;

        // 模板全 0 时继续 GL_EQUAL 1 会裁没整块，退回不裁剪
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

        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        // 反转遮罩：模板 0 的区域才是内容（Live2D InvertedMask 语义）
        glStencilFunc(batch.invertedMask ? GL_NOTEQUAL : GL_EQUAL, 1, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);

        glUseProgram(program);
        glUniformMatrix4fv(uniformMvp, 1, GL_FALSE, mvp);
        glUniform1i(uniformTex, 0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glUniform1f(uniformOpacity, opacity);
        drawRange(batch.firstIndex, batch.indexCount);

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

    // 只在首帧打一次日志，避免 60Hz 刷屏
    static bool loggedOnce = false;
    if (!loggedOnce) {
        loggedOnce = true;
        LOGI("首帧绘制完成: batches=%d/%zu skippedInvisible=%d stencilBits=%d usable=%d",
             drawnBatches, s_batches.size(), skippedInvisible, stencilBits,
             stencilUsable ? 1 : 0);
    }

    // 模板清除方式统计，5s 一行
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

    // FPS 统计，0.5s 窗口，GL 线程独占，Kotlin 侧轮询
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

// 释放原生资源，调用线程不确定：CPU 侧持锁可随时释放
// glDelete* 只在持有 EGL context 的 GL 线程执行，否则只丢句柄，GPU 内存随 context teardown 回收
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeRelease(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    LOGI("nativeRelease: enter onGlThread=%d",
         (g_glThreadId == std::this_thread::get_id()) ? 1 : 0);

    const bool onGlThread = (g_glThreadId == std::this_thread::get_id());
    if (onGlThread) {
        releaseGl();
    } else {
        // 只丢句柄，不跨线程调 GL
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
    // 参数快照失效，下一帧强制求值
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

// 最近 0.5s 窗口的渲染帧率
JNIEXPORT jfloat JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeFps(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_fps.load(std::memory_order_relaxed);
}

// 诊断：一行统计，含 drawable/批次/纹理/顶点/遮罩数/包围盒
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

// 物理抖动度量：返回一行并清零（滑动窗口），flips/f 为抖动直接证据
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

// 物理开关：关闭后输出参数每帧写回默认值，用于二分定位抖动来源
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

JNIEXPORT jboolean JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeIsPhysicsEnabled(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_physicsEnabled.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

// 物理参考模式：仅保留 JNI ABI 兼容，无行为差异
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetPhysicsReferenceMode(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const bool on = enabled == JNI_TRUE;
    g_physicsReferenceMode.store(on, std::memory_order_relaxed);
    LOGI("物理参考模式: %s（已退役，无行为差异）", on ? "on" : "off");
}

// 逐帧曲线追踪，输出与 host/ref/refphysics 的 CSV 对齐，默认关闭
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetPhysicsTrace(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const bool on = enabled == JNI_TRUE;
    g_physicsTrace.store(on, std::memory_order_relaxed);
    g_traceFrame = 0;
    LOGI("物理曲线追踪: %s", on ? "on" : "off");
}

// pose 兜底互斥开关：关闭后恢复缺 pose3 时两套部件同时可见
// pose3.json 读到的组不受影响
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

// 合成面捕：1Hz 正弦写 ParamAngleX/Y/Z + 0.5Hz 半波 ParamMouthOpenY
// 关闭时注入过的参数复位成 NaN（NaN = 本帧不覆盖）
JNIEXPORT void JNICALL
Java_com_exarry_arvtubelink_ui_live2d_Live2DRenderer_nativeSetSyntheticFace(
        JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const bool on = enabled == JNI_TRUE;
    const bool wasOn = g_syntheticFace.exchange(on, std::memory_order_relaxed);
    if (wasOn == on) return;
    g_syntheticStartSec = 0.0;
    if (!on) {
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
