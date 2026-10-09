package com.shilapi.xcertplay.hud

/**
 * 独立 HUD 演示用的固定导航数据包生成器。
 *
 * 说明：
 * - 仅用于 debug 构建。
 * - 生成的数据包格式与经过逆向分析的比亚迪 IVI HAL（硬件抽象层）导航接口完全匹配。
 * - 数据包以逗号分隔的十六进制字符串形式返回，供广播 Intent 使用。
 */
internal object StandaloneHudPackets {

    /**
     * 生成「开始导航显示」指令包。
     * 对应原厂协议中的启动命令。
     */
    fun start(): String = "43,E0,00,3A,01,02"

    /**
     * 生成「清除导航显示」指令包。
     * 用于结束演示后清空 HUD 上的引导信息。
     */
    fun clear(): String = "43,E0,00,3A,01,01"

    /**
     * 生成导航引导数据包（转向 + 距离）。
     *
     * @param turn 转向类型：
     *             1 = 左转
     *             2 = 右转
     *             11 = 直行
     * @param distanceMeters 距离（米），有效范围 0~9999
     * @return 逗号分隔的十六进制字符串，可直接放入广播 Intent
     *
     * 内部会生成三条 record：
     * - 0x43F01018 : 距离信息
     * - 0x43F01010 : 转向类型
     * - 0x43F01030 : 再次携带转向类型（原厂协议要求）
     */
    fun guidance(turn: Int, distanceMeters: Int): String {
        require(turn in setOf(1, 2, 11)) { "Demo permits only left, right and straight" }
        require(distanceMeters in 0..9999) { "Invalid demo distance" }
        return (record(0x43F01018, distanceMeters) + record(0x43F01010, turn) +
                record(0x43F01030, turn)).joinToString(",") { "%02X".format(it.toInt() and 255) }
    }

    /**
     * 将「功能 ID + 4 字节整数值」编码为 9 字节的协议记录。
     *
     * 格式：
     * [feature 高字节] [feature 次高] [feature 次低] [feature 低字节] [长度=4]
     * [value 高字节] [value 次高] [value 次低] [value 低字节]
     *
     * @param feature 32 位功能标识（大端）
     * @param value   要携带的整数值（大端）
     */
    private fun record(feature: Int, value: Int): ByteArray = byteArrayOf(
        (feature ushr 24).toByte(), (feature ushr 16).toByte(),
        (feature ushr 8).toByte(), feature.toByte(), 4,
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte(),
    )
}
