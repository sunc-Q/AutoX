package com.stardust.autojs.core.image.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.stardust.app.OnActivityResultDelegate
import com.stardust.autojs.core.util.ScriptPromiseAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

/**
 * Created by Stardust on 2017/5/17.
 */
interface ScreenCaptureRequester {
    var screenCapture: ScreenCapturer?
    suspend fun requestScreenCapture(context: Context, orientation: Int)
    fun requestScreenCaptureLegacy(context: Context, orientation: Int): ScriptPromiseAdapter
    fun recycle()

    /**
     * 当前仍可用的 MediaProjection（跨脚本复用），没有则返回 null。
     * 系统内录复用它，避免每次录音都重新弹授权框。
     */
    fun getAliveMediaProjection(): MediaProjection? = null

    /**
     * 确保拿到可用的 MediaProjection：已有活投影直接复用，否则请求一次屏幕录制授权。
     */
    suspend fun ensureMediaProjection(
        context: Context,
        orientation: Int = Configuration.ORIENTATION_UNDEFINED
    ): MediaProjection {
        getAliveMediaProjection()?.let { return it }
        requestScreenCapture(context, orientation)
        return getAliveMediaProjection()
            ?: throw IllegalStateException("未能获得屏幕录制授权，无法内录")
    }

    interface Callback {
        fun onRequestResult(result: Int, data: Intent?)
    }
    class ActivityScreenCaptureRequester(
        private val mMediator: OnActivityResultDelegate.Mediator,
        private val mActivity: Activity
    ) : OnActivityResultDelegate {
        val result = CompletableDeferred<Intent>()
        private var mCallback: Callback? = null
        init {
            mMediator.addDelegate(REQUEST_CODE_MEDIA_PROJECTION, this)
        }


        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            mMediator.removeDelegate(this)
            if (mCallback != null) {
                mCallback!!.onRequestResult(resultCode, data)
            } else if (resultCode == Activity.RESULT_OK) {
                result.complete(data!!)
            } else {
                result.cancel(CancellationException("user cancel"))
            }
        }

        fun cancel() {
            recycle()
            result.cancel()
        }

        suspend fun request(): Intent {
            mActivity.startActivityForResult(
                (mActivity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(),
                REQUEST_CODE_MEDIA_PROJECTION
            )
            val intent = result.await()
            recycle()
            return intent
        }

        fun recycle() {
            mMediator.removeDelegate(this)
        }

        fun setOnActivityResultCallback(callback: Callback?) {
            mCallback = callback
        }

        fun requestLegacy() {
            mActivity.startActivityForResult(
                (mActivity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                    .createScreenCaptureIntent(),
                REQUEST_CODE_MEDIA_PROJECTION
            )
        }

        companion object {
            private const val REQUEST_CODE_MEDIA_PROJECTION = 17777
        }
    }
}
