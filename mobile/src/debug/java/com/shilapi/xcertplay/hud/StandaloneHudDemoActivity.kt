package com.shilapi.xcertplay.hud

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

/**
 * 独立 HUD（抬头显示）演示 Activity。
 *
 * 功能说明：
 * - 这是一个**停放状态下**的有限探测工具，用于测试比亚迪车机原生 HUD 的导航引导广播接口。
 * - 不依赖 shell、socket、SDK 特权或任何辅助进程，仅通过系统广播与原厂接收器通信。
 * - 仅限 debug 构建使用，且对固件指纹、签名、包名有严格校验，防止在非目标车机上误运行。
 *
 * 演示流程：
 * 1. 前 8 秒发送左转 500 米引导（Muscat Road）
 * 2. 后 8 秒发送右转 800 米引导（Sultan Qaboos Street）
 * 3. 总计 16 秒后自动清除 HUD 显示
 */
class StandaloneHudDemoActivity : Activity() {

    /** 主线程 Handler，用于定时发送引导包 */
    private val handler = Handler(Looper.getMainLooper())

    /** 状态显示文本控件 */
    private lateinit var status: TextView

    /** 当前是否正在向 HUD 显示内容（用于决定是否需要发送清除指令） */
    private var showing = false

    /** 演示开始的系统启动时间（elapsedRealtime） */
    private var started = 0L

    /** 上一次发送的转向类型，用于避免重复日志 */
    private var lastTurn = 0

    /** 演示是否正在运行 */
    private var running = false

    /**
     * 目标原厂广播接收器组件。
     * 包名：com.byd.clusterdebug
     * 类名：com.byd.clusterdebug.BroadcastReceiverCAN
     * 这是比亚迪车机用于接收导航引导信息并显示到 HUD 的系统组件。
     */
    private val target = ComponentName("com.byd.clusterdebug", "com.byd.clusterdebug.BroadcastReceiverCAN")

    /**
     * 定时任务：每秒发送一次当前阶段的导航引导数据。
     * 前 8 秒左转，后 8 秒右转，16 秒后自动结束。
     */
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val elapsed = SystemClock.elapsedRealtime() - started
                // 超过 16 秒则结束演示并清除 HUD
                if (elapsed >= 16_000) {
                    clear("demo complete")
                    return
                }
                // 前 8 秒为左转（turn=1），后 8 秒为右转（turn=2）
                val turn = if (elapsed < 8_000) 1 else 2
                val distance = if (turn == 1) 500 else 800
                val road = if (turn == 1) "Muscat Road" else "Sultan Qaboos Street"

                // 发送引导数据包（调用 BydStandalonePackets 生成协议内容）
                transmit(BydStandalonePackets.guidance(if (turn == 1) 2 else 3, 0, distance, road)!!)

                // 仅在转向类型变化时打印日志，减少日志噪音
                if (turn != lastTurn) {
                    Log.i(TAG, "APP_GUIDANCE uid=${Process.myUid()} turn=$turn distance=$distance")
                }
                lastTurn = turn

                // 更新界面显示当前引导信息
                status.text = (if (turn == 1) "LEFT — 500 m" else "RIGHT — 800 m") + "\n" + road

                // 1 秒后再次执行
                handler.postDelayed(this, 1_000)
            } catch (error: Exception) {
                Log.e(TAG, "Demo failed", error)
                clear("send failed")
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        // 创建状态文本
        status = TextView(this).apply {
            textSize = 32f
            text = "Standalone HUD test ready"
        }

        // 构建简单垂直布局：状态 + 说明 + 开始按钮 + 清除按钮
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            addView(status)

            // 演示说明文字
            addView(TextView(this@StandaloneHudDemoActivity).apply {
                text = "Parked test: Muscat Road / left 500 m, then Sultan Qaboos Street / right 800 m; 8 seconds each, then clear."
                textSize = 22f
            })

            // 「开始 16 秒测试」按钮
            addView(Button(this@StandaloneHudDemoActivity).apply {
                text = "Start 16-second test"
                setOnClickListener { startDemo() }
            })

            // 「清除 HUD」按钮（手动发送清除指令）
            addView(Button(this@StandaloneHudDemoActivity).apply {
                text = "Clear HUD"
                setOnClickListener {
                    try {
                        validateTarget()
                        showing = true
                        clear("button")
                    } catch (error: Exception) {
                        status.text = "Clear unavailable: ${error.message}"
                    }
                }
            })
        })

        // 启动时进行预检：验证目标接收器是否符合预期固件
        try {
            validateTarget()
            Log.i(TAG, "PREFLIGHT_OK uid=${Process.myUid()} stock receiver verified")
            // 如果 Intent 带有 run=true 且是首次创建，则自动开始演示
            if (intent.getBooleanExtra("run", false) && state == null) {
                handler.post { startDemo() }
            }
        } catch (error: Exception) {
            status.text = "Test unavailable: ${error.message}"
            Log.e(TAG, "Preflight failed", error)
        }
    }

    /**
     * 严格校验当前运行环境是否为目标测试固件。
     *
     * 校验项包括：
     * 1. Android 版本必须 >= P (API 28)
     * 2. 包名必须是专用测试包 com.shihab.diplay.hudtest，且 UID >= 10000（非系统应用）
     * 3. 设备指纹必须完全匹配指定的比亚迪 IVI 固件
     * 4. 目标接收器包版本号、系统标志、签名证书 SHA-256 必须一致
     * 5. 接收器必须启用、导出且无权限要求
     *
     * 任一条件不满足都会抛出异常，阻止演示运行。
     */
    private fun validateTarget() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            error("This test is restricted to the inspected firmware")
        }
        check(packageName == "com.shihab.diplay.hudtest" && Process.myUid() >= 10000)
        check(Build.FINGERPRINT == "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys") {
            "This test is restricted to the inspected firmware"
        }

        val info = packageManager.getPackageInfo(target.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(info.longVersionCode == 10601004L) { "Different stock receiver version" }
        check(info.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM != 0)

        // 验证签名证书的 SHA-256 指纹
        val certs = info.signingInfo!!.apkContentsSigners
        check(
            certs.size == 1 && MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) } ==
                "efe3ca8ada0d10c655c3df9910ad2ebc121a47d9a6358434eb24074309933efc"
        )

        val receiver = packageManager.getReceiverInfo(target, 0)
        check(receiver.enabled && receiver.exported && receiver.permission.isNullOrEmpty())
    }

    /**
     * 启动 16 秒演示流程。
     * 1. 再次校验环境
     * 2. 开启诊断保持（防止其他逻辑干扰）
     * 3. 发送「开始」数据包
     * 4. 启动定时任务
     */
    private fun startDemo() {
        if (running) return
        try {
            validateTarget()

            BydNavigationOutputs.setDiagnosticHold(true)
            showing = true // 即使后续操作失败也保留清除逻辑
            transmit(StandaloneHudPackets.start())
            started = SystemClock.elapsedRealtime()
            running = true
            lastTurn = 0
            Log.i(TAG, "APP_START uid=${Process.myUid()} helper=none")
            // 延迟 250ms 后开始第一轮引导发送，后续由 tick 自己调度
            handler.postDelayed(tick, 250)
        } catch (error: Exception) {
            Log.e(TAG, "Standalone preflight/start failed", error)
            status.text = "Test unavailable: ${error.message}"
            clear("start failed")
        }
    }

    /**
     * 向原厂 HUD 接收器发送广播数据包。
     *
     * @param packet 协议字符串（逗号分隔的十六进制字节）
     */
    private fun transmit(packet: String) {
        sendBroadcast(
            Intent("byd.hud.NAVIGATION_DEMO")
                .setComponent(target)
                .putExtra("normal", packet)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        )
    }

    /**
     * 停止演示并发送清除指令。
     *
     * @param reason 清除原因（用于日志记录）
     */
    private fun clear(reason: String) {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (showing) {
            try {
                transmit(StandaloneHudPackets.clear())
                // 广播发送成功不代表硬件已确认清除
                Log.i(TAG, "APP_CLEAR_SENT uid=${Process.myUid()} reason=$reason")
                showing = false
                status.text = "Clear sent — check windshield"
            } catch (error: Exception) {
                Log.e(TAG, "Clear failed; retry with Clear HUD", error)
                status.text = "Clear failed — tap Clear HUD"
            }
        }
    }

    /** Activity 停止时自动清除 HUD，并释放诊断保持 */
    override fun onStop() {
        clear("activity stopped")
        BydNavigationOutputs.setDiagnosticHold(false)
        super.onStop()
    }

    /** Activity 销毁时再次确保清除 */
    override fun onDestroy() {
        clear("activity destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BYD-Standalone"
    }
}
