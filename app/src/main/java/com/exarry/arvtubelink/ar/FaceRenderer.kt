package com.exarry.arvtubelink.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
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

class FaceRenderer(
    private val manager: ArSessionManager,
    private val displayRotation: () -> Int,
    private val mapper: BlendShapeMapper,
    private val smoother: Smoother,
    private val vtsClient: VtsWsClient,
    private val onFacePresenceChanged: (Boolean) -> Unit = {}
) : GLSurfaceView.Renderer {

    private var frameCount = 0L

    @Volatile
    private var lastSendTime = 0L

    private val sendPending = AtomicBoolean(false)

    private var wasFaceFound = false

    private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** UI 线程写、GL 线程读 */
    @Volatile
    private var calibrationMode = false

    @Volatile
    private var sendIntervalMs = 1000L / 30

    private var meshProgram = 0
    private var meshPositionLoc = -1
    private var meshMvpLoc = -1
    private var meshColorLoc = -1
    private var meshCenterLoc = -1
    private var meshFadeRLoc = -1
    private var meshFadeEndLoc = -1
    private var meshVertexVbo = 0
    private var meshLineIndexVbo = 0
    private var meshLineIndexCount = 0
    private var meshPointVbo = 0
    private var crossVbo = 0

    private var meshTopologyVertexCount = -1

    /** 网格失败后本会话只画十字，重进校准模式重试 */
    private var meshDrawFailed = false

    private var prevCalibrationActive = false

    private var lastFacePresence: Boolean? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var surfaceWidth = 1
    private var surfaceHeight = 1

    /** 2D 方案全场景共用同一正交矩阵，坐标已在 CPU 端投影 */
    private val orthoMatrix = FloatArray(16)

    private val vertexH = FloatArray(4)

    private var projected2d = FloatArray(0)

    private var scratchVertices: FloatBuffer? = null

    fun setCalibrationMode(enabled: Boolean) {
        calibrationMode = enabled
    }

    fun setSendFrequencyHz(hz: Int) {
        sendIntervalMs = 1000L / hz.coerceIn(10, 60)
    }

    @Volatile
    private var streamingPaused = false

    /** GL 线程写、Live2D 协程读，人脸丢失置 null */
    @Volatile
    var latestFaceSnapshot: Map<String, Float>? = null
        private set

    fun setStreamingPaused(paused: Boolean) {
        streamingPaused = paused
    }

    /** IO 协程写、UI 线程读 */
    private val sentFrameCount = java.util.concurrent.atomic.AtomicLong(0L)

    fun readAndResetSentFrames(): Long = sentFrameCount.getAndSet(0L)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        // 校准可视化靠 uColor 的 alpha 分层，须开混合；全局状态，EGL context 重建后重进本方法
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // context 重建后旧句柄全部失效，只清零不 glDelete，校准模式下按需懒重建
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
        // 校准模式下视口上移 25% 高度，避开占屏高约 1/4 的底部 Dialog 面板
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

        val frame = try {
            session.update()
        } catch (e: Exception) {
            InAppLog.e(TAG, "session.update() failed", e)
            return
        }
        frameCount++

        if (calibActive != prevCalibrationActive) {
            prevCalibrationActive = calibActive
            meshDrawFailed = false
            meshTopologyVertexCount = -1
            lastFacePresence = null
        }

        // ARFrame 只含变化项，全量取用 getAllTrackables
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

        if (blendShapes == null) {
            if (calibActive) {
                drawCalibrationVisualization(frame, null)
            }
            if (frameCount % LOG_INTERVAL == 0L) {
                InAppLog.i(TAG, "帧 $frameCount：未检测到追踪中的人脸 / BlendShapes 为空")
            }
            if (wasFaceFound) {
                wasFaceFound = false
                latestFaceSnapshot = null
                dispatchSend(emptyMap())
            }
            return
        }
        wasFaceFound = true

        val blendShapeMap: Map<String, Float> = blendShapes.getBlendShapeDataMapKeyString()
        // HashMap 是数据拷贝，取完立即释放 native 句柄，防止逐帧泄漏
        blendShapes.release()

        val headPose = face?.let { readHeadPose(it) }

        val vtsParams = mapper.map(blendShapeMap)

        val mergedParams = if (headPose == null) {
            vtsParams.parameters
        } else {
            vtsParams.parameters + mapper.mapHeadPose(headPose.translation, headPose.quaternion)
        }

        val smoothed = smoother.smoothAll(mergedParams)

        // 放在 smoother 之后：Live2D 与 VTS 取同一份平滑值，避免相位差
        latestFaceSnapshot = if (vtsParams.faceFound) smoothed else null

        if (frameCount % LOG_INTERVAL == 0L && InAppLog.isDebugEnabled) {
            InAppLog.i(TAG, "帧数据($frameCount): $blendShapeMap")
            headPose?.let {
                InAppLog.i(TAG, "头姿: T=${it.translation.contentToString()}, Q=${it.quaternion.contentToString()}")
            }
        }

        if (frameCount % INAPP_LOG_INTERVAL == 0L && InAppLog.isDebugEnabled) {
            InAppLog.i(TAG, "帧 $frameCount：发送参数 $smoothed")
            headPose?.let {
                InAppLog.i(
                    TAG,
                    "帧 $frameCount 头姿原始：T=${it.translation.contentToString()}," +
                        " Q=${it.quaternion.contentToString()}"
                )
            }
        }

        if (calibActive) {
            drawCalibrationVisualization(frame, face)
        }

        dispatchSend(smoothed)
    }

    private fun drawCalibrationVisualization(frame: ARFrame, face: ARFace?) {
        try {
            val presence = face != null
            if (presence != lastFacePresence) {
                lastFacePresence = presence
                mainHandler.post { onFacePresenceChanged(presence) }
            }
            if (!ensureCalibrationProgram()) return
            drawCrosshair()
            if (face != null && !meshDrawFailed) {
                drawFaceMesh(frame, face)
            }
        } catch (e: Exception) {
            meshDrawFailed = true
            InAppLog.e(TAG, "校准可视化绘制异常，降级为十字准星", e)
        }
    }

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
            // 淡出 uniform 缺失不致命：驱动可能优化掉未用变量，-1 对 glUniform* 是空操作
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

    /** gl_FragCoord 是视口左下角起的像素坐标，圆心按 glViewport 后的实际视口中心算 */
    private fun applyFadeUniforms() {
        if (meshCenterLoc < 0 || meshFadeRLoc < 0 || meshFadeEndLoc < 0) return
        val w = surfaceWidth.toFloat()
        val h = surfaceHeight.toFloat()
        val viewportYOffset = if (calibrationMode) (surfaceHeight * CALIB_VIEWPORT_Y_OFFSET) else 0f
        val viewportH = h - viewportYOffset
        val cx = w * 0.5f
        val cy = viewportYOffset + viewportH * 0.5f
        val shortSide = minOf(w, viewportH)
        GLES20.glUniform2f(meshCenterLoc, cx, cy)
        GLES20.glUniform1f(meshFadeRLoc, shortSide * FADE_START_RATIO)
        GLES20.glUniform1f(meshFadeEndLoc, shortSide * FADE_END_RATIO)
    }

    private fun drawCrosshair() {
        if (crossVbo == 0) {
            crossVbo = genBuffer()
            // 单位：半屏高=1，4 顶点十字 + 12 顶点对准框，全部以 (0,0) 为中心
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
        GLES20.glUniform4f(meshColorLoc, 1f, 1f, 1f, CROSSHAIR_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 4)
        GLES20.glUniform4f(meshColorLoc, 0.95f, 0.92f, 0.80f, FRAME_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_LINES, 4, 12)
        GLES20.glDisableVertexAttribArray(meshPositionLoc)
    }

    /** CPU 投影链：局部 -> world(face.getPose) -> 手动针孔 -> NDC，相机固定在原点朝 -Z */
    private fun drawFaceMesh(frame: ARFrame, face: ARFace) {
        var geometry: ARFaceGeometry? = null
        try {
            geometry = face.getFaceGeometry()
            val vertexBuf = geometry.getVertices() ?: return
            val floatCount = vertexBuf.remaining()
            if (floatCount < 9) return
            val verts = FloatArray(floatCount)
            vertexBuf.get(verts)
            val vertexCount = floatCount / 3
            if (vertexCount > Short.MAX_VALUE) { // 线段索引走 GL_UNSIGNED_SHORT，需防溢出
                meshDrawFailed = true
                return
            }
            if (vertexCount != meshTopologyVertexCount) {
                rebuildLineTopology(geometry, vertexCount)
                meshTopologyVertexCount = vertexCount
                if (meshDrawFailed) return
            }

            val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()

            // ARPose.toMatrix 输出列主序，与 multiplyMV 布局一致
            val modelMatrix = FloatArray(16)
            face.getPose().toMatrix(modelMatrix, 0)
            val worldV = FloatArray(4)

            if (projected2d.size < floatCount) projected2d = FloatArray(floatCount)
            var sumNdcX = 0f
            var sumNdcY = 0f
            var validCount = 0
            var invalidCount = 0
            // 相机 HAL 损坏不用 frame.camera：虚拟相机在原点朝 -Z，焦距常数决定网格远近
            val FOCAL_PX = 1600f
            for (i in 0 until vertexCount) {
                val o = i * 3
                vertexH[0] = verts[o]
                vertexH[1] = verts[o + 1]
                vertexH[2] = verts[o + 2]
                vertexH[3] = 1f
                Matrix.multiplyMV(worldV, 0, modelMatrix, 0, vertexH, 0)
                // z >= -0.01f 视为无效（背面/贴脸穿模），同时保证 -wz 作分母不为零
                val wz = worldV[2]
                val ok = wz < -0.01f
                var ndcX = 0f
                var ndcY = 0f
                if (ok) {
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
            GLES20.glUniform4f(
                meshColorLoc, MESH_R, MESH_G, MESH_B, MESH_ALPHA
            )
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, meshLineIndexVbo)
            GLES20.glDrawElements(
                GLES20.GL_LINES, meshLineIndexCount, GLES20.GL_UNSIGNED_SHORT, 0
            )
            GLES20.glDisableVertexAttribArray(meshPositionLoc)

            drawCentroidMarker(sumNdcX, sumNdcY, validCount)

            if (frameCount % CALIB_LOG_INTERVAL == 0L && InAppLog.isDebugEnabled) {
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
            // ARFaceGeometry 持 native 句柄，每帧显式释放防泄漏
            try {
                geometry?.release()
            } catch (e: Exception) {
                InAppLog.w(TAG, "faceGeometry.release failed: ${e.message}")
            }
        }
    }

    /** 三角索引 -> 线段索引，按顶点对去重（3F=2E） */
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
            val lines = ShortArray((n / 3) * 6)
            val seen = HashSet<Long>((n / 3) * 3)
            var w = 0
            var i = 0
            while (i + 2 < n) {
                val a = idx[i]
                val b = idx[i + 1]
                val c = idx[i + 2]
                if (a in 0 until vertexCount && b in 0 until vertexCount && c in 0 until vertexCount) {
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

    /** 无向边归一化后入集合，首次出现返回 true；顶点索引 < 2^16 时高低位不重叠 */
    private fun addEdge(seen: HashSet<Long>, u: Int, v: Int): Boolean {
        val lo = if (u < v) u else v
        val hi = if (u < v) v else u
        return seen.add((lo.toLong() shl 32) or hi.toLong())
    }

    /** 有效顶点平均 NDC，与十字重合即对准 */
    private fun drawCentroidMarker(sumNdcX: Float, sumNdcY: Float, validCount: Int) {
        if (validCount <= 0) return
        val ndcX = sumNdcX / validCount
        val ndcY = sumNdcY / validCount
        if (Math.abs(ndcX) > 1f || Math.abs(ndcY) > 1f) return
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
        GLES20.glUniform4f(meshColorLoc, MESH_R, MESH_G, MESH_B, CENTROID_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
        GLES20.glDisableVertexAttribArray(meshPositionLoc)
    }

    private fun genBuffer(): Int {
        val out = IntArray(1)
        GLES20.glGenBuffers(1, out, 0)
        return out[0]
    }

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

    private data class HeadPose(val translation: FloatArray, val quaternion: FloatArray)

    /** 纯内存 JNI 读取，可在 GL 线程直接调用；失败返回 null 仅缺头姿项 */
    private fun readHeadPose(face: ARFace): HeadPose? {
        return try {
            val pose = face.getPose()
            val translation = FloatArray(3)
            pose.getTranslation(translation, 0)
            val quaternion = FloatArray(4)
            pose.getRotationQuaternion(quaternion, 0)
            HeadPose(translation, quaternion)
        } catch (e: Exception) {
            InAppLog.w(TAG, "getPose failed: ${e.message}")
            null
        }
    }

    private fun dispatchSend(params: Map<String, Float>) {
        if (streamingPaused) return
        val now = System.currentTimeMillis()
        if (now - lastSendTime < sendIntervalMs) {
            return
        }
        lastSendTime = now

        if (!sendPending.compareAndSet(false, true)) {
            return
        }
        sendScope.launch {
            try {
                // 未认证时内部静默丢弃并返回 false，不抛异常
                if (vtsClient.injectParameters(params)) sentFrameCount.incrementAndGet()
            } catch (e: Exception) {
                InAppLog.w(TAG, "injectParameters failed: ${e.message}")
            } finally {
                sendPending.set(false)
            }
        }
    }

    fun release() {
        sendScope.cancel()
        latestFaceSnapshot = null
    }

    companion object {
        private const val TAG = "ARBlendShapes"
        private const val LOG_INTERVAL = 10L
        private const val INAPP_LOG_INTERVAL = 60L
        private const val CALIB_LOG_INTERVAL = 30L

        private const val CALIB_BG_R = 0.05f
        private const val CALIB_BG_G = 0.07f
        private const val CALIB_BG_B = 0.10f

        private const val MESH_R = 0.35f
        private const val MESH_G = 0.85f
        private const val MESH_B = 0.95f
        private const val MESH_ALPHA = 0.45f
        private const val CROSSHAIR_ALPHA = 0.85f
        private const val FRAME_ALPHA = 0.7f
        private const val CENTROID_ALPHA = 0.9f

        /** 视口上移比例，须与 onSurfaceChanged 的 glViewport 一致 */
        private const val CALIB_VIEWPORT_Y_OFFSET = 0.25f
        /** 淡出半径：满亮度 = 短边 × 本值，全透明 = 短边 × FADE_END_RATIO */
        private const val FADE_START_RATIO = 0.30f
        private const val FADE_END_RATIO = 0.75f

        private const val CROSS_ARM = 0.09f
        private const val FRAME_HALF_W = 0.34f
        private const val FRAME_HALF_H = 0.42f

        /** GLSL 源码保持 ASCII；gl_PointSize 仅作用于 GL_POINTS */
        private const val CALIB_VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                "uniform mat4 uMVP;\n" +
                "void main() {\n" +
                "    gl_Position = uMVP * aPosition;\n" +
                "    gl_PointSize = 10.0;\n" +
                "}\n"
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
