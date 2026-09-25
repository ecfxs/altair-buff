package com.altair.probe

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内自更新
 * ============
 *
 * ## 为什么能免确认安装
 * 正常 App 安装 APK 时系统强制弹确认框。但 **root 下 `pm install -r` 以 uid=0 执行，
 * 直接绕过确认**。本项目的云手机已实测拿到完整 root（uid=0 + 满 Capabilities）。
 *
 * ## 两个必须解决的坑
 * 1. **安装会杀掉本应用进程** —— 所以安装动作必须放进一个脱离本进程组的独立脚本
 *    （`setsid`），否则脚本随应用一起被杀，装到一半就断了，还可能留下损坏的安装。
 * 2. **装完要自己重启** —— 同一个脚本里在安装后执行 `am start` 把应用拉回来，
 *    并把全过程写进 `/data/local/tmp/altair_update.log`，下次启动可以读出来确认结果。
 *
 * ## 版本判断
 * 不需要额外的版本清单文件：直接下载 APK，用 `getPackageArchiveInfo`
 * 读 **APK 自身** 的 versionCode 与包名，比当前版本高才安装。
 */
class Updater(
    private val ctx: Context,
    private val sh: RootShell,
    private val log: (String) -> Unit
) {

    companion object {
        private const val PREF = "updater"
        private const val KEY_URL = "apk_url"
        /**
         * 默认更新源：GitHub Release 的 latest 固定地址。
         * 它**永远指向最新 release**，所以配一次就永久有效 —— 以后每次发新版
         * 你只要在 App 里点「自更新」即可，不需要改任何东西。
         */
        const val DEFAULT_URL =
            "https://github.com/ecfxs/altair-buff/releases/latest/download/probe-release.apk"
        private const val SCRIPT = "/data/local/tmp/altair_update.sh"
        private const val LOGFILE = "/data/local/tmp/altair_update.log"

        /** GitHub Release 资产地址（所有代理都基于它）。 */
        private const val GH_ASSET =
            "https://github.com/ecfxs/altair-buff/releases/latest/download/probe-release.apk"

        /**
         * 更新源列表，**按国内可达性排序**。
         *
         * P0 实测（2026-09-17，国内网络）：
         *   GitHub 直连   ❌ 完全不通（30s 超时）
         *   gh-proxy.com  ✅ 410 KB/s，文件完整   ← 最快
         *   ghfast.top    ✅ 200 KB/s，文件完整
         *   jsDelivr CDN  ✅ 224 KB/s，但可能因 CDN 缓存滞留**旧版**
         *
         * 所以把代理放前面（最可能通、且始终指向 latest），GitHub 直连放中间，
         * jsDelivr 放最后（它能通但新鲜度不保证）。
         *
         * 第三方代理是公共服务，可能失效 —— 这正是要**多源冗余**的原因。
         */
        val SOURCES: List<Pair<String, String>> = listOf(
            "gh-proxy 代理" to "https://gh-proxy.com/$GH_ASSET",
            "ghfast 代理" to "https://ghfast.top/$GH_ASSET",
            "GitHub 直连" to GH_ASSET,
            "jsDelivr CDN" to
                "https://cdn.jsdelivr.net/gh/ecfxs/altair-buff@main/dist/probe-release.apk"
        )
    }

    // ------------------------------------------------------------ 配置持久化

    /**
     * 「使用指定文件更新」那一栏记住的地址；没保存过则返回 [DEFAULT_URL]。
     *
     * 只记地址、不记"是否自动检查"：更新**只应该**在用户手动点击时发生（设计原则），
     * 所以没有 auto-check 这个开关 —— 有了它就会有人打开，然后半夜把正在挂机的机器重启掉。
     */
    fun savedUrl(): String {
        val u = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_URL, "") ?: ""
        return u.ifBlank { DEFAULT_URL }
    }

    fun saveUrl(u: String) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_URL, u.trim()).apply()

    fun currentVersionCode(): Long = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        }
    } catch (_: Throwable) {
        1L
    }

    fun currentVersionName(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    /** 读取上次自更新的日志。应用被 pm install 杀掉后，下次启动靠它确认结果。 */
    private fun ensure(): Boolean = if (sh.isAlive) true else sh.open()

    fun readUpdateLog(): String {
        if (!ensure()) return "(root shell 不可用)"
        val t = sh.exec("cat $LOGFILE 2>/dev/null", 5000)
        return t.ifBlank { "(暂无更新日志)" }
    }

    // ------------------------------------------------------------ 多源自动更新

    /**
     * 依次尝试全部更新源，找到比当前**版本更高**的就装。
     *
     * 关键：不是「第一个能下就用」，而是「下到了还要比版本」——
     * 因为 jsDelivr 这类 CDN 可能仍缓存旧版，若先下到旧包就立刻判定
     * 「已是最新」，会导致**静默地永远不更新**。所以每个源都要校验 versionCode。
     */
    fun updateAuto(): String {
        if (!ensure()) return "root shell 不可用，无法自更新"
        val cur = currentVersionCode()
        val sb = StringBuilder()
        sb.append("当前版本 versionCode=$cur (${currentVersionName()})\n")
        sb.append("依次尝试 ${SOURCES.size} 个更新源…\n\n")

        var anyValid = false
        for ((name, url) in SOURCES) {
            val apk = File(ctx.cacheDir, "update.apk")
            sb.append("▸ $name\n")
            val got = try {
                download(url, apk, connectMs = 10_000, readMs = 90_000)
                apk.length() > 1000
            } catch (t: Throwable) {
                sb.append("   不可达: ${(t.message ?: t.javaClass.simpleName).take(70)}\n")
                false
            }
            if (!got) continue

            val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            if (info == null) { sb.append("   不是有效 APK\n"); continue }
            if (info.packageName != ctx.packageName) {
                sb.append("   包名不匹配: ${info.packageName}\n"); continue
            }
            anyValid = true
            val v = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            sb.append("   下载 ${apk.length() / 1024} KB   versionCode=$v\n")
            if (v > cur) {
                sb.append("\n✅ 找到更高版本（$cur → $v），来源：$name\n\n")
                sb.append(installDetached(apk.absolutePath))
                return sb.toString()
            }
            sb.append("   不高于当前版本，继续试下一个源\n")
        }

        sb.append("\n")
        sb.append(
            if (!anyValid) "更新检查失败：没有获得有效更新包，请查看各来源错误。"
            else "✅ 已是最新版本（所有可达源都未提供更高版本）"
        )
        return sb.toString()
    }

    // ------------------------------------------------------------ 从 URL 更新

    /**
     * 下载 [url] 指向的 APK 并安装。
     * [force] 为 true 时即使版本不更高也强制重装（用于救砖/回滚）。
     */
    fun updateFromUrl(url: String, force: Boolean): String {
        if (!ensure()) return "root shell 不可用，无法自更新"
        if (url.isBlank()) return "更新源 URL 为空"
        val sb = StringBuilder()
        val apk = File(ctx.cacheDir, "update.apk")
        try {
            sb.append("更新源: $url\n")
            var lastBucket = -1
            download(url, apk) { pct ->
                val b = pct / 10
                if (b != lastBucket) {
                    lastBucket = b
                    log("  下载 $pct%")
                }
            }
            sb.append("下载完成 ${apk.length() / 1024} KB\n")

            val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                ?: return sb.append("下载的文件不是有效 APK").toString()
            if (info.packageName != ctx.packageName) {
                return sb.append("包名不匹配：期望 ${ctx.packageName}，实际 ${info.packageName}").toString()
            }
            val newVer = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val curVer = currentVersionCode()
            sb.append("版本: 当前 $curVer (${currentVersionName()}) → 目标 $newVer\n")
            if (newVer <= curVer && !force) {
                return sb.append("→ 已是最新，无需更新").toString()
            }
            sb.append("\n").append(installDetached(apk.absolutePath))
        } catch (t: Throwable) {
            sb.append("更新失败: ${t.javaClass.simpleName}: ${t.message}")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ 从本地文件安装

    /**
     * 安装本地已有的 APK（用于平台「上传文件」后手动指定路径）。
     * 因为应用自身受 scoped storage 限制读不到 /sdcard，所以先用 root 拷进 cacheDir 再解析。
     */
    fun installLocal(pathInput: String, force: Boolean): String {
        if (!ensure()) return "root shell 不可用"
        val path = pathInput.trim()
        if (path.isEmpty()) return "路径为空"

        val exists = sh.exec("test -f ${RootShell.quote(path)} && echo YES || echo NO", 4000)
        if (!exists.contains("YES")) return "文件不存在（用 root 也看不到）: $path"

        val staged = File(ctx.cacheDir, "local.apk")
        staged.delete()
        val cp = sh.exec("cp ${RootShell.quote(path)} ${RootShell.quote(staged.absolutePath)}", 15000)
        if (!staged.exists() || staged.length() < 1000) {
            return "复制失败: $cp"
        }

        val info = ctx.packageManager.getPackageArchiveInfo(staged.absolutePath, 0)
            ?: return "不是有效 APK"
        if (info.packageName != ctx.packageName) return "包名不匹配: ${info.packageName}"
        val curVer = currentVersionCode()
        val localVersion = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (localVersion <= curVer && !force) {
            return "本地 APK 版本 $localVersion 不高于当前 $curVer（可勾选「强制」重装）"
        }
        return "本地 APK 版本 $localVersion（当前 $curVer）\n\n" +
            installDetached(staged.absolutePath)
    }

    // ------------------------------------------------------------ 安装（脱离进程）

    /**
     * 把安装动作写成一个独立脚本，用 setsid 脱离本进程组后执行。
     *
     * 脚本做的事：等 3 秒（让本次 UI 输出刷完）→ `pm install -r -d` → 记录退出码
     * → 等 2 秒 → `am start` 把应用拉回来。全程写入 [LOGFILE]。
     */
    private fun installDetached(apkPath: String): String {
        val script = """
            #!/system/bin/sh
            echo "=== altair self-update ===" > $LOGFILE
            date >> $LOGFILE 2>&1
            echo "apk=$apkPath" >> $LOGFILE
            sleep 3
            echo "--- pm install -r -d ---" >> $LOGFILE
            pm install -r -d "$apkPath" >> $LOGFILE 2>&1
            echo "install_exit=${'$'}?" >> $LOGFILE
            sleep 2
            echo "--- relaunch ---" >> $LOGFILE
            am start -n ${ctx.packageName}/.MainActivity >> $LOGFILE 2>&1
            echo "done" >> $LOGFILE
        """.trimIndent()

        val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val write = sh.execute("echo '$b64' | base64 -d > $SCRIPT && chmod 700 $SCRIPT", 6000)
        check(write.ok) { "无法准备更新脚本：${write.text()}" }

        val hasSetsid = sh.execute("command -v setsid", 3000).ok
        val launcher = if (hasSetsid) "setsid $SCRIPT" else "nohup $SCRIPT"
        // 用 execute 而不是 exec：这里要的是精确控制的重定向顺序，不能给命令追加任何东西。
        val launch = sh.execute("$launcher >/dev/null 2>&1 </dev/null &", 4000)
        check(launch.ok) { "无法启动更新：${launch.text()}" }

        return """
            已启动后台安装（$launcher）

            ⚠ 本应用马上会被系统杀掉 —— 这是正常现象。
            约 5 秒后会自动重启。重启后点「更新日志」即可确认结果。
            也可在 root shell 里看: cat $LOGFILE

            若 setsid 不可用会退回 nohup（进程被杀时可能中断安装）。
        """.trimIndent()
    }

    // ------------------------------------------------------------ 下载

    private fun download(
        rawUrl: String,
        out: File,
        connectMs: Int = 20_000,
        readMs: Int = 60_000,
        onPct: (Int) -> Unit = {}
    ) {
        require(URL(rawUrl).protocol == "https") { "更新地址必须使用 HTTPS" }
        out.delete()
        // ★ 缓存破坏参数，必须有。
        // GitHub 的 /releases/latest/download/ 重定向会被 CDN 按 URL 缓存。
        // 实测：刚发完新版直接请求 latest 会拿到**上一个版本**的 APK，
        // 结果是「下载到旧包 → 版本不更高 → 提示已是最新 → 静默永不更新」。
        // 加上每次都不同的时间戳参数即可绕过。
        val sep = if (rawUrl.contains("?")) "&" else "?"
        val url = "$rawUrl${sep}_t=${System.currentTimeMillis()}"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectMs
                readTimeout = readMs
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "altair-probe")
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { ins ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        read += n
                        if (total > 0) onPct(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
