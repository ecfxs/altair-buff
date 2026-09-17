#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 发布前静态检查
#
# 起因：control-server.py 里的网页面板用了普通三引号字符串存 HTML，
# 导致 JS 里的 \n 被 Python 转成真实换行、把字符串字面量截断，
# 整个面板脚本语法失效，页面永远停在「加载中」——
# 而这种错 Python 语法检查发现不了，只有真正把 JS 抠出来跑一次才知道。
#
# 本脚本做三件事：
#   1. 所有 tools/*.py 的 Python 语法检查
#   2. 启动 control-server，带着鉴权抓面板页，抽出内联 JS 做 node --check
#   3. 用最小 DOM 桩**真跑一遍面板的 refresh()**，确认能渲染出设备卡片
#
# 用法: bash tools/check-assets.sh
# ---------------------------------------------------------------------------
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
FAIL=0
PORT=8897

say()  { printf "  %s\n" "$*"; }
ok()   { printf "  ✅ %s\n" "$*"; }
bad()  { printf "  ❌ %s\n" "$*"; FAIL=1; }

echo "==== 1) Python 语法 ===="
for f in tools/*.py; do
  if python3 -c "import ast,sys;ast.parse(open('$f',encoding='utf-8').read())" 2>/dev/null; then
    ok "$f"
  else
    bad "$f"
    python3 -c "import ast;ast.parse(open('$f',encoding='utf-8').read())" 2>&1 | tail -3 | sed 's/^/       /'
  fi
done

echo
echo "==== 2) 网页面板内联 JS 语法 ===="
if ! command -v node >/dev/null; then
  say "跳过（未安装 node）"
else
  rm -f tools/control-store.json
  (python3 tools/control-server.py --port $PORT > /tmp/_chk_srv.log 2>&1 &)
  sleep 2
  CRED=$(python3 -c "
import json
try:
    d=json.load(open('tools/control-store.json'))['auth']
    print(d['panelUser'], d['panelPass'])
except Exception: print('admin x')")
  read -r U P <<< "$CRED"
  if curl -sS -m 8 -u "$U:$P" -o /tmp/_chk_panel.html "http://127.0.0.1:$PORT/" 2>/dev/null; then
    python3 - <<'PY'
import re
h=open('/tmp/_chk_panel.html',encoding='utf-8').read()
m=re.search(r'<script>(.*?)</script>', h, re.S)
open('/tmp/_chk_panel.js','w',encoding='utf-8').write(m.group(1) if m else '')
print(f"  · 面板 {len(h)} 字节，内联 JS {len(m.group(1)) if m else 0} 字节")
PY
    if node --check /tmp/_chk_panel.js 2>/tmp/_chk_jserr; then
      ok "内联 JS 语法"
    else
      bad "内联 JS 语法"
      head -8 /tmp/_chk_jserr | sed 's/^/       /'
    fi
    # 关键：真跑一遍 refresh()
    cat > /tmp/_chk_run.js <<'EOF'
const els={};
const mk=id=>(els[id]={id,innerHTML:'加载中…',textContent:'',value:'',checked:false,style:{},
  classList:{add(){},remove(){},contains(){return false}},matches:()=>false});
// 面板会用到哪些 DOM id，桩就得有哪些 —— 缺一个就会在检查里暴露出来
['s-total','s-online','s-run','s-cycle','s-upd','devs','token','f-dev','f-pkg','f-input',
 'f-notes','f-buffs','msg','modal','m-title','m-body',
 'b-en0','b-en1','b-en2','b-key0','b-key1','b-key2','b-dur0','b-dur1','b-dur2'].forEach(mk);
global.document={getElementById:id=>els[id]||mk(id), addEventListener(){}, querySelector:()=>null};
global.setInterval=()=>0;
// ★ 保存真实定时器再覆盖。之前直接吞掉带延时的回调，导致断言根本没执行 ——
//   那是最危险的一类失败：检查显示"通过"，其实什么都没验。
const __realST = global.setTimeout.bind(global);
global.setTimeout = (f, t) => __realST(f, Math.min(t === undefined ? 0 : t, 60));
global.alert=()=>{};
global.fetch=async u=>u.startsWith('/api/devices')
  ? {json:async()=>({devices:{'dev1':{lastSeen:Date.now()/1000-10,
      lastReport:{versionName:'x',armed:true,foreground:'g',targetPkg:'g',logTail:['a']}}},
      defaultConfig:{revision:'r1'}})}
  : {json:async()=>({ok:true,config:{revision:'r1'}})};
EOF
    cat /tmp/_chk_panel.js >> /tmp/_chk_run.js
    cat >> /tmp/_chk_run.js <<'EOF'
global.__asserted = false;
setTimeout(()=>{
  global.__asserted = true;
  const rendered = els['devs'].innerHTML.includes('dev1');
  const total = String(els['s-total'].textContent) === '1';
  if (rendered && total) { console.log('  ✅ refresh() 真跑通过，设备卡片能渲染'); process.exit(0); }
  console.log('  ❌ refresh() 未渲染出设备卡片'); console.log('     devs=',els['devs'].innerHTML.slice(0,100));
  console.log('     total=',els['s-total'].textContent); process.exit(1);
},300);
// 看门狗：断言若从未执行，判失败（防止"假通过"）
__realST(()=>{
  if(!global.__asserted){ console.log('  ❌ 断言未执行 —— 检查脚本自身有问题，判失败'); process.exit(1); }
}, 2500);
EOF
    if ! node /tmp/_chk_run.js; then FAIL=1; fi
  else
    bad "抓不到面板页（服务没起来？）"
    tail -5 /tmp/_chk_srv.log | sed 's/^/       /'
  fi
  pkill -f "control-server.py" 2>/dev/null
  rm -f tools/control-store.json tools/control-store.json.tmp
fi

echo
if [ "$FAIL" = "0" ]; then
  echo "==== 静态检查全部通过 ✅ ===="
else
  echo "==== 静态检查发现问题 ❌ ===="
fi
exit $FAIL
