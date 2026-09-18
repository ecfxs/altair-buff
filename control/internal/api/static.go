package api

import (
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"strings"

	"altair/internal/webassets"
)

// staticHandler 提供前端产物，并对未知路径回退 index.html（SPA 路由）。
//
// 默认用 go:embed 打进二进制的产物；--static-dir 指定时改读磁盘（开发时改前端不必重编 Go）。
func (s *Server) staticHandler() http.Handler {
	var sub fs.FS
	var err error
	if s.staticDir != "" {
		sub = os.DirFS(s.staticDir)
		slog.Info("前端产物改从磁盘读取（开发模式）", "dir", s.staticDir)
	} else {
		sub, err = fs.Sub(webassets.FS, "dist")
		if err != nil {
			slog.Error("前端产物不可用", "err", err)
		}
	}
	if err != nil {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			http.Error(w, "前端产物缺失", http.StatusInternalServerError)
		})
	}
	files := http.FileServer(http.FS(sub))
	index, indexErr := fs.ReadFile(sub, "index.html")
	if indexErr != nil {
		slog.Warn("前端产物里没有 index.html —— 面板页不可用（跑 make web 生成）", "err", indexErr)
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(r.URL.Path, "/")
		if p == "" {
			p = "index.html"
		}
		if f, err := sub.Open(p); err == nil {
			_ = f.Close()
			// 带内容哈希的静态资源可以长缓存；index.html 不缓存，保证升级后立刻生效
			if strings.HasPrefix(p, "assets/") {
				w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
			} else {
				w.Header().Set("Cache-Control", "no-cache")
			}
			files.ServeHTTP(w, r)
			return
		}
		if indexErr != nil {
			// 二进制里没打包前端（还没跑 make web）—— 给一条能自救的提示，而不是裸 404
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write([]byte(missingWebPage))
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache")
		_, _ = w.Write(index)
	})
}

const missingWebPage = `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<title>前端未构建</title></head>
<body style="background:#0d1014;color:#d8dde3;font:13px/1.65 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif;padding:40px">
<h1 style="font-size:16px">前端尚未构建</h1>
<p style="color:#8b97a6">后端 API 已在运行。构建面板：</p>
<pre style="background:#141920;border:1px solid #262d36;border-radius:8px;padding:14px;color:#7fd18b">cd control/web &amp;&amp; pnpm install &amp;&amp; pnpm build
cd .. &amp;&amp; make web</pre>
<p style="color:#6c7787">或开发时用 <code style="color:#7fd18b">altaird --static-dir control/web/dist</code> 直接读磁盘产物。</p>
</body></html>`
