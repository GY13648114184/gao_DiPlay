package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 临时测试桥接广播接收器。
 *
 * 功能说明：
 * - 仅用于 debug 构建的临时测试场景。
 * - 只有拥有 DUMP 权限的 shell 才能配置此桥接（通过系统广播触发）。
 * - 提供两个动作：
 *   1. byd.hud.STARTER_CONFIG : 配置 Starter 桥接的 token
 *   2. byd.hud.STARTER_DEMO   : 触发一次演示
 *
 * 注意：正式版本不会包含此接收器，仅作为开发/调试辅助。
 */
class StarterBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 配置桥接 token（需要 shell DUMP 权限才能发送此广播）
            "byd.hud.STARTER_CONFIG" -> {
                val token = intent.getStringExtra("token") ?: return
                BydStarterBridge.configure(context, token)
                resultData = "Starter configured"
            }
            // 触发一次演示流程
            "byd.hud.STARTER_DEMO" -> BydStarterBridge.demonstrate(context)
        }
    }
}
