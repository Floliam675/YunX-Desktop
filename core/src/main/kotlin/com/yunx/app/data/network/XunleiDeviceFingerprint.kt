/*
 * YunX Desktop - AGPL-3.0. Ported from CYQawa/YunX (Android) XunleiDeviceFingerprint.kt.
 * Desktop adaptation: SharedPreferences persistence replaced by a local properties file.
 */

package com.yunx.app.data.network

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import kotlin.random.Random

/**
 * 迅雷设备指纹管理器（桌面版）：逻辑与原 Android 版完全一致（§8 公式）：
 * - 每台设备首次启动生成唯一 deviceId/peerId/devicesign，此后永久复用（进程重启不变）；
 * - devicesign 按 §8 公式：div101.{deviceId}{md5(sha1(deviceId + package + appid + appkey))}；
 * - 未初始化（异常路径）时回退到 XunleiConstants 官方抓包指纹，保证行为不崩。
 */
object XunleiDeviceFingerprint {

    private const val KEY_ID = "device_id"
    private const val KEY_PEER = "peer_id"
    private const val KEY_SIGN = "device_sign"

    // devicesign 计算常量（与文档 §8 / alist 一致）
    private const val PACKAGE_NAME = "com.xunlei.downloadprovider"
    private const val APPID = "40"
    private const val APP_KEY = "34a062aaa22f906fca4fefe9fb3a3021"
    private const val HEX = "0123456789abcdef"

    @Volatile
    private var initialized = false

    @Volatile
    private var prefsFile: File? = null

    // 未初始化时的 fallback：官方抓包真实设备（保持旧行为，绝不崩）
    @Volatile
    private var deviceId: String = XunleiConstants.DEVICE_ID
    @Volatile
    private var peerId: String = XunleiConstants.PEER_ID
    @Volatile
    private var deviceSign: String = XunleiConstants.DEVICE_SIGN

    /** 进程启动时调用一次（桌面入口初始化）；幂等，可重复调用 */
    fun init(file: File) {
        prefsFile = file
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching {
                val props = Properties()
                if (file.exists()) file.inputStream().use { props.load(it) }
                val savedId = props.getProperty(KEY_ID)
                if (savedId != null) {
                    deviceId = savedId
                    peerId = props.getProperty(KEY_PEER, XunleiConstants.PEER_ID)
                    deviceSign = props.getProperty(KEY_SIGN, XunleiConstants.DEVICE_SIGN)
                } else {
                    // 首次启动：生成唯一设备指纹并持久化
                    val newId = randomHex(32)
                    val newPeer = randomHex(32)
                    val newSign = buildDeviceSign(newId)
                    file.parentFile?.mkdirs()
                    props.setProperty(KEY_ID, newId)
                    props.setProperty(KEY_PEER, newPeer)
                    props.setProperty(KEY_SIGN, newSign)
                    file.outputStream().use { props.store(it, "yunx xunlei device fingerprint") }
                    deviceId = newId
                    peerId = newPeer
                    deviceSign = newSign
                }
                initialized = true
            }
        }
    }

    fun deviceId(): String = deviceId

    fun peerId(): String = peerId

    fun deviceSign(): String = deviceSign

    /** devicesign：div101.{deviceId}{md5(sha1(deviceId + package_name + appid + app_key))} */
    private fun buildDeviceSign(id: String): String {
        val base = id + PACKAGE_NAME + APPID + APP_KEY
        val sha1 = sha1Hex(base)
        val md5 = md5Hex(sha1)
        return "div101.$id$md5"
    }

    private fun randomHex(len: Int): String = buildString {
        repeat(len) { append(HEX[Random.nextInt(16)]) }
    }

    private fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
