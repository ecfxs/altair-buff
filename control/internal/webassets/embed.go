// Package webassets 把前端构建产物打进二进制（go:embed）。
//
// 这样部署就真的是「一个文件」：scp 上去重启即可，VPS 上不需要 Node/nginx 静态目录。
// 开发时用 --static-dir 覆盖成磁盘目录，改前端不用重编 Go。
package webassets

import "embed"

//go:embed all:dist
var FS embed.FS
