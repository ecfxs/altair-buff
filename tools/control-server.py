#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
集控服务器（参考实现）
======================

给云手机上的 APK 提供「状态上报 + 配置下发」两件事，并自带一个网页面板。
只用 Python 标准库，不需要装任何依赖。

## 为什么是设备主动上报
云手机跑在机房 NAT 后面，没有公网 IP，**服务器无法主动连它**。
所以协议只能是设备定时来敲服务器：

    GET  /api/config?deviceId=xxx   设备拉取自己的配置
    POST /api/report                设备上报自己的状态

## 启动
    python3 tools/control-server.py                  # 监听 0.0.0.0:8899
    python3 tools/control-server.py --port 9000

然后在 App 里填： http://<这台机器的公网IP>:8899

## 网页面板
浏览器打开 http://127.0.0.1:8899/
可以看到所有已上报的设备、最后在线时间、状态，并逐台编辑配置。

## 配置字段（设备端认识的）
    revision     必填，改了它设备才会重新加载
    targetPkg    目标游戏包名（前台门禁用）
    pressMs      点击按压时长（毫秒）
    skillPoints  技能键坐标 [[x,y], ...]（归一化）
    notes        备注，设备端只记录

存储：同目录下的 control-store.json（首次运行自动创建）
"""

import argparse
import base64
import hmac
import html
import json
import os
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

STORE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "control-store.json")
LOCK = threading.Lock()

def gen_secret(nbytes=18):
    """生成 URL 安全的随机密钥。"""
    return secrets.token_urlsafe(nbytes)


def ensure_auth(st):
    """确保存储里有凭据；首次运行自动生成并落盘。

    刻意把「面板密码」和「设备 token」分成两套：
      · 面板密码 —— 人用，走 HTTP Basic Auth
      · 设备 token —— APK 用，走 X-Altair-Token 头
    两者独立，APK 里存的 token 泄漏不会连带泄漏面板密码。
    """
    a = st.setdefault("auth", {})
    changed = False
    if not a.get("panelUser"):
        a["panelUser"] = "admin"; changed = True
    if not a.get("panelPass"):
        a["panelPass"] = gen_secret(12); changed = True
    if not a.get("deviceToken"):
        a["deviceToken"] = gen_secret(18); changed = True
    if changed:
        save_store(st)
    return a



# 注意：必须是**原始字符串** r""" —— 里面 JS 的 \n 要原样保留。
# 用普通 """ 会让 Python 把 \n 转成真实换行，把 JS 字符串字面量截断，
# 结果是整个面板脚本语法失效、页面永远停在「加载中」。
PANEL_HTML = r"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>阿尔泰挂机 · 监控台</title>
<style>
 *{box-sizing:border-box}
 body{background:#0d1014;color:#d8dde3;font:13px/1.65 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:0;padding:20px}
 h1{font-size:16px;margin:0 0 2px;font-weight:600;letter-spacing:.02em}
 h2{font-size:11px;color:#7c8899;margin:26px 0 10px;letter-spacing:.1em;font-weight:600}
 .muted{color:#6c7787;font-size:11px}
 .icon{width:14px;height:14px;flex:none;fill:currentColor;vertical-align:-2px}
 .icon.lg{width:16px;height:16px}
 header{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:16px}
 .stats{display:flex;gap:10px;flex-wrap:wrap;margin-left:auto}
 .stat{background:#151a21;border:1px solid #262d36;border-radius:8px;padding:7px 14px;min-width:92px}
 .stat b{display:block;font-size:19px;color:#eaf0f6;line-height:1.25;font-variant-numeric:tabular-nums}
 .stat span{font-size:10.5px;color:#7c8899}
 .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(360px,1fr));gap:13px}
 .card{background:#141920;border:1px solid #262d36;border-radius:10px;padding:14px;position:relative}
 .card.on{border-color:#2c6b41} .card.warm{border-color:#6b5c2c} .card.off{border-color:#5a2a30;opacity:.75}
 .chead{display:flex;align-items:center;gap:8px;margin-bottom:2px}
 .did{font-family:ui-monospace,monospace;color:#7fd18b;font-size:13.5px;font-weight:600}
 .model{font-size:11px;color:#8b97a6;margin-left:2px}
 .age{position:absolute;right:14px;top:13px;font-size:11px;display:flex;align-items:center;gap:5px}
 .dot{width:8px;height:8px;border-radius:50%;display:inline-block}
 .sec{margin-top:11px;padding-top:10px;border-top:1px solid #212832}
 .rowk{display:flex;justify-content:space-between;font-size:11.5px;color:#9aa7b6;margin:2px 0}
 .rowk b{color:#d8dde3;font-weight:500;font-variant-numeric:tabular-nums}
 .badge{display:inline-flex;align-items:center;gap:5px;font-size:11px;padding:2px 9px;border-radius:20px;font-weight:500}
 .b-run{background:#12331f;color:#7fd18b} .b-idle{background:#1e242c;color:#8b97a6}
 .b-err{background:#3a1b1f;color:#ff8b8b} .b-cast{background:#33301a;color:#ffd479}
 .prog{height:5px;background:#1e242c;border-radius:3px;overflow:hidden;margin:7px 0 4px}
 .prog i{display:block;height:100%;background:linear-gradient(90deg,#2563eb,#3bd16f);transition:width .9s linear}
 .cd{font-family:ui-monospace,monospace;font-size:19px;color:#eaf0f6;font-variant-numeric:tabular-nums}
 .bl{display:flex;gap:6px;flex-wrap:wrap;font-size:10.5px;margin-top:7px}
 .bl span{background:#1a2028;border:1px solid #262d36;border-radius:5px;padding:2px 8px;color:#8b97a6}
 .bl span.on{color:#7fd18b;border-color:#2c6b41}
 .acts{display:flex;gap:7px;margin-top:12px}
 button{background:#242c36;color:#d8dde3;border:1px solid #313a45;border-radius:7px;padding:7px 13px;
        cursor:pointer;font-size:12px;display:inline-flex;align-items:center;gap:6px}
 button:hover{background:#2e3844}
 button.pri{background:#2563eb;border-color:#2563eb;color:#fff}
 button.pri:hover{background:#3b82f6}
 button.dan{background:#3a1b1f;border-color:#5a2a30;color:#ff8b8b}
 button.dan:hover{background:#4a2228}
 input,select{background:#0b0e12;color:#c9d4e0;border:1px solid #313a45;border-radius:6px;padding:6px 9px;font-size:12px}
 textarea{width:100%;height:150px;background:#0b0e12;color:#c9d4e0;border:1px solid #313a45;border-radius:7px;
          padding:10px;font-family:ui-monospace,monospace;font-size:11.5px}
 .form{background:#141920;border:1px solid #262d36;border-radius:10px;padding:16px;display:grid;gap:11px;
       grid-template-columns:repeat(auto-fit,minmax(210px,1fr))}
 .form label{display:flex;flex-direction:column;gap:5px;font-size:11px;color:#8b97a6}
 .form label input,.form label select{width:100%}
 .buffrow{display:flex;gap:8px;align-items:center;font-size:11.5px;color:#9aa7b6;margin:3px 0}
 .buffrow input[type=number]{width:62px}
 .buffrow input[type=text]{width:52px}
 .full{grid-column:1/-1}
 .token{background:#0b0e12;border:1px solid #313a45;border-radius:7px;padding:10px;
        font-family:ui-monospace,monospace;font-size:13px;color:#7fd18b;word-break:break-all}
 #modal{position:fixed;inset:0;background:#000a;display:none;align-items:center;justify-content:center;padding:24px;z-index:50}
 #modal.show{display:flex}
 .sheet{background:#141920;border:1px solid #313a45;border-radius:12px;width:min(820px,100%);
        max-height:82vh;display:flex;flex-direction:column;overflow:hidden}
 .shead{display:flex;align-items:center;gap:10px;padding:13px 16px;border-bottom:1px solid #212832}
 .shead b{font-family:ui-monospace,monospace;color:#7fd18b;font-size:13px}
 .shead button{margin-left:auto}
 .sbody{overflow:auto;padding:14px 16px;font-family:ui-monospace,monospace;font-size:11.5px;
        color:#9aa7b6;white-space:pre-wrap;word-break:break-all;line-height:1.75}
 .empty{color:#6c7787;text-align:center;padding:34px;border:1px dashed #262d36;border-radius:10px}
</style></head><body>

<header>
  <h1>阿尔泰挂机 · 监控台</h1>
  <div class="stats">
    <div class="stat"><b id="s-total">-</b><span>设备总数</span></div>
    <div class="stat"><b id="s-online" style="color:#7fd18b">-</b><span>在线 &lt;3min</span></div>
    <div class="stat"><b id="s-run" style="color:#7fd18b">-</b><span>挂机中</span></div>
    <div class="stat"><b id="s-cycle">-</b><span>累计轮次</span></div>
    <div class="stat"><b id="s-upd">-</b><span>距刷新</span></div>
  </div>
</header>

<h2>设备</h2>
<div id="devs"><div class="empty">加载中…</div></div>

<h2>设备 Token（填到 App 的「集控 Token」）</h2>
<div class="token" id="token">__TOKEN__</div>
<div class="muted" style="margin-top:6px">
  面板账号 <code>__USER__</code> 的口令不在此显示 · 服务器执行
  <code>python3 control-server.py --show</code> 查看或更换
</div>

<h2>参数下发</h2>
<div class="form">
  <label>目标设备
    <select id="f-dev" onchange="loadCfg()"></select>
  </label>
  <label>目标游戏包名（前台门禁用）
    <input id="f-pkg" placeholder="com.nexon.mod">
  </label>
  <label>技能键输入方式
    <select id="f-input">
      <option value="keyevent">键盘按键 input keyevent</option>
      <option value="touch">触摸点击 input swipe（需先采点）</option>
    </select>
  </label>
  <label>备注
    <input id="f-notes" placeholder="例如：方案A / 主教">
  </label>
  <div class="full">
    <div class="muted" style="margin-bottom:5px">BUFF 配置（启用的里取最短时长 × 0.94 作为循环周期）</div>
    <div id="f-buffs"></div>
  </div>
  <div class="full" style="display:flex;gap:9px;align-items:center;flex-wrap:wrap">
    <button class="pri" onclick="pushCfg()">下发配置</button>
    <button onclick="loadCfg(true)">重新载入</button>
    <span id="msg" class="muted"></span>
  </div>
</div>

<div id="modal" onclick="closeModal()">
  <div class="sheet" onclick="event.stopPropagation()">
    <div class="shead">
      <span class="icon lg">__ICO_LOG__</span>
      <b id="m-title">日志</b>
      <button onclick="closeModal()">关闭</button>
    </div>
    <div class="sbody" id="m-body"></div>
  </div>
</div>

<script>
/* ---------- 内联 SVG 图标（不依赖任何外部库，离线可用） ---------- */
const ICO = {
  play : '<path d="M8 5v14l11-7z"/>',
  stop : '<path d="M6 6h12v12H6z"/>',
  log  : '<path d="M4 5h16v2H4zm0 5h16v2H4zm0 5h10v2H4z"/>',
  phone: '<path d="M17 1H7a2 2 0 0 0-2 2v18a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V3a2 2 0 0 0-2-2zm0 18H7V5h10v14z"/>',
  clock: '<path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 11H9v-2h2V6h2z"/>',
  gear : '<path d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm9 4a9 9 0 0 0-.1-1.3l2-1.6-2-3.4-2.4 1a9 9 0 0 0-2.2-1.3L15.8 2h-3.9l-.4 2.4a9 9 0 0 0-2.2 1.3l-2.4-1-2 3.4 2 1.6A9 9 0 0 0 6.8 12c0 .4 0 .9.1 1.3l-2 1.6 2 3.4 2.4-1a9 9 0 0 0 2.2 1.3l.4 2.4h3.9l.4-2.4a9 9 0 0 0 2.2-1.3l2.4 1 2-3.4-2-1.6c.1-.4.1-.9.1-1.3z"/>',
  refresh:'<path d="M12 5V1L7 6l5 5V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z"/>'
};
const svg = (n, big) => '<svg class="icon' + (big ? ' lg' : '') + '" viewBox="0 0 24 24">' + ICO[n] + '</svg>';

const REFRESH = 5000;
let left = REFRESH / 1000;
let cfgCache = {};

function esc(t){ return String(t==null?'':t).replace(/[<>&]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;'}[c])); }

function ago(t){
  if(!t) return '—';
  const s = Math.max(0, Math.floor(Date.now()/1000 - t));
  if(s < 60) return s + 's 前';
  if(s < 3600) return Math.floor(s/60) + 'm 前';
  if(s < 86400) return Math.floor(s/3600) + 'h 前';
  return Math.floor(s/86400) + 'd 前';
}

function dotColor(age){ return age < 180 ? '#3bd16f' : (age < 900 ? '#ffc53d' : '#ff6b6b'); }

function stateBadge(e){
  const st = (e && e.state) || 'IDLE';
  const map = { WAITING:['b-run','挂机中 · 等待'], CASTING:['b-cast','正在补 BUFF'],
                IDLE:['b-idle','已停止'], PAUSED:['b-idle','已暂停'], ERROR:['b-err','出错已熔断'] };
  const [cls, txt] = map[st] || ['b-idle', st];
  return '<span class="badge ' + cls + '">' + txt + '</span>';
}

/* 倒计时：用设备上报的 ts 校正时钟偏差，避免两边时钟不一致导致显示错乱 */
function countdown(e, ts){
  if(!e || !e.nextDueAt || !e.running) return {txt:'—', pct:0};
  const skew = ts ? (Date.now() - ts) : 0;
  const nowDev = Date.now() - skew;
  const leftMs = Math.max(0, e.nextDueAt - nowDev);
  const period = e.cyclePeriodMs || 0;
  const mm = Math.floor(leftMs/60000), ss = Math.floor((leftMs%60000)/1000);
  return { txt: mm + ':' + String(ss).padStart(2,'0'),
           pct: period > 0 ? Math.min(100, 100*(1 - leftMs/period)) : 0 };
}

async function refresh(){
  let st;
  try { st = await (await fetch('/api/devices', {cache:'no-store'})).json(); }
  catch(e){ document.getElementById('devs').innerHTML = '<div class="empty">连接失败</div>'; return; }
  cfgCache = st;

  const devs = st.devices || {};
  const ids = Object.keys(devs).sort((a,b) => (devs[b].lastSeen||0) - (devs[a].lastSeen||0));
  const now = Date.now()/1000;
  let online = 0, running = 0, cycles = 0;

  const cards = ids.map(id => {
    const d = devs[id] || {}, r = d.lastReport || {}, e = r.engine || {};
    const age = now - (d.lastSeen || now);
    if(age < 180) online++;
    if(e.running) running++;
    cycles += (e.cycleCount || 0);
    const cls = age < 180 ? 'on' : (age < 900 ? 'warm' : 'off');
    const cd = countdown(e, r.ts);

    const buffs = (e.buffs || []).map(b =>
      '<span class="' + (b.enabled ? 'on' : '') + '">BUFF' + b.idx +
      ' · 键' + b.key + ' · ' + b.durationMin + '分</span>').join('');

    return `<div class="card ${cls}">
      <div class="age"><span class="dot" style="background:${dotColor(age)}"></span>${ago(d.lastSeen)}</div>
      <div class="chead">${svg('phone','lg')}
        <span class="did">${esc(id)}</span>
      </div>
      <div class="model">${esc(r.model || '未知机型')} · Android ${esc(r.android || '?')} · v${esc(r.versionName || '?')}</div>

      <div class="sec">
        <div class="rowk"><span>引擎状态</span><span>${stateBadge(e)}</span></div>
        <div class="prog"><i style="width:${cd.pct.toFixed(1)}%"></i></div>
        <div style="display:flex;justify-content:space-between;align-items:flex-end">
          <span class="muted">距下次补 BUFF</span><span class="cd">${cd.txt}</span>
        </div>
        <div class="rowk"><span>已完成</span><b>${e.cycleCount || 0} 轮</b></div>
        <div class="rowk"><span>输入方式</span><b>${e.inputMethod === 'touch' ? '触摸点击' : '键盘按键'}</b></div>
        ${e.lastResult ? '<div class="rowk"><span>上次结果</span><b>' + esc(e.lastResult) + '</b></div>' : ''}
        ${e.lastError ? '<div class="rowk" style="color:#ff8b8b"><span>最近错误</span><b style="color:#ff8b8b">' + esc(e.lastError) + '</b></div>' : ''}
        ${buffs ? '<div class="bl">' + buffs + '</div>' : ''}
      </div>

      <div class="sec">
        <div class="rowk"><span>前台应用</span><b>${esc(r.foreground || '—')}</b></div>
        <div class="rowk"><span>门禁</span><b style="color:${r.armed ? '#7fd18b' : '#ffc53d'}">${r.armed ? '已启用' : '已禁用'}</b></div>
      </div>

      <div class="acts">
        <button class="pri" onclick="cmd('${id}','start')">${svg('play')}启动</button>
        <button class="dan" onclick="cmd('${id}','stop')">${svg('stop')}停止</button>
        <button onclick="openLog('${id}')">${svg('log')}日志</button>
        <button onclick="loadCfg(false,'${id}')">${svg('gear')}参数</button>
      </div>
    </div>`;
  }).join('');

  document.getElementById('devs').innerHTML = cards ||
    '<div class="empty">还没有设备上报。在 App 里填服务器地址 + Token，点「上报一次」。</div>';
  document.getElementById('s-total').textContent = ids.length;
  document.getElementById('s-online').textContent = online;
  document.getElementById('s-run').textContent = running;
  document.getElementById('s-cycle').textContent = cycles;

  // 设备下拉
  const sel = document.getElementById('f-dev');
  const prev = sel.value;
  sel.innerHTML = '<option value="">（默认配置，对所有未单独配置的设备生效）</option>' +
    ids.map(i => '<option value="' + i + '">' + i + '</option>').join('');
  if(ids.includes(prev)) sel.value = prev;
}

async function cmd(dev, action){
  const r = await fetch('/api/command', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify({deviceId: dev, action: action})});
  const j = await r.json();
  if(!j.ok){ alert('下发失败: ' + j.error); return; }
  toast('已下发「' + (action==='start'?'启动':'停止') + '」，设备下次轮询（≤60s）后生效');
}

function toast(t){
  const el = document.getElementById('msg');
  el.textContent = t;
  setTimeout(() => { if(el.textContent === t) el.textContent = ''; }, 5000);
}

function openLog(dev){
  const d = (cfgCache.devices || {})[dev] || {};
  const r = d.lastReport || {};
  const tail = r.logTail || [];
  document.getElementById('m-title').textContent = dev + ' · 最近 ' + tail.length + ' 行日志';
  document.getElementById('m-body').textContent = tail.length ? tail.join('\n')
    : '（这台设备最近一次上报没有带日志）';
  document.getElementById('modal').classList.add('show');
}
function closeModal(){ document.getElementById('modal').classList.remove('show'); }
document.addEventListener('keydown', e => { if(e.key === 'Escape') closeModal(); });

/* ---------- 参数表单 ---------- */
function buffRows(cfg){
  const b = cfg.buff || [];
  let html = '';
  for(let i = 0; i < 3; i++){
    const x = b[i] || {idx:i+1, enabled:false, key:i+1, durationMin:5};
    html += '<div class="buffrow">' +
      '<input type="checkbox" id="b-en' + i + '"' + (x.enabled ? ' checked' : '') + '>' +
      '<span style="width:52px">BUFF' + (i+1) + '</span>' +
      '数字键 <input type="number" id="b-key' + i + '" min="1" max="4" value="' + (x.key||i+1) + '">' +
      '持续 <input type="number" id="b-dur' + i + '" min="1" max="240" value="' + (x.durationMin||5) + '"> 分钟' +
      '</div>';
  }
  document.getElementById('f-buffs').innerHTML = html;
}

function loadCfg(force, dev){
  const sel = document.getElementById('f-dev');
  if(dev) sel.value = dev;
  const id = sel.value;
  const cfg = id ? ((cfgCache.devices || {})[id] || {}).config : cfgCache.defaultConfig;
  const c = cfg || cfgCache.defaultConfig || {};
  document.getElementById('f-pkg').value = c.targetPkg || '';
  document.getElementById('f-input').value = c.inputMethod || 'keyevent';
  document.getElementById('f-notes').value = c.notes || '';
  buffRows(c);
  if(force) toast('已重新载入' + (id ? ('设备 ' + id) : '默认配置'));
}

async function pushCfg(){
  const id = document.getElementById('f-dev').value;
  const old = (id ? ((cfgCache.devices || {})[id] || {}).config : cfgCache.defaultConfig) || {};
  const buff = [];
  for(let i = 0; i < 3; i++){
    buff.push({
      idx: i+1,
      enabled: document.getElementById('b-en' + i).checked,
      key: parseInt(document.getElementById('b-key' + i).value, 10) || (i+1),
      durationMin: parseInt(document.getElementById('b-dur' + i).value, 10) || 5
    });
  }
  const cfg = Object.assign({}, old, {
    deviceId: id,
    revision: 'r' + Date.now(),          // 必须变，否则设备不会重新加载
    targetPkg: document.getElementById('f-pkg').value.trim(),
    inputMethod: document.getElementById('f-input').value,
    notes: document.getElementById('f-notes').value.trim(),
    buff: buff
  });
  const r = await fetch('/api/config', {method:'POST', headers:{'Content-Type':'application/json'},
    body: JSON.stringify(cfg)});
  const j = await r.json();
  toast(j.ok ? ('已下发 revision=' + j.config.revision + '，设备下次轮询后生效')
             : ('失败: ' + j.error));
  if(j.ok) setTimeout(refresh, 600);
}

setInterval(() => {
  left -= 0.25;
  if(left <= 0){ left = REFRESH/1000; refresh(); }
  document.getElementById('s-upd').textContent = Math.max(0,left).toFixed(1) + 's';
}, 250);

refresh();
</script>
</body></html>"""


DEFAULT_CONFIG = {
    "revision": "r1",
    "targetPkg": "com.nexon.mod",
    "pressMs": 90,
    "skillPoints": [],
    "inputMethod": "keyevent",
    "buff": [
        {"idx": 1, "enabled": True,  "key": 1, "durationMin": 5},
        {"idx": 2, "enabled": False, "key": 2, "durationMin": 5},
        {"idx": 3, "enabled": False, "key": 3, "durationMin": 5},
    ],
    "notes": "初始配置",
}


# ---------------------------------------------------------------- 存储

def load_store():
    if not os.path.exists(STORE_PATH):
        return {"devices": {}, "defaultConfig": dict(DEFAULT_CONFIG)}
    try:
        with open(STORE_PATH, encoding="utf-8") as f:
            d = json.load(f)
        d.setdefault("devices", {})
        d.setdefault("defaultConfig", dict(DEFAULT_CONFIG))
        return d
    except Exception as e:
        print(f"[warn] 存储损坏，重建: {e}")
        return {"devices": {}, "defaultConfig": dict(DEFAULT_CONFIG)}


def save_store(d):
    tmp = STORE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
    os.replace(tmp, STORE_PATH)


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "AltairControl/1.0"

    # ---------- 鉴权 ----------

    def _auth(self):
        with LOCK:
            return dict(ensure_auth(load_store()))

    def _panel_ok(self):
        """面板：HTTP Basic Auth。浏览器会原生弹出登录框。"""
        hdr = self.headers.get("Authorization", "") or ""
        if not hdr.startswith("Basic "):
            return False
        try:
            raw = base64.b64decode(hdr[6:]).decode("utf-8")
            u, p = raw.split(":", 1)
        except Exception:
            return False
        a = self._auth()
        return (hmac.compare_digest(u, a["panelUser"])
                and hmac.compare_digest(p, a["panelPass"]))

    def _device_ok(self):
        """设备 API：随机 Token，走 X-Altair-Token 头（也允许 ?token= 便于调试）。"""
        a = self._auth()
        tok = self.headers.get("X-Altair-Token", "") or ""
        if not tok:
            q = parse_qs(urlparse(self.path).query)
            tok = (q.get("token") or [""])[0]
        if not tok:
            return False
        return hmac.compare_digest(tok, a["deviceToken"])

    # 只给面板用的路径（走 Basic Auth）。其余 /api/* 都是设备接口（走 Token）。
    PANEL_PATHS = {"/", "/index.html", "/api/devices"}

    def _auth_route(self):
        """按路径分流鉴权。通过返回 True；被拒绝返回 False（响应已发出）。"""
        path = urlparse(self.path).path
        if path == "/healthz":
            return True                              # 探活免鉴权
        if path in self.PANEL_PATHS:
            if self._panel_ok():
                return True
            self._deny_panel()
            return False
        if path.startswith("/api/"):
            if self._device_ok():
                return True
            self._deny_device()
            return False
        # 其它路径（静态资源等）一律按面板处理
        if self._panel_ok():
            return True
        self._deny_panel()
        return False

    def _deny_panel(self):
        # 认证失败退避，拖慢暴力破解
        time.sleep(1.0)
        body = b"\n401 Unauthorized\n"
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="Altair Control"')
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print(f"[auth] 面板认证失败 来自 {self.client_address[0]}")

    def _deny_device(self):
        time.sleep(0.5)
        self._json({"ok": False, "error": "invalid or missing device token"}, 401)
        print(f"[auth] 设备 token 无效 来自 {self.client_address[0]}")

    # ---------- 工具 ----------
    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _html(self, text, code=200):
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        if n <= 0:
            return {}
        raw = self.rfile.read(n)
        return json.loads(raw.decode("utf-8"))

    # ---------- GET ----------
    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)

        # 健康检查不需要鉴权（便于探活/排障）
        if u.path == "/healthz":
            return self._json({"ok": True, "ts": int(time.time())})

        if not self._auth_route():
            return

        if u.path == "/api/config":
            dev = (q.get("deviceId") or [""])[0]
            with LOCK:
                st = load_store()
                cfg = None
                desired = None
                if dev and dev in st["devices"]:
                    cfg = st["devices"][dev].get("config")
                    desired = st["devices"][dev].get("desired")
                if not cfg:
                    cfg = st["defaultConfig"]
                cfg = dict(cfg)                       # 别改到存储里的对象
                cfg["desired"] = desired or {"running": False, "rev": 0}
            print(f"[config] 下发 -> device={dev or '(未指定)'} revision={cfg.get('revision')} "
                  f"desired_running={cfg['desired'].get('running')}")
            return self._json(cfg)

        if u.path in ("/", "/index.html"):
            return self._html(self._panel())

        if u.path == "/api/devices":
            with LOCK:
                st = load_store()
            return self._json(st)

        return self._json({"error": "not found"}, 404)

    # ---------- POST ----------
    def do_POST(self):
        u = urlparse(self.path)

        if not self._auth_route():
            return

        if u.path == "/api/report":
            try:
                data = self._read_json()
            except Exception as e:
                return self._json({"ok": False, "error": f"bad json: {e}"}, 400)
            dev = str(data.get("deviceId") or "unknown")
            with LOCK:
                st = load_store()
                d = st["devices"].setdefault(dev, {})
                d["lastReport"] = data
                d["lastSeen"] = time.time()
                d.setdefault("firstSeen", time.time())
                save_store(st)
            armed = "已启用" if data.get("armed") else "已禁用"
            print(f"[report] {dev}  v{data.get('versionName')}  "
                  f"前台={data.get('foreground')}  动作{armed}  [{time.strftime('%H:%M:%S')}]")
            return self._json({"ok": True, "ts": int(time.time())})

        if u.path == "/api/command":
            # 面板下发启停指令。
            # 云手机在 NAT 后服务器连不上它，所以这里只是写"期望状态"，
            # 设备下次轮询 /api/config 时把 desired 带回去执行。
            # 用 rev 去重，避免设备每轮都重复启停。
            try:
                data = self._read_json()
            except Exception as e:
                return self._json({"ok": False, "error": str(e)}, 400)
            dev = str(data.get("deviceId") or "")
            if not dev:
                return self._json({"ok": False, "error": "缺少 deviceId"}, 400)
            action = str(data.get("action") or "")
            if action not in ("start", "stop"):
                return self._json({"ok": False, "error": "action 只能是 start/stop"}, 400)
            with LOCK:
                st = load_store()
                d = st["devices"].setdefault(dev, {})
                d["desired"] = {"running": action == "start",
                                "rev": int(d.get("desired", {}).get("rev", 0)) + 1,
                                "at": time.time()}
                save_store(st)
            print(f"[command] {dev} <- {action}  (rev={d['desired']['rev']})")
            return self._json({"ok": True, "desired": d["desired"]})

        if u.path == "/api/config":
            # 面板提交：设置某台设备（或默认）的配置
            try:
                data = self._read_json()
            except Exception as e:
                return self._json({"ok": False, "error": str(e)}, 400)
            dev = str(data.pop("deviceId", "") or "")
            data["revision"] = str(data.get("revision") or f"r{int(time.time())}")
            if isinstance(data.get("skillPoints"), str):
                try:
                    data["skillPoints"] = json.loads(data["skillPoints"])
                except Exception:
                    data["skillPoints"] = []
            with LOCK:
                st = load_store()
                if dev:
                    st["devices"].setdefault(dev, {})["config"] = data
                    target = f"设备 {dev}"
                else:
                    st["defaultConfig"] = data
                    target = "默认配置"
                save_store(st)
            print(f"[config] 更新 {target} -> revision={data['revision']}")
            return self._json({"ok": True, "config": data})

        return self._json({"error": "not found"}, 404)

    # ---------- 面板 ----------

    def _panel(self):
        with LOCK:
            st = load_store()
            ensure_auth(st)
        return (PANEL_HTML
                .replace("__ICO_LOG__", '<path d="M4 5h16v2H4zm0 5h16v2H4zm0 5h10v2H4z"/>')
                .replace("__TOKEN__", html.escape(st["auth"]["deviceToken"]))
                .replace("__USER__", html.escape(st["auth"]["panelUser"])))

    def log_message(self, fmt, *args):
        pass  # 只打印我们自己关心的行


def main():
    # 关闭输出缓冲，否则后台运行时日志要等很久才刷出来
    try:
        sys.stdout.reconfigure(line_buffering=True)
        sys.stderr.reconfigure(line_buffering=True)
    except Exception:
        pass

    ap = argparse.ArgumentParser(description="阿尔泰挂机 · 集控服务器")
    ap.add_argument("--port", type=int, default=8899)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--show", action="store_true", help="打印当前凭据后退出")
    ap.add_argument("--set-password", metavar="新密码", help="修改面板密码后退出")
    ap.add_argument("--set-token", metavar="新Token", help="修改设备 Token 后退出")
    a = ap.parse_args()

    with LOCK:
        st = load_store()
        auth = ensure_auth(st)
        save_store(st)

    if a.show:
        print("=" * 66)
        print("  当前凭据")
        print("=" * 66)
        print(f"  面板地址    http://<本机IP>:{a.port}/")
        print(f"  面板账号    {auth['panelUser']}")
        print(f"  面板密码    {auth['panelPass']}")
        print(f"  设备 Token  {auth['deviceToken']}")
        print("=" * 66)
        return

    if a.set_password or a.set_token:
        with LOCK:
            st = load_store()
            au = ensure_auth(st)
            if a.set_password:
                au["panelPass"] = a.set_password
                print(f"面板密码已更新为: {a.set_password}")
            if a.set_token:
                au["deviceToken"] = a.set_token
                print(f"设备 Token 已更新为: {a.set_token}")
            save_store(st)
        print("（改完记得重启服务： systemctl restart altair-control）")
        return

    print("=" * 66)
    print("  阿尔泰挂机 · 集控服务器")
    print("=" * 66)
    print(f"  面板        http://<本机IP>:{a.port}/")
    print(f"  面板账号    {auth['panelUser']}")
    print(f"  面板密码    {auth['panelPass']}")
    print(f"  设备 Token  {auth['deviceToken']}")
    print(f"  存储        {STORE_PATH}")
    print(f"  已有设备    {len(st['devices'])} 台")
    print("-" * 66)
    print("  再次查看凭据: python3 control-server.py --show")
    print("  改登录口令  : python3 control-server.py --set-password 新口令")
    print("  改设备Token : python3 control-server.py --set-token 新Token")
    print("=" * 66)
    print()
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
