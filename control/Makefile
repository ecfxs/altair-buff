# 阿尔泰集控（altaird）
#
# 依赖 Go 1.27+。本仓库把工具链装在工作区内，所以先 source 环境：
#   source ../.toolchain/env.sh
#
# 常用：
#   make dev        起后端（8788，读磁盘前端目录）
#   make web-dev    起前端 dev server（proxy /api → 8788）
#   make check      编得过 + go vet + 前端 tsc
#   make run        构建前端并打进二进制，本机跑单文件版本
#   make release    交叉编译 Linux 单二进制

SHELL   := /bin/bash
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
BIN     := dist/altaird
WEB     := web
WEB_DIST:= $(WEB)/dist
EMBED   := internal/webassets/dist

.PHONY: help gen web web-dev dev check test build release run clean fmt pw-install e2e

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

gen: ## 从 openapi.yaml 生成前端类型
	cd $(WEB) && pnpm gen

web: ## 构建前端并拷进 embed 目录
	cd $(WEB) && pnpm install
	cd $(WEB) && pnpm build
	rm -rf $(EMBED) && mkdir -p $(EMBED) && cp -R $(WEB_DIST)/. $(EMBED)/
	@echo "前端产物已拷入 $(EMBED)"

web-dev: ## 前端 dev server（需要另开 make dev）
	cd $(WEB) && pnpm dev

dev: ## 起后端，前端读磁盘目录（改前端不用重编）
	go run ./cmd/altaird --db data/dev.db --static-dir $(WEB_DIST)

check: ## 编译 + vet + 前端类型检查
	go build ./...
	go vet ./...
	cd $(WEB) && pnpm exec tsc --noEmit

test: ## 跑 Go 测试
	go test ./... -race -count=1

build: ## 编译当前平台
	mkdir -p dist
	go build -trimpath -ldflags "-s -w -X main.version=$(VERSION)" -o $(BIN) ./cmd/altaird

release: ## 交叉编译 Linux amd64 单二进制（纯 Go SQLite，无需 cgo）
	mkdir -p dist
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath \
		-ldflags "-s -w -X main.version=$(VERSION)" -o dist/altaird-linux-amd64 ./cmd/altaird
	@ls -lh dist/altaird-linux-amd64

run: build ## 本机跑单文件版本（用内嵌前端产物）
	./$(BIN)

fake: ## 起 3 台假设备造流量（仅开发/CI 用；已加护栏，拒绝打非本机地址）
	go run ./cmd/altaird fake-device --n 3 --interval 5s

pw-install: ## 装 E2E 用的浏览器（装在工作区 .pw-browsers/，不污染系统）
	cd $(WEB) && PLAYWRIGHT_BROWSERS_PATH=$(CURDIR)/.pw-browsers pnpm exec playwright install chromium

e2e: ## 真浏览器界面验收（自己起服务与假设备，跑完截图落在 data/e2e-shots/）
	cd $(WEB) && PLAYWRIGHT_BROWSERS_PATH=$(CURDIR)/.pw-browsers pnpm exec playwright test

clean:
	rm -rf dist data
