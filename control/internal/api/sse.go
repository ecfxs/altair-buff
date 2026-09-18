package api

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"time"
)

// Hub 是极简 SSE 广播器。
//
// 云手机在 NAT 后无法被服务器推送，但**浏览器可以主动连服务器**，
// 所以面板的实时性是合法的：设备一上报，服务器就把事件推给所有面板连接。
type Hub struct {
	mu      sync.Mutex
	clients map[chan Event]struct{}
}

// Event 是一条 SSE 事件。
type Event struct {
	Name string
	Data any
}

// NewHub 建广播器。
func NewHub() *Hub {
	return &Hub{clients: make(map[chan Event]struct{})}
}

// Subscribe 注册一个客户端，返回带缓冲的信道。
func (h *Hub) Subscribe() chan Event {
	ch := make(chan Event, 16)
	h.mu.Lock()
	h.clients[ch] = struct{}{}
	h.mu.Unlock()
	return ch
}

// Unsubscribe 注销客户端。
func (h *Hub) Unsubscribe(ch chan Event) {
	h.mu.Lock()
	if _, ok := h.clients[ch]; ok {
		delete(h.clients, ch)
		close(ch)
	}
	h.mu.Unlock()
}

// Publish 广播事件。客户端缓冲满时**丢弃该事件而不是阻塞** ——
// 面板拿到任何事件都会重取数据，丢一条不会造成状态不一致，阻塞会拖死上报路径。
func (h *Hub) Publish(name string, data any) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.clients {
		select {
		case ch <- Event{Name: name, Data: data}:
		default:
			slog.Debug("SSE 客户端缓冲已满，丢弃事件", "event", name)
		}
	}
}

// Clients 返回当前连接数。
func (h *Hub) Clients() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.clients)
}

// handleEvents 是 SSE 端点。
func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeErr(w, http.StatusInternalServerError, "当前连接不支持流式推送")
		return
	}
	h := w.Header()
	h.Set("Content-Type", "text/event-stream; charset=utf-8")
	h.Set("Cache-Control", "no-cache")
	h.Set("Connection", "keep-alive")
	h.Set("X-Accel-Buffering", "no") // 让 Caddy/nginx 不要缓冲

	ch := s.hub.Subscribe()
	defer s.hub.Unsubscribe(ch)

	fmt.Fprintf(w, "event: hello\ndata: {\"ts\":%d}\n\n", time.Now().UnixMilli())
	flusher.Flush()

	keepalive := time.NewTicker(20 * time.Second)
	defer keepalive.Stop()

	for {
		select {
		case <-r.Context().Done():
			return
		case <-keepalive.C:
			// 注释行保活，避免中间层掐掉空闲连接
			fmt.Fprint(w, ": keepalive\n\n")
			flusher.Flush()
		case ev, ok := <-ch:
			if !ok {
				return
			}
			body, err := json.Marshal(ev.Data)
			if err != nil {
				continue
			}
			fmt.Fprintf(w, "event: %s\ndata: %s\n\n", ev.Name, body)
			flusher.Flush()
		}
	}
}
