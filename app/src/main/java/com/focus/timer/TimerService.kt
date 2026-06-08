package com.focus.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/**
 * 计时前台服务 —— 负责「看不见但一直在跑」的核心逻辑
 *
 * 为什么需要 Service？
 * - Activity 切到后台时可能被系统回收，计时就会停
 * - Service 配合「前台通知」可以告诉系统：我正在干活，别杀我
 *
 * 职责分工：
 * - 维护计时状态（秒数、运行/暂停/完成）
 * - 每秒 +1，到 3600 秒自动停止并响铃震动
 * - 通过广播把最新状态通知给 MainActivity
 * - 在通知栏显示一个低调的运行中提示
 */
class TimerService : Service() {

    /**
     * companion object = 伴生对象，类似 Java 的 static
     * 这里放常量，方便 MainActivity 和 Service 之间约定「暗号」
     */
    companion object {
        const val MAX_SECONDS = 3600  // 最大 60 分钟 = 3600 秒

        // Activity → Service 的指令（用户点了什么按钮）
        const val ACTION_START = "com.focus.timer.ACTION_START"
        const val ACTION_PAUSE = "com.focus.timer.ACTION_PAUSE"
        const val ACTION_RESET = "com.focus.timer.ACTION_RESET"
        const val ACTION_QUERY_STATE = "com.focus.timer.ACTION_QUERY_STATE"

        // Service → Activity 的事件（计时进度变化）
        const val ACTION_TICK = "com.focus.timer.ACTION_TICK"
        const val ACTION_COMPLETED = "com.focus.timer.ACTION_COMPLETED"

        // 广播里携带的数据字段名
        const val EXTRA_ELAPSED_SECONDS = "extra_elapsed_seconds"
        const val EXTRA_IS_RUNNING = "extra_is_running"
        const val EXTRA_IS_COMPLETED = "extra_is_completed"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "focus_timer_channel"
    }

    private val handler = Handler(Looper.getMainLooper())

    // ===== 计时核心状态（整个 APP 的「唯一真相」） =====
    private var elapsedSeconds = 0
    private var isRunning = false
    private var isCompleted = false
    private var ringtone: Ringtone? = null  // 到点铃声，重置时需要停止

    /**
     * 计时心跳任务：每 1 秒执行一次
     * Handler + Runnable 是 Android 里常用的定时方式
     */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || isCompleted) return

            elapsedSeconds++
            if (elapsedSeconds >= MAX_SECONDS) {
                // 到达 60 分钟上限
                elapsedSeconds = MAX_SECONDS
                isRunning = false
                isCompleted = true
                onTimerCompleted()
            } else {
                // 正常跑秒：通知界面 + 更新通知栏
                broadcastTick()
                updateNotification()
                handler.postDelayed(this, 1000L)  // 预约下一秒
            }
        }
    }

    // 本服务不需要绑定模式，返回 null
    override fun onBind(intent: Intent?): IBinder? = null

    /** 服务首次创建：建立通知渠道（Android 8+ 必须） */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * 每次收到启动指令时调用
     * MainActivity 通过 Intent.action 告诉 Service 要做什么
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_RESET -> resetTimer()
            ACTION_QUERY_STATE -> broadcastTick()  // 仅回报当前状态，不改变计时
            else -> {
                // 首次启动：立刻升为前台服务（显示通知），并同步状态
                startForeground(NOTIFICATION_ID, buildNotification(isCompleted))
                broadcastTick()
            }
        }
        // START_STICKY：如果被系统意外杀掉，会尝试自动重启
        return START_STICKY
    }

    /** 服务销毁：清理定时任务和铃声 */
    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        stopAlarm()
        super.onDestroy()
    }

    // ==================== 计时控制 ====================

    /** 开始 / 继续计时 */
    private fun startTimer() {
        if (isCompleted) return  // 已完成后不允许再开始
        if (!isRunning) {
            isRunning = true
            startForeground(NOTIFICATION_ID, buildNotification())
            handler.removeCallbacks(tickRunnable)
            handler.postDelayed(tickRunnable, 1000L)
            broadcastTick()
        }
    }

    /** 暂停计时：冻结当前秒数 */
    private fun pauseTimer() {
        if (isRunning) {
            isRunning = false
            handler.removeCallbacks(tickRunnable)
            broadcastTick()
            updateNotification()
        }
    }

    /** 重置：归零、停止、关闭铃声，回到初始状态 */
    private fun resetTimer() {
        isRunning = false
        isCompleted = false
        elapsedSeconds = 0
        handler.removeCallbacks(tickRunnable)
        stopAlarm()
        broadcastTick()
        updateNotification()
    }

    /** 60 分钟到达：广播完成事件 + 响铃震动 */
    private fun onTimerCompleted() {
        broadcastCompleted()
        playAlarmAndVibrate()
        updateNotification(completed = true)
    }

    // ==================== Activity 通信（广播） ====================

    /** 每秒或状态变化时，广播当前进度给 MainActivity */
    private fun broadcastTick() {
        sendBroadcast(
            Intent(ACTION_TICK).apply {
                setPackage(packageName)  // 限制只有本 APP 能收到，更安全
                putExtra(EXTRA_ELAPSED_SECONDS, elapsedSeconds)
                putExtra(EXTRA_IS_RUNNING, isRunning)
                putExtra(EXTRA_IS_COMPLETED, isCompleted)
            }
        )
    }

    /** 计时完成专用广播 */
    private fun broadcastCompleted() {
        sendBroadcast(
            Intent(ACTION_COMPLETED).apply {
                setPackage(packageName)
                putExtra(EXTRA_ELAPSED_SECONDS, elapsedSeconds)
                putExtra(EXTRA_IS_RUNNING, false)
                putExtra(EXTRA_IS_COMPLETED, true)
            }
        )
    }

    // ==================== 到点提醒 ====================

    /**
     * 播放系统内置闹钟铃声 + 震动
     * 不需要外部音频文件，直接用 RingtoneManager 获取系统默认音
     */
    private fun playAlarmAndVibrate() {
        // 优先用闹钟音，没有则降级为通知音或铃声
        val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        ringtone = RingtoneManager.getRingtone(this, alarmUri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            play()
        }

        // 获取震动器（兼容不同 Android 版本）
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }

        // 震动模式：等0ms → 震800ms → 停300ms → 震800ms ... 循环
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 800, 300, 800, 300, 800, 300, 800),
                        -1  // -1 表示不重复（震完就停）
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(longArrayOf(0, 800, 300, 800, 300, 800, 300, 800), -1)
            }
        }
    }

    /** 停止铃声和震动（重置时调用） */
    private fun stopAlarm() {
        ringtone?.stop()
        ringtone = null
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.cancel()
    }

    // ==================== 前台通知 ====================

    /** 创建通知渠道（Android 8+ 发通知前必须先建渠道） */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // 低优先级，不发出提示音
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /** 构建前台服务通知（让用户知道 APP 在后台计时） */
    private fun buildNotification(completed: Boolean = false): Notification {
        // 点击通知回到主界面
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val elapsedText = formatTime(elapsedSeconds)
        val contentText = if (completed) {
            getString(R.string.notification_completed)
        } else {
            getString(R.string.notification_text, elapsedText)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launchIntent)
            .setOngoing(!completed)  // 计时中不可滑动删除
            .setSilent(true)
            .build()
    }

    /** 刷新通知栏上的计时进度 */
    private fun updateNotification(completed: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(completed))
    }

    /** 秒数 → "00:00:00" 格式 */
    private fun formatTime(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}
