package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// runCalibrate 起本地标定工具服务。
//
// 与旧的 tools/calibrate/server.py **接口完全一致**（GET /api/images、POST /api/save、
// 静态托管工作区根目录），所以 tools/calibrate/index.html 不用改一行就能继续用。
// 标定工具天生是本机工具（要读写 shots/），所以独立于集控服务，默认只绑 127.0.0.1。
func runCalibrate(args []string) error {
	fs := flag.NewFlagSet("calibrate", flag.ExitOnError)
	root := fs.String("root", ".", "工作区根目录")
	addr := fs.String("addr", "127.0.0.1:8787", "监听地址")
	if err := fs.Parse(args); err != nil {
		return err
	}
	absRoot, err := filepath.Abs(*root)
	if err != nil {
		return err
	}
	shots := filepath.Join(absRoot, "shots")
	out := filepath.Join(shots, "_analysis", "calibration.json")

	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/images", func(w http.ResponseWriter, r *http.Request) {
		exts := map[string]bool{".png": true, ".jpg": true, ".jpeg": true, ".webp": true}
		files := []string{}
		if entries, err := os.ReadDir(shots); err == nil {
			for _, e := range entries {
				if e.IsDir() || !exts[strings.ToLower(filepath.Ext(e.Name()))] {
					continue
				}
				files = append(files, e.Name())
			}
			sort.Strings(files)
		}
		rel, _ := filepath.Rel(absRoot, out)
		writeCalJSON(w, http.StatusOK, map[string]any{"images": files, "out": rel})
	})
	mux.HandleFunc("POST /api/save", func(w http.ResponseWriter, r *http.Request) {
		var data map[string]any
		if err := json.NewDecoder(r.Body).Decode(&data); err != nil {
			writeCalJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
			writeCalJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		body, err := json.MarshalIndent(data, "", "  ")
		if err != nil {
			writeCalJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		if err := os.WriteFile(out, body, 0o644); err != nil {
			writeCalJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		cnt := 0
		if items, ok := data["items"].([]any); ok {
			cnt = len(items)
		}
		slog.Info("标定已保存", "path", out, "items", cnt)
		writeCalJSON(w, http.StatusOK, map[string]any{"ok": true, "path": out, "items": cnt})
	})
	mux.Handle("GET /", http.FileServer(http.Dir(absRoot)))

	fmt.Println(strings.Repeat("=", 66))
	fmt.Println("  标定工具已启动（altaird calibrate）")
	fmt.Printf("  打开: http://%s/tools/calibrate/index.html\n", *addr)
	fmt.Printf("  截图目录: %s\n", shots)
	fmt.Printf("  保存到:   %s\n", out)
	fmt.Println("  Ctrl+C 退出")
	fmt.Println(strings.Repeat("=", 66))

	srv := &http.Server{Addr: *addr, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	return srv.ListenAndServe()
}

func writeCalJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
