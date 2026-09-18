# 上线手册（altaird 切换）

> 这是**硬切换**（方案 §9.3 / D10）：新 APK 说 v1 协议，旧 Python 服务端不认识。
> 所以 APK 与新服务端必须**同一次上线**，没有灰度期。
> 全程按本手册走，每步都有可验证的检查点。

## 0. 先想清楚（5 分钟）

| 决策 | 默认 | 说明 |
|---|---|---|
| 用不用 `--legacy-device-api` | **建议用** | 窗口期里未升级的云手机仍能被看见。它让"APK 铺开慢"不再是致命问题 |
| 报表/日志保留期 | 30 天 / 7 天 | 200 台 60s 周期约 2.9 GB；500 台请把上报改 14 天、日志改 3 天 |
| 域名 | 必须 | Caddy 自动签证书；没有域名就只能自签或 HTTP（客户端要额外配置信任） |

## 1. 切换前：把现有配置抄下来（**最容易漏的一步**）

不迁移旧数据。`control-store.json` 里每台设备的 BUFF 键位/时长、目标包名、输入方式，
**切换后不会再有任何界面显示它们**，只能手工翻 JSON。

```bash
# 在 VPS 上（旧服务所在机器）
python3 -c "
import json
st=json.load(open('/opt/altair-control/control-store.json'))
print('=== 默认配置 ===');print(json.dumps(st['defaultConfig'],ensure_ascii=False,indent=2))
for k,v in st['devices'].items():
    if v.get('config'): print(f'=== 设备 {k} ===');print(json.dumps(v['config'],ensure_ascii=False,indent=2))
"
```
把输出贴到别处存好，装完新面板后照着录进去。

## 2. 本地：构建 + 验收 + 打包

```bash
cd control
source ../.toolchain/env.sh
make check && make test                 # 编译 + vet + 类型 + 单测
bash scripts/smoke.sh                   # 协议层 66 项
make pw-install && make e2e             # 界面层 11 项（可选，本机跑）
make web && make release                # 前端打进二进制 + 交叉编译 Linux
```

**检查点**：`dist/altaird-linux-amd64` 存在，`file` 显示 `ELF 64-bit ... statically linked`。

```bash
scp dist/altaird-linux-amd64 root@VPS:/tmp/
scp -r deploy scripts root@VPS:/tmp/
```

## 3. VPS：前置检查 + 安装

```bash
sudo bash /tmp/scripts/preflight.sh control.example.com /tmp/altaird-linux-amd64
```

**检查点**：全部 ✅。有 ❌ 先解决（脚本会给出具体命令）。

```bash
sudo bash /tmp/deploy/install.sh control.example.com /tmp/altaird-linux-amd64
```

它会：装 Caddy → 建 `altair` 用户与 `/var/lib/altair` → 放二进制 → 写 systemd unit 与 Caddyfile →
起服务 → 放行 80/443 → 打印面板地址与**初始凭据（口令只显示这一次，立刻记下）**。

**检查点**：
```bash
systemctl is-active altaird                 # → active
curl -fsS http://127.0.0.1:8788/healthz     # → {"ok":true,...}
sudo tail -30 /var/log/caddy/altair.log 2>/dev/null || journalctl -u caddy -n 30
curl -fsS https://control.example.com/healthz   # 证书签好后应返回 ok
```

## 4. 单台设备验证（**先只上 1 台**）

1. 给 1 台云手机装新 APK（协议 v1）。
2. App 里填服务器 `https://control.example.com`，Token 从面板「设置」页复制。
3. 打开「启用集控定期上报」。
4. 面板「总览」应出现这台设备，且：设备 ID 正确、状态不是「已停止」、心跳有电量/温度。

**检查点**（在 VPS 上直接看协议层）：
```bash
journalctl -u altaird -f | grep -E "上报|下发配置|设备上线"
```
应能看到 `msg=设备上线 device=...` 与周期性的 `msg=上报`。

> 若这台设备仍跑旧 APK：用 `--legacy-device-api` 重启服务后它也能被看见
> （`systemctl edit altaird` 在 `ExecStart` 末尾加该参数，或改 unit 文件后 `daemon-reload`）。

## 5. 录入配置

面板 →「配置」页：
1. scope 选 **（默认配置）**，填目标包名、输入方式、BUFF 三项，下发。
2. 逐台设备建覆盖（如果它跟默认不一样）→ 对照第 1 步抄下来的清单核对。
3. 「设备详情」页确认 `配置同步=True`（否则设备还没拉到新 revision）。

**检查点**：每台设备的详情页 `appliedRevision` 与 `configRevision` 一致。

## 6. 全量铺开

1. 全量安装新 APK（这一步是人工的，耗时取决于台数 —— 因此第 0 步建议开 `--legacy-device-api`）。
2. 面板确认「设备总数 / 在线」达到预期。
3. 确认没问题后关掉 legacy 开关。

## 7. 收尾验收清单

```bash
# 面板能登、能看、能下发
curl -fsS https://control.example.com/healthz
journalctl -u altaird --since "-1h" | grep -c panic      # 期望 0

# 数据在长、磁盘有余量
du -sh /var/lib/altair
```

- [ ] 所有云手机出现在总览，在线数与预期一致
- [ ] 随机抽 2 台：启停下发在一个上报周期内生效
- [ ] 随机抽 2 台：配置同步 = True
- [ ] 截图能上传（面板设备详情 → 截图）
- [ ] 面板「审计」里能看到刚才的动作
- [ ] `/var/lib/altair` 日增长量符合预估（200 台约 160 MB/天）

## 8. 回滚预案（分级）

| 症状 | 动作 |
|---|---|
| 面板/证书有问题，服务本身好 | Caddy 配置改回旧端口或 `systemctl restart caddy`；不动数据库 |
| 新服务起不来 | `sudo bash /tmp/deploy/upgrade.sh` 会自动回滚上一版二进制；或 `cp /opt/altair/altaird.bak /opt/altair/altaird && systemctl restart altaird` |
| 设备大面积连不上 | 打开 `--legacy-device-api`（旧 APK 立刻可见），同时逐台排查新 APK；这是本次切换最主要的保险 |
| 必须整体退回旧 Python | 旧 `control-server.py` 在验收通过前**不删除**；`systemctl stop altaird` → 起旧服务 → Caddy 指回旧端口 → 云手机改回旧地址 |

> 数据库迁移是单向的（`schema_migrations` 只前进）。但 `altaird` 的迁移都是**加表/加列**，
> 不回退也不会破坏旧数据；真要退回 Python，注意两边的配置已经不同步，需从第 1 步的清单重录。

## 9. 故障排查

| 现象 | 先看这里 |
|---|---|
| 证书申请失败 | 域名是否解析到本机；80/443 是否被占用或被防火墙拦；`journalctl -u caddy -n 50` |
| 面板打得开但设备不上报 | 设备端服务器地址是否带 `https://`；Token 是否一致（面板「设置」页）；`journalctl -u altaird | grep "Token 无效"` |
| 面板不实时（一直"轮询"） | Caddyfile 里 `flush_interval -1` 是否被删（SSE 靠它关缓冲）；浏览器 F12 看 `/api/v1/panel/events` 是否 200 |
| 设备显示「已停止」但引擎在跑 | 服务端从未下发过时 `rev=0`，设备会忽略（这是有意的，见方案 §13.3 偏差 6） |
| 磁盘涨得比预期快 | 设置页把 `reportRetentionDays` / `logRetentionDays` 调小；确认没有把 payload 塞回 `reports` |
| 服务反复重启 | `journalctl -u altaird -n 100`；多半是 `/var/lib/altair` 权限（unit 里 `User=altair`） |
