package com.exarry.arvtubelink.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.exarry.arvtubelink.utils.InAppLog
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

sealed class ArEngineState {
    data object Available : ArEngineState()

    data object NotInstalled : ArEngineState()

    data object Unsupported : ArEngineState()
}

/** 人脸追踪会话生命周期管理，不做相机渲染 */
class ArSessionManager(private val context: Context) {

    var state: ArEngineState = ArEngineState.NotInstalled
        private set

    private var session: ARSession? = null

    /** 在 Default 调度器检测，UNKNOWN_* 一律按 Unsupported */
    @Suppress("DEPRECATION") // checkAvailability/ARAvailability 已过时，但仍是唯一能区分三态的 API
    suspend fun checkAvailability(): ArEngineState = withContext(Dispatchers.Default) {
        val result = if (AREnginesApk.isAREngineApkReady(context)) {
            ArEngineState.Available
        } else {
            when (AREnginesApk.checkAvailability(context)) {
                ARAvailability.SUPPORTED_NOT_INSTALLED,
                ARAvailability.SUPPORTED_APK_TOO_OLD -> ArEngineState.NotInstalled
                else -> ArEngineState.Unsupported
            }
        }
        state = result
        result
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** 已存在会话时直接返回；配置为前置摄像头 + BlendShape */
    fun createArSession(): ARSession? {
        if (!hasCameraPermission()) {
            InAppLog.w(TAG, "camera permission not granted")
            return null
        }
        if (session != null) {
            InAppLog.w(TAG, "session already exists")
            return session
        }
        return try {
            val newSession = ARSession(context)
            val config = ARFaceTrackingConfig(newSession).apply {
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
            InAppLog.e(TAG, "AR Engine service not installed", e)
            null
        } catch (e: ARUnavailableServiceApkTooOldException) {
            state = ArEngineState.NotInstalled
            InAppLog.e(TAG, "AR Engine service apk too old", e)
            null
        } catch (e: ARUnavailableDeviceNotCompatibleException) {
            state = ArEngineState.Unsupported
            InAppLog.e(TAG, "device not compatible with AR Engine", e)
            null
        } catch (e: ARUnavailableEmuiNotCompatibleException) {
            state = ArEngineState.Unsupported
            InAppLog.e(TAG, "EMUI not compatible with AR Engine", e)
            null
        } catch (e: ARUnSupportedConfigurationException) {
            InAppLog.e(TAG, "config unsupported", e)
            null
        } catch (e: ARCameraNotAvailableException) {
            InAppLog.e(TAG, "camera unavailable", e)
            null
        } catch (e: ARFatalException) {
            InAppLog.e(TAG, "AR fatal exception on create", e)
            null
        }
    }

    /** 须在 GLSurfaceView 渲染启动前调用 */
    fun resumeSession(): Boolean = try {
        session?.resume()
        session != null
    } catch (e: ARCameraNotAvailableException) {
        InAppLog.e(TAG, "camera unavailable on resume", e)
        releaseSession()
        false
    } catch (e: Exception) {
        InAppLog.e(TAG, "resume failed", e)
        false
    }

    /** 须先 glSurfaceView.onPause() 停渲染线程 */
    fun pauseSession() {
        try {
            session?.pause()
        } catch (e: Exception) {
            InAppLog.w(TAG, "pause failed", e)
        }
    }

    fun releaseSession() {
        try {
            session?.stop()
        } catch (e: Exception) {
            InAppLog.w(TAG, "stop failed", e)
        }
        session = null
    }

    fun getSession(): ARSession? = session

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        try {
            session?.setDisplayGeometry(rotation, width, height)
        } catch (e: Exception) {
            InAppLog.w(TAG, "setDisplayGeometry failed", e)
        }
    }

    companion object {
        private const val TAG = "ArSessionManager"
    }
}
