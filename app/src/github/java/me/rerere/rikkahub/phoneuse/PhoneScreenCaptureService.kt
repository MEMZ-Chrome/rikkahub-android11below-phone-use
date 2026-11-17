package me.rerere.rikkahub.phoneuse

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.PHONE_SCREEN_CAPTURE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import java.io.ByteArrayOutputStream

private const val TAG = "PhoneScreenCapture"
private const val NOTIFICATION_ID = 2005
private const val JPEG_QUALITY = 80
private const val FIRST_FRAME_TIMEOUT_MS = 2000L
private const val FRAME_POLL_MS = 50L

/**
 * Android 11 以下没有 [android.accessibilityservice.AccessibilityService.takeScreenshot]，
 * 只能让用户授权录屏，再从镜像出来的画面里取帧。
 *
 * 录屏期间状态栏一直挂着系统提示，所以只在用户手动开启时运行；系统也要求（API 29 起）
 * 先进入 mediaProjection 类型的前台服务，才允许调用 [MediaProjectionManager.getMediaProjection]。
 *
 * 已知限制：镜像尺寸在开启时定下，之后旋转屏幕不会重建，画面会被拉伸；关掉再开会按新方向重建。
 */
class PhoneScreenCaptureService : Service() {
    companion object {
        private const val ACTION_START = "me.rerere.rikkahub.action.PHONE_SCREEN_CAPTURE_START"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.PHONE_SCREEN_CAPTURE_STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** 录屏开始和结束时通知 [PhoneUseManager]，设置页的开关和工具都看它 */
        @Volatile
        var stateListener: ((Boolean) -> Unit)? = null

        @Volatile
        private var instance: PhoneScreenCaptureService? = null

        fun start(context: Context, resultCode: Int, data: Intent): Boolean = runCatching {
            val intent = Intent(context, PhoneScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
            true
        }.onFailure {
            Log.e(TAG, "Unable to start screen capture", it)
        }.getOrDefault(false)

        fun stop(context: Context) {
            val intent = Intent(context, PhoneScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.e(TAG, "Unable to stop screen capture", it) }
        }

        /** 最新一帧的 JPEG，还没收到画面时返回 null */
        suspend fun capture(maxSide: Int): ByteArray? = instance?.capture(maxSide)
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var releasing = false

    // 一直是同一帧给多个调用方用会互相 recycle，读写都加锁
    private val frameLock = Any()
    private var latestFrame: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data == null) stopSelf() else startCapture(resultCode, data)
            }

            ACTION_STOP -> stopCapture()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (projection != null) return
        instance = this
        releasing = false
        startForegroundCompat()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching { manager?.getMediaProjection(resultCode, data) }.getOrNull()
        if (mediaProjection == null) {
            Log.e(TAG, "Screen capture permission was not granted")
            stopSelf()
            return
        }

        val thread = HandlerThread("phone-screen-capture").apply { start() }
        val handler = Handler(thread.looper)
        frameThread = thread
        projection = mediaProjection

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            // 用户从系统界面停掉录屏、或者系统回收时走到这里
            override fun onStop() {
                handler.post { stopCapture() }
            }
        }, handler)

        val size = realScreenSize()
        val reader = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ onFrame(it) }, handler)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "rikkahub-screen-capture",
            size.x,
            size.y,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
        stateListener?.invoke(true)
    }

    private fun stopCapture() {
        if (releasing) return
        releasing = true
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.let { runCatching { it.stop() } }
        projection = null
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
        frameThread?.quitSafely()
        frameThread = null
        if (instance === this) instance = null
        stateListener?.invoke(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onFrame(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            // 每行末尾可能有对齐用的空白，先按带空白行的宽度建位图再裁掉
            val paddedWidth = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val frame = if (paddedWidth != image.width) {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            } else {
                padded
            }
            synchronized(frameLock) {
                latestFrame?.recycle()
                latestFrame = frame
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read a screen frame", e)
        } finally {
            image.close()
        }
    }

    /** 取一份最新画面的拷贝，调用方负责 recycle */
    private fun copyFrame(): Bitmap? = synchronized(frameLock) {
        latestFrame?.copy(Bitmap.Config.ARGB_8888, false)
    }

    private suspend fun capture(maxSide: Int): ByteArray? = withContext(Dispatchers.Default) {
        val frame = awaitFrame() ?: return@withContext null
        try {
            val ratio = maxSide.toFloat() / maxOf(frame.width, frame.height)
            val scaled = if (ratio < 1f) {
                frame.scale((frame.width * ratio).toInt(), (frame.height * ratio).toInt())
            } else {
                frame
            }
            try {
                ByteArrayOutputStream()
                    .also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    .toByteArray()
            } finally {
                if (scaled !== frame) scaled.recycle()
            }
        } finally {
            frame.recycle()
        }
    }

    /** 刚授权时第一帧还没到，等一下；之后基本都能立刻拿到 */
    private suspend fun awaitFrame(): Bitmap? {
        val deadline = SystemClock.uptimeMillis() + FIRST_FRAME_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            copyFrame()?.let { return it }
            delay(FRAME_POLL_MS)
        }
        return copyFrame()
    }

    private fun realScreenSize(): Point {
        val windowManager = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        return Point().also { display.getRealSize(it) }
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, PHONE_SCREEN_CAPTURE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.phone_use_screen_capture_running))
            .setContentText(getString(R.string.phone_use_screen_capture_running_desc))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, RouteActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
