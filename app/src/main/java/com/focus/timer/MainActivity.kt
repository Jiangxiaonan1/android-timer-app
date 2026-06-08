package com.focus.timer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.focus.timer.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面 Activity —— 负责「看得见、摸得着」的部分
 *
 * 职责分工：
 * - 显示全屏屏保 UI（黑底、双时钟、三个按钮）
 * - 每秒刷新手机当前系统时间
 * - 把用户的按钮操作转发给 TimerService
 * - 接收 Service 广播，更新计时时长显示
 *
 * 不负责：真正的计时逻辑（交给 TimerService 在后台跑）
 */
class MainActivity : AppCompatActivity() {

    // ViewBinding：把 activity_main.xml 里的控件绑定成代码变量，避免 findViewById
    private lateinit var binding: ActivityMainBinding

    // 主线程定时器，用于每秒刷新「系统时间」
    private val handler = Handler(Looper.getMainLooper())

    // 以下三个变量是界面侧的「镜像状态」，数据源头在 TimerService
    private var elapsedSeconds = 0   // 已计秒数
    private var isRunning = false    // 是否正在计时
    private var isCompleted = false  // 是否已跑满 60 分钟

    // 系统时间格式化器，输出如 14:30:25
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Android 13+ 需要动态申请「通知权限」，否则前台服务通知可能不显示
    // 注意：即使用户拒绝，计时功能仍然可用
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 无论是否授权，计时功能均可使用 */ }

    /**
     * 广播接收器：监听 TimerService 发来的计时更新
     *
     * 通信方式类比：Service 是后厨做菜，Activity 是服务员
     * Service 每过一秒就「广播」一次当前进度，Activity 收到后刷新屏幕
     */
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TimerService.ACTION_TICK, TimerService.ACTION_COMPLETED -> {
                    // 从广播 Intent 中取出 Service 传来的数据
                    elapsedSeconds = intent.getIntExtra(TimerService.EXTRA_ELAPSED_SECONDS, 0)
                    isRunning = intent.getBooleanExtra(TimerService.EXTRA_IS_RUNNING, false)
                    isCompleted = intent.getBooleanExtra(TimerService.EXTRA_IS_COMPLETED, false)
                    updateElapsedDisplay()
                    updateButtonStates()
                }
            }
        }
    }

    /**
     * 系统时钟刷新任务：每 1000ms 更新一次上方「当前时间」
     * 这个只在 Activity 里跑，不需要放 Service（跟专注计时无关）
     */
    private val systemClockRunnable = object : Runnable {
        override fun run() {
            binding.tvSystemTime.text = timeFormat.format(Date())
            handler.postDelayed(this, 1000L)  // 1 秒后再次执行自己
        }
    }

    /**
     * Activity 创建入口（打开 APP 时第一个执行）
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullscreen()                        // 全屏沉浸式
        setupScreenOn()                          // 屏幕常亮
        requestNotificationPermissionIfNeeded()  // 申请通知权限
        setupButtons()                           // 绑定按钮点击
        updateButtonStates()                     // 初始化按钮可用状态
        startTimerService()                      // 启动后台计时服务
    }

    /**
     * Activity 变为可见时调用（包括首次打开、从后台切回）
     */
    override fun onStart() {
        super.onStart()
        // 注册广播接收器，开始监听 Service 的计时更新
        val filter = IntentFilter().apply {
            addAction(TimerService.ACTION_TICK)
            addAction(TimerService.ACTION_COMPLETED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tickReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(tickReceiver, filter)
        }
        handler.post(systemClockRunnable)  // 开始刷新系统时间
        // 向 Service 询问当前状态（解决从后台切回时界面不同步的问题）
        sendServiceAction(TimerService.ACTION_QUERY_STATE)
    }

    /**
     * Activity 不可见时调用（切到后台、锁屏等）
     * 记得注销接收器和定时任务，避免内存泄漏
     */
    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(systemClockRunnable)
        unregisterReceiver(tickReceiver)
    }

    /**
     * 窗口重新获得焦点时，再次隐藏状态栏和导航栏
     * 防止用户滑出系统栏后无法自动收回
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    /** 配置全屏沉浸式模式 */
    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
    }

    /** 隐藏状态栏 + 导航栏，实现真正全屏屏保效果 */
    private fun hideSystemBars() {
        // 新版 API（Android 11+）
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // 旧版 API 兼容（Android 10）
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    /** 屏幕常亮，计时过程中不会自动息屏 */
    private fun setupScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Android 13+ 动态申请通知权限 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** 绑定三个按钮的点击事件，点击后发送指令给 TimerService */
    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            if (!isCompleted) {  // 已完成后不能再开始
                sendServiceAction(TimerService.ACTION_START)
            }
        }
        binding.btnPause.setOnClickListener {
            sendServiceAction(TimerService.ACTION_PAUSE)
        }
        binding.btnReset.setOnClickListener {
            sendServiceAction(TimerService.ACTION_RESET)
        }
    }

    /**
     * 启动前台服务
     * 使用 startForegroundService 而非普通 startService，
     * 确保 Android 8+ 系统允许服务在后台持续运行
     */
    private fun startTimerService() {
        val intent = Intent(this, TimerService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    /**
     * 向 TimerService 发送操作指令
     * 通过 Intent 的 action 字段区分：开始 / 暂停 / 重置 / 查询状态
     */
    private fun sendServiceAction(action: String) {
        val intent = Intent(this, TimerService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    /** 把秒数格式化为 HH:MM:SS 显示在中间大时钟上 */
    private fun updateElapsedDisplay() {
        binding.tvElapsedTime.text = formatTime(elapsedSeconds)
    }

    /**
     * 根据当前状态控制按钮是否可点
     * - 计时中：只能暂停，不能开始
     * - 已完成：不能开始
     * - 有进度或已完成：可以重置
     */
    private fun updateButtonStates() {
        binding.btnStart.isEnabled = !isRunning && !isCompleted
        binding.btnPause.isEnabled = isRunning
        binding.btnReset.isEnabled = elapsedSeconds > 0 || isRunning || isCompleted
    }

    /** 秒数 → "00:00:00" 格式字符串 */
    private fun formatTime(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
