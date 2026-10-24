package com.exarry.arvtubelink.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.huawei.hiar.AREnginesApk
import com.huawei.hiar.AREnginesApk.ARAvailability
import com.huawei.hiar.ARConfigBase
import com.huawei.hiar.ARFaceTrackingConfig
import com.huawei.hiar.ARSession
import com.huawei.hiar.exceptions.ARCameraNotAvailableException
import com.huawei.hiar.exceptions.ARFatalException
import com.huawei.hiar.exceptions.ARUnavailableDeviceNotCompatibleException
import com.huawei.hiar.exceptions.ARUnavailableEmuiNotCompatibleException
import com.huawei.hiar.exceptions.ARUnavailableServiceApkTooOldException
import com.huawei.hiar.exceptions.ARUnavailableServiceNotInstalledException
import com.huawei.hiar.exceptions.ARUnSupportedConfigurationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 华为 AR Engine 可用状态。 */
sealed class ArEngineState {
    /** AR Engine 服务已就绪，可创建会话。 */
    data object Available : ArEngineState()

    /** 设备支持但服务未安装/过旧，需到华为应用市场安装或更新。 */
    data object NotInstalled : ArEngineState()

    /** 设备不支持 AR Engine（非华为机型/EMUI 能力不足/检测失败）。 */
    data object Unsupported : ArEngineState()
}

/**
 * AR 引擎层：可用性检测、人脸追踪会话生命周期管理。不做相机渲染。
 */
class ArSessionManager(private val context: Context) {

    var state: ArEngineState = ArEngineState.NotInstalled
        private set

    private var session: ARSession? = null

    /** 异步检测 AR Engine 服务是否已安装/可用。 */
    @Suppress("DEPRECATION") // checkAvailability/ARAvailability 已过时，但仍是唯一能区分三态的 API
    suspend fun checkAvailability(): ArEngineState = withContext(Dispatchers.Default) {
        val result = if (AREnginesApk.isAREngineApkReady(context)) {
            ArEngineState.Available
        } else {
            when (AREnginesApk.checkAvailability(context)) {
                ARAvailability.SUPPORTED_NOT_INSTALLED,
                ARAvailability.SUPPORTED_APK_TOO_OLD -> ArEngineState.NotInstalled
                // UNSUPPORTED_* 及 UNKNOWN_*（检测中/超时/错误）：按不支持降级
                else -> ArEngineState.Unsupported
            }
        }
        state = result
        result
    }

    /** 相机权限是否已授予（AR Engine 人脸追踪必需）。 */
    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** 创建人脸追踪会话（前置摄像头，开启 BlendShape 表情系数输出）。 */
    fun createArSession(): ARSession? {
        if (!hasCameraPermission()) {
            Log.w(TAG, "camera permission not granted")
            return null
        }
        if (session != null) {
            Log.w(TAG, "session already exists")
            return session
        }
        return try {
            val newSession = ARSession(context)
            val config = ARFaceTrackingConfig(newSession).apply {
                // 按位或组合：默认人脸检测 + 表情系数（BlendShapes）
                setFaceDetectMode(
                    ARConfigBase.FaceDetectMode.FACE_ENABLE_DEFAULT.enumValue or
                        ARConfigBase.FaceDetectMode.FACE_ENABLE_BLEND_SHAPE.enumValue
                )
                setCameraLensFacing(ARConfigBase.CameraLensFacing.FRONT)
            }
            newSession.configure(config)
            session = newSession
            newSession
        } catch (e: ARUnavailableServiceNotInstalledException) {
            state = ArEngineState.NotInstalled
            Log.e(TAG, "AR Engine service not installed", e)
            null
        } catch (e: ARUnavailableServiceApkTooOldException) {
            state = ArEngineState.NotInstalled
            Log.e(TAG, "AR Engine service apk too old", e)
            null
        } catch (e: ARUnavailableDeviceNotCompatibleException) {
            state = ArEngineState.Unsupported
            Log.e(TAG, "device not compatible with AR Engine", e)
            null
        } catch (e: ARUnavailableEmuiNotCompatibleException) {
            state = ArEngineState.Unsupported
            Log.e(TAG, "EMUI not compatible with AR Engine", e)
            null
        } catch (e: ARUnSupportedConfigurationException) {
            Log.e(TAG, "config unsupported", e)
            null
        } catch (e: ARCameraNotAvailableException) {
            Log.e(TAG, "camera unavailable", e)
            null
        } catch (e: ARFatalException) {
            Log.e(TAG, "AR fatal exception on create", e)
            null
        }
    }

    /** 恢复会话（须在 GLSurfaceView 渲染启动前调用）。 */
    fun resumeSession(): Boolean = try {
        session?.resume()
        session != null
    } catch (e: ARCameraNotAvailableException) {
        Log.e(TAG, "camera unavailable on resume", e)
        releaseSession()
        false
    } catch (e: Exception) {
        Log.e(TAG, "resume failed", e)
        false
    }

    /** 暂停会话（须先 glSurfaceView.onPause() 停掉渲染线程）。 */
    fun pauseSession() {
        try {
            session?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "pause failed", e)
        }
    }

    /** 彻底停止并释放会话，之后需重新 createArSession()。 */
    fun releaseSession() {
        try {
            session?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        session = null
    }

    fun getSession(): ARSession? = session

    /** 渲染 surface 尺寸变化时同步显示几何。 */
    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        try {
            session?.setDisplayGeometry(rotation, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "setDisplayGeometry failed", e)
        }
    }

    companion object {
        private const val TAG = "ArSessionManager"
    }
}
