package com.altair.probe

import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * 集控（多设备统一管理）—— 设备端
 * ================================
 *
 * ## 架构约束：为什么是「设备主动上报」而不是「服务器推送」
 * 云手机跑在机房 NAT 后面，**没有公网 IP，服务器无法主动连它**。
 * 所以只能反过来：设备定时**上报状态** + 定时**拉取配置**。
 * 这也顺带解决了防火墙问题 —— 设备侧只需要能出网。
 *
 * ```
 *   控制端（你的服务器）                     设备端（云手机 APK）
 *   ┌──────────────────────┐               ┌──────────────────────┐
 *   │ GET  /api/config     │ ◀── 定时拉取 ── │ ConfigPuller         │
 *   │ POST /api/report     │ ◀── 定时上报 ── │ StateReporter        │
 *   │ Web 面板             │                │ 告警 → 钉钉/飞书      │
 *   └──────────────────────┘               └──────────────────────┘
 * ```
 *
 * ## 协议
 * 上报（设备 → 服务器）  POST {server}/api/report
 * ```json
 * {
 *   "deviceId": "a1b2c3d4", "ts": 1699999999999,
 *   "versionCode": 18, "versionName": "0.18.0",
 *   "targetPkg": "com.nexon.mod", "foreground": "com.nexon.mod",
 *   "armed": true, "uptimeMs": 123456,
 *   "logTail": ["...", "..."]
 * }
 * ```
 *
 * 配置（服务器 → 设备）  GET {server}/api/config?deviceId=xxx
 * ```json
 * {
 *   "revision": "r7",
 *   "targetPkg": "com.nexon.mod",
 *   "pressMs": 90,
 *   "skillPoints": [[0.7416,0.5673],[0.8041,0.5756]],
 *   "notes": "方案A"
 * }
 * ```
 * **只有 revision 变化时才会应用并热重载** —— 避免每轮都重写配置。
 */
class Management(private val ctx: Context, private val sh: RootShell) {

    companion object {
        private const val PREF = "mgmt"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SERVER = "server"
        private const val KEY_INTERVAL = "intervalMs"
        private const val KEY_DEVICE = "deviceId"
        private const val KEY_TOKEN = "deviceToken"
        private const val KEY_REVISION = "appliedRevision"
        const val DEFAULT_INTERVAL_MS = 60_000L
        const val CONFIG_FILE = "pulled_config.json"
    }

    private fun sp() = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 配置项

    var enabled: Boolean
        get() = sp().getBoolean(KEY_ENABLED, false)
        set(v) = sp().edit().putBoolean(KEY_ENABLED, v).apply()

    /** 服务器基地址，例如 http://1.2.3.4:8080 （不带结尾斜杠） */
    var server: String
        get() = sp().getString(KEY_SERVER, "") ?: ""
        set(v) = sp().edit().putString(KEY_SERVER, v.trim().trimEnd('/')).apply()

    var intervalMs: Long
        get() = sp().getLong(KEY_INTERVAL, DEFAULT_INTERVAL_MS)
        set(v) = sp().edit().putLong(KEY_INTERVAL, v.coerceAtLeast(15_000L)).apply()

    /**
     * 设备 Token。
     *
     * 与面板密码**互相独立** —— 服务端生成的两套密钥各管一边：
     *   · 面板密码 → 人用，HTTP Basic Auth
     *   · 设备 Token → 本 App 用，放 X-Altair-Token 头
     * 这样 APK 里的 token 万一泄漏，也不会连带泄漏面板密码。
     * Token 在集控面板页面上直接可以看到并复制。
     */
    var token: String
        get() = sp().getString(KEY_TOKEN, "") ?: ""
        set(v) = sp().edit().putString(KEY_TOKEN, v.trim()).apply()

    /** 设备唯一标识：首次使用时生成并固定下来。 */
    val deviceId: String
        get() {
            val saved = sp().getString(KEY_DEVICE, "") ?: ""
            if (saved.isNotBlank()) return saved
            val androidId = runCatching {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
            val id = if (!androidId.isNullOrBlank()) androidId.take(12)
                     else UUID.randomUUID().toString().replace("-", "").take(12)
            sp().edit().putString(KEY_DEVICE, id).apply()
            return id
        }

    // ------------------------------------------------------------ 状态上报

    /** 上报一次。返回给人看的结果文本。 */
    fun reportOnce(): String {
        val base = server
        if (base.isBlank()) return "未配置集控服务器地址"
        val url = "$base/api/report"
        return try {
            val body = buildReport().toString()
            val resp = httpPostJson(url, body)
            "上报成功 → $url\n  deviceId=${deviceId}\n  服务器响应: ${resp.take(200)}"
        } catch (t: Throwable) {
            "上报失败: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun buildReport(): JSONObject {
        val o = JSONObject()
        o.put("deviceId", deviceId)
        o.put("ts", System.currentTimeMillis())
        o.put("uptimeMs", android.os.SystemClock.elapsedRealtime())
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            o.put("versionCode", pi.longVersionCode)
            o.put("versionName", pi.versionName ?: "")
        }
        runCatching {
            val target = OverlayService.targetPkgOf(ctx)
            o.put("targetPkg", target)
            val fg = ShellCore.probe.foregroundPackage()
            o.put("foreground", fg)
            o.put("armed", fg == target)
        }
        // 日志尾部（最近 8 行），便于在控制端粗看发生了什么
        runCatching {
            val tail = LogBus.dump().trimEnd().lines().takeLast(8)
            val arr = JSONArray()
            tail.forEach { arr.put(it.take(200)) }
            o.put("logTail", arr)
        }
        return o
    }

    // ------------------------------------------------------------ 配置拉取

    /** 拉取一次配置。只有 revision 变化才应用。 */
    fun pullConfigOnce(): String {
        val base = server
        if (base.isBlank()) return "未配置集控服务器地址"
        val url = "$base/api/config?deviceId=${URLEncoder.encode(deviceId, "UTF-8")}"
        return try {
            val text = httpGet(url)
            if (text.isBlank()) return "拉取失败：服务器无响应"
            val o = JSONObject(text)
            val rev = o.optString("revision", "")
            val applied = sp().getString(KEY_REVISION, "") ?: ""
            if (rev.isNotBlank() && rev == applied) {
                return "配置 revision=$rev 与已应用的一致，无需重载"
            }
            // 落盘，便于其它模块读取与追溯
            File(ctx.filesDir, CONFIG_FILE).writeText(o.toString(2))
            val msgs = applyConfig(o)
            sp().edit().putString(KEY_REVISION, rev).apply()
            buildString {
                append("已应用新配置 revision=").append(rev.ifBlank { "(无)" }).append('\n')
                msgs.forEach { append("  · ").append(it).append('\n') }
                append("  已保存到 files/").append(CONFIG_FILE)
            }
        } catch (t: Throwable) {
            "拉取失败: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 应用配置里我们认识的字段；不认识的只记录，不报错。 */
    private fun applyConfig(o: JSONObject): List<String> {
        val msgs = mutableListOf<String>()
        o.optString("targetPkg", "").takeIf { it.isNotBlank() }?.let {
            OverlayService.setTargetPkgOf(ctx, it)
            msgs += "targetPkg = $it"
        }
        if (o.has("pressMs")) {
            val p = o.optInt("pressMs", 90)
            ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE)
                .edit().putInt("pressMs", p).apply()
            msgs += "pressMs = $p"
        }
        o.optJSONArray("skillPoints")?.let { arr ->
            val pts = mutableListOf<Pair<Float, Float>>()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                if (p.length() >= 2) pts += p.optDouble(0).toFloat() to p.optDouble(1).toFloat()
            }
            if (pts.isNotEmpty()) {
                OverlayService.savePickedPointsOf(ctx, pts)
                msgs += "skillPoints 共 ${pts.size} 个"
            }
        }
        o.optString("notes", "").takeIf { it.isNotBlank() }?.let { msgs += "notes: $it" }
        if (msgs.isEmpty()) msgs += "(配置里没有本版本认识的字段)"
        return msgs
    }

    // ------------------------------------------------------------ 定时循环

    @Volatile private var running = false
    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        if (server.isBlank()) { LogBus.emit("集控：未配置服务器地址，未启动"); return }
        running = true
        worker = Thread {
            LogBus.emit("集控：已启动，每 ${intervalMs / 1000} 秒上报一次（deviceId=$deviceId）")
            while (running) {
                try {
                    // 上报交给外层日志，避免刷屏
                    val r = reportOnce()
                    LogBus.emit("集控上报: ${r.lineSequence().first()}")
                    val c = pullConfigOnce()
                    if (!c.startsWith("配置 revision")) LogBus.emit("集控配置: ${c.lineSequence().first()}")
                } catch (t: Throwable) {
                    LogBus.emit("集控循环异常: ${t.message}")
                }
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "mgmt-loop" }
        worker?.start()
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        LogBus.emit("集控：已停止")
    }

    // ------------------------------------------------------------ HTTP

    private fun httpPostJson(url: String, body: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "altair-probe")
            if (token.isNotBlank()) setRequestProperty("X-Altair-Token", token)
        }
        try {
            OutputStreamWriter(c.outputStream, "UTF-8").use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            if (code == 401) throw RuntimeException("HTTP 401 鉴权失败 —— 请检查「集控 Token」是否与服务器一致")
            if (code !in 200..299) throw RuntimeException("HTTP $code ${text.take(120)}")
            return text
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "altair-probe")
            if (token.isNotBlank()) setRequestProperty("X-Altair-Token", token)
        }
        try {
            val code = c.responseCode
            if (code == 401) throw RuntimeException("HTTP 401 鉴权失败 —— 请检查「集控 Token」是否与服务器一致")
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return c.inputStream.bufferedReader().readText()
        } finally {
            runCatching { c.disconnect() }
        }
    }
}
