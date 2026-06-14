package com.focus.timer

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.IOException

/**
 * 屏保背景轮播：从 assets/backgrounds/ 读取图片，双 ImageView 交叉淡入淡出。
 *
 * ── 手动调整入口（改下面 companion object 里的常量即可）──
 * · INTERVAL_MS  每张图停留多久再切下一张
 * · FADE_MS      两张图之间淡入淡出动画时长
 * · ASSETS_DIR   图片文件夹（相对 assets 目录）
 */
class BackgroundSlideshow(
    private val currentView: ImageView,
    private val nextView: ImageView,
    assets: AssetManager
) {
    companion object {

        // ===== 可调参数：背景轮播 =====

        /** 背景图片所在目录，对应 app/src/main/assets/backgrounds/ */
        private const val ASSETS_DIR = "backgrounds"

        /**
         * 【常改】每张图片停留时间（毫秒）
         * 1000 = 1 秒，16000 = 16 秒，30000 = 30 秒
         */
        private const val INTERVAL_MS = 16000L

        /**
         * 【常改】切换下一张时的淡入淡出动画时长（毫秒）
         * 建议小于 INTERVAL_MS，否则一张图大部分时间都在渐变
         */
        private const val FADE_MS = 1500L

        /** 渐变动画每帧间隔（毫秒），约 60fps，一般不用改 */
        private const val FRAME_MS = 16L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val imagePaths: List<String> = loadImagePaths(assets)
    private var currentIndex = 0
    private var running = false

    private val advanceRunnable = Runnable {
        if (running && imagePaths.size > 1) {
            showNext()
        }
        scheduleAdvance()
    }

    init {
        if (imagePaths.isNotEmpty()) {
            currentView.setImageBitmap(decodeBitmap(assets, imagePaths[0]))
            currentView.alpha = 1f
            nextView.alpha = 0f
        }
    }

    fun start() {
        if (imagePaths.isEmpty()) return
        if (!running) {
            running = true
            scheduleAdvance()
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacksAndMessages(null)
    }

    fun release() {
        stop()
        recycleBitmap(currentView)
        recycleBitmap(nextView)
    }

    private fun recycleBitmap(imageView: ImageView) {
        val bitmap = (imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        imageView.setImageDrawable(null)
        bitmap?.recycle()
    }

    private fun scheduleAdvance() {
        if (!running || imagePaths.size <= 1) return
        handler.removeCallbacks(advanceRunnable)
        handler.postDelayed(advanceRunnable, INTERVAL_MS)
    }

    private fun showNext() {
        val assets = currentView.context.assets
        val nextIndex = (currentIndex + 1) % imagePaths.size
        val bitmap = decodeBitmap(assets, imagePaths[nextIndex]) ?: return

        nextView.setImageBitmap(bitmap)
        nextView.alpha = 0f

        val oldBitmap = (currentView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap

        fadeCross(currentView, nextView) {
            oldBitmap?.takeIf { it !== bitmap }?.recycle()
            currentIndex = nextIndex
            currentView.setImageBitmap(bitmap)
            currentView.alpha = 1f
            nextView.alpha = 0f
            nextView.setImageDrawable(null)
        }
    }

    private fun fadeCross(from: ImageView, to: ImageView, onEnd: () -> Unit) {
        val steps = (FADE_MS / FRAME_MS).toInt().coerceAtLeast(1)
        var step = 0
        val fadeRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                step++
                val progress = step.toFloat() / steps
                to.alpha = progress
                from.alpha = 1f - progress
                if (step < steps) {
                    handler.postDelayed(this, FRAME_MS)
                } else {
                    onEnd()
                }
            }
        }
        handler.post(fadeRunnable)
    }

    private fun decodeBitmap(assets: AssetManager, path: String): Bitmap? {
        return try {
            assets.open(path).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun loadImagePaths(assets: AssetManager): List<String> {
        return try {
            assets.list(ASSETS_DIR)
                ?.filter { it.endsWith(".jpg", ignoreCase = true) || it.endsWith(".png", ignoreCase = true) }
                ?.sorted()
                ?.map { "$ASSETS_DIR/$it" }
                ?: emptyList()
        } catch (_: IOException) {
            emptyList()
        }
    }
}
