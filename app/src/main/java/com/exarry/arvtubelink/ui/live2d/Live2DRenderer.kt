package com.exarry.arvtubelink.ui.live2d

import android.content.res.AssetManager
import android.opengl.GLSurfaceView
import com.exarry.arvtubelink.utils.InAppLog
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "Live2D"

/**
 * Purism Core 原生渲染器的 Kotlin 桥接层（P0 静态渲染）。
 *
 * ## 这是什么
 * `GLSurfaceView.Renderer` 的实现，把三个 GL 回调原样转给 `liblive2d_jni.so`：
 * `onSurfaceCreated` → VBO/IBO 上传、`onSurfaceChanged` → 视口与投影、
 * `onDrawFrame` → glDrawElements。真正的模型加载（.moc3 → Purism Core →
 * 顶点/索引数组）也全在 C++ 侧完成，本类只负责把 [AssetManager] 和路径递过去。
 *
 * ## 线程模型
 * - [init] 由宿主在**主线程**调用一次（也可重建时再调），内部持锁 + 记标记；
 * - GLSurfaceView 用独立 GL 线程回调 [onSurfaceCreated] 等，
 *   首次回调时若模型还没加载过，会在 GL 线程里补一次 [init]。
 *   为什么不能只在主线程 init 后就假设 GL 侧可用：Renderer 可能在 init 之前
 *   就被 setRenderer 触发首帧（组合顺序不保证），补一次幂等。
 * - C++ 侧所有 JNI 入口都在 GL 线程执行（init 例外），不跨线程访问 model。
 *
 * ## 生命周期
 * GLSurfaceView 销毁时不会自动调用 `nativeRelease`（Renderer 接口没有对应回调），
 * 因此由 [release] 显式调用；[Live2DScreen] 在组合离开时触发。
 *
 * ## P0 边界
 * 只画纯色几何面，不加载贴图、不读 model3.json、不驱动参数、不播动作。
 */
class Live2DRenderer(
    private val assetManager: AssetManager,
    mocAssetPath: String? = null,
) : GLSurfaceView.Renderer {

    /**
     * 当前模型的 .moc3 路径；null = 还没有加载任何模型（首次进入且用户未导入）。
     *
     * 两种形态：
     *  - **本地绝对路径**（用户导入的模型，如 `/data/.../live2d_models/1730/xxx.moc3`）；
     *  - **assets 相对路径**（历史内置模型形态，仅保留兼容，当前 APK 不再内置任何模型）。
     *
     * 用是否以 `/` 开头区分，见 [loadModel]。
     */
    @Volatile
    var mocAssetPath: String? = mocAssetPath
        private set

    /** 模型是否已成功加载（false 时 [onDrawFrame] 只清屏）。 */
    @Volatile
    var modelReady: Boolean = false
        private set

    /** 最近一次失败原因（null = 无错误）；供 UI 展示诊断。 */
    @Volatile
    var lastError: String? = null
        private set

    private val initLock = Any()
    private var initAttempted = false

    /**
     * 加载模型。幂等：成功后重复调用直接返回 true。
     *
     * 应用不内置模型，[mocAssetPath] 为 null 表示用户还没有导入——这不是错误，
     * 只返回 false 并保持 `lastError = null`，由 UI 呈现空状态引导导入。
     *
     * @return true = 模型可用
     */
    fun init(): Boolean = synchronized(initLock) {
        if (modelReady) return true
        val path = mocAssetPath
        if (path == null) {
            lastError = null
            return false
        }
        if (!libraryLoaded) {
            // so 未打进 APK / ABI 不匹配：companion init 已失败，不再尝试 native 调用
            lastError = libraryError ?: "liblive2d_jni.so 加载失败"
            return false
        }
        if (initAttempted) {
            // 已经尝试过一次（无论成败）就不重试：每次重试都要重新分配 384KB + 模型内存，
            // 失败通常是确定性的（模型文件缺失/损坏），重试只会拖慢首帧
            return modelReady
        }
        initAttempted = true

        val error = loadNative(path)

        if (error == null) {
            modelReady = true
            lastError = null
            InAppLog.i(TAG, "Purism Core 模型加载成功: $mocAssetPath")
            InAppLog.i(TAG, "native 统计: ${runCatching { nativeStats() }.getOrDefault("n/a")}")
        } else {
            modelReady = false
            lastError = error
            InAppLog.e(TAG, "Purism Core 模型加载失败: $error")
        }
        return modelReady
    }

    /**
     * 调用 native 加载模型（不改变任何状态，只返回错误串）。
     *
     * 路径形态决定入口：以 `/` 开头 = 本地绝对路径 → [nativeLoadModelFromPath]；
     * 否则按 assets 相对路径 → [nativeInit]。
     */
    private fun loadNative(path: String): String? = try {
        if (path.startsWith("/")) {
            nativeLoadModelFromPath(path)
        } else {
            nativeInit(assetManager, path)
        }
    } catch (t: Throwable) {
        "加载模型异常: ${t.javaClass.simpleName}: ${t.message}"
    }

    /**
     * 加载/切换到指定的模型（用户导入的本地文件）。
     *
     * 与 [init] 的区别：这里**允许重复调用**，且会清掉参数绑定缓存与 init 重试
     * 标记 —— 新模型的参数表与旧模型不同，必须重新解析。
     *
     * @param path `.moc3` 路径：以 `/` 开头 = 本地绝对路径，否则按 assets 相对路径处理
     * @return true = 新模型已加载（GL 侧由 native 在下一帧重建）
     */
    fun loadModel(path: String): Boolean = synchronized(initLock) {
        if (!libraryLoaded) {
            lastError = libraryError ?: "liblive2d_jni.so 加载失败"
            return false
        }

        val error = loadNative(path)

        // 无论成败，参数缓存都必须失效：旧下标对新模型没有意义
        parameterIndices.clear()
        parametersBound = false
        initAttempted = true

        if (error == null) {
            mocAssetPath = path
            modelReady = true
            lastError = null
            InAppLog.i(TAG, "模型切换成功: $path")
            InAppLog.i(TAG, "native 统计: ${runCatching { nativeStats() }.getOrDefault("n/a")}")
        } else {
            // 加载失败时旧模型已被 native 侧清空，只能标记不可用
            modelReady = false
            lastError = error
            InAppLog.e(TAG, "模型切换失败: $error ($path)")
        }
        return modelReady
    }

    // ---------------- GLSurfaceView.Renderer ----------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 首帧可能早于宿主调用 init：这里补一次（幂等，失败不重试）
        if (!modelReady && !initAttempted) init()
        nativeSurfaceCreated()
        InAppLog.i(TAG, "onSurfaceCreated: ready=$modelReady")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        nativeSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // 模型没就绪时 C++ 侧只会清屏（g_indices 为空），无需在此短路——
        // 保持每帧一次调用，避免 GLSurfaceView 认为渲染线程空闲
        nativeDrawFrame()
    }

    /** 释放原生资源（GL 线程由 GLSurfaceView 保证，或宿主直接调用）。 */
    fun release() {
        runCatching { nativeRelease() }
        modelReady = false
        initAttempted = false
        lastError = null
        parameterIndices.clear()
        parametersBound = false
    }

    // ---------------- P3：参数驱动 ----------------

    /**
     * 参数名 → Purism Core 参数下标缓存。
     *
     * 值为 -1 表示模型里没有该参数（例如这张模型没有 `ParamMouthForm`）。
     * 缓存一次，推流循环里只做 HashMap 读取，不做 JNI 字符串查找。
     * 全部为不可变的原始类型，跨线程读安全；写入只在 [bindParameters] 里发生。
     */
    private val parameterIndices = HashMap<String, Int>()

    @Volatile
    private var parametersBound = false

    /**
     * 把一批 Cubism 参数名解析成下标并缓存。幂等：绑定成功后再调用直接返回。
     *
     * @param names Cubism 4 参数名（如 `ParamEyeLOpen`）
     * @return true = 至少有一个参数存在于模型中
     */
    fun bindParameters(names: Collection<String>): Boolean {
        if (!modelReady || names.isEmpty()) return false
        if (parametersBound) return parameterIndices.values.any { it >= 0 }

        val array = names.toTypedArray()
        val indices = try {
            nativeResolveParameterIds(array)
        } catch (t: Throwable) {
            InAppLog.e(TAG, "参数下标解析失败: ${t.javaClass.simpleName}: ${t.message}")
            return false
        } ?: return false

        if (indices.size != array.size) {
            InAppLog.w(TAG, "参数下标数量不匹配: 期望 ${array.size} 实得 ${indices.size}")
            return false
        }

        parameterIndices.clear()
        var hit = 0
        for (i in array.indices) {
            parameterIndices[array[i]] = indices[i]
            if (indices[i] >= 0) hit++
        }
        parametersBound = true
        InAppLog.i(TAG, "参数绑定: ${hit}/${array.size} 命中")

        val missing = array.filter { (parameterIndices[it] ?: -1) < 0 }
        if (missing.isNotEmpty()) {
            InAppLog.w(TAG, "模型缺少参数（将忽略）: ${missing.joinToString()}")
        }
        return hit > 0
    }

    /**
     * 推送一帧参数。
     *
     * @param params `[id, value, id, value, ...]` 扁平数组；调用方负责把
     *               `-1`（模型不存在的参数）剔除。
     * @return 实际写入的参数个数（-1 = 模型未就绪）
     *
     * 线程：任意线程可调用。C++ 侧只改 CPU 顶点并置脏标记，真正的 VBO
     * 重传发生在 GL 线程的 onDrawFrame 里（见 live2d_jni.cpp 的 P3 说明）。
     */
    fun updateParams(params: FloatArray): Int {
        if (!modelReady || params.isEmpty()) return -1
        return try {
            nativeUpdateParameters(params)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeUpdateParameters 失败: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }

    /** 已绑定的参数名到下标；未绑定时为空表。 */
    fun parameterIndexMap(): Map<String, Int> = parameterIndices

    // ---------------- P4：动作 / 表情 ----------------

    /** 一个可播放的动作组（model3.json Motions 的一个键）。 */
    data class MotionGroup(val index: Int, val name: String, val motionCount: Int)

    /** 一份表情（model3.json Expressions 的一项）。 */
    data class ExpressionInfo(val index: Int, val name: String)

    /**
     * 读取动作组与表情清单。只在模型就绪后调用；失败返回空表而不是抛异常。
     *
     * native 返回的是逐行文本（`motion\t<下标>\t<名字>\t<数量>` /
     * `expression\t<下标>\t<名字>`）。这里用最朴素的分割解析：清单只有十几行，
     * 引 JSON 解析器不值得。
     */
    fun listActions(): Pair<List<MotionGroup>, List<ExpressionInfo>> {
        if (!modelReady) return emptyList<MotionGroup>() to emptyList()
        val raw = try {
            nativeListActions()
        } catch (t: Throwable) {
            InAppLog.w(TAG, "动作清单读取失败: ${t.javaClass.simpleName}: ${t.message}")
            return emptyList<MotionGroup>() to emptyList()
        } ?: return emptyList<MotionGroup>() to emptyList()

        val motions = mutableListOf<MotionGroup>()
        val expressions = mutableListOf<ExpressionInfo>()
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "motion" -> {
                    val index = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val name = parts.getOrNull(2) ?: continue
                    val count = parts.getOrNull(3)?.toIntOrNull() ?: 0
                    motions += MotionGroup(index, name, count)
                }
                "expression" -> {
                    val index = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val name = parts.getOrNull(2) ?: continue
                    expressions += ExpressionInfo(index, name)
                }
            }
        }
        InAppLog.i(TAG, "动作清单: ${motions.size} 组 / 表情 ${expressions.size} 个")
        return motions to expressions
    }

    /**
     * 播放动作。
     *
     * @param group 动作组下标（[MotionGroup.index]）
     * @param index 组内动作下标；-1 = 该组第 0 个（推荐，避免越界）
     * @return 实际播放的动作下标，负数 = 失败
     */
    fun playMotion(group: Int, index: Int = -1): Int {
        if (!modelReady) return -1
        return try {
            nativePlayMotion(group, index)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativePlayMotion 失败: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }

    /**
     * 设置表情；[index] < 0 取消当前表情。
     *
     * @return 当前生效的表情下标（-1 = 无）
     */
    fun setExpression(index: Int): Int {
        if (!modelReady) return -1
        return try {
            nativeSetExpression(index)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeSetExpression 失败: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }

    /**
     * 读取渲染帧率（native 侧每 0.5s 更新一次）。
     *
     * 读失败或还没统计出窗口时返回 0，调用方据此显示「--」。
     */
    fun fps(): Float {
        if (!modelReady) return 0f
        return try {
            nativeFps()
        } catch (t: Throwable) {
            0f
        }
    }

    // ---------------- P5：手势变换 ----------------

    /**
     * 推送手势变换（拖拽 + 缩放）。
     *
     * @param dx    水平偏移，单位 = 屏幕半宽（±1 = 贴到屏幕左右边缘）
     * @param dy    垂直偏移，单位 = 屏幕半高（向上为正）
     * @param scale 缩放倍数
     *
     * 边界由 C++ 侧统一钳制：缩放 0.5~3.0，平移按**模型包围盒**算
     * （只要还有 80px 可见就允许拖，见 [panLimits]）。这里不做二次判断，
     * 避免两处常量漂移。线程：任意线程可调用。
     */
    fun setTransform(dx: Float, dy: Float, scale: Float) {
        if (!modelReady) return
        try {
            nativeSetTransform(dx, dy, scale)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeSetTransform 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 查询指定缩放下的平移可行区间 `[minX, maxX, minY, maxY]`。
     *
     * 边界值由 native 按包围盒计算（单一事实来源）。模型未就绪或 native
     * 返回异常时给一个保守的对称区间，调用方按普通数值处理即可。
     */
    fun panLimits(scale: Float): FloatArray {
        if (!modelReady) return FALLBACK_PAN_LIMITS.copyOf()
        return try {
            nativePanLimits(scale) ?: FALLBACK_PAN_LIMITS.copyOf()
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativePanLimits 失败: ${t.javaClass.simpleName}: ${t.message}")
            FALLBACK_PAN_LIMITS.copyOf()
        }
    }

    /** 重置手势变换（1x、居中）。 */
    fun resetTransform() {
        if (!modelReady) return
        try {
            nativeResetTransform()
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeResetTransform 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private external fun nativeInit(assetManager: AssetManager, mocPath: String): String?
    private external fun nativeLoadModelFromPath(mocPath: String): String?
    private external fun nativeSurfaceCreated()
    private external fun nativeSurfaceChanged(width: Int, height: Int)
    private external fun nativeDrawFrame()
    private external fun nativeRelease()
    private external fun nativeStats(): String
    private external fun nativeUpdateParameters(params: FloatArray): Int
    private external fun nativeResolveParameterIds(names: Array<String>): IntArray?
    private external fun nativePlayMotion(group: Int, index: Int): Int
    private external fun nativeSetExpression(index: Int): Int
    private external fun nativeListActions(): String?
    private external fun nativeFps(): Float
    private external fun nativeSetTransform(dx: Float, dy: Float, scale: Float)
    private external fun nativeResetTransform()
    private external fun nativePanLimits(scale: Float): FloatArray?

    companion object {
        /** native 不可用时的平移兜底区间（对称、够用即可）。 */
        private val FALLBACK_PAN_LIMITS = floatArrayOf(-2f, 2f, -2f, 2f)

        /**
         * so 是否加载成功。
         *
         * ⚠️ 不能在 init 块里直接 `System.loadLibrary` 然后让异常冒出去：
         * 那样异常发生在**类初始化**阶段，会被包成 ExceptionInInitializerError，
         * 之后任何对该类成员的访问都会抛 NoClassDefFoundError，连错误文案都读不到。
         * 这里吞掉异常并记下原因，由 [init] 转成可展示的字符串。
         */
        private val libraryLoaded: Boolean
        private val libraryError: String?

        init {
            var loaded = false
            var error: String? = null
            try {
                System.loadLibrary("live2d_jni")
                loaded = true
            } catch (t: Throwable) {
                // UnsatisfiedLinkError：so 未打进 APK、ABI 不匹配、或设备不支持的架构
                error = "liblive2d_jni.so 加载失败: ${t.javaClass.simpleName}: ${t.message}"
            }
            libraryLoaded = loaded
            libraryError = error
        }
    }
}
