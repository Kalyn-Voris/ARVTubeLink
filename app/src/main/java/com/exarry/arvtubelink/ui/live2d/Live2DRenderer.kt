package com.exarry.arvtubelink.ui.live2d

import android.opengl.GLSurfaceView
import com.exarry.arvtubelink.utils.InAppLog
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "Live2D"

/** Purism Core 原生渲染器桥接：三个 GL 回调转 JNI，模型加载在 C++ 侧 */
class Live2DRenderer(
    mocPath: String? = null,
) : GLSurfaceView.Renderer {

    /** 当前模型 .moc3 绝对路径，null = 未加载任何模型 */
    @Volatile
    var mocPath: String? = mocPath
        private set

    @Volatile
    var modelReady: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val initLock = Any()
    private var initAttempted = false

    /** 加载模型，幂等；mocPath 为 null 返回 false 且 lastError 保持 null */
    fun init(): Boolean = synchronized(initLock) {
        if (modelReady) return true
        val path = mocPath
        if (path == null) {
            lastError = null
            return false
        }
        if (!libraryLoaded) {
            lastError = libraryError ?: "liblive2d_jni.so 加载失败"
            return false
        }
        if (initAttempted) {
            return modelReady
        }
        initAttempted = true

        val error = loadNative(path)

        if (error == null) {
            modelReady = true
            lastError = null
            InAppLog.i(TAG, "Purism Core 模型加载成功: $mocPath")
            InAppLog.i(TAG, "native 统计: ${runCatching { nativeStats() }.getOrDefault("n/a")}")
        } else {
            modelReady = false
            lastError = error
            InAppLog.e(TAG, "Purism Core 模型加载失败: $error")
        }
        return modelReady
    }

    private fun loadNative(path: String): String? = try {
        nativeLoadModelFromPath(path)
    } catch (t: Throwable) {
        "加载模型异常: ${t.javaClass.simpleName}: ${t.message}"
    }

    /** 切换模型，允许重复调用；参数下标缓存必须失效 */
    fun loadModel(path: String): Boolean = synchronized(initLock) {
        if (!libraryLoaded) {
            lastError = libraryError ?: "liblive2d_jni.so 加载失败"
            return false
        }

        val error = loadNative(path)

        parameterIndices.clear()
        parametersBound = false
        initAttempted = true

        if (error == null) {
            mocPath = path
            modelReady = true
            lastError = null
            InAppLog.i(TAG, "模型切换成功: $path")
            InAppLog.i(TAG, "native 统计: ${runCatching { nativeStats() }.getOrDefault("n/a")}")
        } else {
            modelReady = false
            lastError = error
            InAppLog.e(TAG, "模型切换失败: $error ($path)")
        }
        return modelReady
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 首帧可能早于宿主 init，GL 线程补一次幂等加载
        if (!modelReady && !initAttempted) init()
        nativeSurfaceCreated()
        InAppLog.i(TAG, "onSurfaceCreated: ready=$modelReady")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        nativeSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // 未就绪时 C++ 只清屏，仍保持每帧一次调用
        nativeDrawFrame()
    }

    /** GLSurfaceView 销毁不回调 nativeRelease，须由宿主显式调用 */
    fun release() {
        runCatching { nativeRelease() }
        modelReady = false
        initAttempted = false
        lastError = null
        parameterIndices.clear()
        parametersBound = false
    }

    /** 参数名 → 下标，-1 = 模型无此参数；原始类型只读，跨线程安全 */
    private val parameterIndices = HashMap<String, Int>()

    @Volatile
    private var parametersBound = false

    /** 解析参数名到下标并缓存，幂等 */
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

    /** 任意线程可调用，VBO 重传在 GL 线程的 onDrawFrame 内完成 */
    fun updateParams(params: FloatArray): Int {
        if (!modelReady || params.isEmpty()) return -1
        // ±Infinity 降级为 NaN（本帧无输入）；NaN 是有意义哨兵须原样保留，只查 value 位
        var i = 1
        while (i < params.size) {
            val v = params[i]
            if (!v.isFinite() && !v.isNaN()) params[i] = Float.NaN
            i += 2
        }
        return try {
            nativeUpdateParameters(params)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeUpdateParameters 失败: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }

    fun parameterIndexMap(): Map<String, Int> = parameterIndices

    /** model3.json Motions 的一个键 */
    data class MotionGroup(val index: Int, val name: String, val motionCount: Int)

    /** model3.json Expressions 的一项 */
    data class ExpressionInfo(val index: Int, val name: String)

    /** native 逐行返回 motion\t下标\t名字\t数量 / expression\t下标\t名字 */
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

    /** index -1 = 该组第 0 个，避免越界 */
    fun playMotion(group: Int, index: Int = -1): Int {
        if (!modelReady) return -1
        return try {
            nativePlayMotion(group, index)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativePlayMotion 失败: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }

    /** index < 0 取消当前表情 */
    fun setExpression(index: Int): Int {
        if (!modelReady) return -1
        return try {
            nativeSetExpression(index)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeSetExpression 失败: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }

    /** native 每 0.5s 更新一次，未统计出窗口返回 0 */
    fun fps(): Float {
        if (!modelReady) return 0f
        return try {
            nativeFps()
        } catch (t: Throwable) {
            0f
        }
    }

    /** 关闭后 ParamHair* / ParamScarf 每帧写回模型默认值，状态由 native 持有 */
    fun setPhysicsEnabled(enabled: Boolean) {
        if (!modelReady) return
        try {
            nativeSetPhysicsEnabled(enabled)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeSetPhysicsEnabled 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun isPhysicsEnabled(): Boolean {
        if (!modelReady) return false
        return try {
            nativeIsPhysicsEnabled()
        } catch (t: Throwable) {
            false
        }
    }

    /** 缺 pose3.json 时按同名 A/B 后缀推断互斥组，只保留一套 */
    fun setPoseFallback(enabled: Boolean) {
        if (!modelReady) return
        try {
            nativeSetPoseFallback(enabled)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeSetPoseFallback 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 旁路输入增益/低通与输出死区/低通，按标准语义求解 */
    fun setPhysicsReferenceMode(enabled: Boolean) {
        if (!modelReady) return
        try {
            nativeSetPhysicsReferenceMode(enabled)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeSetPhysicsReferenceMode 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 每帧输出一行 trace: frame time dt ParamHairFront */
    fun setPhysicsTrace(enabled: Boolean) {
        if (!modelReady) return
        try {
            nativeSetPhysicsTrace(enabled)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeSetPhysicsTrace 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 按渲染帧采样的滑动窗口，读完即清零 */
    fun physicsJitter(): String? {
        if (!modelReady) return null
        return try {
            nativePhysicsJitter()
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativePhysicsJitter 失败: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 每帧 1Hz 正弦写 ParamAngleX/Y/Z，0.5Hz 半波写 ParamMouthOpenY */
    fun setSyntheticFace(enabled: Boolean) {
        if (!modelReady) return
        try {
            nativeSetSyntheticFace(enabled)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeSetSyntheticFace 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** dx/dy 单位 = 屏幕半宽/半高，向上为正；缩放 0.5~3.0 与 80px 边界由 C++ 钳制 */
    fun setTransform(dx: Float, dy: Float, scale: Float) {
        if (!modelReady) return
        try {
            nativeSetTransform(dx, dy, scale)
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeSetTransform 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 返回 [minX, maxX, minY, maxY]，边界按模型包围盒算 */
    fun panLimits(scale: Float): FloatArray {
        if (!modelReady) return FALLBACK_PAN_LIMITS.copyOf()
        return try {
            nativePanLimits(scale) ?: FALLBACK_PAN_LIMITS.copyOf()
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativePanLimits 失败: ${t.javaClass.simpleName}: ${t.message}")
            FALLBACK_PAN_LIMITS.copyOf()
        }
    }

    fun resetTransform() {
        if (!modelReady) return
        try {
            nativeResetTransform()
        } catch (t: Throwable) {
            InAppLog.w(TAG, "nativeResetTransform 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

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
    private external fun nativeSetPhysicsEnabled(enabled: Boolean)
    private external fun nativeSetPoseFallback(enabled: Boolean)
    private external fun nativeSetPhysicsTrace(enabled: Boolean)
    private external fun nativeSetPhysicsReferenceMode(enabled: Boolean)
    private external fun nativeIsPhysicsEnabled(): Boolean
    private external fun nativePhysicsJitter(): String?
    private external fun nativeSetSyntheticFace(enabled: Boolean)
    private external fun nativeSetTransform(dx: Float, dy: Float, scale: Float)
    private external fun nativeResetTransform()
    private external fun nativePanLimits(scale: Float): FloatArray?

    companion object {
        private val FALLBACK_PAN_LIMITS = floatArrayOf(-2f, 2f, -2f, 2f)

        /** 不让 loadLibrary 异常在类初始化阶段外抛，否则后续访问全是 NoClassDefFoundError */
        private val libraryLoaded: Boolean
        private val libraryError: String?

        init {
            var loaded = false
            var error: String? = null
            try {
                System.loadLibrary("live2d_jni")
                loaded = true
            } catch (t: Throwable) {
                error = "liblive2d_jni.so 加载失败: ${t.javaClass.simpleName}: ${t.message}"
            }
            libraryLoaded = loaded
            libraryError = error
        }
    }
}
