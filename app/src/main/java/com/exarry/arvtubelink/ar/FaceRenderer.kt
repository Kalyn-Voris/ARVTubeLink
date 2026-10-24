package com.exarry.arvtubelink.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.exarry.arvtubelink.mapping.BlendShapeMapper
import com.exarry.arvtubelink.utils.InAppLog
import com.exarry.arvtubelink.mapping.Smoother
import com.exarry.arvtubelink.network.VtsWsClient
import com.huawei.hiar.ARFace
import com.huawei.hiar.ARFaceGeometry
import com.huawei.hiar.ARFrame
import com.huawei.hiar.ARTrackable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 驱动 AR Engine 帧循环的 GL 渲染器，同时是数据管线入口：
 *
 *   AR 原始系数(66项) + ARFace头姿(平移/四元数) -> [BlendShapeMapper]映射
 *   -> [Smoother]EMA平滑 -> 频率节流（默认 30Hz，设置页「推流频率」可配置）
 *   -> [VtsWsClient] WebSocket 发送（IO 协程，绝不阻塞 GL 线程）
 *
 * 线程模型：
 *  - onDrawFrame 在 GL 渲染线程执行（约 30~60 次/秒），只做纯内存计算；
 *  - 网络发送丢给独立 IO 协程池；OkHttp 的 WebSocket.send 本身也是非阻塞入队，
 *    双保险保证 onDrawFrame 不因网络延迟卡顿；
 *  - [sendPending] 原子标志防积压：上一帧尚未出队则丢弃本帧（等下一个节流窗口），
 *    避免 WebSocket 发送队列无限增长导致内存溢出。
 *
 * 校准网格可视化（[setCalibrationMode]）：
 *  - UI 线程只写 @Volatile 标志；shader program / VBO 等全部 GL 资源由 GL 线程
 *    在首次绘制时懒创建，UI 层绝不直接触碰 OpenGL；
 *  - CPU 投影方案（参考 droid-girl/arfaces_labs：数据逻辑与渲染逻辑分离）：
 *    ARFace 网格顶点在 CPU 端走「局部 -> world(face.getPose) -> 手动针孔投影 -> NDC」
 *    得到 2D 屏幕坐标（x 轴按 aspect 放大），GL 侧只用同一个正交矩阵画 2D 图元——
 *    不做任何 GPU 端 3D 变换，也不渲染摄像头画面；
 *  - 深色底上绘制：ARFace 网格线框（三角索引展开成线段，GLES20 GL_LINES，绿色）、
 *    屏幕中心十字准星 + 对准框（正交投影，严格居中于 (0,0)，无任何 y 轴偏移）、
 *    面部质心标记点（GL_POINTS，与十字重合=对准）；
 *  - 降级链：shader 编译 / 几何读取 / 索引展开任一环节失败 -> 置降级标志，
 *    后续帧只画十字准星（甚至无 GL 反馈），绘制整体 try-catch，绝不影响主追踪与发送管线。
 *
 * @param displayRotation 提供 Activity 当前屏幕旋转（Surface.ROTATION_*）。
 * @param mapper          AR 系数 -> VTS 参数映射器（纯函数，线程安全）。
 * @param smoother        1€ 滤波平滑器（内部 ConcurrentHashMap，线程安全）。
 * @param vtsClient       VTS WebSocket 客户端（未认证时 injectParameters 自动静默丢弃）。
 * @param onFacePresenceChanged 校准模式下人脸检测结果变化回调：GL 线程触发但内部
 *        已 post 到主线程，UI 可直接更新视图；非校准模式不回调。重新进入校准模式
 *        时重置人脸去重状态，首帧必回调当前状态。
 */
class FaceRenderer(
    private val manager: ArSessionManager,
    private val displayRotation: () -> Int,
    private val mapper: BlendShapeMapper,
    private val smoother: Smoother,
    private val vtsClient: VtsWsClient,
    private val onFacePresenceChanged: (Boolean) -> Unit = {}
) : GLSurfaceView.Renderer {

    private var frameCount = 0L

    /** 上一次网络发送的时间戳（毫秒），节流基准。 */
    @Volatile
    private var lastSendTime = 0L

    /** 防积压标志：true 表示上一帧数据仍在发送中，本帧丢弃。 */
    private val sendPending = AtomicBoolean(false)

    /** 上一有效帧是否检测到人脸（仅 GL 线程读写）：用于只在「有->无」瞬间补发一次空帧。 */
    private var wasFaceFound = false

    /** 网络发送专用协程池；[release] 时取消。绝不复用 GL 线程做 IO。 */
    private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- 校准模式 / 调试日志开关（UI 线程写、GL 线程读） ----------

    /** 校准网格可视化开关：true 时额外绘制网格/准星（数据管线完全不受影响）。 */
    @Volatile
    private var calibrationMode = false

    /**
     * 详细调试日志开关。由 MainActivity 依据 SharedPreferences（Key: enable_debug_logs）
     * 写入——渲染器自己绝不读 SharedPreferences。开启后：校准模式输出网格诊断、
     * 追踪模式输出逐帧头姿原始值（均进内嵌日志面板）。
     */
    @Volatile
    private var debugLogsEnabled = false

    /**
     * 节流间隔（毫秒）。由 UI 线程经 [setSendFrequencyHz] 写入（换算与粒度说明见该方法），
     * 渲染器不读 SharedPreferences；初始值 = 默认档 30Hz。
     */
    @Volatile
    private var sendIntervalMs = 1000L / 30

    // ---------- 校准可视化 GL 状态（句柄仅 GL 线程懒创建/使用） ----------

    private var meshProgram = 0
    private var meshPositionLoc = -1
    private var meshMvpLoc = -1
    private var meshColorLoc = -1
    // 径向淡出 uniform（片元着色器）：屏幕中心像素坐标 + 起始/结束淡出半径（像素）
    private var meshCenterLoc = -1
    private var meshFadeRLoc = -1
    private var meshFadeEndLoc = -1
    private var meshVertexVbo = 0
    private var meshLineIndexVbo = 0
    private var meshLineIndexCount = 0
    private var meshPointVbo = 0
    private var crossVbo = 0

    /** 网格拓扑缓存的顶点数：AR 网格拓扑固定，顶点数不变则不重建线段索引。 */
    private var meshTopologyVertexCount = -1

    /** 降级标志：网格链路失败后本（校准）会话只画十字准星；重新进入校准模式时重试。 */
    private var meshDrawFailed = false

    /** 上一帧的校准开关（仅 GL 线程）：检测沿变以重置降级与回调去重状态。 */
    private var prevCalibrationActive = false

    /** 上一次回调 UI 的人脸存在状态（null=待首次回调），仅变化时 post。 */
    private var lastFacePresence: Boolean? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var surfaceWidth = 1
    private var surfaceHeight = 1

    // GL 线程复用的矩阵（避免逐帧分配）。
    // 2D 方案下全场景共用同一个正交矩阵 [orthoMatrix]：网格/质心坐标已在 CPU 端
    // 投影到该正交空间，GL 侧无 model/view/proj 矩阵。
    private val orthoMatrix = FloatArray(16)

    /**
     * 单顶点 CPU 投影中间量（GL 线程复用）：局部齐次坐标 [x,y,z,1]。
     * 投影链：局部 -> world(face.getPose) -> 手动针孔投影 -> NDC。
     */
    private val vertexH = FloatArray(4)

    /** 投影后的 2D 顶点数组（GL 线程复用，容量不足时扩容）。 */
    private var projected2d = FloatArray(0)

    /** 顶点中转 direct buffer（复用，容量不足时扩容重建）。 */
    private var scratchVertices: FloatBuffer? = null

    /**
     * 进入/退出校准网格可视化（UI 线程调用）。只翻转 @Volatile 标志：
     * GL 资源由 GL 线程懒创建，退出不清除（下次进入直接复用），无跨线程 GL 调用。
     */
    fun setCalibrationMode(enabled: Boolean) {
        calibrationMode = enabled
    }

    /** 详细调试日志开关（UI 线程调用，来源 SharedPreferences enable_debug_logs）。 */
    fun setDebugLogsEnabled(enabled: Boolean) {
        debugLogsEnabled = enabled
    }

    /**
     * 推流频率（UI 线程调用，来源 SharedPreferences send_frequency_hz）。
     * 档位 10/20/30/40/50/60 Hz，内部换算为整除毫秒间隔 1000/HZ
     * （100/50/33/25/20/16 ms）；档位外的值钳到边界兜底。
     * 60Hz 档取 16ms 是毫秒粒度取整，实际约 62Hz；发送率另有渲染帧率封顶。
     */
    fun setSendFrequencyHz(hz: Int) {
        sendIntervalMs = 1000L / hz.coerceIn(10, 60)
    }

    /**
     * 推流暂停门控（参数自适应探测期使用）：true 时 [dispatchSend] 直接丢弃全部待发帧
     * ——正常表情帧与追踪丢失空帧都不出网，模型状态由适应编排的探针注入独占
     * （探针经 VtsWsClient.injectParameters 直发，不经过本渲染器管线）。
     * 门控位于节流/防积压之前：sendIntervalMs 与 sendPending 均不受影响（恢复后按
     * 原频率继续）；映射与平滑照常逐帧执行（平滑器持续消费真实帧，恢复推流无状态
     * 断层，无需 reset）。UI 线程写、GL 线程读（@Volatile）。
     */
    @Volatile
    private var streamingPaused = false

    /** 推流暂停开关（UI 线程调用，来源自适应编排；true 期间正常推流全部停发）。 */
    fun setStreamingPaused(paused: Boolean) {
        streamingPaused = paused
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        // 开启 Alpha 混合：校准可视化全靠 uColor 的 alpha 表达视觉层级，而 GL 默认
        // 关闭混合——alpha 只写进 uniform、不参与混合时所有线都是全不透明。
        // 混合状态是全局 GL 状态，一次设置即可；EGL context 重建时会重新进入本方法。
        // glClear 不受混合影响，背景色仍按 CALIB_BG 原样铺底。
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // EGL context 若重建，旧 GL 句柄全部失效：此处只清零（旧 context 已销毁，glDelete 无意义），
        // 校准模式下后续帧会按需懒重建
        meshProgram = 0
        meshVertexVbo = 0
        meshLineIndexVbo = 0
        meshPointVbo = 0
        crossVbo = 0
        meshLineIndexCount = 0
        meshTopologyVertexCount = -1
        meshPositionLoc = -1
        meshMvpLoc = -1
        meshColorLoc = -1
        meshCenterLoc = -1
        meshFadeRLoc = -1
        meshFadeEndLoc = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        // 校准模式下，GL 内容整体上移 25% 高度，让引导框落在可视区中心
        // （底部 Dialog 面板约占屏高 1/4，遮挡了原始中心位置）
        // 若 25% 仍偏下/偏上，直接手动调整该比例即可
        val yOffset = if (calibrationMode) (height * 0.25f).toInt() else 0
        GLES20.glViewport(0, yOffset, width, height - yOffset)
        manager.setDisplayGeometry(displayRotation(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val calibActive = calibrationMode
        if (calibActive) {
            GLES20.glClearColor(CALIB_BG_R, CALIB_BG_G, CALIB_BG_B, 1f)
        } else {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = manager.getSession() ?: return

        // update() 推进一帧；相机不可用/会话异常时跳过本帧，不让渲染线程崩溃
        val frame = try {
            session.update()
        } catch (e: Exception) {
            InAppLog.e(TAG, "session.update() failed", e)
            return
        }
        frameCount++

        // 校准模式沿变（GL 线程侧）：（重新）进入时重试网格链路、重置 UI 回调去重状态
        if (calibActive != prevCalibrationActive) {
            prevCalibrationActive = calibActive
            meshDrawFailed = false
            meshTopologyVertexCount = -1 // 强制下次重试网格拓扑（上次会话可能降级过）
            lastFacePresence = null
        }

        // ARFrame 只有 getUpdatedTrackables（仅含变化项），取全量用 session.getAllTrackables
        val faces = try {
            session.getAllTrackables(ARFace::class.java)
        } catch (e: Exception) {
            InAppLog.e(TAG, "getAllTrackables failed", e)
            return
        }
        val face = faces.firstOrNull { it.trackingState == ARTrackable.TrackingState.TRACKING }
        val blendShapes = if (face == null) {
            null
        } else {
            try {
                face.getFaceBlendShapes()
            } catch (e: Exception) {
                InAppLog.w(TAG, "getFaceBlendShapes failed: ${e.message}")
                null
            }
        }

        // ---------- 数据管线 ----------
        if (blendShapes == null) {
            // 校准可视化不依赖人脸：无脸时也画十字并回调 UI「未检测到」
            if (calibActive) {
                drawCalibrationVisualization(frame, null)
            }
            // 未追踪到人脸：保留每 10 帧的心跳日志
            if (frameCount % LOG_INTERVAL == 0L) {
                InAppLog.i(TAG, "帧 $frameCount：未检测到追踪中的人脸 / BlendShapes 为空")
            }
            // 追踪丢失：仅在「有->无」的瞬间补发一次空参数（faceFound=false），
            // 让 VTS 松开注入、参数回归模型默认值；持续丢失期间不重复发送
            if (wasFaceFound) {
                wasFaceFound = false
                dispatchSend(emptyMap())
            }
            return
        }
        wasFaceFound = true

        // 0) 原始 66 项系数 -> Map<String, Float>
        val blendShapeMap: Map<String, Float> = blendShapes.getBlendShapeDataMapKeyString()
        // HashMap 是数据拷贝，取完立即释放 native 句柄，防止逐帧泄漏
        blendShapes.release()

        // 0.5) 头部姿态：平移 + 旋转四元数（读取失败不致命，仅本帧缺头姿参数）。
        // face 为可空：走到这里时 blendShapes 非空必然意味着 face 非空，
        // 但编译器无法跨变量推断该关联，用 ?.let 显式传递
        val headPose = face?.let { readHeadPose(it) }

        // 1) 映射：AR 系数名 -> VTS 参数名（含眨眼反转、缺失补 0）
        val vtsParams = mapper.map(blendShapeMap)

        // 1.5) 头姿映射（FaceAngleX/Y/Z + FacePositionX/Y/Z）合并进同一帧参数后再统一平滑
        val mergedParams = if (headPose == null) {
            vtsParams.parameters
        } else {
            vtsParams.parameters + mapper.mapHeadPose(headPose.translation, headPose.quaternion)
        }

        // 2) 平滑：按参数名做 1€ 滤波，抑制抖动（返回新 Map，无共享可变状态）
        val smoothed = smoother.smoothAll(mergedParams)

        // 原始系数/头姿 Logcat 日志：仅「开启详细日志」时输出（体积大且高频，不进内嵌面板）
        if (debugLogsEnabled && frameCount % LOG_INTERVAL == 0L) {
            Log.i(TAG, "帧数据($frameCount): $blendShapeMap")
            headPose?.let {
                Log.i(TAG, "头姿: T=${it.translation.contentToString()}, Q=${it.quaternion.contentToString()}")
            }
        }

        // 内嵌日志：每 60 帧打印一次实际待发送的 VTS 参数 map（注入前的最终值；
        // 60 帧的时长随推流频率变化）
        if (frameCount % INAPP_LOG_INTERVAL == 0L) {
            // 「开启详细日志」时才输出（logcat 与内嵌面板同时受控）
            if (debugLogsEnabled) {
                InAppLog.i(TAG, "帧 $frameCount：发送参数 $smoothed")
            }
            // 「开启详细日志」时：头姿原始值进内嵌面板（默认只进 Logcat，防刷屏）
            if (debugLogsEnabled && headPose != null) {
                InAppLog.i(
                    TAG,
                    "帧 $frameCount 头姿原始：T=${headPose.translation.contentToString()}," +
                        " Q=${headPose.quaternion.contentToString()}"
                )
            }
        }

        // 校准可视化放发送之前：绘制只降级不抛出，任何异常都不影响数据管线出口
        if (calibActive) {
            drawCalibrationVisualization(frame, face)
        }

        // 3)+4)+5) 节流 + 防积压 + IO 协程异步发送
        dispatchSend(smoothed)
    }

    // ===================== 校准网格可视化（全部仅 GL 线程执行） =====================

    /**
     * 校准可视化一帧的统一入口：人脸状态回调 + 十字/对准框 + 网格线框 + 质心点。
     * 整体 try-catch：任何绘制异常只置降级标志（后续帧只剩十字），绝不向
     * onDrawFrame 主流程抛出——主追踪与发送管线不受渲染问题影响。
     */
    private fun drawCalibrationVisualization(frame: ARFrame, face: ARFace?) {
        try {
            // 1) 人脸存在状态仅变化时通知 UI（重新进入校准模式时去重状态已重置，首帧必回调）
            val presence = face != null
            if (presence != lastFacePresence) {
                lastFacePresence = presence
                mainHandler.post { onFacePresenceChanged(presence) }
            }
            // 2) 极简 shader program：编译/链接失败则本会话无任何 GL 反馈（仅日志与回调）
            if (!ensureCalibrationProgram()) return
            // 3) 中心十字 + 对准框（正交投影，不依赖人脸）
            drawCrosshair()
            // 4) 网格线框 + 质心标记（网格链路失败后跳过，十字仍保留）
            if (face != null && !meshDrawFailed) {
                drawFaceMesh(frame, face)
            }
        } catch (e: Exception) {
            meshDrawFailed = true
            InAppLog.e(TAG, "校准可视化绘制异常，降级为十字准星", e)
        }
    }

    /** 确保校准用 shader program 就绪（GL 线程懒创建）。失败置降级标志并返回 false。 */
    private fun ensureCalibrationProgram(): Boolean {
        if (meshProgram != 0) return true
        if (meshDrawFailed) return false
        try {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, CALIB_VERTEX_SHADER)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, CALIB_FRAGMENT_SHADER)
            if (vs == 0 || fs == 0) {
                meshDrawFailed = true
                return false
            }
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            if (status[0] == 0) {
                InAppLog.e(TAG, "校准 program 链接失败: ${GLES20.glGetProgramInfoLog(program)}")
                GLES20.glDeleteProgram(program)
                meshDrawFailed = true
                return false
            }
            val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
            val mvpLoc = GLES20.glGetUniformLocation(program, "uMVP")
            val colorLoc = GLES20.glGetUniformLocation(program, "uColor")
            val centerLoc = GLES20.glGetUniformLocation(program, "uCenter")
            val fadeRLoc = GLES20.glGetUniformLocation(program, "uFadeR")
            val fadeEndLoc = GLES20.glGetUniformLocation(program, "uFadeEnd")
            if (posLoc < 0 || mvpLoc < 0 || colorLoc < 0) {
                InAppLog.e(TAG, "校准 program 变量定位失败: pos=$posLoc mvp=$mvpLoc color=$colorLoc")
                GLES20.glDeleteProgram(program)
                meshDrawFailed = true
                return false
            }
            // 淡出 uniform 缺失不致命：老驱动可能优化掉未用变量，置 -1 后
            // glUniform* 对 -1 是无操作，画面退化为「无淡出」，不降级
            if (centerLoc < 0 || fadeRLoc < 0 || fadeEndLoc < 0) {
                InAppLog.w(
                    TAG,
                    "校准 shader 淡出 uniform 缺失（center=$centerLoc fadeR=$fadeRLoc fadeEnd=$fadeEndLoc），本次会话不做径向淡出"
                )
            }
            meshProgram = program
            meshPositionLoc = posLoc
            meshMvpLoc = mvpLoc
            meshColorLoc = colorLoc
            meshCenterLoc = centerLoc
            meshFadeRLoc = fadeRLoc
            meshFadeEndLoc = fadeEndLoc
            InAppLog.i(TAG, "校准 shader program 就绪")
            return true
        } catch (e: Exception) {
            InAppLog.e(TAG, "校准 program 创建异常", e)
            meshDrawFailed = true
            return false
        }
    }

    /** 编译单个 shader；失败返回 0（日志进内嵌面板）。 */
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            InAppLog.e(TAG, "shader 编译失败(type=$type): ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * 设置径向淡出 uniform（每帧一次，三个绘制点共用同一组参数）。
     *
     * 坐标系：gl_FragCoord 是**窗口像素坐标**，原点在**视口左下角**，且受 glViewport 影响。
     * 校准模式下 onSurfaceChanged 把视口设为 (0, 0.25h, w, 0.75h)——即内容整体上移，
     * 可见区域的几何中心落在「窗口坐标 y = 0.25h + 0.75h/2 = 0.625h」。若直接用
     * (w/2, h/2) 做圆心，淡出会偏下、与屏幕上的十字错位。按视口实际中心计算。
     *
     * 半径取「短边」为基准：竖屏下即宽度，保证左右两侧先淡出、上下保留更多细节；
     * 用短边而非对角线可避免横屏时中心区域过大导致几乎看不到淡出。
     */
    private fun applyFadeUniforms() {
        // uniform 缺失（-1）时 glUniform* 无操作，直接返回省掉计算
        if (meshCenterLoc < 0 || meshFadeRLoc < 0 || meshFadeEndLoc < 0) return
        val w = surfaceWidth.toFloat()
        val h = surfaceHeight.toFloat()
        // 视口中心（窗口像素坐标）：与 onSurfaceChanged 的 glViewport 参数保持一致
        val viewportYOffset = if (calibrationMode) (surfaceHeight * CALIB_VIEWPORT_Y_OFFSET) else 0f
        val viewportH = h - viewportYOffset
        val cx = w * 0.5f
        val cy = viewportYOffset + viewportH * 0.5f
        val shortSide = minOf(w, viewportH)
        GLES20.glUniform2f(meshCenterLoc, cx, cy)
        GLES20.glUniform1f(meshFadeRLoc, shortSide * FADE_START_RATIO)
        GLES20.glUniform1f(meshFadeEndLoc, shortSide * FADE_END_RATIO)
    }

    /**
     * 中心十字准星 + 脸部对准框：正交投影，x 轴按屏幕宽高比校正（不同比例屏幕不变形）。
     * 十字与对准框几何中心**严格位于屏幕正中心 (0,0)**，无任何 y 轴偏移；
     * 方框=建议的脸部范围；不依赖人脸检测，永远可画（降级保底）。
     */
    private fun drawCrosshair() {
        if (crossVbo == 0) {
            crossVbo = genBuffer()
            // 单位：半屏高=1。十字 4 顶点 + 对准框 12 顶点（6 条线段），全部以 (0,0) 为中心
            val data = floatArrayOf(
                -CROSS_ARM, 0f, 0f, CROSS_ARM, 0f, 0f,
                0f, -CROSS_ARM, 0f, 0f, CROSS_ARM, 0f,
                -FRAME_HALF_W, -FRAME_HALF_H, 0f, FRAME_HALF_W, -FRAME_HALF_H, 0f,
                FRAME_HALF_W, -FRAME_HALF_H, 0f, FRAME_HALF_W, FRAME_HALF_H, 0f,
                FRAME_HALF_W, FRAME_HALF_H, 0f, -FRAME_HALF_W, FRAME_HALF_H, 0f,
                -FRAME_HALF_W, FRAME_HALF_H, 0f, -FRAME_HALF_W, -FRAME_HALF_H, 0f
            )
            val fb = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            fb.put(data)
            fb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, crossVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, fb, GLES20.GL_STATIC_DRAW)
        }
        val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        Matrix.orthoM(orthoMatrix, 0, -aspect, aspect, -1f, 1f, -1f, 1f)

        GLES20.glUseProgram(meshProgram)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, crossVbo)
        GLES20.glEnableVertexAttribArray(meshPositionLoc)
        GLES20.glVertexAttribPointer(meshPositionLoc, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glUniformMatrix4fv(meshMvpLoc, 1, false, orthoMatrix, 0)
        applyFadeUniforms()
        GLES20.glLineWidth(3f)
        // 视觉层级：十字最亮 → 对准框次之 → 网格最弱
        GLES20.glUniform4f(meshColorLoc, 1f, 1f, 1f, CROSSHAIR_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 4)
        GLES20.glUniform4f(meshColorLoc, 0.95f, 0.92f, 0.80f, FRAME_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_LINES, 4, 12)
        GLES20.glDisableVertexAttribArray(meshPositionLoc)
    }

    /**
     * 绘制人脸网格线框 + 面部质心点（CPU 投影方案）。
     *
     * 网格 API（arenginesdk-4.0.0.5）：
     * ARFace.getFaceGeometry() -> ARFaceGeometry；getVertices():FloatBuffer、
     * getTriangleIndices():IntBuffer、release()；ARPose.toMatrix(float[], int)
     * 直接输出列主序 4x4，ARPose 无 release()，无需释放。
     *
     * 投影链全部在 CPU 完成：局部 -> world(face.getPose) -> 手动针孔投影 -> NDC
     * -> (ndc_x*aspect, ndc_y, 0)（与十字正交空间对齐）。
     * getVertices() 返回人脸**局部**坐标，必须先应用人脸位姿变换。
     * 设备 ROM 相机 HAL 损坏（ARCamera 恒 PAUSED、相机视角矩阵恒全 0，华为 AR 测量
     * 同报错），不用 frame.camera：虚拟相机置于原点、朝 -Z，焦距常数 FOCAL_PX。
     * 边界防御：
     *  - z 未到虚拟相机前方安全深度（wz >= -0.01f，含背面/贴脸穿模）-> 无效顶点；
     *  无效顶点记为 (0,0,0) 且不参与质心统计。
     */
    private fun drawFaceMesh(frame: ARFrame, face: ARFace) {
        var geometry: ARFaceGeometry? = null
        try {
            geometry = face.getFaceGeometry()
            val vertexBuf = geometry.getVertices() ?: return
            val floatCount = vertexBuf.remaining()
            if (floatCount < 9) return // 不足一个三角形
            val verts = FloatArray(floatCount)
            vertexBuf.get(verts)
            val vertexCount = floatCount / 3
            if (vertexCount > Short.MAX_VALUE) { // 线段索引用 GL_UNSIGNED_SHORT，防溢出
                meshDrawFailed = true
                return
            }
            if (vertexCount != meshTopologyVertexCount) {
                rebuildLineTopology(geometry, vertexCount)
                meshTopologyVertexCount = vertexCount
                if (meshDrawFailed) return
            }

            val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()

            // 人脸局部 -> 世界：face.getPose() 的 ARPose 经 toMatrix 直接得列主序 model 矩阵
            // （与 multiplyMV 的列主序布局一致），worldV 循环外建一次复用，避免逐顶点分配
            val modelMatrix = FloatArray(16)
            face.getPose().toMatrix(modelMatrix, 0)
            val worldV = FloatArray(4)

            if (projected2d.size < floatCount) projected2d = FloatArray(floatCount)
            var sumNdcX = 0f
            var sumNdcY = 0f
            var validCount = 0
            var invalidCount = 0
            // 设备 ROM 相机 HAL 损坏，不用 frame.camera：虚拟相机置于原点、朝 -Z。
            // FOCAL_PX 为焦距像素常数，调网格远近/大小改这里
            val FOCAL_PX = 1600f
            for (i in 0 until vertexCount) {
                val o = i * 3
                vertexH[0] = verts[o]
                vertexH[1] = verts[o + 1]
                vertexH[2] = verts[o + 2]
                vertexH[3] = 1f
                Matrix.multiplyMV(worldV, 0, modelMatrix, 0, vertexH, 0)
                // 无效顶点过滤：顶点未到虚拟相机前方安全深度（z >= -0.01f，
                // 含背面/贴脸穿模）视为无效，同时保证 -wz 作分母不会除零
                val wz = worldV[2]
                val ok = wz < -0.01f
                var ndcX = 0f
                var ndcY = 0f
                if (ok) {
                    // 针孔投影 -> NDC；若画面上下/左右颠倒，把 ndcY/ndcX 加上负号调正
                    ndcX = (worldV[0] / -wz) * FOCAL_PX / surfaceWidth
                    ndcY = (worldV[1] / -wz) * FOCAL_PX / surfaceHeight
                }
                if (ok) {
                    projected2d[o] = ndcX * aspect
                    projected2d[o + 1] = ndcY
                    projected2d[o + 2] = 0f
                    sumNdcX += ndcX
                    sumNdcY += ndcY
                    validCount++
                } else {
                    projected2d[o] = 0f
                    projected2d[o + 1] = 0f
                    projected2d[o + 2] = 0f
                    invalidCount++
                }
            }

            // CPU 已算好的 2D 坐标逐帧上传（GL_DYNAMIC_DRAW）：GL 只做连线，无任何投影逻辑
            if (meshVertexVbo == 0) meshVertexVbo = genBuffer()
            val fb = scratchFloatBuffer(floatCount)
            fb.position(0)
            fb.put(projected2d, 0, floatCount)
            fb.position(0)
            GLES20.glUseProgram(meshProgram)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVertexVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floatCount * 4, fb, GLES20.GL_DYNAMIC_DRAW)

            GLES20.glEnableVertexAttribArray(meshPositionLoc)
            GLES20.glVertexAttribPointer(meshPositionLoc, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glUniformMatrix4fv(meshMvpLoc, 1, false, orthoMatrix, 0)
            applyFadeUniforms()
            // 网格 = 参考层，视觉权重最低：低饱和青 + 0.45 alpha（约 3.2:1 对比度，
            // 达 WCAG 非文本 3:1，户外可见又不刺眼）
            GLES20.glUniform4f(
                meshColorLoc, MESH_R, MESH_G, MESH_B, MESH_ALPHA
            )
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, meshLineIndexVbo)
            GLES20.glDrawElements(
                GLES20.GL_LINES, meshLineIndexCount, GLES20.GL_UNSIGNED_SHORT, 0
            )
            GLES20.glDisableVertexAttribArray(meshPositionLoc)

            drawCentroidMarker(sumNdcX, sumNdcY, validCount)

            if (debugLogsEnabled && frameCount % CALIB_LOG_INTERVAL == 0L) {
                val cx = if (validCount > 0) sumNdcX / validCount else 0f
                val cy = if (validCount > 0) sumNdcY / validCount else 0f
                InAppLog.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "校准诊断：顶点 %d，线段 %d，无效 %d，质心 NDC=(%.3f, %.3f)",
                        vertexCount, meshLineIndexCount / 2, invalidCount, cx, cy
                    )
                )
            }
        } finally {
            // 与 blendShapes 同款防泄漏：ARFaceGeometry 持 native 句柄，每帧显式释放
            try {
                geometry?.release()
            } catch (e: Exception) {
                InAppLog.w(TAG, "faceGeometry.release failed: ${e.message}")
            }
        }
    }

    /**
     * 三角索引 -> 线段索引，**按顶点对去重**：同一条边只画一次。
     *
     * AR 人脸网格是闭合流形——每条内部边被相邻两个三角共享，逐个三角无条件写 3 条边
     * 会把整张网重复画约 2 倍，视觉上产生摩尔纹。按 3F = 2E（每条边被 2 个面共享）：
     * 820 面 → 2460 段，去重后约 1230 段（降约 50%），实测 48.4%（带边界网格的边界边
     * 只被 1 个面共享，略高于闭流形理论值）。
     *
     * 去重键 `(min shl 32) or max`：顶点索引 < 2^16（Short 上限已由调用方校验），
     * shl 32 后高低位不重叠，Long 键无碰撞。
     * 拓扑固定，只在顶点数变化时重建一次，HashSet 开销不在逐帧路径上。
     * 越界/为空的索引防御性跳过；全部无效则降级（只画十字）。
     */
    private fun rebuildLineTopology(geometry: ARFaceGeometry, vertexCount: Int) {
        try {
            val idxBuf = geometry.getTriangleIndices()
            if (idxBuf == null) {
                InAppLog.w(TAG, "校准网格：getTriangleIndices 为空，降级为十字准星")
                meshDrawFailed = true
                return
            }
            val n = idxBuf.remaining()
            val idx = IntArray(n)
            idxBuf.get(idx)
            // 去重后线段数上限：按 3F = 2E 估算实际只需一半，但三角形数可能远多于 V
            // （非流形数据），仍按 3F 上限分配最稳（一次性内存，非逐帧）
            val lines = ShortArray((n / 3) * 6)
            val seen = HashSet<Long>((n / 3) * 3)
            var w = 0
            var i = 0
            while (i + 2 < n) {
                val a = idx[i]
                val b = idx[i + 1]
                val c = idx[i + 2]
                if (a in 0 until vertexCount && b in 0 until vertexCount && c in 0 until vertexCount) {
                    // 三个顶点对逐个去重写入
                    if (addEdge(seen, a, b)) {
                        lines[w++] = a.toShort(); lines[w++] = b.toShort()
                    }
                    if (addEdge(seen, b, c)) {
                        lines[w++] = b.toShort(); lines[w++] = c.toShort()
                    }
                    if (addEdge(seen, c, a)) {
                        lines[w++] = c.toShort(); lines[w++] = a.toShort()
                    }
                }
                i += 3
            }
            if (w < 6) {
                InAppLog.w(TAG, "校准网格：无有效三角索引，降级为十字准星")
                meshDrawFailed = true
                return
            }
            if (meshLineIndexVbo == 0) meshLineIndexVbo = genBuffer()
            val sb = ByteBuffer.allocateDirect(w * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
            sb.put(lines, 0, w)
            sb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, meshLineIndexVbo)
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, w * 2, sb, GLES20.GL_STATIC_DRAW)
            meshLineIndexCount = w
        } catch (e: Exception) {
            InAppLog.e(TAG, "校准网格拓扑构建失败，降级为十字准星", e)
            meshDrawFailed = true
        }
    }

    /**
     * 边去重：顶点对无向归一化后入集合，首次出现返回 true（调用方据此写入索引）。
     * 键 `(min shl 32) or max`——顶点索引已由调用方保证 < Short.MAX_VALUE，
     * 高低 32 位不重叠，Long 键唯一。
     */
    private fun addEdge(seen: HashSet<Long>, u: Int, v: Int): Boolean {
        val lo = if (u < v) u else v
        val hi = if (u < v) v else u
        return seen.add((lo.toLong() shl 32) or hi.toLong())
    }

    /**
     * 面部质心标记点（GL_POINTS）：全部**有效**顶点的平均 NDC 坐标（CPU 端投影循环已算好），
     * 与中心十字重合即对准。无有效顶点或质心出视野（|NDC|>1）时不画。
     */
    private fun drawCentroidMarker(sumNdcX: Float, sumNdcY: Float, validCount: Int) {
        if (validCount <= 0) return
        val ndcX = sumNdcX / validCount
        val ndcY = sumNdcY / validCount
        if (Math.abs(ndcX) > 1f || Math.abs(ndcY) > 1f) return
        // NDC 点换算到与十字相同的正交空间（x 轴按 aspect 放大）
        val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val pt = floatArrayOf(ndcX * aspect, ndcY, 0f)

        if (meshPointVbo == 0) meshPointVbo = genBuffer()
        val fb = scratchFloatBuffer(3)
        fb.position(0)
        fb.put(pt)
        fb.position(0)
        GLES20.glUseProgram(meshProgram)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshPointVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 12, fb, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glEnableVertexAttribArray(meshPositionLoc)
        GLES20.glVertexAttribPointer(meshPositionLoc, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glUniformMatrix4fv(meshMvpLoc, 1, false, orthoMatrix, 0)
        applyFadeUniforms()
        // 质心点是对准判断依据，权重高于网格：同色系青色但 alpha 0.9（+点尺寸 10px）
        GLES20.glUniform4f(meshColorLoc, MESH_R, MESH_G, MESH_B, CENTROID_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
        GLES20.glDisableVertexAttribArray(meshPositionLoc)
    }

    private fun genBuffer(): Int {
        val out = IntArray(1)
        GLES20.glGenBuffers(1, out, 0)
        return out[0]
    }

    /** GL 线程复用的顶点中转 direct buffer（容量不足时扩容重建）。 */
    private fun scratchFloatBuffer(minFloats: Int): FloatBuffer {
        var buf = scratchVertices
        if (buf == null || buf.capacity() < minFloats) {
            buf = ByteBuffer.allocateDirect(minFloats * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            scratchVertices = buf
        }
        return buf
    }

    /** 一帧头部姿态原始数据：translation=[x,y,z]（米）、quaternion=[x,y,z,w]。 */
    private data class HeadPose(val translation: FloatArray, val quaternion: FloatArray)

    /**
     * 读取 ARFace 头部姿态（[ARFace.getPose] -> ARPose）：
     * 平移用 getTranslation(float[], 0)，旋转用 getRotationQuaternion(float[], 0)
     * （arenginesdk-4.0.0.5 的 ARPose 签名）。
     * 仅用于 VTS 数据管线（FaceAngleX/Y/Z + FacePositionX/Y/Z 映射），与校准渲染无关。
     *
     * 均为纯内存 JNI 读取（微秒级），直接在 GL 线程调用不会阻塞渲染；
     * 耗时的网络发送已隔离在 IO 协程（见 [dispatchSend]），管线整体不阻塞 GL 线程。
     *
     * @return null 表示本帧读取失败：表情参数照常发送，仅缺头姿项。
     */
    private fun readHeadPose(face: ARFace): HeadPose? {
        return try {
            val pose = face.getPose()
            val translation = FloatArray(3)
            pose.getTranslation(translation, 0)
            val quaternion = FloatArray(4)
            // 四元数按 (x, y, z, w) 顺序填充：ARQuaternion 以 x()/y()/z()/w() 访问器暴露分量，
            // getValues 按 INDEX_X/Y/Z/W (=0/1/2/3) 顺序写出，ARPose.getRotationQuaternion
            // 直通调用、无重排。
            pose.getRotationQuaternion(quaternion, 0)
            HeadPose(translation, quaternion)
        } catch (e: Exception) {
            InAppLog.w(TAG, "getPose failed: ${e.message}")
            null
        }
    }

    /**
     * 频率节流（默认 30Hz，设置页「推流频率」可配置）+ 防积压 + IO 协程异步发送的统一出口
     * （正常表情帧与追踪丢失时的 faceFound=false 空帧都走这里）。
     * 参数 Map 为本帧新建的只读对象（smoothAll 的 mapValues 产物 / emptyMap），
     * 交给 IO 线程后不会再被 GL 线程修改，无需额外快照。
     */
    private fun dispatchSend(params: Map<String, Float>) {
        // 推流暂停门控（自适应探测期）：正常帧/空帧一律不出网，模型由探针注入独占
        if (streamingPaused) return
        // 3) 频率节流（GL 线程读 @Volatile）：渲染帧率可能高于节流频率，多余帧只算不发
        val now = System.currentTimeMillis()
        if (now - lastSendTime < sendIntervalMs) {
            return
        }
        lastSendTime = now

        // 4) 防积压：上一帧还在发送队列则整帧丢弃（等下一个节流窗口）
        if (!sendPending.compareAndSet(false, true)) {
            return
        }
        // 5) 网络发送放到 IO 协程：即使 VTS 端卡死，也绝不阻塞 GL 渲染线程。
        //    （OkHttp WebSocket.send 本身是非阻塞入队，此处为双保险）
        sendScope.launch {
            try {
                // 未认证时内部静默丢弃并返回 false，不会抛异常
                vtsClient.injectParameters(params)
            } catch (e: Exception) {
                // 理论上 injectParameters 不抛；兜底防止 IO 协程异常影响后续发送
                InAppLog.w(TAG, "injectParameters failed: ${e.message}")
            } finally {
                sendPending.set(false)
            }
        }
    }

    /** 停止发送并释放 IO 协程池。Activity onDestroy 时调用；之后本渲染器不可再用。 */
    fun release() {
        sendScope.cancel()
    }

    companion object {
        private const val TAG = "ARBlendShapes"
        private const val LOG_INTERVAL = 10L
        /** 内嵌日志：发送参数汇总的帧间隔（每 60 帧一条，时长随推流频率变化，避免刷爆环形缓冲）。 */
        private const val INAPP_LOG_INTERVAL = 60L

        /** 校准诊断日志的帧间隔（仅「开启详细日志」时输出）。 */
        private const val CALIB_LOG_INTERVAL = 30L

        // 校准模式背景色（深蓝黑，突出网格线）
        private const val CALIB_BG_R = 0.05f
        private const val CALIB_BG_G = 0.07f
        private const val CALIB_BG_B = 0.10f

        // ---------- 校准可视化配色与视觉层级（改这里统一调） ----------
        // 网格线：低饱和青 #59D9F2，0.45 alpha 下约 3.2:1 对比度（WCAG 非文本达标）
        private const val MESH_R = 0.35f
        private const val MESH_G = 0.85f
        private const val MESH_B = 0.95f
        /** 网格线不透明度：参考层，权重最低。 */
        private const val MESH_ALPHA = 0.45f
        /** 中心十字：白色最亮，是「对准」的判断基准。 */
        private const val CROSSHAIR_ALPHA = 0.85f
        /** 对准框：暖白，与白色十字区分。 */
        private const val FRAME_ALPHA = 0.7f
        /** 质心标记点：判断是否重合的关键依据，比网格实。 */
        private const val CENTROID_ALPHA = 0.9f

        // ---------- 径向淡出（片元着色器 uCenter / uFadeR / uFadeEnd） ----------
        /** 视口相对整屏的上移比例，必须与 onSurfaceChanged 的 glViewport 保持一致。 */
        private const val CALIB_VIEWPORT_Y_OFFSET = 0.25f
        /** 淡出起始半径 = 短边 × 本值（此半径内满亮度）。 */
        private const val FADE_START_RATIO = 0.30f
        /** 完全透明半径 = 短边 × 本值。 */
        private const val FADE_END_RATIO = 0.75f

        // 十字准星 / 对准框尺寸（单位：半屏高=1，x 轴经 aspect 校正；几何中心 = 屏幕正中心 (0,0)）
        private const val CROSS_ARM = 0.09f
        private const val FRAME_HALF_W = 0.34f
        private const val FRAME_HALF_H = 0.42f

        // 极简 shader：单矩阵 + 纯色 + 径向淡出（见 CALIB_FRAGMENT_SHADER）。
        // GLSL 源码保持 ASCII，避免任何编码问题。
        // gl_PointSize 只影响 GL_POINTS（质心标记点），线段绘制不受影响。
        private const val CALIB_VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                "uniform mat4 uMVP;\n" +
                "void main() {\n" +
                "    gl_Position = uMVP * aPosition;\n" +
                "    gl_PointSize = 10.0;\n" +
                "}\n"
        // 径向淡出：越靠近 uCenter（屏幕中心 = 十字准星处）越亮，超出 uFadeR 后按
        // smoothstep 线性衰减到 uFadeEnd 处的 0。用 gl_FragCoord（窗口像素坐标）而非
        // 顶点属性，避免改 VBO 布局与 glVertexAttribPointer 步长，零额外顶点开销。
        // 十字/对准框/网格/质心点共用本 shader：中心处 alpha 不衰减 → 十字最亮。
        private const val CALIB_FRAGMENT_SHADER =
            "precision mediump float;\n" +
                "uniform vec4 uColor;\n" +
                "uniform vec2 uCenter;\n" +
                "uniform float uFadeR;\n" +
                "uniform float uFadeEnd;\n" +
                "void main() {\n" +
                "    float d = distance(gl_FragCoord.xy, uCenter);\n" +
                "    float k = 1.0 - smoothstep(uFadeR, uFadeEnd, d);\n" +
                "    gl_FragColor = vec4(uColor.rgb, uColor.a * k);\n" +
                "}\n"
    }
}
