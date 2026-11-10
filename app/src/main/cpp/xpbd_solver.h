// 头发 / 裙摆 XPBD 求解器，header-only，不依赖 Android

#pragma once

#include <cmath>
#include <cstddef>
#include <limits>
#include <string>
#include <vector>

namespace xpbd {

// json Input.Type：X / Y 为位移型，Angle 为角度型
enum InputType { kTypeX = 0, kTypeY = 1, kTypeAngle = 2 };

struct Input {
    int paramIndex = -1;
    float weight = 1.0f;   // 已除以 100
    int type = kTypeX;
    bool reflect = false;
};

struct Output {
    int paramIndex = -1;
    int vertexIndex = 0;
    float scale = 1.0f;
    float weight = 1.0f;   
    int type = kTypeX;
    bool reflect = false;
};

struct Vertex {
    float restX = 0.0f;        // json 原始 Position（求解器不用；Radius 兜底计算用）
    float restY = 0.0f;
    float mobility = 1.0f;     // 速度继承比例
    float delay = 1.0f;        // 惯性延迟（越大越拖）→ 约束柔度
    float acceleration = 1.0f; // 跟随强度（越大越紧）→ 约束柔度
    float radius = 0.0f;       // 杆长（json Radius；无效时由解析侧退回 Position 差）
};

struct Chain {
    std::string id;
    std::vector<Input> inputs;
    std::vector<Output> outputs;
    std::vector<Vertex> vertices;

    // 位移型量程 posMin/posDef/posMax，角度型量程 angMin/angDef/angMax
    float posMin = -10.0f, posDef = 0.0f, posMax = 10.0f;
    float angMin = -10.0f, angDef = 0.0f, angMax = 10.0f;
    float gravityX = 0.0f, gravityY = -1.0f;

    std::vector<float> px, py;        // 位置（粒子空间 = Normalization 数值空间）
    std::vector<float> lastX, lastY;  
    std::vector<float> velX, velY;    
    std::vector<float> curOut;        
    std::vector<float> prevOut;       
    bool initialized = false;

    float debugDirX = 0.0f, debugDirY = 1.0f;

    // 上一子步的平滑后输入汇总（NaN = 尚未跑过，强制第一步求解）
    float prevInputX = std::numeric_limits<float>::quiet_NaN();
    float prevInputY = std::numeric_limits<float>::quiet_NaN();
    float prevInputAngle = std::numeric_limits<float>::quiet_NaN();

    // 死区状态：committed 贴着 raw ± dz，带内不动、带外同斜率跟随
    float deadCommittedX = 0.0f, deadCommittedY = 0.0f, deadCommittedAngle = 0.0f;
    bool deadPrimed = false;

    float filtX = 0.0f, filtY = 0.0f, filtAngle = 0.0f;
    bool filtPrimed = false;
    int restSteps = 0;
    bool skipped = false;
};

struct ParamTable {
    int count = 0;
    const float *min = nullptr;
    const float *max = nullptr;
    const float *def = nullptr;
};

struct Tuning {
    // 固定子步长：与渲染帧率、json Meta.Fps 双解耦
    float substepDt = 1.0f / 60.0f;

    int maxSubstepsPerFrame = 8;

    // 静止判定阈值：链内最大粒子速率平方（归一化空间/s）
    float restSpeedSq = 1.0e-6f;

    // 目标方向约束柔度基值（XPBD α = 1/刚度），逐节取 基值 × Delay / Acceleration
    float poseCompliance = 0.003f;

    float bendCompliance = 0.002f;

    // 弯曲约束下限：|x_{i+1} − x_{i−1}| ≥ ratio × (r_i + r_{i+1})，0.30 ≈ 允许折到 145°
    float bendRatio = 0.30f;

    // 全局速度阻尼（1/s）：v *= exp(−damping·h)
    float damping = 1.2f;

    // mobility 的参考频率（json 里是每 60Hz 步的速度继承比例）
    float mobilityRefFps = 60.0f;

    // 速度硬钳因子：每子步最多位移 50 × max(radius,1)
    float speedRodFactor = 50.0f;

    // 位移型输入（Type=X/Y）的根粒子平移增益，1.0 = 忠于 json 语义
    float translationGain = 1.0f;

    // 动态粒子逆质量（json 无质量字段，统一 1）；根粒子逆质量 0 = 运动学钉死
    float invMass = 1.0f;

    // 输入死区（归一化空间单位，0 = 关闭；0.05 ≈ 满量程 ±10 的 0.5%）
    float inputDeadzone = 0.05f;

    float inputSmoothHz = 12.0f;

    // 低通收敛吸附阈值：残差小于它就与目标完全贴合，静止判据才可能成立
    float inputSnapEps = 1.0e-4f;
};

inline Tuning &tuning() {
    static Tuning t;
    return t;
}

// 源参数值 → Normalization 区间的分段线性映射：量程先排序，折算中心取源量程中点（不是 json Default）
inline float normalizeParameterValue(float value, float paramMin, float paramMax,
                                     float normMin, float normMax, float normDef,
                                     bool inverted) {
    const float srcHigh = paramMax > paramMin ? paramMax : paramMin;
    const float srcLow = paramMax > paramMin ? paramMin : paramMax;
    const float normHigh = normMin < normMax ? normMax : normMin;
    const float normLow = normMin < normMax ? normMin : normMax;

    if (value > srcHigh) value = srcHigh;
    if (value < srcLow) value = srcLow;

    const float srcMid = srcLow + (srcHigh - srcLow) * 0.5f;
    const float offset = value - srcMid;
    if (offset == 0.0f) return inverted ? normDef : -normDef;

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

inline float directionToRadian(float fromX, float fromY, float toX, float toY) {
    constexpr float kPi = 3.14159265358979f;
    constexpr float kTwoPi = 6.28318530717959f;
    float delta = std::fmod(std::atan2(toY, toX) - std::atan2(fromY, fromX), kTwoPi);
    if (delta > kPi) {
        delta -= kTwoPi;
    } else if (delta < -kPi) {
        delta += kTwoPi;
    }
    return delta;
}

// 复位到竖直静息姿态：根粒子 (0,0)，其后每节在父节点上方 +Radius
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

// 输入端平滑：先死区后低通，返回可喂给求解器的输入
inline void smoothInput(Chain &chain, float h, float rawX, float rawY,
                        float rawAngle, float *outX, float *outY,
                        float *outAngle) {
    const Tuning &t = tuning();
    const float dz = t.inputDeadzone;

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

// 链是否收敛到静止：只看速度，阈值 1e-6 对应 1e-3 归一化单位/秒（杆长量级 3~10）
inline bool isResting(const Chain &chain) {
    const float limit = tuning().restSpeedSq;
    for (size_t i = 1; i < chain.velX.size(); ++i) {
        const float sq = chain.velX[i] * chain.velX[i] + chain.velY[i] * chain.velY[i];
        if (sq > limit) return false;
    }
    return true;
}

// 推进一个 XPBD 子步：输入汇总 → Advance → 单次 Gauss-Seidel → 速度回推；返回 false = 非有限值已复位
inline bool step(Chain &chain, const ParamTable &params, const float *caches,
                 float h) {
    const size_t count = chain.vertices.size();
    if (count < 2 || chain.px.size() != count) return true;

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

    float totalX;
    float totalY;
    float totalAngle;
    smoothInput(chain, h, rawX, rawY, rawAngle, &totalX, &totalY, &totalAngle);

    if (totalX == chain.prevInputX && totalY == chain.prevInputY &&
        totalAngle == chain.prevInputAngle && isResting(chain)) {
        ++chain.restSteps;
        chain.skipped = true;
        return true;   
    }
    chain.prevInputX = totalX;
    chain.prevInputY = totalY;
    chain.prevInputAngle = totalAngle;
    chain.restSteps = 0;
    chain.skipped = false;

    // 根粒子运动学钉死；角度映射为目标方向 d（输入角 0 → (0,1)，与静息竖直链平行）
    const float deg2rad = 0.017453292519943295f;
    const float totalRad = totalAngle * deg2rad;
    float dirX = std::sin(totalRad);
    float dirY = std::cos(totalRad);

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

    // Advance：阻尼 → 硬钳 → 位置预测
    for (size_t i = 1; i < count; ++i) {
        const Vertex &v = chain.vertices[i];
        const float mobility = v.mobility < 1e-3f ? 1e-3f : (v.mobility > 1.0f ? 1.0f : v.mobility);
        // mobility 是每 60Hz 步的速度继承比例，衰减率 = −ln(m)·refFps，指数里取 +ln(m)
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

    // 距离约束（柔度 0）与目标方向约束，自根向尾一次 Gauss-Seidel
    for (size_t i = 1; i < count; ++i) {
        const Vertex &v = chain.vertices[i];
        const float wCur = tuning().invMass;
        const float wPrev = (i >= 2) ? tuning().invMass : 0.0f;   // 根粒子运动学钉死
        const float wSum = wCur + wPrev;

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

        // 目标方向约束：α̃ = α/h²，α = 基值 × Delay / Acceleration；目标 T_i = x_{i−1} + d·radius
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

    // 弯曲约束（单边）：相邻两杆折得太拢时推开隔一个粒子的两端
    for (size_t i = 1; i + 1 < count; ++i) {
        const float wA = (i >= 2) ? tuning().invMass : 0.0f;
        const float wC = tuning().invMass;
        const float wSum = wA + wC;
        if (wSum <= 0.0f) continue;
        const float minLen = tuning().bendRatio * (chain.vertices[i].radius + chain.vertices[i + 1].radius);
        const float dx = chain.px[i + 1] - chain.px[i - 1];
        const float dy = chain.py[i + 1] - chain.py[i - 1];
        const float len = std::sqrt(dx * dx + dy * dy);
        if (len >= minLen || len <= 1e-9f) continue;   
        const float alphaTilde = tuning().bendCompliance / h2;
        const float s = (minLen - len) / (wSum + alphaTilde);
        const float nx = dx / len;
        const float ny = dy / len;
        chain.px[i + 1] += s * wC * nx;
        chain.py[i + 1] += s * wC * ny;
        chain.px[i - 1] -= s * wA * nx;
        chain.py[i - 1] -= s * wA * ny;
    }

    // Update：v ← (x − x_prev)/h，约束修正进入速度即 XPBD 隐式阻尼
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

    // 非有限值整链复位
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

// 取一条输出的几何量（未乘 Scale / 未混合）；Angle 为相邻两杆夹角（vi==1 参考 (0,1)），X/Y 为本杆分量
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

}  
