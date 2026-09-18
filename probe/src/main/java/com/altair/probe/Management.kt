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
 * 集控（多设备统一管理）—— 设备端（**协议 v1**）
 * ============================================
 *
 * ## 架构约束：为什么是「设备主动上报」而不是「服务器推送」
 * 云手机跑在机房 NAT 后面，**没有公网 IP，服务器无法主动连它**。
 * 所以只能反过来：设备定时**上报状态**，指令搭在**上报的响应**里带回来。
 * 这也顺带解决了防火墙问题 —— 设备侧只需要能出网。
 *
 * ```
 *   控制端（altaird）                        设备端（云手机 APK）
 *   ┌───────────────────────────────┐        ┌──────────────────────────┐
 *   │ POST /api/v1/device/report    │ ◀───── │ 一轮往返：               │
 *   │   响应带回 desired /          │        │  · 上报状态与心跳        │
 *   │   configRevision /            │        │  · 收启停与一次性指令    │
 *   │   nextReportInMs / commands   │        │  · 执行并回报            │
 *   │ GET  /api/v1/device/config    │ ◀───── │ 仅当 revision 变化时拉配置│
 *   │ POST /api/v1/device/screenshot│ ◀───── │ 截图上传（点播或主动）   │
 *   └───────────────────────────────┘        └──────────────────────────┘
 * ```
 *
 * ## 与旧协议（v0）的差别
 * 1. **合并往返**：v0 每轮固定两次请求（`POST /api/report` + `GET /api/config`）；
 *    v1 把期望状态、配置版本、下次上报周期、一次性指令全塞进上报响应 ——
 *    启停延迟从「≤1 周期 + 1 次轮询」降到「≤1 周期」，流量减半。
 * 2. **上报周期由服务端下发**（`nextReportInMs`）：面板上改周期即时生效，不用重发 APK。
 * 3. **心跳**：电量/充电/温度/可用内存/网络延迟，面板能看出哪台机器在发烫或掉网。
 * 4. **截图上传统**：服务端可点播，也可本地触发。
 * 5. **回执**：一次性指令带 id，设备执行后在下次上报里用 `ackedCommands` 确认，服务端据此去重。
 *
 * ## 上报体（设备 → 服务器）  POST {server}/api/v1/device/report
 * ```json
 * {
 *   "deviceId": "a1b2c3d4", "protocolVersion": 1, "ts": 1699999999999,
 *   "versionCode": 24, "versionName": "0.24.0",
 *   "targetPkg": "com.nexon.mod", "foreground": "com.nexon.mod",
 *   "armed": true, "uptimeMs": 123456, "appliedRevision": "r17",
 *   "engine": { "running": true, "state": "WAITING", "cycleCount": 12, "buffs": [] },
 *   "heartbeat": { "batteryPct": 87, "charging": true, "thermalC": 38.5, "memFreeMb": 1204, "netRttMs": 42 },
 *   "logTail": ["...", "..."], "ackedCommands": ["c_8f3a"]
 * }
 * ```
 *
 * ## 上报响应（服务器 → 设备）
 * ```json
 * {
 *   "ok": true, "ts": 1699999999999,
 *   "desired": { "running": true, "rev": 7 },
 *   "configRevision": "r17",
 *   "nextReportInMs": 30000,
 *   "commands": [{ "id": "c_8f3a", "action": "screenshot" }]
 * }
 * ```
 *
 * 配置（服务器 → 设备）  GET {server}/api/v1/device/config?deviceId=xxx
 * ```json
 * {
 *   "revision": "r7",
 *   "targetPkg": "com.nexon.mod",
 *   "pressMs": 90,
 *   "skillPoints": [[0.7416,0.5673],[0.8041,0.5756]],
 *   "buff": [{ "idx": 1, "enabled": true, "key": 1, "durationMin": 5 }],
 *   "desired": { "running": true, "rev": 7 },
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
        private const val KEY_DESIRED_REV = "desiredRev"
        private const val KEY_REVISION = "appliedRevision"
        const val DEFAULT_INTERVAL_MS = 60_000L

        /** 设备协议版本：v1 = 合并往返 + 心跳 + 截图 + 服务端下发上报周期。 */
        const val PROTOCOL_VERSION = 1

        /** 上报周期由服务端通过 nextReportInMs 下发，设备侧只做钳制。 */
        const val MIN_INTERVAL_MS = 15_000L
        const val MAX_INTERVAL_MS = 300_000L

        const val CONFIG_FILE = "pulled_config.json"
    }

    private fun sp() = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ v1 运行时状态

    /** 指令回执队列：搭下一次上报带回（省一次往返），**上报成功后才清**，失败不丢回执。 */
    private val ackQueue = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** 本次上报实际写进了哪些回执 id（成功后按这批清队列）。 */
    @Volatile private var reportAcks: List<String> = emptyList()

    /** 最近一次上报的往返耗时（毫秒），作为 netRttMs 上报。 */
    @Volatile private var lastRttMs: Int = 0

    /** 服务端下发的上报周期（服务端说了算，改周期不用重新下发 APK）。 */
    @Volatile var nextIntervalMs: Long = DEFAULT_INTERVAL_MS
        private set

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

    /** 上报一次（供界面按钮用），返回给人看的结果文本。 */
    fun reportOnce(): String {
        return try {
            val o = postReport()
            "上报成功 → $server/api/v1/device/report\n  deviceId=$deviceId\n  服务器响应: ${o?.toString()?.take(200) ?: "(空)"}"
        } catch (t: Throwable) {
            "上报失败: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 真正发一次上报，返回解析后的响应；成功时清掉已带回的回执。 */
    private fun postReport(): JSONObject? {
        val base = server
        if (base.isBlank()) throw IllegalStateException("未配置集控服务器地址")
        val url = "$base/api/v1/device/report"
        val body = buildReport().toString()
        val t0 = System.currentTimeMillis()
        val resp = httpPostJson(url, body)
        lastRttMs = (System.currentTimeMillis() - t0).toInt()
        val sent = reportAcks
        if (sent.isNotEmpty()) synchronized(ackQueue) { ackQueue.removeAll(sent.toSet()) }
        return runCatching { JSONObject(resp) }.getOrNull()
    }

    /**
     * 一个完整的集控周期（v1 合并往返）。
     *
     * 旧实现每轮固定两次请求：`POST /api/report` + `GET /api/config`。
     * v1 把 desired / configRevision / nextReportInMs / commands 直接塞进上报响应，
     * 所以正常情况下**一轮就够** —— 启停延迟从「≤1 个上报周期 + 1 次轮询」降到「≤1 个上报周期」，
     * 设备流量也少一半。只有配置版本变了才多一次拉取。
     */
    private fun cycleOnce(): String {
        val sb = StringBuilder()
        val o = postReport() ?: return "上报成功（响应不可解析）"
        sb.append("上报成功")

        applyDesired(o)?.let { sb.append("\n  ").append(it) }

        val cfgRev = o.optString("configRevision", "")
        val applied = sp().getString(KEY_REVISION, "") ?: ""
        if (cfgRev.isNotBlank() && cfgRev != applied) {
            sb.append("\n  ").append(pullConfigOnce().lineSequence().first())
        }

        val next = o.optInt("nextReportInMs", 0)
        if (next > 0) nextIntervalMs = next.toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

        o.optJSONArray("commands")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optString("id", "")
                when (c.optString("action", "")) {
                    "screenshot" -> {
                        val r = uploadScreenshot(label = "点播")
                        sb.append("\n  截图指令 ${id}: ${r.lineSequence().first()}")
                        // 只有真的传上去了才回执，否则服务端会以为成功
                        if (id.isNotBlank() && r.startsWith("截图已上传")) {
                            synchronized(ackQueue) { ackQueue.add(id) }
                        }
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun buildReport(): JSONObject {
        val o = JSONObject()
        o.put("deviceId", deviceId)
        o.put("protocolVersion", PROTOCOL_VERSION)
        o.put("ts", System.currentTimeMillis())
        o.put("uptimeMs", android.os.SystemClock.elapsedRealtime())
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            o.put("versionCode", pi.longVersionCode)
            o.put("versionName", pi.versionName ?: "")
        }
        // 设备型号 —— 多机挂机时靠它区分"这是哪台云手机"
        runCatching {
            o.put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            o.put("android", android.os.Build.VERSION.RELEASE)
            o.put("deviceId", deviceId)
        }
        // 引擎状态 —— 监控台据此显示 BUFF 进度
        runCatching { o.put("engine", Engine.statusJson()) }
        // 已应用的配置版本：服务端据此判断设备是否还停在旧配置上（面板会显示"配置未生效"）
        runCatching {
            val rev = sp().getString(KEY_REVISION, "") ?: ""
            if (rev.isNotBlank()) o.put("appliedRevision", rev)
        }
        // 心跳：面板上能一眼看出哪台机器在发烫 / 掉网 / 快没电
        runCatching { heartbeat()?.let { o.put("heartbeat", it) } }
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
        // 指令回执：搭在下一次上报里带回，省一次往返。
        // 只有上报成功后才清队列（见 cycleOnce），否则失败会丢回执。
        reportAcks = synchronized(ackQueue) { ackQueue.toList() }
        if (reportAcks.isNotEmpty()) {
            val arr = JSONArray()
            reportAcks.forEach { arr.put(it) }
            o.put("ackedCommands", arr)
        }
        return o
    }

    /**
     * 设备心跳。
     *
     * 电池/温度走 BatteryManager 的粘性广播（不需要注册长期接收者）；
     * 内存走 ActivityManager.MemoryInfo；网络延迟用一次上报的往返时间来估。
     */
    private fun heartbeat(): JSONObject? {
        val o = JSONObject()
        runCatching {
            // 粘性广播：不需要注册长期接收者，读一次就有当前值
            val it2 = ctx.registerReceiver(null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            if (it2 != null) {
                val level = it2.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = it2.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) o.put("batteryPct", level * 100 / scale)
                val status = it2.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                o.put("charging", status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == android.os.BatteryManager.BATTERY_STATUS_FULL)
                val t = it2.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
                if (t > 0) o.put("thermalC", t / 10.0)
            }
        }
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (am != null) {
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                o.put("memFreeMb", (mi.availMem / 1024 / 1024).toInt())
            }
        }
        if (lastRttMs > 0) o.put("netRttMs", lastRttMs)
        return if (o.length() == 0) null else o
    }

    // ------------------------------------------------------------ 配置拉取

    /** 拉取一次配置。只有 revision 变化才应用。 */
    fun pullConfigOnce(): String {
        val base = server
        if (base.isBlank()) return "未配置集控服务器地址"
        val url = "$base/api/v1/device/config?deviceId=${URLEncoder.encode(deviceId, "UTF-8")}"
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

    /**
     * 应用期望状态（远程启停）。返回描述文本；无变化返回 null。
     *
     * rev 去重：只有 rev 变了才执行，避免每轮都重复启停。
     * **rev == 0 表示服务端从未下发过**（例如新设备刚接入），此时绝不能动引擎 ——
     * 早期实现会把它当成"下发停止"，结果新设备一连上就被强制停掉。
     */
    private fun applyDesired(o: JSONObject): String? {
        val d = o.optJSONObject("desired") ?: return null
        val rev = d.optInt("rev", 0)
        if (rev <= 0) return null
        val applied = sp().getInt(KEY_DESIRED_REV, 0)
        if (rev == applied) return null
        val want = d.optBoolean("running", false)
        if (want) Engine.start(ctx) else Engine.stop("监控台下发停止")
        sp().edit().putInt(KEY_DESIRED_REV, rev).apply()
        return "远程指令：${if (want) "启动" else "停止"}（rev=$rev）"
    }

    /** 应用配置里我们认识的字段；不认识的只记录，不报错。 */
    private fun applyConfig(o: JSONObject): List<String> {
        val msgs = mutableListOf<String>()

        // ---- 远程启停（desired state 模式）----
        // 云手机在 NAT 后服务器连不上它，所以命令搭在设备轮询的返回里。
        applyDesired(o)?.let { msgs += it }
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
                // ★ 必须通知悬浮窗刷新：它内存里那份采集点是 onCreate 时读的，
                //   不通知的话 ROI 与「点1..4」会一直用旧坐标，直到重启悬浮窗。
                OverlayService.notifyPicksChanged()
                msgs += "skillPoints 共 ${pts.size} 个"
            }
        }
        // 远程改输入方式
        o.optString("inputMethod", "").takeIf { it.isNotBlank() }?.let {
            ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE)
                .edit().putString("inputMethod", it).apply()
            msgs += "inputMethod = $it"
        }
        // 远程下发 BUFF 配置（个数/按键/时长）→ 写进 buff SharedPreferences，引擎直接读
        o.optJSONArray("buff")?.let { arr ->
            val ed = ctx.getSharedPreferences("buff", Context.MODE_PRIVATE).edit()
            var n = 0
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val idx = b.optInt("idx", i + 1) - 1          // 上报用 1 基，存储用 0 基
                if (idx !in 0..2) continue
                ed.putBoolean("enabled$idx", b.optBoolean("enabled", false))
                ed.putInt("key$idx", (b.optInt("key", idx + 1) - 1).coerceIn(0, 3))
                ed.putInt("dur$idx", b.optInt("durationMin", 5).coerceIn(1, 240))
                n++
            }
            ed.apply()
            if (n > 0) msgs += "BUFF 配置已更新 $n 项"
        }
        // 回城模式：每轮补完 BUFF 自动进自由市场
        if (o.has("autoFreeMarket")) {
            val on = o.optBoolean("autoFreeMarket", false)
            ctx.getSharedPreferences("buff", Context.MODE_PRIVATE)
                .edit().putBoolean("autoFreeMarket", on).apply()
            msgs += "回城模式（自动进自由市场）= ${if (on) "开" else "关"}"
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
            LogBus.emit("集控：已启动（协议 v${PROTOCOL_VERSION}），上报周期由服务端下发（初始 ${intervalMs / 1000} 秒）")
            while (running) {
                try {
                    // 上报 + 收指令一轮完成；服务端说改周期就改周期
                    val r = cycleOnce()
                    LogBus.emit("集控: ${r.lineSequence().first()}")
                } catch (t: Throwable) {
                    LogBus.emit("集控循环异常: ${t.message}")
                }
                val sleepMs = if (nextIntervalMs > 0) nextIntervalMs else intervalMs
                try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
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

    // ------------------------------------------------------------ 截图上传

    /**
     * 截图并上传。服务端可以通过 commands 点播，本地界面也可以直接调。
     *
     * 通道与 Probe 里验证过的一致：root 下 `screencap -p`。
     * PNG 转 JPEG(80) 再传 —— 云手机的上行带宽比服务器磁盘更贵；
     * 超过服务端 2MB 上限时自动降档重压一次。
     */
    fun uploadScreenshot(label: String = ""): String {
        val base = server
        if (base.isBlank()) return "截图上传失败: 未配置集控服务器地址"
        val ts = System.currentTimeMillis()
        val dir = File(ctx.cacheDir, "shots").apply { mkdirs() }
        val png = File(dir, "shot_$ts.png")
        val jpg = File(dir, "shot_$ts.jpg")
        return try {
            if (!ShellCore.ensureRoot()) return "截图上传失败: 无 root 权限"
            ShellCore.root.timedExec("screencap -p ${png.absolutePath}", 12_000)
            if (!png.exists() || png.length() == 0L) return "截图上传失败: screencap 没产出文件（可能黑屏或通道不可用）"

            val bmp = android.graphics.BitmapFactory.decodeFile(png.absolutePath)
                ?: return "截图上传失败: 图片解码失败"
            var quality = 80
            var bytes: ByteArray
            while (true) {
                java.io.FileOutputStream(jpg).use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                }
                bytes = jpg.readBytes()
                if (bytes.size <= 2 * 1024 * 1024 || quality <= 30) break
                quality -= 20
            }
            bmp.recycle()

            httpPostMultipart("$base/api/v1/device/screenshot", jpg, ts, label)
            "截图已上传（${bytes.size / 1024} KB，质量 $quality）"
        } catch (t: Throwable) {
            "截图上传失败: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            png.delete()
            jpg.delete()
        }
    }

    /** 发一个 multipart/form-data（meta 字段是 JSON，file 字段是图片）。 */
    private fun httpPostMultipart(url: String, file: File, ts: Long, label: String): String {
        val boundary = "----altair" + System.nanoTime()
        val meta = JSONObject().apply {
            put("deviceId", deviceId)
            put("ts", ts)
            if (label.isNotBlank()) put("label", label)
        }.toString()
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "altair-probe")
            if (token.isNotBlank()) setRequestProperty("X-Altair-Token", token)
        }
        try {
            java.io.DataOutputStream(c.outputStream).use { out ->
                fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"meta\"\r\n\r\n")
                text(meta + "\r\n")
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"file\"; filename=\"shot.jpg\"\r\n")
                text("Content-Type: image/jpeg\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                text("\r\n--$boundary--\r\n")
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code == 401) throw RuntimeException("HTTP 401 鉴权失败 —— 请检查「集控 Token」")
            if (code !in 200..299) throw RuntimeException("HTTP $code ${text.take(120)}")
            return text
        } finally {
            runCatching { c.disconnect() }
        }
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
