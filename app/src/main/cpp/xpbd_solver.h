// ============================================================================
//  xpbd_solver.h - 头发 / 裙摆 XPBD 求解器（header-only，可脱离 Android 编译）
// ============================================================================
//
//  为什么是独立头文件：物理是唯一「必须靠数值实验调参」的模块，把它从
//  live2d_jni.cpp 里拆出来后，同一份代码既能被 App（NDK）编译，也能被
//  local_debug/repro/host/xpbd_host.cpp 在桌面端逐帧回放与量化（滞后、过冲、
//  饱和、NaN）。调参闭环从「改 C++ → 全量重编 → 装机 → 抓 logcat」
//  缩短到「改一行 → 重编 host → 1 秒出曲线」。
//
//  算法（Macklin et al., "Small Steps in Physics Simulation", SCR'19）：
//
//    每渲染帧 → 累加时钟 → 按固定子步 h 循环
//      每个子步只做 **一次** XPBD 约束求解（Gauss-Seidel 顺序投影）
//
//    「多子步 × 单次迭代」替代「大时间步 × 多次迭代」：小步长下刚性约束的
//    离散误差 O(h²) 与人为阻尼都随子步数下降，阻尼不再吃掉惯性。
//
//  三类约束：
//    1) 距离约束（等式，柔度 0）—— 不可伸长杆，杆长取 json Radius；
//    2) 目标方向约束（等式，柔度 α 来自 json Delay/Acceleration）—— 把粒子
//       拉向「父粒子 + 输入目标方向 × 杆长」。它同时是输入注入点与刚度来源：
//       α 大 = 软 = 跟得慢 = 拖尾明显；α→0 = 刚性 = 贴着输入走。
//    3) 弯曲约束（单边不等式）—— 防大幅甩动时链对折。
//
//  惯性从哪来（这是「拖尾」与「低通迟滞」的分界）：
//    速度是显式状态，子步里先 v ← 阻尼·v 再预测 x ← x + v·h，约束解算后
//    用 v ← (x − x_prev)/h 回推。约束只做「位置修正」，不消灭切向动量 ——
//    所以头部换向时粒子仍带着原方向的动量继续走，形成过冲与回摆。
//    纯低通滤波（一阶 IIR）没有状态动量，换向时只会单调收敛，永远不会过冲：
//    这是二者在数值上的唯一可靠判据（见 analyze_xpbd.py 的「惯性」一节）。
//
//  稳定性保障：
//    · 固定子步长 h（与渲染帧率、json Meta.Fps 双解耦）；
//    · XPBD 柔度 α̃ = α/h²，约束按 1/(w_sum + α̃) 缩放 —— 等价于对刚度
//      1/α 的弹簧做隐式积分，无条件稳定；柔度只改「跟随快慢」，不改稳定性；
//    · 逐粒子速度硬钳（只封数值爆炸路径，远高于任何真实运动）；
//    · 非有限值检测 → 整链复位静息姿态，由调用方决定是否记日志。
// ============================================================================
#pragma once

#include <cmath>
#include <cstddef>
#include <limits>
#include <string>
#include <vector>

namespace xpbd {

/** json Input.Type：X / Y 为位移型，Angle 为角度型。 */
enum InputType { kTypeX = 0, kTypeY = 1, kTypeAngle = 2 };

/** 一条物理输入：参数 → Normalization 区间的位移或角度。 */
struct Input {
    int paramIndex = -1;
    float weight = 1.0f;   // 已除以 100
    int type = kTypeX;
    bool reflect = false;
};

/** 一条物理输出：粒子间几何量 → 参数。 */
struct Output {
    int paramIndex = -1;
    int vertexIndex = 0;
    float scale = 1.0f;
    float weight = 1.0f;   // 已除以 100
    int type = kTypeX;
    bool reflect = false;
};

/** 一节质点（json 的 Vertices 项）。 */
struct Vertex {
    float restX = 0.0f;        // json 原始 Position（求解器不用；Radius 兜底计算用）
    float restY = 0.0f;
    float mobility = 1.0f;     // 速度继承比例
    float delay = 1.0f;        // 惯性延迟（越大越拖）→ 约束柔度
    float acceleration = 1.0f; // 跟随强度（越大越紧）→ 约束柔度
    float radius = 0.0f;       // 杆长（json Radius；无效时由解析侧退回 Position 差）
};

/** 一条物理链（physics3.json 的一个 PhysicsSetting）。 */
struct Chain {
    std::string id;
    std::vector<Input> inputs;
    std::vector<Output> outputs;
    std::vector<Vertex> vertices;

    float posMin = -10.0f, posDef = 0.0f, posMax = 10.0f;
    float angMin = -10.0f, angDef = 0.0f, angMax = 10.0f;
    float gravityX = 0.0f, gravityY = -1.0f;

    // ---- 粒子运行时状态（下标 = 粒子）----
    std::vector<float> px, py;        // 位置（粒子空间 = Normalization 数值空间）
    std::vector<float> lastX, lastY;  // 子步起点位置（速度回推用）
    std::vector<float> velX, velY;    // 速度
    std::vector<float> curOut;        // 本子步输出
    std::vector<float> prevOut;       // 上一子步输出（帧间插值用）
    bool initialized = false;

    /** 上一步的目标方向（诊断用：观测量，不参与求解）。 */
    float debugDirX = 0.0f, debugDirY = 1.0f;

    // ---- P14：静止链跳过（「只算有输入变化的链」）----
    /** 上一子步的输入汇总（NaN = 尚未跑过，强制第一步求解）。 */
    float prevInputX = std::numeric_limits<float>::quiet_NaN();
    float prevInputY = std::numeric_limits<float>::quiet_NaN();
    float prevInputAngle = std::numeric_limits<float>::quiet_NaN();

    // ---- P16：物理输入平滑（真实面捕噪声抑制）----
    /**
     * 死区状态（sticky band）：committed = 当前已提交给求解器的输入。
     *
     * 每子步把 committed 拉向 raw ± dz：raw 在带内时 committed 不动（静止噪声
     * 被吃掉），raw 越出带外时 committed 贴着带沿跟随（真实运动以原斜率通过，
     * 只恒定落后 dz 的位移，肉眼不可辨）。不做速率限幅 —— 限幅会让快速甩头
     * 变成转头慢半拍，那是 P8 的老毛病。
     */
    float deadCommittedX = 0.0f, deadCommittedY = 0.0f, deadCommittedAngle = 0.0f;
    /** 死区状态是否已初始化（首个子步直接提交，不从带内起步）。 */
    bool deadPrimed = false;

    /** 一阶低通状态（输入平滑；fc=0 时不使用）。 */
    float filtX = 0.0f, filtY = 0.0f, filtAngle = 0.0f;
    bool filtPrimed = false;
    /** 连续被判为静止的子步数（诊断用，也用于日志限频）。 */
    int restSteps = 0;
    /** 本子步是否走了「静止跳过」快路径（诊断用）。 */
    bool skipped = false;
};

/** 参数表（由宿主提供：模型参数的量程与默认值）。 */
struct ParamTable {
    int count = 0;
    const float *min = nullptr;
    const float *max = nullptr;
    const float *def = nullptr;
};

// ---------------------------------------------------------------------------
//  可调参数（主旋钮）
// ---------------------------------------------------------------------------
//
//  为什么是运行时结构体而不是 constexpr：物理手感只能靠数值实验调，宿主端
//  回放工具需要在**不重编 App** 的前提下扫参（见 local_debug/repro/host/
//  xpbd_host.cpp）。App 侧用默认值，行为与 constexpr 完全一致。
struct Tuning {
    /**
     * 固定子步长：与渲染帧率、json Fps 双解耦。
     *
     * P14（性能急救）：1/120 → 1/60。本机 90fps 下 1/120 每帧要跑 1~2 个子步、
     * 每条链每子步一次 Gauss-Seidel 投影；big_fat_fish 55 链 / 149 输出时单帧
     * 物理开销翻倍，实测把帧率压到 44fps。1/60 与模型自带的 Meta.Fps=60 对齐，
     * 同样的柔度语义下惯性观感几乎不变（过冲 82%→79%、滞后 +89ms→+95ms），
     * 但子步数减半。桌面回放台仍可覆盖成 1/120 做高精度对照。
     */
    float substepDt = 1.0f / 60.0f;

    /** 单帧子步上限（掉帧防御；0.05s 帧 dt 钳制下最多 3 步，余量到 8）。 */
    int maxSubstepsPerFrame = 8;

    /**
     * 静止判定阈值（链内最大粒子速率平方，单位 = 归一化空间/s 的平方）。
     *
     * 输入与上一子步完全相同、且所有粒子都低于该速率时，链条已经收敛到稳态，
     * 跳过该子步的求解。稳态下输出是常量（相邻杆平行 → 角度输出 0，位移输出
     * 就是杆向量分量），跳过不改变画面，却把「静止的几十条链」从每帧计算里
     * 整条摘掉 —— 这是「只算有输入变化的链」的实现基础（P14）。
     */
    float restSpeedSq = 1.0e-6f;

    /**
     * 目标方向约束的柔度基值（XPBD α = 1/刚度）。
     *
     * α 越小跟得越紧、越像刚性杆；α 越大越软、拖尾越长、越容易过冲。
     * 逐节柔度 = 基值 × Delay / Acceleration。
     *
     * 0.003 由宿主回放台标定（local_debug/repro/host/bench_xpbd.py）：同输入下
     * 与参考基准（hiyori 多音激励，±17° 线性区）对比：
     *   滞后 +89ms（基准 +100ms）、传递增益 0.227（基准 0.414）、
     *   换向过冲 82%（基准 91%）—— 滞后与基准重合、保留惯性过冲；
     * 樱花miku（40 链 / 8 节长链）：滞后 +89ms、过冲 72%，同样不发散。
     * 阶跃响应可见「冲过终点 → 回摆 → 收敛」的二次振荡（周期约 440ms），
     * 这是一阶低通（迟滞）永远做不出来的形状。
     */
    float poseCompliance = 0.003f;

    /** 弯曲约束柔度（单边防对折；只在违例时激活）。 */
    float bendCompliance = 0.002f;

    /**
     * 弯曲约束下限比例：|x_{i+1} − x_{i−1}| ≥ ratio × (r_i + r_{i+1})。
     * 0.30 ≈ 允许相邻两杆折到 145°，再折就顶住。
     */
    float bendRatio = 0.30f;

    /**
     * 全局速度阻尼（1/s）：v *= exp(−damping·h)。
     * 只压惯性回摆的长尾，不动拖尾相位。过大就退化成低通迟滞。
     */
    float damping = 1.2f;

    /** mobility 语义的参考频率（Mobility 是「每 60Hz 步的速度继承比例」）。 */
    float mobilityRefFps = 60.0f;

    /** 速度硬钳因子：每子步最多位移 50 × max(radius,1)，只封数值爆炸路径。 */
    float speedRodFactor = 50.0f;

    /**
     * 位移型输入（Type=X/Y）的根粒子平移增益（1.0 = 完全忠于 json 语义）。
     *
     * 根粒子被直接钉在 Normalization 数值上（hiyori 为 ±10，而杆长 Radius≈3），
     * 所以位移输入会带着整条链平移数倍杆长 —— 这是 json 作者的设计，基准实现
     * 在满量程应力输入下同样有 46% 的帧输出顶到 ±1。改成 1.0 之外的任何值都
     * 等于凭空削弱模型作者写下的响应强度，故默认不做缩放。
     *
     * 保留这个旋钮只为诊断：宿主回放台可以用 gain=0.5 之类的值确认「输出幅度
     * 的差异来自位移输入量纲」而不是求解器本身的增益错误。
     */
    float translationGain = 1.0f;

    /** 动态粒子逆质量（json 无质量字段，统一 1；根粒子逆质量 0 = 运动学钉死）。 */
    float invMass = 1.0f;

    // -----------------------------------------------------------------------
    //  P16：物理输入端平滑（真实面捕噪声抑制）
    // -----------------------------------------------------------------------
    //
    //  为什么问题出在「输入端」而不是求解器：XPBD 是纯位置约束求解器，输入
    //  怎么变它就怎么跟。P13 重写时删掉了旧 P8 的输入增益 + 两级低通，
    //  于是**真实面捕的高频噪声第一次被原样灌进物理链**。数值实验
    //  （host/bench，30Hz 零阶保持 + 0.35° 高斯噪声）：
    //    干净输入  >4Hz 能量 2.5%、符号反转 0.64/帧
    //    带噪输入  >4Hz 能量 50.4%、|Δout| 放大 6 倍
    //  即：噪声不是「物理算错了」，而是物理忠实地把 3~15Hz 的采样抖动放大成
    //  头发微颤。一阶跟踪式求解器同样没有输入低通，但它每步只跟 1/5 的
    //  位移（等于极重的隐式平滑），所以天然不显；我们是二阶惯性系统，
    //  必须显式补上过滤。

    /**
     * 输入死区（归一化空间单位，0 = 关闭）。
     *
     * 语义是「sticky band」：目标值在已提交值 ±dz 以内时完全不提交，超出时
     * 把已提交值推到贴边。对静止噪声是死的（双向抖动互相抵消），对真实慢速
     * 运动不设限速（超过 dz 就同斜率跟随，不会像「差值小于死区就丢弃」那样
     * 让缓慢转头推不动头发）。
     *
     * 取值 0.05 ≈ 满量程(±10)的 0.5%，约合 0.15° 头部角 —— 低于人眼可辨的
     * 位移（头发端部对应位移约 0.3px @1080p）。host 联合扫参（final_grid.py /
     * verify_p16.py）的收益（dur 带噪静止面捕，σ=0.10°）：
     *   静止噪声摆幅 0.092 → 0.034（1/2.7），>4Hz 能量 76% → 49%；
     *   rest 跳过率 18% → 31%（静止链真的睡着，省掉整链求解）。
     * 代价仅在**慢速走近零速**时有量化（2°/s 与 0.5°/s 转头的运动跨度不变，
     * maxStair 反而略降：0.0038 → 0.0032），快甩头完全无感。
     * 再大（0.08/0.12）静止噪声继续降，但收益开始饱和，且 0.5°/s 慢速转头的
     * 输出与理想输入相关性明显变差（0.09 → -0.14），故选 0.05。
     */
    float inputDeadzone = 0.05f;

    /**
     * 输入一阶低通截止频率（Hz，0 = 关闭）。
     *
     * 把 30Hz 面捕的**零阶保持台阶**磨成连续输入：渲染 60/90Hz 时，每个
     * 面捕帧之间的参数是恒定值、然后一跳，物理在台阶边沿吃到的是脉冲加速度；
     * 低通把这个边沿拉成 τ = 1/(2π·fc) 的指数段。
     *
     * 取 12Hz 的理由（host 扫参 decisive.py / final_grid.py）：
     *   对 30Hz 台阶与 >10Hz 噪声衰减到 ~1/5，是**唯一能降低真实误差**的项 ——
     *   带 0.35° 噪声时与 240Hz 连续真值的 RMSE 0.0496 → 0.0408（-18%），
     *   >10Hz 抖动能量 0.34% → 0.09%；
     *   代价是运动幅度 -3.5%（spanRatio 0.965，低于可辨阈值）。
     * 低于 8Hz 会把真实甩头的过冲一起削掉（回到 P8「手感削没了」），
     * 高于 20Hz 对台阶几乎无效（20Hz 档 RMSE 反而回到 0.0442）。
     */
    float inputSmoothHz = 12.0f;

    /**
     * 低通收敛吸附阈值：残差小于它就与目标完全贴合。
     *
     * 必须存在：一阶 IIR 是渐近收敛的，永远到不了目标，`prevInput == total`
     * 的静止判据（P14）就永远不成立，静止链会被永久唤醒 —— 那正是「链一直
     * 在唤醒/休眠之间切换」的另一种表现。吸附让它在 ~0.1s 后精确落入稳态。
     */
    float inputSnapEps = 1.0e-4f;
};

/** 当前生效的参数（App 侧只读；宿主端回放工具可改）。 */
inline Tuning &tuning() {
    static Tuning t;
    return t;
}

// ---------------------------------------------------------------------------
//  输入归一化
// ---------------------------------------------------------------------------

/**
 * 源参数值 → Normalization 区间的分段线性映射。
 *
 * 量程处理约定：
 *  - 源量程与归一量程都先按上下界排序，不依赖调用方传参顺序；
 *  - 折算中心取**源量程中点**（不是 json Default 字段），中点两侧各用
 *    「归一跨度 / 源跨度」作为斜率；
 *  - Reflect=false 时整体取负，角度侧再配套一次取负，方向才自洽。
 *
 * NaN 输入天然安全：所有比较为假 → 结果保持 0。
 */
inline float normalizeParameterValue(float value, float paramMin, float paramMax,
                                     float normMin, float normMax, float normDef,
                                     bool inverted) {
    const float srcHigh = paramMax > paramMin ? paramMax : paramMin;
    const float srcLow = paramMax > paramMin ? paramMin : paramMax;
    const float normHigh = normMin < normMax ? normMax : normMin;
    const float normLow = normMin < normMax ? normMin : normMax;

    // 先钳进源量程，后续只处理「相对中点的偏移」。
    if (value > srcHigh) value = srcHigh;
    if (value < srcLow) value = srcLow;

    const float srcMid = srcLow + (srcHigh - srcLow) * 0.5f;
    const float offset = value - srcMid;
    if (offset == 0.0f) return inverted ? normDef : -normDef;

    // 正负半程分别取跨度：源跨度为零表示该侧退化，输出保持中点值。
    const bool upperHalf = offset > 0.0f;
    const float srcSpan = upperHalf ? (srcHigh - srcMid) : (srcLow - srcMid);
    const float normSpan = upperHalf ? (normHigh - normDef) : (normLow - normDef);

    float mapped = normDef;
    if (srcSpan != 0.0f) {
        const float slope = normSpan / srcSpan;
        mapped = offset * slope + normDef;
    }
    return inverted ? mapped : -mapped;
}

/** from→to 的有向角，归一化到 (-π, π]。 */
inline float directionToRadian(float fromX, float fromY, float toX, float toY) {
    constexpr float kPi = 3.14159265358979f;
    constexpr float kTwoPi = 6.28318530717959f;
    // fmod 把差值压进 (-2π, 2π)，再按需平移一个整圈到 (-π, π]。
    float delta = std::fmod(std::atan2(toY, toX) - std::atan2(fromY, fromX), kTwoPi);
    if (delta > kPi) {
        delta -= kTwoPi;
    } else if (delta < -kPi) {
        delta += kTwoPi;
    }
    return delta;
}

// ---------------------------------------------------------------------------
//  生命周期
// ---------------------------------------------------------------------------

/**
 * 复位到竖直静息姿态：根粒子 (0,0)，其后每节在父节点上方 +Radius 处。
 *
 * 静息姿态与「输入角 0 的目标方向 (0,1)」平行 → 稳态相邻杆夹角 0、输出 0，
 * 首帧即稳定，不需要额外的收敛步。
 */
inline void initialize(Chain &chain) {
    const size_t count = chain.vertices.size();
    chain.px.assign(count, 0.0f);
    chain.py.assign(count, 0.0f);
    chain.lastX.assign(count, 0.0f);
    chain.lastY.assign(count, 0.0f);
    chain.velX.assign(count, 0.0f);
    chain.velY.assign(count, 0.0f);
    for (size_t i = 1; i < count; ++i) {
        chain.px[i] = chain.px[i - 1];
        chain.py[i] = chain.py[i - 1] + chain.vertices[i].radius;
    }
    chain.lastX = chain.px;
    chain.lastY = chain.py;
    chain.curOut.assign(chain.outputs.size(), 0.0f);
    chain.prevOut.assign(chain.outputs.size(), 0.0f);
    chain.debugDirX = 0.0f;
    chain.debugDirY = 1.0f;
    // P14：复位静止跳过判据，保证复位后的第一子步一定真正求解一次
    chain.prevInputX = std::numeric_limits<float>::quiet_NaN();
    chain.prevInputY = std::numeric_limits<float>::quiet_NaN();
    chain.prevInputAngle = std::numeric_limits<float>::quiet_NaN();
    chain.restSteps = 0;
    chain.skipped = false;
    chain.deadCommittedX = chain.deadCommittedY = chain.deadCommittedAngle = 0.0f;
    chain.deadPrimed = false;
    chain.filtX = chain.filtY = chain.filtAngle = 0.0f;
    chain.filtPrimed = false;
    chain.initialized = true;
}

/**
 * 输入端平滑（P16）：死区 + 一阶低通，返回「可以喂给求解器的输入」。
 *
 *  处理顺序（先死区后低通，不能反）：
 *  1) 死区在**原始目标**上做 sticky band —— 抖动被吃掉，真实位移无损传递；
 *  2) 低通把死区输出的阶跃（30Hz 面捕台阶）磨成指数段，消除脉冲加速度。
 * 若先低通后死区，低通的渐近尾巴会让死区永远收到 1e-5 级残差，死区判据反复
 * 在临界值附近翻转，等于没装；同时死区输出被低通「重新平滑」后，静止判据
 * （prevInput 逐位相等）反而更难成立。
 */
inline void smoothInput(Chain &chain, float h, float rawX, float rawY,
                        float rawAngle, float *outX, float *outY,
                        float *outAngle) {
    const Tuning &t = tuning();
    const float dz = t.inputDeadzone;

    // ---- 1) 死区（sticky band）----
    // 提交值永远贴着目标值 ±dz：目标在带内就原地不动（噪声死掉），目标越出
    // 带外就跟着走（真实运动 1:1 通过，只是恒定落后 dz 的位移，肉眼不可辨）。
    // 这里不做「速率限幅」——限幅会把快速甩头变成转头慢半拍，那是 P8 的老毛病。
    if (dz > 0.0f) {
        if (!chain.deadPrimed) {
            chain.deadCommittedX = rawX;
            chain.deadCommittedY = rawY;
            chain.deadCommittedAngle = rawAngle;
            chain.deadPrimed = true;
        } else {
            const auto band = [dz](float target, float committed) -> float {
                if (target - committed > dz) return target - dz;
                if (committed - target > dz) return target + dz;
                return committed;
            };
            chain.deadCommittedX = band(rawX, chain.deadCommittedX);
            chain.deadCommittedY = band(rawY, chain.deadCommittedY);
            chain.deadCommittedAngle = band(rawAngle, chain.deadCommittedAngle);
        }
    } else {
        chain.deadCommittedX = rawX;
        chain.deadCommittedY = rawY;
        chain.deadCommittedAngle = rawAngle;
    }
    const float targetX = chain.deadCommittedX;
    const float targetY = chain.deadCommittedY;
    const float targetAngle = chain.deadCommittedAngle;

    // ---- 2) 一阶低通 ----
    if (t.inputSmoothHz > 0.0f && h > 0.0f) {
        const float tau = 1.0f / (6.283185307179586f * t.inputSmoothHz);
        const float a = h / (tau + h);   // 每子步的混合系数（与子步长自动对齐）
        if (!chain.filtPrimed) {
            chain.filtX = targetX;
            chain.filtY = targetY;
            chain.filtAngle = targetAngle;
            chain.filtPrimed = true;
        } else {
            const auto follow = [a, &t](float &state, float target) {
                state += (target - state) * a;
                if (std::fabs(target - state) < t.inputSnapEps) state = target;
            };
            follow(chain.filtX, targetX);
            follow(chain.filtY, targetY);
            follow(chain.filtAngle, targetAngle);
        }
        *outX = chain.filtX;
        *outY = chain.filtY;
        *outAngle = chain.filtAngle;
        return;
    }

    chain.filtPrimed = false;
    *outX = targetX;
    *outY = targetY;
    *outAngle = targetAngle;
}

// ---------------------------------------------------------------------------
//  子步
// ---------------------------------------------------------------------------

/**
 * 链条是否已经收敛到静止（P14）。
 *
 * 判据只看速度：位置只在「速度非零」或「约束违例」时才会变，而约束在收敛后
 * 不再违例。阈值取得极小（tuning().restSpeedSq = 1e-6，对应 1e-3 归一化单位/秒，
 * 杆长量级是 3~10），因此绝不会把「慢速拖尾」误判成静止。
 */
inline bool isResting(const Chain &chain) {
    const float limit = tuning().restSpeedSq;
    for (size_t i = 1; i < chain.velX.size(); ++i) {
        const float sq = chain.velX[i] * chain.velX[i] + chain.velY[i] * chain.velY[i];
        if (sq > limit) return false;
    }
    return true;
}

/**
 * 推进一条链一个 XPBD 子步。
 *
 * 子步流程（UE5 Chaos Groom 的 Advance → Solve → Update 三段式）：
 *  1) 输入汇总：Type × Weight 累加位移/角度；根粒子钉在平移上，角度映射为
 *     目标方向 d（输入角 0 → (0,1)，与静息竖直链平行）；
 *  2) Advance：v ← 阻尼·v，逐粒子硬钳，x ← x + v·h；
 *  3) Solve（单次 Gauss-Seidel，自根向尾）：
 *       距离约束 → 目标方向约束；弯曲约束统一在后；
 *  4) Update：v ← (x − x_prev)/h，再硬钳；
 *  5) NaN 防护：任一粒子非有限 → 复位静息姿态并返回 false。
 *
 * P14 静止跳过：输入与上一子步完全一致、且全链速度低于 restSpeedSq 时直接返回，
 * 不跑 Advance/Solve/Update。稳态输出是常量（见 isResting 注释），画面不变，
 * 但静止链（一个模型里通常占多数）不再消耗每帧的求解开销。
 *
 * @return false = 检出非有限值并已复位（调用方据此记日志）
 */
inline bool step(Chain &chain, const ParamTable &params, const float *caches,
                 float h) {
    const size_t count = chain.vertices.size();
    if (count < 2 || chain.px.size() != count) return true;

    // ---- 1) 输入汇总 ----
    float rawX = 0.0f;
    float rawY = 0.0f;
    float rawAngle = 0.0f;
    for (const Input &input : chain.inputs) {
        if (input.paramIndex < 0 || input.paramIndex >= params.count) continue;
        const size_t pi = static_cast<size_t>(input.paramIndex);
        const bool isAngle = (input.type == kTypeAngle);
        const float unit = normalizeParameterValue(
                                   caches[input.paramIndex], params.min[pi],
                                   params.max[pi],
                                   isAngle ? chain.angMin : chain.posMin,
                                   isAngle ? chain.angMax : chain.posMax,
                                   isAngle ? chain.angDef : chain.posDef,
                                   input.reflect) *
                           input.weight;
        if (!std::isfinite(unit)) continue;   // 单个异常输入不毒化整链
        if (isAngle) {
            rawAngle += unit;
        } else if (input.type == kTypeY) {
            rawY += unit;
        } else {
            rawX += unit;
        }
    }

    // ---- 1b) 输入端平滑（P16：死区 + 一阶低通，见 smoothInput）----
    float totalX;
    float totalY;
    float totalAngle;
    smoothInput(chain, h, rawX, rawY, rawAngle, &totalX, &totalY, &totalAngle);

    // ---- P14：静止链跳过（输入没变 + 已收敛）----
    // prevInput* 初值是 NaN，与任何 total 都不相等，因此每链第一次必然求解。
    // P16：比较的是**平滑后**的输入 —— 如果比原始输入，滤波还在收敛（每子步
    // 都在变）时链就不会被判静止，静止检测永远不会触发。
    if (totalX == chain.prevInputX && totalY == chain.prevInputY &&
        totalAngle == chain.prevInputAngle && isResting(chain)) {
        ++chain.restSteps;
        chain.skipped = true;
        return true;   // curOut 保持上次求解的结果：稳态输出本来就是常量
    }
    chain.prevInputX = totalX;
    chain.prevInputY = totalY;
    chain.prevInputAngle = totalAngle;
    chain.restSteps = 0;
    chain.skipped = false;

    // ---- 2) 根粒子（运动学钉死）+ 目标方向 ----
    const float deg2rad = 0.017453292519943295f;
    const float totalRad = totalAngle * deg2rad;
    float dirX = std::sin(totalRad);
    float dirY = std::cos(totalRad);

    // 位移型输入按杆长尺度缩放（见 tuning().translationGain），并与角度一起旋转到
    // 与目标方向同一坐标系 —— 头一转，位移输入的方向也跟着转。
    float tX = totalX * tuning().translationGain;
    float tY = totalY * tuning().translationGain;
    {
        const float c = std::cos(-totalRad);
        const float s = std::sin(-totalRad);
        const float rx = tX * c - tY * s;
        const float ry = tX * s + tY * c;
        tX = rx;
        tY = ry;
    }
    chain.px[0] = tX;
    chain.py[0] = tY;
    chain.debugDirX = dirX;
    chain.debugDirY = dirY;

    const float h2 = h * h;
    const float globalDamp = std::exp(-tuning().damping * h);

    // ---- 3) Advance：阻尼 → 硬钳 → 位置预测 ----
    for (size_t i = 1; i < count; ++i) {
        const Vertex &v = chain.vertices[i];
        const float mobility = v.mobility < 1e-3f ? 1e-3f : (v.mobility > 1.0f ? 1.0f : v.mobility);
        // Mobility 语义：json 里是「每 60Hz 步速度继承多少」，m<1 即耗散。
        // 连续化后的衰减率 = −ln(m)·refFps，所以指数里取 +ln(m)。
        // ⚠️ 写成 −ln(m) 会把 0.95 变成每子步 ×1.026（每秒 ×21.7）：阻尼反转成
        // 放大器，任何输入都指数增长 —— 这正是 XPBD 首版「越摆越大、33% 饱和」的根因。
        const float damp = globalDamp *
                std::exp(std::log(mobility) * tuning().mobilityRefFps * h);
        chain.velX[i] *= damp;
        chain.velY[i] *= damp;

        const float vmax = tuning().speedRodFactor * (v.radius > 1.0f ? v.radius : 1.0f) / h;
        const float speedSq = chain.velX[i] * chain.velX[i] + chain.velY[i] * chain.velY[i];
        if (speedSq > vmax * vmax) {
            const float scale = vmax / std::sqrt(speedSq);
            chain.velX[i] *= scale;
            chain.velY[i] *= scale;
        }
        chain.lastX[i] = chain.px[i];
        chain.lastY[i] = chain.py[i];
        chain.px[i] += chain.velX[i] * h;
        chain.py[i] += chain.velY[i] * h;
    }

    // ---- 4a) 距离约束 + 目标方向约束 ----
    for (size_t i = 1; i < count; ++i) {
        const Vertex &v = chain.vertices[i];
        const float wCur = tuning().invMass;
        const float wPrev = (i >= 2) ? tuning().invMass : 0.0f;   // 根粒子运动学钉死
        const float wSum = wCur + wPrev;

        // 距离约束（柔度 0）：把 |x_i − x_{i−1}| 拉回 radius。
        {
            const float dx = chain.px[i] - chain.px[i - 1];
            const float dy = chain.py[i] - chain.py[i - 1];
            const float len = std::sqrt(dx * dx + dy * dy);
            if (len > 1e-9f) {
                const float diff = (len - v.radius) / (len * wSum);
                const float sx = diff * dx;
                const float sy = diff * dy;
                chain.px[i] -= sx * wCur;
                chain.py[i] -= sy * wCur;
                chain.px[i - 1] += sx * wPrev;
                chain.py[i - 1] += sy * wPrev;
            }
        }

        // 目标方向约束（柔度 α_i = 基值 × Delay / Acceleration）：
        // C = |x_i − T_i|，T_i = x_{i−1} + d·radius；修正按 1/(w_sum + α̃) 缩放。
        {
            const float delay = v.delay < 0.05f ? 0.05f : (v.delay > 20.0f ? 20.0f : v.delay);
            const float accel = v.acceleration < 0.05f ? 0.05f : (v.acceleration > 100.0f ? 100.0f : v.acceleration);
            const float alphaTilde = (tuning().poseCompliance * delay / accel) / h2;
            const float tx = chain.px[i - 1] + dirX * v.radius;
            const float ty = chain.py[i - 1] + dirY * v.radius;
            const float dx = chain.px[i] - tx;
            const float dy = chain.py[i] - ty;
            const float dist = std::sqrt(dx * dx + dy * dy);
            if (dist > 1e-9f) {
                const float s = dist / (wSum + alphaTilde);
                const float nx = dx / dist;
                const float ny = dy / dist;
                chain.px[i] -= s * wCur * nx;
                chain.py[i] -= s * wCur * ny;
                chain.px[i - 1] += s * wPrev * nx;
                chain.py[i - 1] += s * wPrev * ny;
            }
        }
    }

    // ---- 4b) 弯曲约束（单边）：相邻两杆折得太拢时推开隔一个粒子的两端 ----
    for (size_t i = 1; i + 1 < count; ++i) {
        const float wA = (i >= 2) ? tuning().invMass : 0.0f;
        const float wC = tuning().invMass;
        const float wSum = wA + wC;
        if (wSum <= 0.0f) continue;
        const float minLen = tuning().bendRatio * (chain.vertices[i].radius + chain.vertices[i + 1].radius);
        const float dx = chain.px[i + 1] - chain.px[i - 1];
        const float dy = chain.py[i + 1] - chain.py[i - 1];
        const float len = std::sqrt(dx * dx + dy * dy);
        if (len >= minLen || len <= 1e-9f) continue;   // 未违例不激活
        const float alphaTilde = tuning().bendCompliance / h2;
        const float s = (minLen - len) / (wSum + alphaTilde);
        const float nx = dx / len;
        const float ny = dy / len;
        chain.px[i + 1] += s * wC * nx;
        chain.py[i + 1] += s * wC * ny;
        chain.px[i - 1] -= s * wA * nx;
        chain.py[i - 1] -= s * wA * ny;
    }

    // ---- 5) Update：速度回推 + 硬钳（约束修正进入速度 = XPBD 的隐式阻尼）----
    for (size_t i = 1; i < count; ++i) {
        const Vertex &v = chain.vertices[i];
        const float vmax = tuning().speedRodFactor * (v.radius > 1.0f ? v.radius : 1.0f) / h;
        float nvx = (chain.px[i] - chain.lastX[i]) / h;
        float nvy = (chain.py[i] - chain.lastY[i]) / h;
        const float speedSq = nvx * nvx + nvy * nvy;
        if (speedSq > vmax * vmax) {
            const float scale = vmax / std::sqrt(speedSq);
            nvx *= scale;
            nvy *= scale;
        }
        chain.velX[i] = nvx;
        chain.velY[i] = nvy;
    }

    // ---- 6) NaN 防护 ----
    for (size_t i = 0; i < count; ++i) {
        if (std::isfinite(chain.px[i]) && std::isfinite(chain.py[i]) &&
            std::isfinite(chain.velX[i]) && std::isfinite(chain.velY[i])) {
            continue;
        }
        initialize(chain);
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
//  输出
// ---------------------------------------------------------------------------

/**
 * 取一条输出的几何量（未乘 Scale / 未混合）。
 *
 * 几何约定：
 *  - Angle：相邻两杆的夹角；vi==1 时参考方向取 (0,1)；
 *  - X/Y：本杆向量的对应分量。
 * 越界 vertexIndex 返回 false（调用方整条输出跳过）。
 */
inline bool evaluateOutput(const Chain &chain, const Output &output, float *value) {
    const int vi = output.vertexIndex;
    const int count = static_cast<int>(chain.vertices.size());
    if (vi < 1 || vi >= count) return false;

    const float trx = chain.px[vi] - chain.px[vi - 1];
    const float trY = chain.py[vi] - chain.py[vi - 1];
    float v = 0.0f;
    if (output.type == kTypeAngle) {
        float pgx = 0.0f;
        float pgy = 1.0f;
        if (vi >= 2) {
            pgx = chain.px[vi - 1] - chain.px[vi - 2];
            pgy = chain.py[vi - 1] - chain.py[vi - 2];
        }
        v = directionToRadian(pgx, pgy, trx, trY);
        if (output.reflect) v = -v;
    } else if (output.type == kTypeY) {
        v = output.reflect ? -trY : trY;
    } else {
        v = output.reflect ? -trx : trx;
    }
    *value = v;
    return true;
}

}  // namespace xpbd
