# control/ —— 阿尔泰集控（altaird）

监控台与集控服务端。**单二进制**：设备 API + 面板 API + 内嵌前端 + SQLite，一个文件部署。

> 设计依据与全部决策记录见 [`../docs/集控重构方案.md`](../docs/集控重构方案.md)。
> 本文件只讲**怎么跑、怎么改、怎么验**。

## 快速开始

```bash
source ../.toolchain/env.sh          # Go 装在工作区内，必须先 source

# 起后端（前端产物从磁盘读，改前端不用重编 Go）
make dev                             # 监听 127.0.0.1:8788

# 另开一个终端：起前端 dev server（/api 会代理到 8788）
make web-dev

# 再开一个终端：造 3 台假设备，让面板有数据
make fake
```

首屏登录用的口令在 `make dev` 的输出横幅里：

```
  面板账号     admin
  面板口令     xxxxxxxx     ← 仅此一次显示，请立刻记下
  设备 Token   xxxxxxxx
```

## 出口（构建与发布）

```bash
make web        # 前端构建 → 拷进 internal/webassets/dist（会被 go:embed 打进二进制）
make build      # 本机二进制 dist/altaird
make release    # 交叉编译 Linux amd64：dist/altaird-linux-amd64
make check      # go build + go vet + 前端 tsc
make test       # go test ./... -race
bash scripts/smoke.sh   # ★ 协议与接口层验收（下面详述）
make pw-install && make e2e   # ★ 界面层验收：真浏览器 + 截图
```

## 两套验收，分工不同

| | `scripts/smoke.sh` | `make e2e`（Playwright） |
|---|---|---|
| 验什么 | 协议与接口：鉴权、上报语义、启停、配置、截图、SSE、安全边界 | 人能不能用：页面渲染、点击、状态跟随、主题生效 |
| 用什么 | curl | 真 Chromium |
| 速度 | ~40s | ~35s（首次要下一次浏览器） |
| 产物 | 通过/失败清单 | `data/e2e-shots/*.png` 截图存档 |

两套都要过。**冒烟脚本是主力**（覆盖 65 项，含安全项），E2E 补的是"渲染层"——

## 端到端验收：`scripts/smoke.sh`

这是本项目**最重要的一道关**，也是旧实现 `tools/check-assets.sh`（正则抠 JS + 手写 DOM 桩）的替代品。
它真的起服务、真的按设备协议上报、真的跑完整条面板链路：

| 覆盖 | 具体验的东西 |
|---|---|
| 启动 | 二进制编译、`/healthz`、首次启动打印凭据 |
| 鉴权 | 未登录 401、错误口令 401、**连续错误触发退避 429**、成功下发会话 Cookie、设备 Token 错误 401 |
| 上报 | 假设备按 v1 协议上报、总览聚合正确、**响应是白名单结构（不含 `auth`/凭据）** |
| 协议语义 | 响应带回 `desired` 与 `configRevision`（合并往返）、下发 `nextReportInMs`、同设备高频上报 429 |
| 启停 | 下发生效、`rev` 自增去重、设备下次上报即收到（≤1 个上报周期） |
| 配置 | 服务端生成 revision、设备拉到新配置、改动静默生成新 revision、历史版本归档与回滚 |
| 截图 | 上传落盘、列表可见、取原图 content-type 正确、删除、**超大文件被拒** |
| 数据 | 曲线接口、日志检索、审计记录、`/me` 拿 Token、设置读写 |
| 实时 | SSE 建连（hello）与收到 `report` 事件 |
| 静态 | 根路径返回 SPA、未知路由回退 index.html（前端路由可用） |
| 前端集成 | `REQUIRE_WEB=1` 时：index.html 必须引到打包产物、资源可取且 content-type 正确、**产物通过 `node --check` 语法校验** |
| 批量/边界 | 批量启停、单设备周期可改、过小周期钳制到 15s、不存在的设备 404 |
| 安全 | **路径穿越防护**（`deviceId` 里的 `../` 不会写到数据目录外）、超大请求体被拒后服务仍健康 |
| 旧协议 | `--legacy-device-api` 下 `/api/report`、`/api/config` 可用且带回 `desired`；**默认不生效** |
| CLI | `show` 打印 Token 且不泄露口令、`set-token` 两种参数顺序都不写错库、过短口令被拒 |

跑完输出 `通过 N 项，失败 M 项`（当前 **72 项**），有失败项时退出码非 0（CI 靠这个卡）。

### 界面层验收：`make e2e`

`web/e2e/panel.spec.ts`，13 条用例：登录跳转与白屏兜底、错误口令提示、总览卡片与统计条、
**点「停止」后设备状态真的跟着变**、日志抽屉、配置下发与历史版本、设置页取 Token、审计记录、登出、
**主题与布局断言**、**设置改动落库三连核对**、
**卡片「编辑」弹窗（改备注 + 拨 BUFF 开关，含卡片高度 ≤300px 的紧凑度回归断言）**、
**卡片上移走的信息在详情页仍可见**。

最后一条值得单独说：本环境里没法自动读图，所以"视觉验收"改成**可计算的值**——
直接比对设计 token 的计算样式（页面底 `#0d1014`、卡片 `#141920`、在线卡片边框 `#2c6b41`、
标题 `16px`）并检查栅格布局（至少两张卡片同行、卡片有实际尺寸）。
这样 Tailwind v4 主题搬错、卡片没渲染、样式全丢这类问题都会被测出来，而不是靠人眼。

```bash
make pw-install   # 首次：下载 Chromium 到 .pw-browsers/（已 gitignore，不污染系统）
make e2e          # 自己起服务 + 3 台假设备 + 构建前端，跑完把截图丢进 data/e2e-shots/
```

> 脚本会**先构建前端**再起服务 —— 否则测的是上一次的产物（这个坑踩过：给卡片加了
> `data-testid`，测试却找不到，因为 `dist/` 还是旧的）。

## 目录

```
api/openapi.yaml            ★ 契约唯一来源（改 API 先改它）
cmd/altaird/main.go         服务入口 + 运维子命令
cmd/altaird/calibrate.go    标定工具的本地服务（接口与旧 Python 版完全一致）
internal/model/             领域类型（HTTP 与 DB 共用）
internal/store/             SQLite：迁移 + 全部查询
internal/auth/              argon2id、会话、设备 Token、登录退避
internal/api/               路由装配、鉴权分流、设备/面板 handlers、SSE、静态
internal/webassets/         go:embed 的前端产物（.gitkeep 占位，make web 填充）
internal/fake/              假设备流量发生器
web/                        React + Vite + Tailwind v4 前端
deploy/                     Caddyfile、systemd unit、install.sh、upgrade.sh
scripts/smoke.sh            端到端验收
```

## 容量与存储（实测，不是估算）

面板只需要「每台设备最近一次上报什么样」，所以完整快照**每台只存一份**（`device_state`），
时间序列只留标量字段。这个决定有实测支撑：

| | 200 台设备 / 60s 周期 |
|---|---|
| 每份上报占用 | **362 字节**（改之前是 1454 字节） |
| 每天 | 99 MB（时间序列）+ 61 MB（日志） |
| 30 天保留 | ≈ 2.9 GB，其中日志按 7 天单独保留 ≈ 0.4 GB |
| 面板列表接口 | p50 **19.6ms**（N+1 共约 1000 次查询，实测不需要优化） |
| 设备上报接口 | p50 1.1ms |

自己量：`bash scripts/scale-check.sh 200 20`。

> 台数上到 500 时按同比例约 7 GB/30 天 —— 把 `reportRetentionDays` 调到 14、`logRetentionDays` 调到 3
> 就够（都在设置页里）。**不要**把完整 payload 塞回 `reports`：那是每 60 秒重复一次的整包 JSON。

## 运维子命令

```bash
altaird show                                    # 看面板账号与设备 Token
altaird set-password 新口令                     # 重设面板口令（原口令只存哈希，无法找回，只能重设）
altaird set-token 新Token                       # 换设备 Token（所有云手机都要改）
altaird fake-device --n 5 --interval 30s        # 假设备
altaird calibrate --root ..                     # 本地标定工具（读写 shots/）
```

> **与旧 Python 版本的差异**：`show` 不再显示面板口令 —— 新实现只存 argon2id 哈希，
> 口令本身在系统里不存在，忘了只能 `set-password` 重设。这是有意的安全收紧。

## 部署

**完整步骤见 [`deploy/RUNBOOK.md`](deploy/RUNBOOK.md)**（含切换清单、验收清单、分级回滚、故障排查）。
最短路径：

```bash
# 开发机
make check && make test && bash scripts/smoke.sh
make web && make release
scp dist/altaird-linux-amd64 root@VPS:/tmp/ && scp -r deploy scripts root@VPS:/tmp/

# VPS：先体检（不改任何东西，只报告能不能装）
sudo bash /tmp/scripts/preflight.sh control.example.com /tmp/altaird-linux-amd64

# 首次安装 / 之后升级
sudo bash /tmp/deploy/install.sh control.example.com /tmp/altaird-linux-amd64
sudo bash /tmp/deploy/upgrade.sh /tmp/altaird-linux-amd64
```

`install.sh` 会校验域名格式与模板文件、装 Caddy、建用户、写 unit 与 Caddyfile、起服务并打印凭据；
`upgrade.sh` 会先确认环境已装、**验证新二进制能在这台机器上执行**、备份数据库（只留最近 3 份防备份撑爆磁盘）、
健康检查失败自动回滚。

架构：`Caddy(443, 自动证书) → 127.0.0.1:8788 altaird`，同源所以**不需要任何 CORS 配置**；
数据在 `/var/lib/altair/`（`altair.db` + `shots/`），迁移在启动时自动跑。

⚠️ `Caddyfile` 里 `flush_interval -1` **不能删** —— SSE 实时推送靠它关掉反代缓冲。

## 上线切换（硬切换，无灰度期）

新协议 v1 与旧 `/api/report`、`/api/config` **不并存**（除非显式开 `--legacy-device-api`）。
切换步骤与回滚预案见方案文档 §9.3；切换前记得把每台设备的 BUFF 参数抄下来（不迁移旧数据）。
