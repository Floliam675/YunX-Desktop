/*
 * YunX Desktop - AGPL-3.0. Desktop account persistence & per-platform login
 * validation (manual paste of cookie / JWT token replaces Android WebView login).
 */
package com.yunx.desktop.app

import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiDeviceFingerprint
import com.yunx.app.data.network.QuarkConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 网盘平台元信息（桌面 UI 展示 + 登录字段定义） */
enum class DriveId(val label: String, val loginHint: String) {
    QUARK("夸克网盘", "粘贴夸克网盘 Cookie（需含 __puus）"),
    UC("UC网盘", "粘贴 UC 网盘 Cookie"),
    BAIDU("百度网盘", "粘贴百度网盘 Cookie（需含 BDUSS）"),
    C139("139网盘", "粘贴 139/和彩云 Cookie"),
    PAN123("123云盘", "粘贴 123 云盘 JWT（authorToken）"),
    XUNLEI("迅雷网盘", "粘贴迅雷 access_token（可附 refresh_token）")
}

/** 账号记录（六平台统一结构） */
data class DriveAccount(
    val id: String = "",
    val platform: String = "",          // DriveId name
    val nickname: String = "",
    val cookie: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val captchaToken: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isLoggedIn(): Boolean = cookie.isNotBlank() || accessToken.isNotBlank()
}

/**
 * 账号持久化：JSON 文件 + 内存 StateFlow（Room 替换）。
 */
class DriveAccountStore(private val file: File) {

    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<Map<String, DriveAccount>> = _accounts.asStateFlow()

    private fun load(): Map<String, DriveAccount> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    put(id, DriveAccount(
                        id = id,
                        platform = o.optString("platform"),
                        nickname = o.optString("nickname"),
                        cookie = o.optString("cookie"),
                        accessToken = o.optString("accessToken"),
                        refreshToken = o.optString("refreshToken"),
                        deviceId = o.optString("deviceId"),
                        captchaToken = o.optString("captchaToken"),
                        updatedAt = o.optLong("updatedAt")
                    ))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun persist() {
        val arr = JSONArray()
        _accounts.value.values.forEach { a ->
            arr.put(JSONObject()
                .put("id", a.id)
                .put("platform", a.platform)
                .put("nickname", a.nickname)
                .put("cookie", a.cookie)
                .put("accessToken", a.accessToken)
                .put("refreshToken", a.refreshToken)
                .put("deviceId", a.deviceId)
                .put("captchaToken", a.captchaToken)
                .put("updatedAt", a.updatedAt))
        }
        file.parentFile?.mkdirs()
        file.writeText(arr.toString(2), Charsets.UTF_8)
    }

    fun account(id: String): DriveAccount? = _accounts.value[id]

    fun set(account: DriveAccount) {
        _accounts.value = _accounts.value + (account.id to account.copy(updatedAt = System.currentTimeMillis()))
        persist()
    }

    fun remove(id: String) {
        _accounts.value = _accounts.value - id
        persist()
    }
}

/** 登录结果消息 */
data class LoginOutcome(val ok: Boolean, val message: String)

/**
 * 六平台登录校验/保存（Cookie 或 JWT 粘贴）。网络校验失败返回失败原因。
 */
class DriveLogin(private val store: DriveAccountStore) {

    private val quarkApi = QuarkApi()
    private val baiduApi = BaiduApi()
    private val pan123Api = Pan123Api()
    private val xunleiApi = XunleiApi()

    // 迅雷短信登录的中间态（sendsms 返回，smslogin 需要）
    private var xunleiSmsCreditKey = ""
    private var xunleiSmsToken = ""

    fun account(id: String): DriveAccount? = store.account(id)

    suspend fun saveQuark(raw: String): LoginOutcome {
        val cookie = raw.trim()
        if (!QuarkConstants.isValidCookie(cookie)) return LoginOutcome(false, "Cookie 格式不完整（需含有效登录字段）")
        val nickname = quarkApi.fetchNickname(cookie) ?: "夸克用户"
        store.set(DriveAccount(id = "QUARK", platform = DriveId.QUARK.name, nickname = nickname, cookie = cookie))
        quarkApi.cookieSink = { merged ->
            store.account("QUARK")?.let { if (it.cookie != merged) store.set(it.copy(cookie = merged)) }
        }
        return LoginOutcome(true, "夸克登录成功：" + nickname)
    }

    suspend fun saveUC(raw: String): LoginOutcome {
        val cookie = raw.trim()
        if (!UCConstants.isValidCookie(cookie)) return LoginOutcome(false, "Cookie 格式不完整")
        val nickname = com.yunx.app.data.network.UCApi().fetchNickname(cookie) ?: "UC用户"
        store.set(DriveAccount(id = "UC", platform = DriveId.UC.name, nickname = nickname, cookie = cookie))
        return LoginOutcome(true, "UC 登录成功：" + nickname)
    }

    suspend fun saveBaidu(raw: String): LoginOutcome {
        val cookie = raw.trim()
        if (!BaiduConstants.isValidCookie(cookie)) return LoginOutcome(false, "Cookie 格式不完整（需含 BDUSS/STOKEN）")
        val nickname = baiduApi.fetchNickname(cookie) ?: "百度用户"
        store.set(DriveAccount(id = "BAIDU", platform = DriveId.BAIDU.name, nickname = nickname, cookie = cookie))
        return LoginOutcome(true, "百度登录成功：" + nickname)
    }

    suspend fun saveC139(raw: String): LoginOutcome {
        val cookie = raw.trim()
        if (!C139Constants.isValidCookie(cookie)) return LoginOutcome(false, "Cookie 格式不完整")
        val nickname = C139Constants.extractAccount(cookie) ?: "139用户"
        store.set(DriveAccount(
            id = "C139", platform = DriveId.C139.name, nickname = nickname,
            cookie = cookie,
            accessToken = C139Constants.extractAuthorization(cookie).orEmpty()
        ))
        return LoginOutcome(true, "139 登录成功：" + nickname)
    }

    suspend fun savePan123(raw: String): LoginOutcome {
        val token = raw.trim()
        if (token.isBlank()) return LoginOutcome(false, "Token 为空")
        val nickname = pan123Api.fetchNickname(token)
        if (nickname == null) return LoginOutcome(false, "Token 无效或已过期（请重新在网页登录后复制 authorToken）")
        store.set(DriveAccount(id = "PAN123", platform = DriveId.PAN123.name, nickname = nickname, accessToken = token))
        return LoginOutcome(true, "123云盘登录成功：" + nickname)
    }

    /** 迅雷：导入 token（校验 JWT 有效期） */
    suspend fun saveXunleiTokens(access: String, refresh: String): LoginOutcome {
        val accessToken = access.trim()
        if (accessToken.isBlank()) return LoginOutcome(false, "access_token 为空")
        val exp = XunleiApi().jwtExp(accessToken)
        store.set(DriveAccount(
            id = "XUNLEI", platform = DriveId.XUNLEI.name, nickname = "迅雷用户",
            accessToken = accessToken, refreshToken = refresh.trim()
        ))
        val now = System.currentTimeMillis() / 1000
        val hint = when {
            exp <= 0 -> ""
            exp < now -> "（token 已过期，下载时将自动刷新）"
            else -> "（有效期至 " + java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(exp * 1000)) + "）"
        }
        return LoginOutcome(true, "迅雷登录成功：迅雷用户" + hint)
    }

    /** 迅雷 token 刷新后回写（保留其它字段） */
    fun updateXunleiTokens(access: String, refresh: String) {
        val cur = store.account("XUNLEI") ?: return
        store.set(cur.copy(accessToken = access, refreshToken = refresh))
    }

    /** 迅雷：发送短信验证码。返回是否已发出（errorDesc 非"短信已发送"视为失败） */
    suspend fun sendXunleiSms(mobile: String): LoginOutcome {
        val dev = XunleiDeviceFingerprint.deviceId()
        val step = xunleiApi.sendSms(mobile.trim(), dev)
        xunleiSmsCreditKey = step.smsCreditKey
        xunleiSmsToken = step.smsToken
        return if (step.needSms || step.message.contains("短信已发送") || step.message.isBlank()) {
            LoginOutcome(true, "短信已发送至 " + mobile.trim() + "，请查看手机")
        } else {
            LoginOutcome(false, step.message.ifBlank { "发送短信失败" })
        }
    }

    /** 迅雷：短信验证码登录 → 换 token 落库 */
    suspend fun xunleiSmsLogin(mobile: String, smsCode: String): LoginOutcome {
        val dev = XunleiDeviceFingerprint.deviceId()
        val step = xunleiApi.smsLogin(mobile.trim(), smsCode.trim(), xunleiSmsCreditKey, xunleiSmsToken, dev)
        if (step.sessionId.isBlank()) return LoginOutcome(false, step.message.ifBlank { "短信验证失败" })
        // 官方时序：smslogin → captcha/init → signin/token
        val captchaToken = xunleiApi.initCaptcha(dev, mobile.trim()) ?: ""
        val tokens = xunleiApi.exchangeToken(step.sessionId, dev, captchaToken)
        if (tokens == null) return LoginOutcome(false, "换取访问令牌失败，请重试")
        val nickname = step.nickname.ifBlank { "迅雷用户" }
        store.set(DriveAccount(
            id = "XUNLEI", platform = DriveId.XUNLEI.name, nickname = nickname,
            accessToken = tokens.first, refreshToken = tokens.second,
            deviceId = dev, captchaToken = captchaToken
        ))
        return LoginOutcome(true, "迅雷登录成功：" + nickname)
    }

    /** 登录分发：UI 表单 → 平台保存校验 */
    suspend fun save(id: String, values: Map<String, String>): LoginOutcome = when (id) {
        DriveId.QUARK.name -> saveQuark(values["raw"].orEmpty())
        DriveId.UC.name -> saveUC(values["raw"].orEmpty())
        DriveId.BAIDU.name -> saveBaidu(values["raw"].orEmpty())
        DriveId.C139.name -> saveC139(values["raw"].orEmpty())
        DriveId.PAN123.name -> savePan123(values["raw"].orEmpty())
        DriveId.XUNLEI.name -> saveXunleiTokens(values["access"].orEmpty(), values["refresh"].orEmpty())
        else -> LoginOutcome(false, "未知平台")
    }
    fun logout(id: String) = store.remove(id)
}
